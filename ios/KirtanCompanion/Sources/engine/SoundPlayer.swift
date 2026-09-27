import Foundation

// The mixer: every voice, every end channel, and the master fader.
//
// The counterpart of SoundPlayer.js — same routing, same public vocabulary, same
// per-end gains — with one addition the web version doesn't need: `renderBlock`.
// On the browser, Web Audio owns the graph and the sound card; here WE are the
// graph, so this class also performs the summing that AudioNode.connect did.
//
// ROUTING (unchanged):
//   dayan/bayan:  voices → that end's EQ chain → end gain ─┐
//   kartal:       voices ─────────────────────→ end gain ─┼→ master gain → out
//
// Summing is per END, not per voice: all of an end's voices are added into one
// buffer first, and only then does that buffer pass through the end's five
// biquads. That is what the web graph does (every player connects to the same
// chain head, so the filters see the sum and share one set of delay registers)
// and is roughly five times cheaper than filtering each voice.
//
// ONE VOICE PER VARIANT, RESTARTED. Each stroke owns a fixed pool of voices —
// one slot per sample variant (three "open" recordings, one "closed") — and a
// trigger RESTARTS the round-robin slot rather than appending a new voice. That
// is exactly what the web does with its Tone.Players: a real drum's ring is never
// identical twice, so consecutive hits overlap the last few variants, but the
// overlap can never grow past the number of variants. An unbounded voice list
// could stack a dozen long rings and drive the sum into a constant clipped buzz;
// a fixed pool keeps the level bounded by construction.
//
// The pools and round-robin cursors are audio-thread only and mutated in place
// (never re-allocated). The volume/mute/EQ setters are UI-thread and only publish
// targets the ramp consumes; `pitchRate` is the one cross-thread value, locked.

final class SoundPlayer {

    let sampleRate: Int
    let blockSize: Int

    private let ends: [LaneId: EndChannel]
    private let master: GainRamp

    private let pitchLock = NSLock()
    private var pitchRate: [LaneId: Double] = [:]

    // Audio-thread-only state. One fixed pool of voices per STROKE (a slot per
    // sample variant), indexed by the stroke's ordinal in StrokeKey.all. Each slot
    // is restarted in place on trigger — never accumulated, never re-allocated.
    private var pools: [[Voice]] = []
    private var keyOrdinal: [StrokeKey: Int] = [:]
    private var lastIndex: [StrokeKey: Int] = [:]

    // Scratch buffers, one per lane plus the master sum. Allocated once: the audio
    // thread must not allocate, or it will stutter the output. Indexed by the lane's
    // ordinal in LaneId.ordered so they can be passed through `inout` in place.
    private var laneBuffers: [[Float]]
    private var masterBuffer: [Float]

    /// One source of randomness for the round-robin variant picker (audio thread).
    private var rng = SystemRandomNumberGenerator()

    init(sampleRate: Int, blockSize: Int) {
        self.sampleRate = sampleRate
        self.blockSize = blockSize
        self.ends = LaneId.ordered.reduce(into: [:]) { $0[$1] = EndChannel($1, sampleRate: Double(sampleRate)) }
        self.master = GainRamp(sampleRate: Double(sampleRate), initial: 1)
        self.laneBuffers = LaneId.ordered.map { _ in [Float](repeating: 0, count: blockSize) }
        self.masterBuffer = [Float](repeating: 0, count: blockSize)
    }

    /// Take the decoded samples and index them by lane. Until this is called every
    /// `play` is a silent no-op, which is how the engine stays safe during the
    /// (async) load.
    func attach(_ samples: [StrokeKey: [PcmSample]]) {
        pools = StrokeKey.all.map { key in
            (samples[key] ?? []).map { s in
                var v = Voice(s, lane: key.lane, startOffset: 0)
                v.finished = true   // idle until its first trigger → renders silence
                return v
            }
        }
        keyOrdinal = StrokeKey.all.enumerated().reduce(into: [:]) { $0[$1.element] = $1.offset }
        lastIndex = [:]
    }

    // ── Commands (UI thread) ───────────────────────────────────────────────

    private var userVolume: Float = 1
    /// A temporary attenuation applied by the platform's audio-focus rules, NOT a
    /// user setting. Kept separate so losing focus can't clobber the user's level
    /// and regaining it can't have to guess it — a real mixer keeps the two apart.
    private var duckFactor: Float = 1

    private func publishMaster() {
        master.setTarget(min(max(userVolume * duckFactor, 0), 1))
        master.retarget()
    }

    /// Master volume, 0..1.
    func setVolume(_ value: Float) {
        userVolume = min(max(value, 0), 1)
        publishMaster()
    }

    /// The user's master setting, unaffected by ducking.
    func volume() -> Float { userVolume }

