package com.kirtan.companion.engine

/**
 * One sounding sample.
 *
 * A voice is created the instant a stroke is triggered and lives until its
 * recording runs out — there is no envelope, because the decay IS the recording.
 * That is the whole reason the app is sample-based rather than synthesised: a
 * mridanga's ring is not something you want to model.
 *
 * [startOffset] places the voice at an exact frame WITHIN the block it was
 * triggered in, which is what makes timing sample-accurate. A stroke scheduled
 * for frame 137 of a 256-frame block contributes silence for 137 frames and
 * then its first sample at 137 — no rounding to the block boundary, so the grid
 * cannot smear. It is consumed on the first render and zeroed, so every later
 * block starts at 0.
 *
 * Resampling by [rate] is what per-end tuning does: the pitch slider sets a
 * playback rate, and a higher rate both raises the pitch and shortens the decay
 * a touch, exactly as tightening a real head would. Linear interpolation is
 * used because the rate is at most ±600 cents (×0.71 to ×1.41) and the material
 * is broadband percussion; a table-driven cubic interpolator would be the next
 * step up if the tuning range were ever widened or a pitched instrument added.
 */
class Voice(
    val sample: PcmSample,
    var startOffset: Int,
) {
    /** Fractional read position in the sample, in source frames. */
    var position: Double = 0.0
        private set

    var finished: Boolean = false
        private set

    /**
     * Add this voice into [out] for [count] frames, advancing by [rate] per
     * output frame.
     */
    fun render(out: FloatArray, count: Int, rate: Double) {
        val data = sample.data
        val last = data.size - 1
        if (last <= 0) {
            finished = true
            return
        }

        // The rate is read per block rather than latched at trigger time, so
        // moving the tune slider audibly bends a drum that is still ringing —
        // which is what Tone.Player.playbackRate does, and what a player
        // expects from something labelled like head tension.
        val step = if (rate > 0.0) rate else 1.0

        var i = if (startOffset > 0) startOffset else 0
        startOffset = 0
        var pos = position

        while (i < count && pos < last) {
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val s0 = data[i0]
            out[i] += s0 + (data[i0 + 1] - s0) * frac
            pos += step
            i++
        }

        position = pos
        if (pos >= last) finished = true
    }
}
