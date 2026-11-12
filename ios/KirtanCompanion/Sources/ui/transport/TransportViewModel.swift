import Foundation
import Observation

/// The engine and its SwiftUI mirror, as one unit.
///
/// Ported from `src/hooks/useTransport.js`, which is the single place in the web
/// app that calls `engine.*` — and this is the single place here. That rule is
/// what keeps "the UI talks to the engine only through `KirtanEngine`" enforceable.
/// The one sanctioned exception is the beat editor, which takes the transport over
/// entirely while it is open; `engine` is exposed for exactly that.
///
/// ── WHAT DID NOT PORT, AND WHY ──
/// The web hook is dominated by `useRef` mirrors of its own state (`bpmRef`,
/// `eqBandsRef` …), because a React handler closes over the values from the render
/// it was created in. Rapid ± taps would otherwise stack from a stale `bpm`; a fast
/// EQ drag would otherwise persist a value a later re-render had superseded.
///
/// None of that applies here. An `@Observable` view model is not recreated per
/// frame and holds its own authoritative state, so `bpmRef` and `bpm` collapse
/// into one field. The refs were a workaround for a React lifecycle, not a domain
/// requirement — porting them would mean porting the prevention machinery for a
/// bug this architecture cannot have.
///
/// What DID port is the clamping discipline: every value is clamped against the
/// shared bounds in `data/` on the way in, so the UI state, the persisted prefs
/// and the engine's DSP can never disagree about what is legal.
///
/// ── TRANSPORT GOES THROUGH THE PLAYBACK CONTROLLER, MIXER DOES NOT ──
/// Play and pause route via [PlaybackController] so the lock-screen controls, the
/// now-playing title and the background session all follow. On Android that
/// round-trips through a `MediaController`; here it is a direct in-process call,
/// but the split is the same. Tempo, volumes, EQ and tuning go straight to the
/// engine: the notification has no opinion about an EQ band, and routing a slider
/// drag through IPC would be slow and pointless.
///
/// The bar PHASE is deliberately not stored: it changes 60×/s while playing, and
/// storing it would invalidate every observer each frame just to move one playhead
/// line. The strip reads `getPhase()` directly inside its animation loop, which is
/// the SwiftUI equivalent of the web strip's rAF loop writing a style without
/// going through state.
@MainActor
@Observable
final class TransportViewModel {

    private let engine: KirtanEngine
    private let playback: PlaybackController
    private let prefs: EqPrefs

    // ── Observable UI state ────────────────────────────────────────────────
    /// Samples finished decoding. Nothing plays until this is true.
    var ready = false
    var playing = false
    /// The step most recently fired, or -1 when stopped.
    var step = -1
    var bpm = DEFAULT_BPM
    var beatName: String?
    /// The beat the engine is pointed at — what Learn draws.
    var loadedBeat: Beat?
    var settings = EqPrefsSnapshot.default
    /// The output device would not open. Sound is impossible until it clears.
    var outputFailed = false
    /// Where the drum is playing: phone speaker, headphones, a Bluetooth speaker.
    var route = OutputRoute.speaker
    /// Set when a Bluetooth device is playing that the user has never been asked
    /// to sync the display for. Drives the one-time banner.
    var syncPrompt: OutputRoute?
    /// Brahmaloka time: the tempo slider's high range (see data/Meter.swift).
    var brahmaloka = false
    /// Counts unlocks; the storm overlay plays once each time it changes.
    var burstKey = 0

    /// The display delay in force for the current output.
    var displayDelayMs: Int { settings.displayDelay(route.key) }

    // ── Authoritative, not rendered ────────────────────────────────────────
    @ObservationIgnored private var loadedBeatId: String?
    @ObservationIgnored private var tapTimes: [Int64] = []
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    init(engine: KirtanEngine, playback: PlaybackController, prefs: EqPrefs) {
        self.engine = engine
        self.playback = playback
        self.prefs = prefs

        // ORDER MATTERS, and getting it wrong is a silent dead app.
        //
        // The engine's event bus has no replay, so a `.ready` emitted before this
        // view model subscribes is dropped — and `ready` is the only thing that
        // flips `ready`, which gates the Play button. So subscribe first, then load.
        observeEngine()
        playback.onPlayWhenReadyChange = { [weak self] playing in
            Task { @MainActor in
                self?.playing = playing
                self?.updateSyncPrompt()
            }
        }
        // A speaker connecting mid-kirtan takes its own delay with it.
        playback.onRouteChange = { [weak self] route in
            Task { @MainActor in
                guard let self else { return }
                self.route = route
                self.engine.setDisplayDelayMs(self.displayDelayMs)
                self.updateSyncPrompt()
            }
        }
        route = playback.route

        // Belt-and-braces: if the engine already loaded before this view model
        // existed (it is process-scoped), seed from `isReady` instead of waiting
        // for a Ready that already fired.
        if engine.isReady {
            ready = true
            applyPrefsToEngine()
        }
    }

