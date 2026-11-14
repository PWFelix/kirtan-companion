package com.kirtan.companion.engine

/**
 * A linear gain ramp, advanced once per rendered frame.
 *
 * Exists because the web engine expresses every volume change as
 * `gain.rampTo(target, 0.05)` — Web Audio's `linearRampToValueAtTime`. Setting
 * a gain abruptly instead produces a step in the waveform, which on a drum is a
 * click; and doing the smoothing with a one-pole filter instead would reach the
 * target exponentially, so a slider move would land at a different time on
 * Android than on the web. Both the master fader and every end channel use
 * this, so the two can't drift apart.
 *
 * Not thread-safe. The target is published from the UI thread through a
 * `@Volatile` field on the owner; [advance] runs only on the audio thread.
 *
 * @param sampleRate the engine's output rate
 * @param seconds ramp duration; 0.05 to match the web engine
 */
class GainRamp(
    private val sampleRate: Double,
    private val seconds: Double = 0.05,
    initial: Float = 1f,
) {
    /** The gain currently in force, mid-ramp values included. */
    var value: Float = initial
        private set

    /** Where we are heading. Written by the owner, read by [advance]. */
    var target: Float = initial

    private var step: Float = 0f

    /**
     * Re-target and recompute the per-frame increment. Called whenever the
     * facts behind the target change (volume, mute), from the owner's setter —
     * never from the audio thread's inner loop.
     */
    fun retarget() {
        val frames = (seconds * sampleRate).toFloat()
        step = if (frames > 0f) (target - value) / frames else 0f
    }

    /**
     * Multiply [count] frames of [buffer] by the ramping gain, advancing it.
     * Snaps to the target on overshoot so the ramp can never oscillate around
     * it, and fills the rest of the block at the settled value.
     */
    fun applyTo(buffer: FloatArray, count: Int) {
        var g = value
        val s = step

        if (s == 0f) {
            // Settled. Still a loop rather than a skip: a muted channel sits at
            // exactly 0 and must silence the block.
            if (g != 1f) {
                for (i in 0 until count) buffer[i] *= g
            }
            return
        }

        val t = target
        for (i in 0 until count) {
            buffer[i] *= g
            g += s
            if ((s > 0f && g >= t) || (s < 0f && g <= t)) {
                value = t
                step = 0f
                for (j in i + 1 until count) buffer[j] *= t
                return
            }
        }
        value = g
    }
}