    /// Attenuate for a transient focus loss (1.0 = full, 0.25 = a quarter).
    func setMasterDuck(_ factor: Float) {
        duckFactor = min(max(factor, 0), 1)
        publishMaster()
    }

    func setEndVolume(_ lane: LaneId, _ value: Float) { ends[lane]?.setVolume(value) }
    func setEndMuted(_ lane: LaneId, _ muted: Bool) { ends[lane]?.setMuted(muted) }
    func isEndMuted(_ lane: LaneId) -> Bool { ends[lane]?.isMuted() ?? false }
    func endVolume(_ lane: LaneId) -> Float { ends[lane]?.volume() ?? 1 }

    /// One band of an end's EQ. Unknown ends — including the karatalas, which
    /// carry no chain — are ignored safely rather than throwing.
    func setEqBand(_ lane: LaneId, _ bandIndex: Int, _ db: Double) { ends[lane]?.setEqBand(bandIndex, db) }
    func eqBandDb(_ lane: LaneId, _ bandIndex: Int) -> Double? { ends[lane]?.eqBandDb(bandIndex) }
    func hasEq(_ lane: LaneId) -> Bool { ends[lane]?.hasEq ?? false }

    /// Per-end tuning in cents, clamped to the shared ±600 range. The rate is read
    /// by Voice.render per block rather than latched at trigger, so moving the
    /// slider audibly bends a drum that is still ringing.
    func setEndPitch(_ lane: LaneId, _ cents: Int) {
        let rate = centsToRate(clampCents(cents))
        pitchLock.lock()
        pitchRate[lane] = rate
        pitchLock.unlock()
    }

    func endPitchRate(_ lane: LaneId) -> Double {
        pitchLock.lock(); defer { pitchLock.unlock() }
        return pitchRate[lane] ?? 1.0
    }

    // ── Audio thread ───────────────────────────────────────────────────────

    /// Trigger a stroke `offsetInBlock` frames into the block about to be rendered.
    /// A stroke whose samples never loaded is a silent no-op.
    func play(_ key: StrokeKey, offsetInBlock: Int) {
        guard let o = keyOrdinal[key], o < pools.count, !pools[o].isEmpty else { return }
        let cursor = lastIndex[key] ?? -1
        let index = pickIndex(count: pools[o].count, lastIndex: cursor, random: &rng)
        lastIndex[key] = index
        // Restart the picked variant's slot in place (position 0, ringing again).
        // Fixed pool → the overlap never grows past the number of variants.
        pools[o][index] = Voice(pools[o][index].sample, lane: key.lane, startOffset: offsetInBlock)
    }

    /// Silence everything already sounding — used on stop.
    func stopAllVoices() {
        for o in pools.indices {
            for j in pools[o].indices { pools[o][j].finished = true }
        }
        lastIndex = [:]
    }

    /// Zero a block of a stored buffer in place.
    private func zero(_ buffer: inout [Float], _ count: Int) {
        for n in 0..<count { buffer[n] = 0 }
    }

    /// Render `count` frames of the whole mix into `out`. Called once per block by
    /// the audio thread, AFTER every trigger for this block has been handed to
    /// `play`.
    func renderBlock(_ out: inout [Float], count: Int) {
        // Snapshot the cross-thread pitch rates once for this block.
        pitchLock.lock()
        let rates = pitchRate
        pitchLock.unlock()

        // 1. Clear the scratch in place. Everything below mutates stored buffers
        //    through `inout` — no copies, no allocation on the audio thread.
        for ordinal in 0..<LaneId.ordered.count {
            zero(&laneBuffers[ordinal], count)
        }
        zero(&masterBuffer, count)

        // 2. Render every pool slot into its lane's buffer at that lane's rate.
        //    Slots are mutated in place (fixed pools, no allocation); a finished
        //    slot renders silence until its next trigger.
        for o in pools.indices {
            let key = StrokeKey.all[o]
            let ordinal = LaneId.ordered.firstIndex(of: key.lane)!
            let rate = rates[key.lane] ?? 1.0
            for j in pools[o].indices {
                pools[o][j].render(into: &laneBuffers[ordinal], count: count, rate: rate)
            }
        }

        // 3. Per end: EQ then fader, summed into master.
        for (ordinal, lane) in LaneId.ordered.enumerated() {
            ends[lane]?.process(&laneBuffers[ordinal], count: count)
            for n in 0..<count { masterBuffer[n] += laneBuffers[ordinal][n] }
        }

        // 4. Master fader, then out.
        master.applyTo(&masterBuffer, count: count)
        for n in 0..<count { out[n] = masterBuffer[n] }
    }

    /// How many voices are still ringing (not finished). Mirrors the web's
    /// `activeVoices()`, for a "N playing" readout if the UI wants one.
    func activeVoiceCount() -> Int {
        pools.reduce(0) { $0 + $1.filter { !$0.finished }.count }
    }
}
