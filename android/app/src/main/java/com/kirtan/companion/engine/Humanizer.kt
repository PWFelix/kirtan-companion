package com.kirtan.companion.engine

import com.kirtan.companion.data.groupsFor
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How much "human error" the drum plays with. One control rather than a page
 * of milliseconds: each level scales every kind of variation below together.
 */
enum class Feel(val amount: Float, val label: String, val dragMs: Double = 0.0) {
    OFF(0f, "Off"),
    TIGHT(0.4f, "Tight"),
    HUMAN(0.75f, "Human"),
    LOOSE(1f, "Loose"),

    /**
     * Laid back, the Bengali way: Human's variation, plus the drum sitting
     * BEHIND the beat and falling further behind as the cycle goes on — it
     * feels like it is slowing through the tal — then landing sam on time, so
     * the "1" the singers lean on never moves. The tempo itself is unchanged.
     */
    DRAG(0.75f, "Drag", dragMs = 30.0),
}

/**
 * Small, deliberate imperfections that stop the loop sounding like a machine.
 *
 * EXPERIMENT. The numbers in the companion object are first guesses, meant to
 * be tuned by ear. Three things vary, all scaled by [Feel.amount]:
 *
 *  1. LOUDNESS. The downbeat is strongest, other beat starts a little softer,
 *     the cells between softer again, plus a random ±dB on every stroke. Even
 *     with round-robin samples, identical levels are a big part of the
 *     "machine gun" sound.
 *  2. TIMING DRIFT. Not fresh noise per stroke — that sounds sloppy — but a
 *     slow random walk pulled back towards the grid, so the feel wanders the way
 *     a player's does, plus a little per-stroke jitter on top. The downbeat
 *     drifts less, so the "1" stays solid for people singing.
 *  3. DRAG (only [Feel.DRAG]). Two deliberate, non-random latenesses:
 *     a small laid-back offset ([Feel.dragMs]: [DRAG_SAM_SCALE] of it on sam,
 *     [DRAG_BEAT_SCALE] on beat starts, all of it between), plus a RAMP that
 *     grows through the bar — position^[DRAG_RAMP_CURVE] × [DRAG_RAMP_STEPS]
 *     of a step, capped at [DRAG_RAMP_MAX_MS] — so each gap is a little longer
 *     than the last and the cycle sounds like it is slowing. Over the bar's
 *     LAST beat it eases back to sam's own small lateness — a few gaps each a
 *     little short, rather than one gap a quarter short at the loop point,
 *     which a user heard as the loop "jumping" when it came round again. The ramp is in steps, not ms, so it feels the same at
 *     any tempo. Because none of it is random it reads as a style, not sloppiness.
 *  4. TWO HANDS. The bayan lands a few ms after the dayan, as it does on a real
 *     two-headed drum, rather than both samples starting on the same frame.
 *
 * EARLY HITS. A stroke can't sound before the block it's placed in, so when the
 * feel is on, every stroke is delayed by a fixed [PAD_MS] and the variation is
 * applied around that. The whole drum then sits ~18 ms behind the playhead,
 * which is hard to see.
 *
 * REPEATABLE. The random stream is reseeded from the beat on every start, so a
 * beat has the same feel each time it plays — useful for comparing settings
 * and for bug reports.
 *
 * Audio thread only, except [feel] and [setBeat], which swap whole values.
 */
class Humanizer(private val sampleRate: Int) {

    @Volatile
    var feel: Feel = Feel.OFF

    /** Per-step accent level (1 = downbeat), rebuilt off the audio thread. */
    @Volatile
    private var accents: FloatArray = FloatArray(0)

    @Volatile
    private var seed: Long = 0L

    private val rng = Random(0L)

    /** Steps where Ge and Ta (bayan open + dayan open) fall together: Drag flams them. */
    @Volatile
    private var flamSteps: BooleanArray = BooleanArray(0)

    /** Per-step share of [Feel.dragMs]: least on sam, most between beats. */
    @Volatile
    private var dragShape: FloatArray = FloatArray(0)

