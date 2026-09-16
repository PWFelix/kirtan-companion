package com.kirtan.companion.playback

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Output-device facts the engine needs before it can be constructed.
 *
 * Kept separate from [KirtanEngine] so the engine itself stays free of `Context`
 * and remains constructible in a plain JVM test with nothing but a sample rate.
 */
object AudioDeviceInfo {

    private const val TAG = "AudioDeviceInfo"

    /**
     * The rate to decode samples at and open the output device with.
     *
     * Asking for the device's NATIVE rate rather than assuming 48 kHz matters
     * twice over: the platform resamples anything that does not match, adding
     * latency and a SRC stage we cannot control, and the bundled recordings are
     * 44.1 kHz so a mismatched choice would mean resampling twice. Reading the
     * property lets [com.kirtan.companion.engine.WavDecoder] do the single
     * conversion straight to the hardware rate.
     *
     * The property is a String and may be absent or zero on some devices, so it
     * is parsed defensively and falls back to 48 kHz — the rate almost every
     * Android device actually runs at.
     */
    fun sampleRate(context: Context): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val reported = manager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()

        return if (reported != null && reported > 0) {
            reported
        } else {
            Log.w(TAG, "no PROPERTY_OUTPUT_SAMPLE_RATE (got $reported); assuming 48000")
            48_000
        }
    }

    /**
     * The device's native output frame count per burst, when reported.
     *
     * Informational: [com.kirtan.companion.engine.AudioRenderer] sizes its own
     * buffer from `AudioTrack.getMinBufferSize`, which is the value that actually
     * governs whether writes block. This exists for a diagnostics readout, so a
     * stutter on one specific handset can be explained rather than guessed at.
     */
    fun framesPerBurst(context: Context): Int? =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
}
