import Foundation

// A step boundary the renderer must act on.
struct StepEvent {
    /// The ABSOLUTE frame at which the step begins.
    let frame: Int64
    /// The derived step index in the loaded beat.
    let step: Int
}

// Turns the musical clock into step boundaries.
//
// This is Sequencer.js with its scheduling inverted — the most deliberate change
// in the whole port. THE WEB PUSHES (Tone.Transport.scheduleRepeat fires a
// callback a hair ahead of the named audio time; correct timing depends on that
// callback landing in a lookahead window — hence its same-step guard and
// epsilon nudge). HERE THE RENDERER PULLS: the clock is a pure linear function of
// frames written, so a step's exact frame is computed in advance and the voice
// starts at that offset inside the block. No callback, no lookahead, no jitter —
// the trigger lands on a sample boundary, not near one.
//
// What is preserved verbatim is the DERIVATION: the step is never counted; it is
// recomputed from bar phase on every event, so it can never drift out of sync with
// the clock and survives a mid-play beat switch.
//
// Audio-thread only, apart from `beat` and the tempo/meter setters (the clock
// owns those; here only the loaded-beat reference and the duplicate guard are
// shared, and both are locked).

final class Sequencer {

    private let clock: MusicalClock
    private let lock = NSLock()
    private var beat: Beat?
    // The last frame we emitted an event for. Consecutive blocks tile the timeline
    // with no overlap, so within a stable grid nothing is emitted twice. This
    // covers the one case that can: a beat switch changes ticksPerStep, so a
    // boundary under the NEW grid can coincide with one already emitted under the
    // old. Drops a duplicate rather than letting the drum double-hit.
    private var lastEmittedFrame: Int64 = .min

    init(_ clock: MusicalClock) {
        self.clock = clock
    }

    /// The loaded beat. Setting it remaps the grid but does NOT restart the clock,
    /// so bar phase — the musical position — is preserved across the switch.
    var currentBeat: Beat? {
        lock.lock(); defer { lock.unlock() }
        return beat
    }

    func setBeat(_ value: Beat?) {
        lock.lock()
        beat = value
        lock.unlock()
        if let value {
            clock.setMeter(steps: value.steps, beatsPerBar: value.beatsPerBar)
        }
    }

    /// A beat switch re-grids the loop, so the duplicate guard must reset.
    func invalidateGuard() {
        lock.lock()
        lastEmittedFrame = .min
        lock.unlock()
    }

    /// Append every step boundary in `[fromFrame, toFrame)` to `out`.
    ///
    /// Half-open so back-to-back blocks tile with no gap and no overlap: a step
    /// exactly at `toFrame` belongs to the next block, placed at offset 0 there.
    /// Cost is O(bars spanned × steps); a block is ~5 ms while a bar is seconds
    /// long, so this is usually zero or a handful of iterations.
    func collectStepEvents(fromFrame: Int64, toFrame: Int64, into out: inout [StepEvent]) {
        guard toFrame > fromFrame else { return }
        let snap = clock.snapshot()
        guard snap.isRunning else { return }

        // Hold the guard lock across the whole sweep so the UI's invalidateGuard
        // cannot race the write-back.
        lock.lock(); defer { lock.unlock() }
        guard let current = beat else { return }

        let tpb = Double(snap.ticksPerBar)
        let tps = Double(snap.ticksPerStep)
        let steps = max(1, current.steps)

        let fromTick = snap.tickAt(fromFrame)
        let toTick = snap.tickAt(toFrame)

        let firstBar = Int(floor(fromTick / tpb))
        let lastBar = Int(ceil(toTick / tpb))

        for bar in firstBar...lastBar {
            let barTick = Double(bar) * tpb
            for s in 0..<steps {
                let tick = barTick + Double(s) * tps
                guard tick >= fromTick, tick < toTick else { continue }
                let frame = snap.frameAt(tick)
                guard frame >= fromFrame, frame < toFrame else { continue }
                guard frame > lastEmittedFrame else { continue }
                lastEmittedFrame = frame
                out.append(StepEvent(frame: frame, step: s))
            }
        }
    }

    /// The strokes one step fires, as (lane, open) pairs.
    ///
    /// Not used by the render path — that iterates the lanes inline so it
    /// allocates nothing, ten times a second forever. This exists so the beat→
    /// stroke mapping can be unit-tested without a clock or a device. An absent
    /// lane reads as a rest, which lets a beat omit the karatalas entirely.
    func strokesAt(_ beat: Beat, step: Int) -> [(LaneId, Bool)] {
        var out: [(LaneId, Bool)] = []
        for lane in beat.activeLanes() {
            guard let stroke = beat.strokeAt(lane, step) else { continue }
            out.append((lane, stroke == .open))
        }
        return out
    }
}