    /** The step where the drag stops growing and eases back to sam: the last beat. */
    @Volatile
    private var releaseStep: Int = 0

    /** The slow timing walk, in ms; positive is late. */
    private var drift = 0.0

    fun setBeat(beat: Beat) {
        val levels = FloatArray(beat.steps.coerceAtLeast(1)) { OFFBEAT_LEVEL }
        var start = 0
        for (group in groupsFor(beat)) {
            if (start in levels.indices) levels[start] = BEAT_LEVEL
            start += group
        }
        levels[0] = DOWNBEAT_LEVEL
        accents = levels
        val dayan = beat.pattern(LaneId.DAYAN)
        val bayan = beat.pattern(LaneId.BAYAN)
        flamSteps = BooleanArray(levels.size) {
            dayan?.getOrNull(it) == Stroke.OPEN && bayan?.getOrNull(it) == Stroke.OPEN
        }
        dragShape = FloatArray(levels.size) {
            when (levels[it]) {
                DOWNBEAT_LEVEL -> DRAG_SAM_SCALE
                BEAT_LEVEL -> DRAG_BEAT_SCALE
                else -> 1f
            }
        }
        releaseStep = releaseStepFor(groupsFor(beat), levels.size)
        seed = (beat.id ?: beat.name).hashCode().toLong()
    }

    /** Back to the start of the beat's random stream. Call from the audio thread on start. */
    fun reset() {
        rng.setSeed(seed)
        drift = 0.0
    }

    /** The fixed delay every stroke carries while the feel is on, in frames. */
    fun padFrames(): Int = if (feel.amount > 0f) msToFrames(PAD_MS) else 0

    /** Advance the timing walk by one step. Call once per step, before its strokes. */
    fun advance() {
        if (feel.amount <= 0f) return
        drift = (drift * DRIFT_PULL + rng.nextGaussian() * DRIFT_STEP_MS)
            .coerceIn(-DRIFT_MAX_MS, DRIFT_MAX_MS)
    }

    /**
     * Frames to delay this stroke from its grid position, including the pad.
     * Never negative.
     */
    fun delayFrames(lane: LaneId, step: Int, stepMs: Double = 0.0): Int {
        val amount = feel.amount
        if (amount <= 0f) return 0
        var ms = drift * (if (step == 0) DOWNBEAT_DRIFT_SCALE else 1.0) +
            rng.nextGaussian() * JITTER_MS
        val flam = feel == Feel.DRAG && flamSteps.getOrElse(step) { false }
        // On a flam the bayan LEADS, so it loses its usual lag behind the dayan.
        if (lane == LaneId.BAYAN && !flam) ms += BAYAN_LAG_MS + rng.nextGaussian() * BAYAN_LAG_JITTER_MS
        ms = (ms * amount).coerceIn(-PAD_MS, MAX_LATE_MS)
        val shape = dragShape
        ms += feel.dragMs * (if (step in shape.indices) shape[step] else 1f)
        // Ge+Ta in Drag: a flam, the bottom end first and the Ta a grace later.
        // Sam's is wider: the "1" is announced, not just struck.
        if (flam && lane == LaneId.DAYAN) ms += if (step == 0) FLAM_SAM_MS else FLAM_MS
        if (feel.dragMs > 0.0 && shape.isNotEmpty()) {
            ms += rampMs(step, shape.size, stepMs, releaseStep)
            // Sam too sits back: the ramp resets there, but not onto the grid.
            if (step == 0) ms += samDragMs(stepMs)
        }
        return msToFrames(PAD_MS + ms)
    }

