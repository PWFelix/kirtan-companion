package com.kirtan.companion.engine

/**
 * One decoded drum sample: mono float PCM in [-1, 1] at the engine's rate.
 *
 * Decoding happens ONCE at load, not per trigger. The bundled recordings are
 * stereo 44.1 kHz (the karatalas are 24-bit, the mridanga ends 16-bit), while
 * the device output rate is whatever AudioTrack negotiates — usually 48 kHz.
 * Converting up front means the render loop only ever deals with the
 * [playbackRate] resampling that per-end tuning requires, and never pays for a
 * format conversion in the signal path.
 *
 * @param data mono samples, normalised
 * @param sampleRate the rate [data] is expressed in — always the engine rate
 */
class PcmSample(
    val data: FloatArray,
    val sampleRate: Int,
) {
    val frameCount: Int get() = data.size

    val durationSeconds: Double get() = if (sampleRate > 0) data.size.toDouble() / sampleRate else 0.0

    /** Silence, used when a file is missing so a stroke can degrade quietly. */
    companion object {
        fun silence(sampleRate: Int) = PcmSample(FloatArray(0), sampleRate)
    }
}
