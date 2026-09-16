package com.kirtan.companion.engine

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The musical clock — a mapping between audio FRAMES and musical TICKS.
 *
 * This replaces `Tone.Transport`, and the model is deliberately the one
 * `src/engine/Sequencer.js` explains at length, because that model is what fixed
 * a real bug rather than a theoretical one:
 *
 *   - The clock is the source of truth for "where in the bar are we?".
 *   - One bar is `ticksPerBar = PPQ × beatsPerBar` ticks long, and the loop
 *     restarts there.
 *   - A step is DERIVED from bar phase, never counted:
 *         phase = (tick mod ticksPerBar) / ticksPerBar
 *         step  = floor(phase × steps + ε)
 *     so a step index is always valid for whichever beat is loaded.
 *
 * Why derive rather than count? A counter is grid-relative — "step 12" is
 * meaningless without "out of 16". Switch to an 8-step beat mid-play and the
 * counter is suddenly an index past the end of the new pattern. Phase is
 * grid-independent: the musical position survives a beat switch, and the new
 * step is recomputed against the new grid. Deriving it also makes drift
 * impossible, because there is no accumulator to fall out of step.
 *
 * STATELESS BY DESIGN. The clock holds no frame counter of its own: the renderer
 * owns the frame position and passes it in, which keeps one authoritative count
 * instead of two that could disagree. Everything here is a pure function of
 * (anchor, tempo, frame), so [tickAt] and [frameAt] are exact inverses.
 *
 * THE FRAME-BASED DIFFERENCE FROM THE WEB. Tone schedules a JS callback and
 * trusts the audio graph to honour the time it names. Here ticks are a linear
 * function of frames written, so a step's exact frame can be computed in advance
 * and a voice started at that sample offset inside a block. No scheduler sits in
 * the signal path, hence no callback jitter to filter out.
 *
 * Setters may be called from any thread; they publish a new anchor and slope.
 * Reads are only meaningful while [isRunning].
 */
class MusicalClock(val sampleRate: Int) {

    companion object {
        /**
         * Pulses per quarter note. Matches Tone.Transport's default PPQ so the
         * tick arithmetic is the same arithmetic the web app does, and so the
         * step interval divides evenly for every standard kirtan grid:
         *    8 steps / 4 beats → 192×4/8  = 96 ticks
         *   12 steps / 4 beats → 192×4/12 = 64 ticks
         *   16 steps / 4 beats → 192×4/16 = 48 ticks
         */
        const val PPQ = 192

        /**
         * Tolerance added before flooring phase→step, for the reason documented
         * in Sequencer.js: the tick value is a float, so a boundary that should
         * be exactly 3.0 can arrive as 2.9999999 and floor to 2 — the same-step
         * guard would then fire and DROP the beat. 1e-6 is far larger than the
         * floating-point error (~1e-9) yet far smaller than half a step, so it
         * can only ever cancel undershoot, never push into the wrong step.
         */
        const val STEP_EPSILON = 1e-6
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    // ── The tempo/position anchor ──────────────────────────────────────────
    // Ticks are a linear function of frames, but the SLOPE changes with tempo.
    // Rather than rewriting history, a tempo change re-anchors: remember the
    // frame and tick we were at and measure from there. That keeps the position
    // continuous across a tempo change instead of jumping.
    @Volatile private var anchorFrame: Long = 0
    @Volatile private var anchorTick: Double = 0.0

    @Volatile
    var bpm: Double = 90.0
        private set

    @Volatile
    var beatsPerBar: Int = 4
        private set

    @Volatile
    var steps: Int = 8
        private set

    val ticksPerBar: Int get() = PPQ * beatsPerBar

    /**
     * One step in ticks. Rounded because a non-dividing steps/beatsPerBar ratio
     * would otherwise give a fractional interval; for every standard kirtan grid
     * the round is a no-op, and a sub-tick rounding beats any drift.
     */
    val ticksPerStep: Int
        get() {
            val exact = ticksPerBar.toDouble() / steps.coerceAtLeast(1)
            return exact.roundToInt().coerceAtLeast(1)
        }

    /** Frames per tick at the current tempo. */
    private val framesPerTick: Double
        get() = 60.0 * sampleRate / (PPQ * bpm)

    /** Begin at bar phase 0, measured from [atFrame]. */
    fun start(atFrame: Long) {
        anchorFrame = atFrame
        anchorTick = 0.0
        isRunning = true
    }

    fun stop() {
        isRunning = false
    }

    /**
     * Set the tempo without discontinuity. The loop bound ([ticksPerBar]) and
     * the step interval are BOTH tempo-relative musical units, so they scale
     * together and nothing needs rescheduling — the same property the web
     * sequencer relies on when it notes a tempo change needs no resched.
     */
    fun setBpm(newBpm: Double, atFrame: Long) {
        if (newBpm == bpm) return
        // Re-anchor at the current position FIRST, so the ticks already elapsed
        // stay where they are and only future frames use the new slope.
        anchorTick = tickAt(atFrame)
        anchorFrame = atFrame
        bpm = newBpm
    }

    /** A beat or meter switch changes the grid; the position survives it. */
    fun setMeter(newSteps: Int, newBeatsPerBar: Int) {
        steps = newSteps.coerceAtLeast(1)
        beatsPerBar = newBeatsPerBar.coerceAtLeast(1)
    }

    /** The absolute tick at an absolute frame. */
    fun tickAt(frame: Long): Double {
        val elapsed = frame - anchorFrame
        return anchorTick + elapsed / framesPerTick
    }

    /** The absolute frame at which an absolute tick occurs. */
    fun frameAt(tick: Double): Long =
        anchorFrame + ((tick - anchorTick) * framesPerTick).toLong()

    /** Bar phase in [0,1) at a frame. 0 while stopped. */
    fun phaseAt(frame: Long): Double {
        if (!isRunning) return 0.0
        val tpb = ticksPerBar.toDouble()
        val tick = tickAt(frame)
        return ((tick % tpb) + tpb) % tpb / tpb
    }

    /** The step index in force at a frame. */
    fun stepAt(frame: Long): Int {
        val phase = phaseAt(frame)
        return floor(phase * steps + STEP_EPSILON).toInt().coerceIn(0, steps.coerceAtLeast(1) - 1)
    }
}