    /**
     * Gain for this stroke, 1.0 meaning unchanged.
     *
     * Bounded to [MIN_GAIN, 1]: a feel may shade a stroke, never lose it. The
     * first version let Loose stack a -3.7 dB off-beat level on -5 dB of jitter
     * while boosting accents above unity into the limiter — ~10 dB between
     * neighbours, and an off-beat Ta at 40 BPM simply vanished on a phone
     * speaker. Accents now come only from lowering the rest, never from boosting.
     */
    fun gain(step: Int): Float {
        val amount = feel.amount
        if (amount <= 0f) return 1f
        val levels = accents
        val accent = if (step in levels.indices) levels[step] else OFFBEAT_LEVEL
        val shaped = 1f - amount * (1f - accent)
        val db = (rng.nextGaussian() * GAIN_JITTER_DB * amount).coerceIn(-2 * GAIN_JITTER_DB, 2 * GAIN_JITTER_DB)
        return (shaped * 10.0.pow(db / 20.0).toFloat()).coerceIn(MIN_GAIN, 1f)
    }

    /**
     * The progressive part of the drag: 0 on sam, growing until [release] (the
     * last beat), then easing linearly down to what sam carries ([samDragMs])
     * so the loop point is just one more of those easing gaps.
     */
    internal fun rampMs(step: Int, steps: Int, stepMs: Double, release: Int = releaseStepFor(emptyList(), steps)): Double {
        val n = steps.coerceAtLeast(1)
        fun grow(at: Int) = ((at.toDouble() / n).pow(DRAG_RAMP_CURVE) * DRAG_RAMP_STEPS * stepMs)
            .coerceAtMost(DRAG_RAMP_MAX_MS)
        if (step == 0 || release !in 1 until n || step <= release) return grow(step)
        val peak = grow(release)
        val t = (step - release).toDouble() / (n - release)
        return peak + (samDragMs(stepMs) - peak) * t
    }

    private fun samDragMs(stepMs: Double) = (DRAG_SAM_STEPS * stepMs).coerceAtMost(DRAG_SAM_MAX_MS)

    private fun msToFrames(ms: Double): Int = (ms * sampleRate / 1000.0).roundToInt().coerceAtLeast(0)

    companion object {
        /**
         * Where the drag starts easing back: the last beat's first step, or the
         * last quarter of the bar when the meter is one long group.
         */
        internal fun releaseStepFor(groups: List<Int>, steps: Int): Int {
            var start = 0
            var last = 0
            for (g in groups) { if (start < steps) last = start; start += g }
            return if (last in 1 until steps) last else steps * 3 / 4
        }

        // Loudness: linear gain at full amount.
        const val DOWNBEAT_LEVEL = 1.0f
        const val BEAT_LEVEL = 0.82f
        const val OFFBEAT_LEVEL = 0.75f
        const val GAIN_JITTER_DB = 1.5
        /** The quietest any stroke may be: -4.4 dB, audible on a phone speaker. */
        const val MIN_GAIN = 0.6f

        // Timing, in ms at full amount.
        const val PAD_MS = 18.0
        const val MAX_LATE_MS = 40.0
        const val DRIFT_PULL = 0.9
        const val DRIFT_STEP_MS = 3.0
        const val DRIFT_MAX_MS = 15.0
        const val DOWNBEAT_DRIFT_SCALE = 0.4
        const val JITTER_MS = 3.0
        const val BAYAN_LAG_MS = 14.0
        const val BAYAN_LAG_JITTER_MS = 3.0

        // Drag: share of Feel.dragMs by position in the bar.
        const val DRAG_SAM_SCALE = 0.3f
        const val DRAG_BEAT_SCALE = 0.7f
        /** Lateness reached by the end of the bar, in steps. */
        const val DRAG_RAMP_STEPS = 0.45
        /** >1: the slowing gathers towards the end of the cycle rather than evenly. */
        const val DRAG_RAMP_CURVE = 1.6
        /** However slow the tempo, never more than this behind. */
        const val DRAG_RAMP_MAX_MS = 200.0
        /** Drag's Ge+Ta flam: how far the dayan's Ta trails the bayan's Ge. */
        const val FLAM_MS = 28.0
        /** ...and on sam, wider. */
        const val FLAM_SAM_MS = 50.0
        /** Drag's lateness on sam itself, in steps — the "1" is late too, just less. */
        const val DRAG_SAM_STEPS = 0.15
        const val DRAG_SAM_MAX_MS = 80.0
    }
}