    /// The splash's Begin tap: decode the samples once, then open the output and
    /// arm the engine (which renders silence until the clock starts).
    func begin() async {
        await engine.loadSounds()
        playback.begin()
    }

    // ── Engine events ──────────────────────────────────────────────────────

    private func observeEngine() {
        engine.on { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
    }

    private func handle(_ event: EngineEvent) {
        switch event {
        case .ready:
            // Hydrating from the Ready handler rather than right after the load
            // resolves is load-bearing: tuning walks a pool of players that is
            // empty until decoding finishes, so applying it any earlier is dropped.
            ready = true
            applyPrefsToEngine()

        case .started:
            playing = true
            updateSyncPrompt()

        case .stopped:
            playing = false
            step = -1

        case .step(let step):
            self.step = step

        case .outputFailed:
            outputFailed = true
        }
    }

    /// Read the persisted mixer once at launch and hydrate the engine. Never
    /// throws: EqPrefs.load already sanitises against the shared bounds.
    func loadSettings() async {
        settings = await prefs.load()
        // Applied immediately in case samples decoded first, and again from the
        // Ready handler if they did not. Two-phase hydration, as in the web hook.
        applyPrefsToEngine()
    }

    /// Push every persisted setting into the engine's DSP.
    private func applyPrefsToEngine() {
        guard engine.isReady else { return }
        engine.setVolume(Float(settings.masterVolume))
        for lane in LaneId.ordered {
            engine.setEndVolume(lane, Float(settings.volume(lane)))
            engine.setEndMuted(lane, settings.isMuted(lane))
        }
        for lane in LaneId.ordered {
            guard let end = settings.end(lane) else { continue }
            for (i, db) in end.bands.enumerated() { engine.setEqBand(lane, i, db) }
            engine.setEndPitch(lane, end.tuneCents)
        }
        engine.setFeel(settings.feel)
        engine.setDisplayDelayMs(displayDelayMs)
        updateSyncPrompt()
    }

    // ── Display sync ───────────────────────────────────────────────────────

    /// Offer to sync once per Bluetooth device, and only while it is playing.
    private func updateSyncPrompt() {
        let candidate: OutputRoute? = route.bluetooth && playing
            && !settings.syncPrompted.contains(route.key)
            && settings.displayDelays[route.key] == nil ? route : nil
        if candidate != syncPrompt { syncPrompt = candidate }
    }

    /// Set the display delay for the CURRENT output and apply it now.
    func setDisplayDelay(_ ms: Int) {
        let clamped = min(max(ms, EqPrefsSnapshot.displayDelayMinMs), EqPrefsSnapshot.displayDelayMaxMs)
        engine.setDisplayDelayMs(clamped)
        var s = settings; s.displayDelays[route.key] = clamped
        publish(s)
        updateSyncPrompt()
    }

    /// Close the "sync the display?" banner for this device, for good.
    func dismissSyncPrompt() {
        guard let key = syncPrompt?.key else { return }
        var s = settings; s.syncPrompted.insert(key)
        publish(s)
        syncPrompt = nil
    }

    /// What the phone itself reports as its output latency, already allowed for.
    var reportedOutputLatencyMs: Int { engine.reportedOutputLatencyMs }

    /// A sync-test tap: its offset from the nearest click. See KirtanEngine.
    func syncTapOffsetMs(atNanos nanos: UInt64) -> Double {
        engine.offsetFromNearestStepMs(nowNanos: nanos)
    }

    /// Start the sync test: a plain click on every beat, humanizer off so each
    /// click lands exactly on its step. Through the preview path, like the editor.
    func startSyncTest() {
        engine.setFeel(.off)
        startPreview(Self.syncTestBeat, previewBpm: Self.syncTestBpm)
    }

    /// End the sync test and give the drum its feel back.
    func stopSyncTest() {
        stopPreview()
        engine.setFeel(settings.feel)
    }

    // ── Tempo ──────────────────────────────────────────────────────────────

    /// Authoritative tempo, mutated synchronously so rapid ± taps stack.
    func changeBpm(_ value: Int) {
        barChange?.cancel()
        bpm = clampBpm(value)
        engine.setBpm(bpm)
        brahmaloka = brahmalokaFor(bpm: bpm, was: brahmaloka)
        SessionPrefs.saveBpm(bpm)
    }

    private var barChange: Task<Void, Never>?

    /// Hill climb's step: the new tempo starts on the 1 nearest `inSeconds` from now
    /// rather than mid-bar; the display follows when it is heard. Stopped, there's no
    /// bar to wait for, so it's an ordinary change.
    func changeBpmAtBar(_ value: Int, inSeconds: Double) {
        let target = clampBpm(value)
        guard let wait = engine.setBpmAtBar(target, inSeconds: inSeconds) else { return changeBpm(target) }
        barChange?.cancel()
        barChange = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            // Stopped before that bar: the engine dropped it, so must the display.
            guard let self, !Task.isCancelled, self.playing else { return }
            self.bpm = target
            self.brahmaloka = brahmalokaFor(bpm: target, was: self.brahmaloka)
            SessionPrefs.saveBpm(target)
        }
    }

