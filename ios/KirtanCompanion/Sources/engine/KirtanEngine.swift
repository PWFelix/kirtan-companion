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
//   playStroke · getPhase()/getStep() · events (ready/started/stopped/step/outputFailed)
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
        sequencer.setBeat(beat)
        sequencer.invalidateGuard()
    }

    func setBpm(_ bpm: Int) {
        clock.setBpm(Double(bpm), atFrame: graph.framesRenderedNow)
    }

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
        clock.start(atFrame: graph.framesRenderedNow)
        soundPlayer.stopAllVoices()
        sequencer.invalidateGuard()
        isPlaying = true
        emit(.started)
    }

    func stop() {
        guard isPlaying else { return }
        isPlaying = false
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

    /// Backed off from framesRendered by the output buffer depth, because those
    /// frames are queued in the device and have not been heard yet. Without this
    /// the visual playhead leads the sound by the buffer latency.
    private func playheadFrame() -> Int64 {
        let f = graph.framesRenderedNow - Int64(KirtanEngine.BLOCK_SIZE * 4)
        return max(0, f)
    }

    // ── The audio thread's entry point ─────────────────────────────────────

    /// Render one block, called by AudioGraph on the audio thread. Order matters:
    /// triggers are placed FIRST, at their exact offsets within this block, and
    /// only then is the block mixed — so a stroke scheduled for frame 137 of 256
    /// sounds at frame 137 and not at the next block boundary.
    func render(fromFrame: Int64, count: Int, out: inout [Float]) {
        // 1. Pad taps queued from the UI thread.
        padLock.lock()
        let pads = pendingPads
        pendingPads.removeAll(keepingCapacity: true)
        padLock.unlock()
        for key in pads { soundPlayer.play(key, offsetInBlock: 0) }

        // 2. Sequenced steps falling inside this block.
        if let beat = sequencer.currentBeat, clock.isRunning {
            stepScratch.removeAll(keepingCapacity: true)
            sequencer.collectStepEvents(fromFrame: fromFrame, toFrame: fromFrame + Int64(count), into: &stepScratch)
            for event in stepScratch {
                let offset = min(max(Int(event.frame - fromFrame), 0), count - 1)
                // Iterated inline: this runs ten times a second forever, so it
                // allocates nothing.
                for lane in LaneId.ordered {
                    guard let stroke = beat.strokeAt(lane, event.step) else { continue }
                    soundPlayer.play(StrokeKey(lane: lane, open: stroke == .open), offsetInBlock: offset)
                }
                emit(.step(event.step))
            }
        }

        // 3. Mix: voices → per-end EQ and fader → master.
        soundPlayer.renderBlock(&out, count: count)
        // 4. Soft-limit the summed mix: the last stage before the speaker.
        softClipInPlace(&out, count: count)
    }
}
