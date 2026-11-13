import Foundation

// What the engine tells the outside world. Commands go down, events come up.
enum EngineEvent {
    /// Sample decoding finished; the engine can now be started.
    case ready
    case started
    case stopped
    /// A step boundary passed. Emitted from the audio thread as the block
    /// containing it is rendered, sample-aligned with the sound. The UI playhead
    /// should still read `getPhase()` per frame rather than animate off these,
    /// because a step event is ~10/s and a playhead wants 60.
    case step(Int)
    /// A sequence bar line: entry `index` is playing, on its bar `bar` (1-based).
    /// Emitted from the audio thread at each downbeat of a playing sequence.
    case sequenceAt(index: Int, bar: Int)
    /// The sequence ran out with loop off, and the engine stopped itself on the
    /// next 1. Followed by `.stopped`.
    case sequenceEnded
    /// The output device went away, or never opened. Playback cannot continue.
    case outputFailed
}

// The single front door to the whole engine.
//
// The UI — and the playback layer — talks ONLY to this. It owns the clock, the
// mixer and the audio graph internally and wires them together; nothing outside
// touches those pieces directly. That rule is the one architectural invariant
// carried over unchanged from the web app, and it is why this port was possible
// at all: KirtanEngine.js already defined the seam.
//
// Public vocabulary, deliberately identical to the JS facade:
//   loadSounds() · arm()/release() · setBeat · setBpm · setVolume ·
//   setEndVolume · setEndMuted · setEqBand · setEndPitch · start()/stop() ·
//   setSequence · setSequenceLoop ·
//   playStroke · getPhase()/getStep() · events (ready/started/stopped/step/outputFailed,
//   sequenceAt/sequenceEnded)
//
// KNOWS NOTHING ABOUT UI OR LIFECYCLE. The one audio type it takes is a
// SampleLoader, to read the bundled recordings.
//
// WHY arm() IS SEPARATE FROM start(). A stream-based output only produces blocks
// while it is running, so if it lived only between start() and stop(), the
// editor's pads — and auditioning a beat before playing it — would be silent.
// arm() therefore opens the device and starts the engine, which then renders
// SILENCE until the clock is started. That is the same trade the browser makes,
// and it is what makes pad latency independent of whether the transport runs.
final class KirtanEngine: BlockRenderer {

    static let BLOCK_SIZE = 256

    private let sampleRate: Int
    private let clock: MusicalClock
    private let soundPlayer: SoundPlayer
    private let sequencer: Sequencer
    private let graph: AudioGraph
    private let bank: SampleBank

    // A tiny multi-subscriber event bus. Emitted from the audio thread (step) and
    // the UI thread (the rest); handlers run on whatever thread emitted.
    private let subsLock = NSLock()
    private var subscribers: [(EngineEvent) -> Void] = []

    // Pads awaiting a trigger, drained at the top of the next block. A pad tap
    // arrives on the UI thread, but the voice list belongs to the audio thread —
    // so the request is queued, costing one block of latency at most (already the
    // buffer delay), keeping every voice-list mutation on one thread.
    private let padLock = NSLock()
    private var pendingPads: [StrokeKey] = []

    // A start() awaiting the audio thread. The clock must be anchored BY the audio
    // thread, at the first frame of the block it is about to render. Anchoring it
    // from the UI thread at framesRenderedNow raced the renderer: if that block was
    // already rendering, step 0 fell before the next block's fromFrame and the
    // first downbeat never played. Clearing voices from the UI thread also raced
    // the voice list. (Same fix as the Android port's a095f5b.)
    private let startLock = NSLock()
    private var pendingStart = false

    /// Human timing and loudness variation; see Humanizer.swift.
    private let humanizer: Humanizer

    // Reused across blocks: the audio thread must not allocate.
    private var stepScratch = [StepEvent]()

    private(set) var isReady = false
    private(set) var isPlaying = false
    private(set) var currentBeat: Beat?