    /// Clamp to what the slider reaches now: 200, or 500 in Brahmaloka.
    private func clampToMode(_ value: Int) -> Int {
        min(max(value, MIN_BPM), brahmaloka ? MAX_BPM : NORMAL_MAX_BPM)
    }

    /// + at the top of the normal range opens Brahmaloka, like holding the slider there.
    func nudgeBpm(_ delta: Int) {
        if delta > 0 && !brahmaloka && bpm >= NORMAL_MAX_BPM { unlockBrahmaloka() }
        else { changeBpm(clampToMode(bpm + delta)) }
    }

    /// Open Brahmaloka time: the high range, plus the storm (BrahmalokaStage watches
    /// `burstKey`) and its thunder.
    func unlockBrahmaloka() {
        guard !brahmaloka else { return }
        brahmaloka = true
        celebrateBrahmaloka()
    }

    /// The storm and thunder alone: the editor keeps its own mode but shares the weather.
    func celebrateBrahmaloka() {
        burstKey += 1
        Rumble.play()
    }

    /// The badge's way out: back to 40–200, at its top.
    func leaveBrahmaloka() {
        brahmaloka = false
        changeBpm(min(bpm, NORMAL_MAX_BPM))
    }

    /// "Keep this speed while I change beats mid-kirtan." A locked tempo makes
    /// `loadBeat` leave the BPM alone instead of adopting the beat's suggestion.
    func setTempoLocked(_ locked: Bool) {
        var s = settings; s.tempoLocked = locked
        publish(s)
    }

    /// How much human variation the drum plays with. See engine/Humanizer.swift.
    /// Mirror the Learn highway for a left-handed player. Display only.
    func setLearnLeftHanded(_ leftHanded: Bool) {
        var s = settings; s.learnLeftHanded = leftHanded
        publish(s)
    }

    /// Which part Learn teaches: the karatalas', or (false) the mridanga's. Display only.
    func setLearnKartal(_ kartal: Bool) {
        var s = settings; s.learnKartal = kartal
        publish(s)
    }

    /// Remember Learn's hill-climb choice (BPM per step, seconds between steps).
    func setHillClimb(step: Int, seconds: Int) {
        var s = settings; s.hillClimbStep = step; s.hillClimbSeconds = seconds
        publish(s)
    }

    func setFeel(_ feel: Feel) {
        engine.setFeel(feel)
        var s = settings; s.feel = feel
        publish(s)
    }

    /// Tap tempo: average the gaps between the last few taps.
    ///
    /// A gap over two seconds means the user started a new count rather than
    /// continuing one, so the history resets instead of averaging a pause into
    /// the tempo — which would otherwise report something near zero BPM.
    func tapTempo() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        tapTimes.append(now)
        if tapTimes.count > 4 { tapTimes.removeFirst() }
        guard tapTimes.count >= 2 else { return }

