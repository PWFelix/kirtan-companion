package com.kirtan.companion.engine

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A step boundary the renderer must act on.
 *
 * @param frame the ABSOLUTE frame at which the step begins
 * @param step the derived step index in the loaded beat
 */
data class StepEvent(val frame: Long, val step: Int)

/**
 * Turns the musical clock into step boundaries.
 *
 * This is `src/engine/Sequencer.js` with its scheduling inverted, and the
 * inversion is the most deliberate change in the whole port.
 *
 * THE WEB PUSHES. `Tone.Transport.scheduleRepeat` registers a callback, the
 * transport fires it slightly ahead of the audio time it names, and the callback
 * passes that time down to `player.start(time)`. Correct timing therefore
 * depends on a JS callback landing inside a lookahead window — which is why
 * that file carries a same-step guard, a float-epsilon nudge, and a comment
 * about callbacks arriving "a hair late".
 *
 * HERE THE RENDERER PULLS. The clock is a pure linear function of frames
 * written, so a step's exact frame can be computed in advance and the voice
 * started at that offset inside the block being rendered. There is no callback,
 * no lookahead window and no jitter to guard against — the trigger is placed on
 * a sample boundary rather than scheduled near one.
 *
 * What is preserved verbatim is the DERIVATION, because that is what fixed a
 * real bug rather than a theoretical one. The step is never counted; it is
 * recomputed from bar phase on every event:
 *
 *     phase = (tick mod ticksPerBar) / ticksPerBar
 *     step  = floor(phase × steps + ε)
 *
 * A counted step is grid-relative — "step 12" means nothing without "out of 16"
 * — so switching from a 16-step to an 8-step beat mid-play would leave the
 * counter indexing past the end of the new pattern. Phase survives the switch,
 * and re-reading the clock means the step can never drift out of sync with it.
 *
 * Audio-thread only, apart from [beat] and the tempo/meter setters.
 */
class Sequencer(private val clock: MusicalClock) {

    /**
     * The loaded beat. Setting it remaps the grid but does NOT restart the
     * clock, so bar phase — and therefore the musical position — is preserved
     * across the switch. That is the entire reason the derivation above works:
     * `setBeat` on the web needs no remap of a stored counter for the same
     * reason.
     */
    @Volatile
    var beat: Beat? = null
        set(value) {
            field = value
            if (value != null) clock.setMeter(value.steps, value.beatsPerBar)
        }

    // The last frame we emitted an event for. Consecutive blocks share a
    // boundary exactly ([from, to) ranges), so within a stable grid nothing can
    // be emitted twice. This guard covers the one case that can: a beat switch
    // changes ticksPerStep, so a boundary under the NEW grid can coincide with
    // one already emitted under the old. It is the pull-model counterpart of the
    // web sequencer's same-step guard, and it drops a duplicate rather than
    // letting the drum double-hit.
    private var lastEmittedFrame: Long = Long.MIN_VALUE

    /** A beat switch re-grids the loop, so the duplicate guard must reset. */
    fun invalidateGuard() {
        lastEmittedFrame = Long.MIN_VALUE
    }

    /**
     * Append every step boundary falling in `[fromFrame, toFrame)` to [out].
     *
     * The range is half-open so that back-to-back blocks tile the timeline with
     * no gap and no overlap: a step exactly at `toFrame` belongs to the next
     * block, and will be placed at offset 0 there.
     *
     * Cost is O(bars spanned × steps), and a block is ~5 ms while a bar is
     * seconds long, so this is normally a handful of iterations and usually
     * zero.
     */
    fun collectStepEvents(fromFrame: Long, toFrame: Long, out: MutableList<StepEvent>) {
        val current = beat ?: return
        if (!clock.isRunning || toFrame <= fromFrame) return

        val tpb = clock.ticksPerBar.toDouble()
        val tps = clock.ticksPerStep.toDouble()
        val steps = current.steps.coerceAtLeast(1)

        val fromTick = clock.tickAt(fromFrame)
        val toTick = clock.tickAt(toFrame)

        val firstBar = floor(fromTick / tpb).toLong()
        val lastBar = ceil(toTick / tpb).toLong()

        for (bar in firstBar..lastBar) {
            val barTick = bar * tpb
            for (s in 0 until steps) {
                val tick = barTick + s * tps
                if (tick < fromTick || tick >= toTick) continue

                val frame = clock.frameAt(tick)
                if (frame < fromFrame || frame >= toFrame) continue
                if (frame <= lastEmittedFrame) continue

                lastEmittedFrame = frame
                out.add(StepEvent(frame, s))
            }
        }
    }

    /**
     * The strokes one step fires, as (lane, open) pairs.
     *
     * NOT used by the render path — that iterates the lanes inline so it
     * allocates nothing, ten times a second forever. This exists so the mapping
     * from beat data to strokes can be unit-tested without a clock, an audio
     * thread or a device. An absent lane reads as a rest, which is what lets a
     * beat omit the karatalas entirely.
     */
    fun strokesAt(beat: Beat, step: Int): List<Pair<LaneId, Boolean>> {
        val out = ArrayList<Pair<LaneId, Boolean>>(3)
        for (lane in beat.activeLanes()) {
            val stroke = beat.strokeAt(lane, step) ?: continue
            out.add(lane to (stroke == Stroke.OPEN))
        }
        return out
    }
}