    init(sampleRate: Int, loader: SampleLoader) {
        self.sampleRate = sampleRate
        self.clock = MusicalClock(sampleRate: sampleRate)
        self.soundPlayer = SoundPlayer(sampleRate: sampleRate, blockSize: KirtanEngine.BLOCK_SIZE)
        self.sequencer = Sequencer(clock)
        self.bank = SampleBank(loader: loader)
        self.graph = AudioGraph(sampleRate: sampleRate, blockSize: KirtanEngine.BLOCK_SIZE)
        self.humanizer = Humanizer(sampleRate: sampleRate)
        self.graph.attachRenderer(self)
    }

    var bpm: Int { Int(clock.bpm) }
    var isArmed: Bool { graph.isRunning }
    var underruns: Int { 0 }

    // ── Events ─────────────────────────────────────────────────────────────

    func on(_ handler: @escaping (EngineEvent) -> Void) {
        subsLock.lock(); subscribers.append(handler); subsLock.unlock()
    }

    private func emit(_ event: EngineEvent) {
        subsLock.lock(); let handlers = subscribers; subsLock.unlock()
        for handler in handlers { handler(event) }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /// Decode every bundled sample at the output rate, off the calling thread.
    /// A stroke whose files are missing or undecodable is skipped rather than
    /// failing the load, so one absent recording cannot leave the app stuck
    /// waiting for a `ready` that never comes.
    @discardableResult
    func loadSounds() async -> Bool {
        // Idempotent: a second caller (a rotation, a re-opened view model) finds
        // the samples already decoded and returns without re-decoding them.
        if isReady { return true }
        let loaded = await Task.detached(priority: .utility) {
            self.bank.load(targetRate: self.sampleRate)
        }.value
        if loaded.isEmpty {
            return false
        }
        soundPlayer.attach(bank.decoded())
        isReady = true
        emit(.ready)
        return true
    }

    /// Open the output device and start the engine. A failure emits outputFailed
    /// and returns false rather than throwing, so the app can still be browsed.
    @discardableResult
    func arm() -> Bool {
        if graph.isRunning { return true }
        let ok = graph.start()
        if !ok { emit(.outputFailed) }
        return ok
    }

    /// Stop the engine and release the device.
    func release() {
        isPlaying = false
        clock.stop()
        graph.stop()
    }

    // ── Commands ───────────────────────────────────────────────────────────

    /// Choose which beat plays. Does NOT restart the clock, so bar phase — the
    /// musical position — survives the switch: swapping Forward for Double Time
    /// mid-kirtan keeps the downbeat where it was.
    func setBeat(_ beat: Beat) {
        currentBeat = beat
        humanizer.setBeat(beat)
        sequencer.setBeat(beat)
        sequencer.invalidateGuard()
    }

    // ── Sequences ──────────────────────────────────────────────────────────

    /// The playing sequence: its entries and the cursor counting through them.
    /// Swapped whole under `planLock`; the cursor itself is touched only by the
    /// audio thread once playing (and by start(), before the clock runs).
    private final class Plan {
        let steps: [SequenceStep]
        let cursor: SequenceCursor
        init(_ steps: [SequenceStep], _ cursor: SequenceCursor) { self.steps = steps; self.cursor = cursor }
    }
    private let planLock = NSLock()
    private var plan: Plan?
    private func currentPlan() -> Plan? { planLock.lock(); defer { planLock.unlock() }; return plan }

    /// The frame a sequence switch re-anchored at, so its 1 isn't counted twice.
    /// Audio thread only.
    private var switchedAtFrame: Int64 = .min

    /// Play `steps` in order, each for its bar count, switching beats on the bar
    /// line. Nil (or empty) goes back to looping one beat. Stopped, it loads the
    /// first entry and waits for start(); playing, an EDIT keeps its place (same
    /// entry and bar where they still exist).
    func setSequence(_ steps: [SequenceStep]?, loop: Bool) {
        guard let steps, !steps.isEmpty else {
            planLock.lock(); plan = nil; planLock.unlock()
            return
        }
        let cursor = SequenceCursor(bars: steps.map(\.bars), loop: loop)
        if isPlaying, let old = currentPlan() {
            cursor.adopt(old.cursor)
            planLock.lock(); plan = Plan(steps, cursor); planLock.unlock()
            let now = steps[cursor.index].beat
            if now != currentBeat { setBeat(now) }
        } else {
            planLock.lock(); plan = Plan(steps, cursor); planLock.unlock()
            setBeat(steps[0].beat)
        }
    }

    /// Loop on or off while it plays: takes effect at the end of the last entry.
    func setSequenceLoop(_ loop: Bool) { currentPlan()?.cursor.loop = loop }

    var hasSequence: Bool { currentPlan() != nil }

    func setBpm(_ bpm: Int) {
        // A direct change overrides one waiting for its bar.
        startLock.lock(); pendingBarBpm = nil; startLock.unlock()
        clock.setBpm(Double(bpm), atFrame: graph.framesRenderedNow)
    }

    /// A tempo waiting for its bar line: (absolute tick of that 1, bpm). Under startLock.
    private var pendingBarBpm: (tick: Double, bpm: Int)?

    /// Change tempo ON THE 1 nearest `inSeconds` from now (Learn's hill climb), so a
    /// speed-up starts a bar instead of landing mid-phrase. Applied on the audio thread
    /// at that downbeat's exact frame. Returns the seconds until then (the UI shows the
    /// new tempo as it is heard), or nil when stopped.
    func setBpmAtBar(_ bpm: Int, inSeconds: Double) -> Double? {
        let state = clock.snapshot()
        guard state.isRunning else { return nil }
        let now = graph.framesRenderedNow
        let bar = state.barTickNear(now + Int64(inSeconds * Double(clock.sampleRate)), now: now)
        startLock.lock(); pendingBarBpm = (bar, bpm); startLock.unlock()
        return Double(max(0, state.frameAt(bar) - now)) / Double(clock.sampleRate)
    }

    /// How much human variation the drum plays with. Takes effect on the next stroke.
    func setFeel(_ feel: Feel) { humanizer.feel = feel }

    func setVolume(_ value: Float) { soundPlayer.setVolume(value) }
    func volume() -> Float { soundPlayer.volume() }
    func setMasterDuck(_ factor: Float) { soundPlayer.setMasterDuck(factor) }

    func setEndVolume(_ lane: LaneId, _ value: Float) { soundPlayer.setEndVolume(lane, value) }
    func setEndMuted(_ lane: LaneId, _ muted: Bool) { soundPlayer.setEndMuted(lane, muted) }
    func isEndMuted(_ lane: LaneId) -> Bool { soundPlayer.isEndMuted(lane) }
    func endVolume(_ lane: LaneId) -> Float { soundPlayer.endVolume(lane) }

    func setEqBand(_ lane: LaneId, _ bandIndex: Int, _ db: Double) { soundPlayer.setEqBand(lane, bandIndex, db) }
    func eqBandDb(_ lane: LaneId, _ bandIndex: Int) -> Double? { soundPlayer.eqBandDb(lane, bandIndex) }
    func hasEq(_ lane: LaneId) -> Bool { soundPlayer.hasEq(lane) }
    func setEndPitch(_ lane: LaneId, _ cents: Int) { soundPlayer.setEndPitch(lane, cents) }
    func endPitch(_ lane: LaneId) -> Double { soundPlayer.endPitchRate(lane) }

    /// Begin playing from the top of the bar.
    func start() {
        guard currentBeat != nil else {
            return // no beat loaded; nothing to start
        }
        if !arm() { return }
        // A sequence always starts from its top.
        if let p = currentPlan() {
            p.cursor.reset()
            setBeat(p.steps[0].beat)
        }
        // Anchored by the audio thread at the top of its next block; see pendingStart.
        clock.stop()
        startLock.lock(); pendingStart = true; startLock.unlock()
        isPlaying = true
        emit(.started)
    }

    func stop() {
        guard isPlaying else { return }
        isPlaying = false
        startLock.lock(); pendingStart = false; pendingBarBpm = nil; startLock.unlock()
        clock.stop()
        // Voices are left to ring out rather than cut: stopping mid-decay is a
        // click, and a drum allowed to finish sounds like a drum that was stopped.
        emit(.stopped)
    }

    /// Sound one stroke immediately — the editor's pads, and auditioning a beat.
    /// Queued to the audio thread.
    func playStroke(_ lane: LaneId, open: Bool) {
        padLock.lock(); pendingPads.append(StrokeKey(lane: lane, open: open)); padLock.unlock()
    }

    // ── Position, for the playhead ─────────────────────────────────────────

    /// Bar phase in [0,1). 0 while stopped. Read once per frame from the UI.
    func getPhase() -> Double { clock.phaseAt(playheadFrame()) }
    /// The step index in force at the playhead.
    func getStep() -> Int { clock.stepAt(playheadFrame()) }

    /// The frame being HEARD, moved later by the user's display delay. See
    /// OutputClock for why this is a timestamp and not a fixed buffer guess;
    /// the guess survives only as the fallback before the first stamp.
    private func playheadFrame(at nowNanos: UInt64 = OutputClock.nowNanos(), delayMs: Int? = nil) -> Int64 {
        let heard = graph.presentedFrame(nowNanos: nowNanos)
            ?? (graph.framesRenderedNow - Int64(KirtanEngine.BLOCK_SIZE * 4))
        let delay = OutputClock.msToFrames(delayMs ?? displayDelayMs, sampleRate: sampleRate)
        return max(0, heard - delay)
    }

    // ── Display sync ───────────────────────────────────────────────────────

    /// Extra delay the user has set for the current output, on top of what the
    /// phone reports. Positive moves the display later. Read on the main thread
    /// only, so it needs no lock.
    private(set) var displayDelayMs = 0

    func setDisplayDelayMs(_ ms: Int) { displayDelayMs = ms }

    /// The session's own latency figure, already allowed for. Set by the
    /// playback layer, which owns AVAudioSession; the engine stays free of it.
    func setOutputLatency(seconds: Double) { graph.setOutputLatency(seconds: seconds) }

    var reportedOutputLatencyMs: Int { graph.outputLatencyMs }

    /// For the sync test: how far the click the listener just tapped on is from
    /// the nearest step, in ms, by what the phone SAYS is being heard (no user
    /// delay). Positive means the phone thinks the click already happened —
    /// the delay it failed to report.
    func offsetFromNearestStepMs(nowNanos: UInt64) -> Double {
        guard let beat = currentBeat else { return 0 }
        let steps = max(1, beat.steps)
        let position = clock.phaseAt(playheadFrame(at: nowNanos, delayMs: 0)) * Double(steps)
        let off = position - position.rounded()
        let stepMs = 60_000 / clock.bpm * beat.beatsPerBar / Double(steps)
        return off * stepMs
    }

    // ── The audio thread's entry point ─────────────────────────────────────

    /// Render one block, called by AudioGraph on the audio thread. Order matters:
    /// triggers are placed FIRST, at their exact offsets within this block, and
    /// only then is the block mixed — so a stroke scheduled for frame 137 of 256
    /// sounds at frame 137 and not at the next block boundary.
    func render(fromFrame: Int64, count: Int, out: inout [Float]) {
        // 0. A pending start anchors the clock HERE, at this block's first frame,
        //    so step 0 is always inside this block.
        startLock.lock()
        let starting = pendingStart
        pendingStart = false
        startLock.unlock()
        if starting {
            soundPlayer.stopAllVoices()
            sequencer.invalidateGuard()
            humanizer.reset()
            clock.start(atFrame: fromFrame)
        }

        // 1. Pad taps queued from the UI thread.
        padLock.lock()
        let pads = pendingPads
        pendingPads.removeAll(keepingCapacity: true)
        padLock.unlock()
        for key in pads { soundPlayer.play(key, offsetInBlock: 0) }

        // 1b. A tempo waiting for its bar line, if that 1 falls in this block:
        //     re-anchor exactly there, so the downbeat keeps its place and every
        //     step after it runs at the new tempo.
        startLock.lock()
        let barChange = pendingBarBpm
        startLock.unlock()
        if let barChange, clock.isRunning {
            let at = clock.frameAt(barChange.tick)
            if at < fromFrame + Int64(count) {
                clock.setBpm(Double(barChange.bpm), atFrame: max(at, fromFrame))
                startLock.lock()
                if pendingBarBpm?.tick == barChange.tick { pendingBarBpm = nil }
                startLock.unlock()
            }
        }

        // 2. Sequenced steps falling inside this block. A LOOP, because a
        //    sequence can switch beats mid-block: the switch re-anchors the clock
        //    on that 1 and the rest of the block is collected again under the new
        //    beat's grid.
        var cursorFrame = fromFrame
        let endFrame = fromFrame + Int64(count)
        blocks: while let beat = sequencer.currentBeat, clock.isRunning {
            stepScratch.removeAll(keepingCapacity: true)
            sequencer.collectStepEvents(fromFrame: cursorFrame, toFrame: endFrame, into: &stepScratch)
            for event in stepScratch {
                if event.step == 0, let p = currentPlan() {
                    if event.frame != switchedAtFrame {
                        switch p.cursor.onDownbeat() {
                        case .end:
                            endSequence()
                            break blocks
                        case .switchTo(let i):
                            switchSequenceBeat(p.steps[i].beat, atFrame: event.frame)
                            cursorFrame = event.frame
                            continue blocks
                        case .stay:
                            break
                        }
                    }
                    emit(.sequenceAt(index: p.cursor.index, bar: p.cursor.bar + 1))
                }
                playStep(beat, event, fromFrame: fromFrame, count: count)
            }
            break
        }

        // 3. Mix: voices → per-end EQ and fader → master.
        soundPlayer.renderBlock(&out, count: count)
        // 4. Soft-limit the summed mix: the last stage before the speaker.
        softClipInPlace(&out, count: count)
    }

    private func playStep(_ beat: Beat, _ event: StepEvent, fromFrame: Int64, count: Int) {
        let offset = min(max(Int(event.frame - fromFrame), 0), count - 1)
        // Iterated inline: this runs ten times a second forever, so it
        // allocates nothing.
        humanizer.advance()
        let stepMs = 60_000 / clock.bpm * beat.beatsPerBar / Double(max(1, beat.steps))
        for lane in LaneId.ordered {
            guard let stroke = beat.strokeAt(lane, event.step) else { continue }
            soundPlayer.play(
                StrokeKey(lane: lane, open: stroke == .open),
                offsetInBlock: offset + humanizer.delayFrames(lane, step: event.step, stepMs: stepMs),
                gain: humanizer.gain(step: event.step)
            )
        }
        emit(.step(event.step))
    }

    /// A sequence moves to its next entry ON this 1. The clock is re-anchored
    /// here (tick 0 = this frame, tempo kept) because the bar line of one meter
    /// is not a bar line of another, so carrying the tick count over would start
    /// the new beat mid-bar. A bar-snapped tempo change was aimed at old ticks,
    /// so it goes.
    private func switchSequenceBeat(_ next: Beat, atFrame: Int64) {
        setBeat(next)
        clock.start(atFrame: atFrame)
        switchedAtFrame = atFrame
        startLock.lock(); pendingBarBpm = nil; startLock.unlock()
    }

    /// Loop off and the last entry done: stop on this 1 without playing it.
    private func endSequence() {
        isPlaying = false
        startLock.lock(); pendingBarBpm = nil; startLock.unlock()
        clock.stop()
        emit(.sequenceEnded)
        emit(.stopped)
    }
}