        var gaps = [Int64]()
        for i in 1..<tapTimes.count { gaps.append(tapTimes[i] - tapTimes[i - 1]) }
        if gaps.last! > Self.tapResetMs {
            tapTimes.removeAll()
            tapTimes.append(now)
            return
        }
        let avg = Double(gaps.reduce(0, +)) / Double(gaps.count)
        guard avg > 0 else { return }
        changeBpm(clampToMode(Int((60_000.0 / avg).rounded())))
    }

    // ── Mixer ──────────────────────────────────────────────────────────────

    func changeVolume(_ value: Double) {
        let v = value.clamped(to: 0...1)
        var s = settings; s.masterVolume = v
        publish(s)
        engine.setVolume(Float(v))
    }

    func changeEndVolume(_ lane: LaneId, _ value: Double) {
        let v = value.clamped(to: 0...1)
        var s = settings; s.endVolumes[lane] = v
        publish(s)
        engine.setEndVolume(lane, Float(v))
    }

    func toggleMute(_ lane: LaneId) {
        let muted = !settings.isMuted(lane)
        var s = settings
        if muted { s.mutedEnds.insert(lane) } else { s.mutedEnds.remove(lane) }
        publish(s)
        engine.setEndMuted(lane, muted)
    }

    func changeEqBand(_ lane: LaneId, bandIndex: Int, db: Double) {
        // Clamped HERE rather than only in the engine, so the state, the persisted
        // value and the DSP cannot disagree about what is legal.
        let gain = clampEqDb(db.isFinite ? db : 0)
        let applied = updateEnd(lane) { e in
            var e = e
            if bandIndex < e.bands.count { e.bands[bandIndex] = gain }
            return e
        }
        if applied { engine.setEqBand(lane, bandIndex, gain) }
    }

    func changeTune(_ lane: LaneId, _ cents: Int) {
        let clamped = cents.clamped(to: TUNE_MIN_CENTS...TUNE_MAX_CENTS)
        let applied = updateEnd(lane) { e in
            var e = e
            e.tuneCents = clamped
            return e
        }
        if applied { engine.setEndPitch(lane, clamped) }
    }

    func toggleEqPanel(_ lane: LaneId) {
        updateEnd(lane) { var e = $0; e.eqPanelOpen.toggle(); return e }
    }

    func toggleTunePanel(_ lane: LaneId) {
        updateEnd(lane) { var e = $0; e.tunePanelOpen.toggle(); return e }
    }

    func setShowBols(_ show: Bool) {
        var s = settings; s.showBols = show
        publish(s)
    }

    func setShowStepLabels(_ show: Bool) {
        var s = settings; s.showStepLabels = show
        publish(s)
    }

    /// The whole-interface scale: purely presentational, applied at the root.
    func setUiScale(_ scale: Double) {
        var s = settings
        s.uiScale = scale.clamped(to: EqPrefsSnapshot.uiScaleMin...EqPrefsSnapshot.uiScaleMax)
        publish(s)
    }

    /// Apply `transform` to one end's settings and republish.
    ///
    /// Returns `false` for a lane that has no EQ or tuning — the karatalas — so a
    /// caller cannot invent settings for an instrument the engine does not shape.
    @discardableResult
    private func updateEnd(_ lane: LaneId, _ transform: (EndSoundPrefs) -> EndSoundPrefs) -> Bool {
        guard let end = settings.end(lane) else { return false }
        let next = transform(end)
        var s = settings
        switch lane {
        case .dayan: s.dayan = next
        case .bayan: s.bayan = next
        case .kartal: return false
        }
        publish(s)
        return true
    }

    /// Publish a new snapshot and schedule its persistence.
    private func publish(_ next: EqPrefsSnapshot) {
        settings = next
        schedulePrefsSave()
    }

    /// Persistence is DEBOUNCED while the DSP is not.
    ///
    /// A slider drag fires dozens of changes a second; the engine must hear every
    /// one (that is what makes the drag feel connected to the sound), but writing
    /// to disk on each would queue I/O behind the gesture. So the state and the DSP
    /// update immediately and only the write coalesces, last one winning.
    private func schedulePrefsSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(EqPrefs.saveDebounceMs))
            // Read `settings` when it FIRES rather than at schedule time, so a
            // fast drag persists the final position.
            guard let self, !Task.isCancelled else { return }
            await self.prefs.save(self.settings)
        }
    }

    // ── Beat selection and transport ───────────────────────────────────────

    /// Point the engine at a beat WITHOUT starting it, adopting the beat's own
    /// suggested tempo unless the user has locked it.
    func loadBeat(_ beat: Beat) {
        engine.setBeat(beat)
        playback.setBeatTitle(beat.name)
        beatName = beat.name
        loadedBeat = beat
        if !settings.tempoLocked && beat.id != loadedBeatId { changeBpm(beat.bpm) }
        loadedBeatId = beat.id
        if let id = beat.id { SessionPrefs.saveBeat(id) }
    }

    /// Start `beat`. A no-op while samples are still decoding, and a no-op while
    /// already playing — the same rule as the web `play()`. The selection that
    /// preceded this tap loadBeat'd the beat, which switched the loop live with bar
    /// phase preserved; calling `engine.start()` on top would re-anchor the clock to
    /// the bar top and kill the still-ringing voices: a lurch, not a switch.
    func play(_ beat: Beat) {
        guard ready else { return }
        guard !playing else { return }
        engine.setBeat(beat)
        engine.setBpm(bpm)
        playback.setBeatTitle(beat.name)
        beatName = beat.name
        loadedBeat = beat
        playback.setPlayWhenReady(true)
    }

    func togglePlay(_ beat: Beat) {
        guard ready else { return }
        if playing { playback.setPlayWhenReady(false) } else { play(beat) }
    }

    func stop() { playback.setPlayWhenReady(false) }

    /// Start the editor's preview loop.
    ///
    /// The sequencing is the fix for "preview is silent": ask the controller to
    /// stop, then start — never racing the two. On Android the stop round-trips
    /// through a binder and must be awaited; here it is synchronous, so this is a
    /// plain stop-then-start.
    func startPreview(_ beat: Beat, previewBpm: Int) {
        guard ready else { return }
        if playing { playback.setPlayWhenReady(false) }
        engine.setBeat(beat)
        engine.setBpm(previewBpm)
        engine.start()
        playing = true
        beatName = beat.name
    }

    /// End the preview. Synchronous, unlike `stop`: nothing round-trips, and the
    /// editor's unmount path must not hang. Also why Home's Play stays safe
    /// afterwards: `play` no-ops while the mirror says playing, so leaving the
    /// editor MUST call this.
    func stopPreview() {
        guard playing else { return }
        engine.stop()
        playing = false
    }

    /// Stop a preview AND hand the engine back to the loaded beat at the user's tempo,
    /// so Home's Play plays what Home shows. For previews of beats that aren't the
    /// loaded one (the community cards).
    func endPreview() {
        // Unconditional, unlike stopPreview: a late "stopped" from the playback
        // controller can clear the mirror while the preview is still sounding.
        engine.stop()
        playing = false
        if let beat = loadedBeat {
            engine.setBeat(beat)
            beatName = beat.name
        }
        engine.setBpm(bpm)
    }

    // ── Position, for the playhead ─────────────────────────────────────────
    /// Bar phase in [0,1); 0 while stopped. Read once per frame by the strip.
    func getPhase() -> Double { engine.getPhase() }
    /// The step index in force at the playhead.
    func getStep() -> Int { engine.getStep() }

    /// The engine, exposed for the beat editor only (it takes the transport over).
    var engineHandle: KirtanEngine { engine }
}

extension TransportViewModel {
    /// A gap longer than this starts a new tap-tempo count.
    static let tapResetMs: Int64 = 2_000
    static let bpmRange = MIN_BPM...MAX_BPM

    /// The sync test's tempo. Slow on purpose: a tap is matched to the NEAREST
    /// click, so the largest measurable delay is half the gap — ±430 ms here.
    static let syncTestBpm = 70

    /// One closed dayan stroke on every beat: the sharpest attack in the kit.
    static let syncTestBeat = Beat(
        id: "sync-test", name: "Sync test", note: "", bpm: syncTestBpm, steps: 4,
        beatsPerBar: 4, cellsPerGroup: 1, groups: nil, description: nil,
        lanePatterns: [.dayan: Array(repeating: .closed, count: 4)]
    )
}
