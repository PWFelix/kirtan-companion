// The musical clock — a mapping between audio FRAMES and musical TICKS.
//
// This replaces Tone.Transport, and the model is deliberately the one
// Sequencer.js explains at length, because it fixed a real bug rather than a
// theoretical one:
//   - The clock is the source of truth for "where in the bar are we?".
//   - One bar is `ticksPerBar = PPQ × beatsPerBar` ticks long, and the loop
//     restarts there.
//   - A step is DERIVED from bar phase, never counted:
//         phase = (tick mod ticksPerBar) / ticksPerBar
//         step  = floor(phase × steps + ε)
//     so a step index is always valid for whichever beat is loaded.
//
// A counter is grid-relative — "step 12" is meaningless without "out of 16";
// switch to an 8-step beat mid-play and the counter indexes past the end. Phase
// is grid-independent: the musical position survives a beat switch, and the new
// step is recomputed against the new grid. Deriving it also makes drift
// impossible, because there is no accumulator to fall out of step.
//
// STATELESS IN PRACTICE. The clock holds no frame counter of its own: the
// renderer owns the frame position and passes it in, keeping one authoritative
// count. Everything is a pure function of (anchor, tempo, frame). Ticks are a
// linear function of frames written, so a step's exact frame can be computed in
// advance and a voice started at that sample offset inside a block — no
// scheduler sits in the signal path, hence no callback jitter to filter.
//
// The mutable state is written from the UI thread (setters) and read from the
// audio thread; a lock snapshots it once per block so the DSP does pure math on
// the copy.

import Foundation

final class MusicalClock {

    static let PPQ = 192

    /// Tolerance added before flooring phase→step: a boundary that should be
    /// exactly 3.0 can arrive as 2.9999999 and floor to 2, dropping the beat.
    /// 1e-6 is far larger than float error (~1e-9) yet far smaller than half a
    /// step, so it can only cancel undershoot, never push into the wrong step.
    static let STEP_EPSILON = 1e-6

    /// A consistent copy of the clock, safe to do pure math on from the audio
    /// thread. The audio path snapshots this once per block.
    struct State {
        var isRunning = false
        var anchorFrame: Int64 = 0
        var anchorTick: Double = 0
        var bpm: Double = 90
        var beatsPerBar: Double = 4
        var steps: Int = 8
        var sampleRate: Int = 0

        /// One bar in whole ticks. For every meter this app can author the
        /// product is exact (4/4 eighths 768, 12/8 768, sixteenths 768, 6/8 576,
        /// 7/8 672) — beatsPerBar is fractional, so this rounds rather than
        /// truncates.
        var ticksPerBar: Int { Int((Double(MusicalClock.PPQ) * beatsPerBar).rounded()) }
        var ticksPerStep: Int { max(1, Int((Double(ticksPerBar) / Double(max(1, steps))).rounded())) }
        var framesPerTick: Double { 60.0 * Double(sampleRate) / (Double(MusicalClock.PPQ) * bpm) }

        func tickAt(_ frame: Int64) -> Double { anchorTick + Double(frame - anchorFrame) / framesPerTick }
        func frameAt(_ tick: Double) -> Int64 { anchorFrame + Int64((tick - anchorTick) * framesPerTick) }

        func phaseAt(_ frame: Int64) -> Double {
            guard isRunning else { return 0 }
            let tpb = Double(ticksPerBar)
            let tick = tickAt(frame)
            let mod = tick.truncatingRemainder(dividingBy: tpb)
            return ((mod < 0 ? mod + tpb : mod)) / tpb
        }

        func stepAt(_ frame: Int64) -> Int {
            let phase = phaseAt(frame)
            let raw = Int((phase * Double(steps) + MusicalClock.STEP_EPSILON).rounded(.down))
            return min(max(raw, 0), max(1, steps) - 1)
        }
    }

    private let lock = NSLock()
    private var state = State()
    let sampleRate: Int

    init(sampleRate: Int) {
        self.sampleRate = sampleRate
        state.sampleRate = sampleRate
    }

    var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return state.isRunning }
    var bpm: Double { lock.lock(); defer { lock.unlock() }; return state.bpm }

    /// Begin at bar phase 0, measured from `atFrame`.
    func start(atFrame: Int64) {
        lock.lock(); defer { lock.unlock() }
        state.anchorFrame = atFrame
        state.anchorTick = 0
        state.isRunning = true
    }

    func stop() {
        lock.lock(); defer { lock.unlock() }
        state.isRunning = false
    }

    /// Set the tempo without discontinuity: re-anchor at the current position
    /// first, so elapsed ticks stay put and only future frames use the new slope.
    func setBpm(_ newBpm: Double, atFrame: Int64) {
        lock.lock(); defer { lock.unlock() }
        guard newBpm != state.bpm else { return }
        state.anchorTick = state.tickAt(atFrame)
        state.anchorFrame = atFrame
        state.bpm = newBpm
    }

    /// A beat or meter switch changes the grid; the position survives it.
    func setMeter(steps: Int, beatsPerBar: Double) {
        lock.lock(); defer { lock.unlock() }
        state.steps = max(1, steps)
        state.beatsPerBar = max(0.25, beatsPerBar)
    }

    func snapshot() -> State {
        lock.lock(); defer { lock.unlock() }
        return state
    }

    // Convenience for the UI playhead (reads a fresh snapshot).
    func tickAt(_ frame: Int64) -> Double { snapshot().tickAt(frame) }
    func frameAt(_ tick: Double) -> Int64 { snapshot().frameAt(tick) }
    func phaseAt(_ frame: Int64) -> Double { snapshot().phaseAt(frame) }
    func stepAt(_ frame: Int64) -> Int { snapshot().stepAt(frame) }
}
