package com.kirtan.companion.engine

import com.kirtan.companion.data.EqBand
import com.kirtan.companion.data.FilterType
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A direct-form-I biquad, coefficient-compatible with the Web Audio
 * `BiquadFilterNode` that `Tone.Filter` wraps.
 *
 * WHY BIT-EXACT COEFFICIENTS MATTER: the five-band chain in
 * [com.kirtan.companion.data.EQ_BANDS] was voiced by ear against the web app's
 * filters, and the same dB value has to mean the same curve on both platforms —
 * a user's saved EQ preset is shared through the cloud and must not sound
 * different on Android. So these follow the RBJ Audio EQ Cookbook exactly as
 * the Web Audio spec implements it.
 *
 * The one place the spec differs from the raw cookbook, and it is easy to get
 * wrong: **Q is IGNORED for shelf filters.** The spec fixes the shelf slope at
 * S = 1, which collapses `(A + 1/A)(1/S - 1) + 2` to `2`, so
 * `alpha = sin(w0)/2 * sqrt(2)`. That is why [EqBand.q] is null for the two
 * shelves — passing Q = 1 there would be harmless, but passing any other value
 * would silently do nothing, so the null says "not applicable" rather than
 * "defaulted".
 *
 * State is two delay registers per instance, so a chain of five costs ten
 * floats. Filters are NOT thread-safe: one instance belongs to one end channel
 * and is only ever touched from the audio thread.
 */
class BiquadFilter {

    // Normalised coefficients: a0 is divided out at build time, so the
    // recurrence below needs no per-sample division.
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Delay registers (Direct Form I).
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private var sampleRate = 0.0
    private var band: EqBand? = null

    /** Currently applied gain in dB. Unity (0 dB) makes the filter a wire. */
    var gainDb: Double = 0.0
        private set

    /**
     * Point the filter at a band and a sample rate. Coefficients depend on both,
     * so this must be called before the first [process] and again if either
     * changes (the rate never does in practice; a preset change does).
     */
    fun configure(band: EqBand, sampleRate: Double) {
        this.band = band
        this.sampleRate = sampleRate
        recompute(gainDb)
    }

    /**
     * Set the gain and rebuild the coefficients.
     *
     * The web app ramps this over 50 ms (`filter.gain.rampTo(db, 0.05)`). The
     * ramp lives in [EndChannel], not here: rebuilding coefficients per sample
     * would be wasteful, and a coefficient jump at block granularity is
     * inaudible where a gain jump would click.
     */
    fun setGain(db: Double) {
        if (db == gainDb) return
        gainDb = db
        recompute(db)
    }

    /** Drop the delay registers — e.g. when the chain is re-voiced. */
    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    private fun recompute(db: Double) {
        val b = band ?: return
        if (sampleRate <= 0.0) return

        // A = 10^(dB/40). Note this is sqrt(10^(dB/20)): the cookbook's A is a
        // *amplitude* ratio whose square is the power ratio the dB names.
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * Math.PI * b.frequency / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)

        when (b.type) {
            FilterType.PEAKING -> {
                val q = b.q ?: 1.0
                val alpha = sinW0 / (2.0 * q)
                val a0 = 1.0 + alpha / a
                b0 = (1.0 + alpha * a) / a0
                b1 = (-2.0 * cosW0) / a0
                b2 = (1.0 - alpha * a) / a0
                a1 = (-2.0 * cosW0) / a0
                a2 = (1.0 - alpha / a) / a0
            }

            FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> {
                // Shelf slope is fixed at S = 1 by the spec — Q is not used.
                val alpha = sinW0 / 2.0 * sqrt(2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                val aPlusOne = a + 1.0
                val aMinusOne = a - 1.0

                if (b.type == FilterType.LOW_SHELF) {
                    val a0 = aPlusOne + aMinusOne * cosW0 + twoSqrtAAlpha
                    b0 = a * (aPlusOne - aMinusOne * cosW0 + twoSqrtAAlpha) / a0
                    b1 = 2.0 * a * (aMinusOne - aPlusOne * cosW0) / a0
                    b2 = a * (aPlusOne - aMinusOne * cosW0 - twoSqrtAAlpha) / a0
                    a1 = -2.0 * (aMinusOne + aPlusOne * cosW0) / a0
                    a2 = (aPlusOne + aMinusOne * cosW0 - twoSqrtAAlpha) / a0
                } else {
                    val a0 = aPlusOne - aMinusOne * cosW0 + twoSqrtAAlpha
                    b0 = a * (aPlusOne + aMinusOne * cosW0 + twoSqrtAAlpha) / a0
                    b1 = -2.0 * a * (aMinusOne + aPlusOne * cosW0) / a0
                    b2 = a * (aPlusOne + aMinusOne * cosW0 - twoSqrtAAlpha) / a0
                    a1 = 2.0 * (aMinusOne - aPlusOne * cosW0) / a0
                    a2 = (aPlusOne - aMinusOne * cosW0 - twoSqrtAAlpha) / a0
                }
            }
        }
    }

    /** Filter one block in place. */
    fun process(buffer: FloatArray, offset: Int, count: Int) {
        val cB0 = b0; val cB1 = b1; val cB2 = b2
        val cA1 = a1; val cA2 = a2
        var lx1 = x1; var lx2 = x2; var ly1 = y1; var ly2 = y2

        for (i in offset until offset + count) {
            val x0 = buffer[i].toDouble()
            val y0 = cB0 * x0 + cB1 * lx1 + cB2 * lx2 - cA1 * ly1 - cA2 * ly2
            buffer[i] = y0.toFloat()
            lx2 = lx1; lx1 = x0
            ly2 = ly1; ly1 = y0
        }

        x1 = lx1; x2 = lx2; y1 = ly1; y2 = ly2
    }
}
