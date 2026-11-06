package com.kirtan.companion.engine

import com.kirtan.companion.data.EQ_BANDS
import com.kirtan.companion.data.EQ_BAND_COUNT
import com.kirtan.companion.data.clampEqDb
import com.kirtan.companion.data.model.LaneId

/**
 * One instrument's channel: its EQ chain, then its fader.
 *
 * The signal path is the one drawn in the header of `src/engine/SoundPlayer.js`,
 * and the two decisions it records are both preserved here because they are
 * audible:
 *
 *   dayan/bayan:  voices → EQ chain → end gain ─┐
 *   kartal:       voices ───────────→ end gain ─┼→ master gain
 *
 *  1. THE EQ SITS PRE-FADER — between the voices and the end gain — so tone
 *     shaping never disturbs volume, mute, or the bayan makeup gain, which are
 *     all applied on the end gain. Put the EQ after the fader and moving a
 *     slider would change how loud the drum is.
 *  2. THE KARATALAS CARRY NO EQ. Out of scope by design: they're a separate
 *     instrument, not a head of the mridanga, so their path stays untouched.
 *     [eq] is null for them and [setEqBand] is a no-op, which is why the mixer
 *     can offer EQ controls for two ends and simply not render them for the
 *     third rather than special-casing a disabled state.
 *
 * An end's FINAL gain is `volume × makeup × (muted ? 0 : 1)`. Volume and mute
 * are kept as separate facts so that unmuting restores the user's volume
 * instead of blindly ramping back to 1 — collapsing them into one number is the
 * bug that makes a muted-then-unmuted lane come back at the wrong level.
 *
 * MAKEUP: phone speakers reproduce the bayan's bass far more weakly than the
 * dayan's ring, so the bayan carries a fixed 1.25. The mixer's "100%" therefore
 * means "balanced on a phone", not "raw sample level". The karatalas are bright
 * and cut through on their own, so they sit at 1.
 *
 * All state is touched only from the audio thread except through the setters,
 * which publish a target the thread ramps toward — so a UI slider can never
 * tear a value mid-block.
 */
class EndChannel(
    val lane: LaneId,
    private val sampleRate: Double,
) {

    companion object {
        /** Fixed per-end compensation; see MAKEUP above. */
        val MAKEUP: Map<LaneId, Float> = mapOf(
            LaneId.DAYAN to 1.0f,
            LaneId.BAYAN to 1.25f,
            LaneId.KARTAL to 1.0f,
        )

        /** Matches `rampTo(target, 0.05)` in the web engine. */
        private const val RAMP_SECONDS = 0.05
    }

    val makeup: Float = MAKEUP[lane] ?: 1.0f

    /** The five cascaded bands, or null for a lane that carries no EQ. */
    private val eq: Array<BiquadFilter>? =
        if (lane == LaneId.KARTAL) null
        else Array(EQ_BAND_COUNT) { i ->
            BiquadFilter().also { it.configure(EQ_BANDS[i], sampleRate) }
        }

    /** Does this end have a tone-shaping chain the mixer can drive? */
    val hasEq: Boolean get() = eq != null

    // ── Gain state ────────────────────────────────────────────────────────
    @Volatile private var volume: Float = 1.0f
    @Volatile private var muted: Boolean = false

    private val ramp = GainRamp(sampleRate, RAMP_SECONDS, initial = makeup)

    /** The gain in force right now — mid-ramp values included. */
    val gain: Float get() = ramp.value

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        publishTarget()
    }

    fun setMuted(value: Boolean) {
        muted = value
        publishTarget()
    }

    fun isMuted(): Boolean = muted

    fun volume(): Float = volume

    /**
     * One band's gain, clamped to the shared range. An out-of-range band index
     * is ignored rather than throwing, matching `setEqBand`'s "unknown end or
     * band is ignored safely" contract.
     */
    fun setEqBand(bandIndex: Int, db: Double) {
        val chain = eq ?: return
        val filter = chain.getOrNull(bandIndex) ?: return
        if (!db.isFinite()) return
        filter.setGain(clampEqDb(db))
    }

    fun eqBandDb(bandIndex: Int): Double? = eq?.getOrNull(bandIndex)?.gainDb

    /** Restore a whole chain — used when a saved preset is applied. */
    fun setEqBands(db: DoubleArray) {
        for (i in db.indices) setEqBand(i, db[i])
    }

    /**
     * Fold volume, mute and makeup into the ramp's target. The three stay
     * separate FIELDS and combine only here, so unmuting restores the user's
     * volume rather than ramping to a remembered-but-wrong level.
     */
    private fun publishTarget() {
        ramp.target = if (muted) 0f else volume * makeup
        ramp.retarget()
    }

    /**
     * Filter and fade one block in place.
     *
     * EQ first, then the fader — pre-fader tone shaping, per decision 1 above.
     */
    fun process(buffer: FloatArray, count: Int) {
        eq?.let { chain ->
            for (filter in chain) filter.process(buffer, 0, count)
        }
        ramp.applyTo(buffer, count)
    }
}
