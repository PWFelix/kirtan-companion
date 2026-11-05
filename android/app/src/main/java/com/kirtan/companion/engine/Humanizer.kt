package com.kirtan.companion.engine

import com.kirtan.companion.data.groupsFor
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How much "human error" the drum plays with. One control rather than a page
 * of milliseconds: each level scales every kind of variation below together.
 */
enum class Feel(val amount: Float, val label: String) {
    OFF(0f, "Off"),
    TIGHT(0.4f, "Tight"),
    HUMAN(0.75f, "Human"),
    LOOSE(1f, "Loose"),
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
 *  3. TWO HANDS. The bayan lands a few ms after the dayan, as it does on a real
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
    fun delayFrames(lane: LaneId, step: Int): Int {
        val amount = feel.amount
        if (amount <= 0f) return 0
        var ms = drift * (if (step == 0) DOWNBEAT_DRIFT_SCALE else 1.0) +
            rng.nextGaussian() * JITTER_MS
        if (lane == LaneId.BAYAN) ms += BAYAN_LAG_MS + rng.nextGaussian() * BAYAN_LAG_JITTER_MS
        ms = (ms * amount).coerceIn(-PAD_MS, MAX_LATE_MS)
        return msToFrames(PAD_MS + ms)
    }

    /** Gain for this stroke, 1.0 meaning unchanged. */
    fun gain(step: Int): Float {
        val amount = feel.amount
        if (amount <= 0f) return 1f
        val levels = accents
        val accent = if (step in levels.indices) levels[step] else OFFBEAT_LEVEL
        val shaped = 1f - amount * (1f - accent)
        val db = (rng.nextGaussian() * GAIN_JITTER_DB * amount).coerceIn(-2 * GAIN_JITTER_DB, 2 * GAIN_JITTER_DB)
        return shaped * 10.0.pow(db / 20.0).toFloat()
    }

    private fun msToFrames(ms: Double): Int = (ms * sampleRate / 1000.0).roundToInt().coerceAtLeast(0)

    companion object {
        // Loudness: linear gain at full amount.
        const val DOWNBEAT_LEVEL = 1.0f
        const val BEAT_LEVEL = 0.82f
        const val OFFBEAT_LEVEL = 0.65f
        const val GAIN_JITTER_DB = 2.5

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
    }
}
