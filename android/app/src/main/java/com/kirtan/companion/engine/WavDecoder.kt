package com.kirtan.companion.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal RIFF/WAVE decoder.
 *
 * Written by hand rather than using `MediaExtractor`/`SoundPool` for three
 * reasons:
 *
 *  1. THE MIXER NEEDS RAW PCM. Every other Android audio API wants to own
 *     playback — SoundPool triggers its own voices, MediaPlayer decodes to its
 *     own output. Neither can be summed through a per-end biquad chain at a
 *     caller-chosen gain, which is exactly what the routing in SoundPlayer.js
 *     specifies.
 *  2. THE BUNDLED FILES ARE NOT ONE FORMAT. The mridanga ends are 16-bit and
 *     the karatalas are 24-bit, both stereo at 44.1 kHz. A decoder that assumed
 *     one bit depth would silently fail on the cymbals — the newest lane, and
 *     the one least likely to have been exercised.
 *  3. IT STAYS PURE JVM. No Android imports, so it runs in an ordinary unit
 *     test with a byte array, which is the only way to check the 24-bit and
 *     stereo paths without a device.
 *
 * Only the `fmt ` and `data` chunks are read; everything else (LIST, fact,
 * padding) is skipped by length. Chunk order is not assumed, because encoders
 * vary.
 *
 * The roadmap's "record or upload custom sample sounds" lands here: user files
 * arrive in whatever the recorder produced, so every common PCM width is
 * handled rather than only the two the bundled set uses.
 */
object WavDecoder {

    private const val FORMAT_PCM = 1
    private const val FORMAT_IEEE_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    class WavFormatException(message: String) : IllegalArgumentException(message)

    /**
     * Decode a WAV file to mono float PCM at [targetRate].
     *
     * @throws WavFormatException if the bytes are not a RIFF/WAVE file this
     *   decoder understands. Callers decide whether that is fatal — the sample
     *   bank treats it as "this stroke is silent", matching the web loader's
     *   rule that one absent or broken file must not sink `loadSounds()`.
     */
    fun decode(bytes: ByteArray, targetRate: Int): PcmSample {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (bytes.size < 12) throw WavFormatException("file too short to be a RIFF file")
        if (riffTag(buf, 0) != "RIFF") throw WavFormatException("missing RIFF header")
        if (riffTag(buf, 8) != "WAVE") throw WavFormatException("not a WAVE file")

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var formatTag = 0
        var dataOffset = -1
        var dataLength = 0

        // Walk the chunks. Each is an 8-byte header (fourcc + size) then `size`
        // bytes of payload, padded to an even boundary.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = riffTag(buf, pos)
            val size = buf.getInt(pos + 4)
            if (size < 0) break
            val payload = pos + 8

            when (id) {
                "fmt " -> {
                    if (payload + 16 > bytes.size) throw WavFormatException("truncated fmt chunk")
                    formatTag = buf.getShort(payload).toInt() and 0xFFFF
                    channels = buf.getShort(payload + 2).toInt() and 0xFFFF
                    sampleRate = buf.getInt(payload + 4)
                    bitsPerSample = buf.getShort(payload + 14).toInt() and 0xFFFF

                    // WAVE_FORMAT_EXTENSIBLE carries the real tag in the
                    // SubFormat GUID's first two bytes; read it rather than
                    // rejecting the file.
                    if (formatTag == FORMAT_EXTENSIBLE && payload + 26 <= bytes.size) {
                        formatTag = buf.getShort(payload + 24).toInt() and 0xFFFF
                    }
                }

                "data" -> {
                    dataOffset = payload
                    // Trust the smaller of the declared size and the bytes
                    // actually present: truncated recordings are common and
                    // should decode what they have rather than fail.
                    dataLength = minOf(size, bytes.size - payload)
                }
            }

            pos = payload + size + (size and 1)
        }

        if (dataOffset < 0) throw WavFormatException("no data chunk")
        if (channels <= 0) throw WavFormatException("channel count is $channels")
        if (sampleRate <= 0) throw WavFormatException("sample rate is $sampleRate")
        if (dataLength <= 0) return PcmSample(FloatArray(0), targetRate)

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) throw WavFormatException("bit depth is $bitsPerSample")

        val frameBytes = bytesPerSample * channels
        val frameCount = dataLength / frameBytes

        // Decode interleaved frames to mono floats in one pass.
        val mono = FloatArray(frameCount)
        when {
            formatTag == FORMAT_IEEE_FLOAT && bitsPerSample == 32 -> {
                for (f in 0 until frameCount) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += buf.getFloat(dataOffset + f * frameBytes + c * 4)
                    }
                    mono[f] = sum / channels
                }
            }

            formatTag == FORMAT_PCM -> when (bitsPerSample) {
                8 -> for (f in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until channels) {
                        // 8-bit WAV is UNSIGNED, centred on 128 — the one width
                        // that is not two's complement.
                        sum += (buf.get(dataOffset + f * frameBytes + c).toInt() and 0xFF) - 128
                    }
                    mono[f] = sum.toFloat() / channels / 128f
                }

                16 -> for (f in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until channels) {
                        sum += buf.getShort(dataOffset + f * frameBytes + c * 2).toInt()
                    }
                    mono[f] = sum.toFloat() / channels / 32768f
                }

                24 -> for (f in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until channels) {
                        sum += read24(buf, dataOffset + f * frameBytes + c * 3)
                    }
                    mono[f] = sum.toFloat() / channels / 8388608f
                }

                32 -> for (f in 0 until frameCount) {
                    var sum = 0L
                    for (c in 0 until channels) {
                        sum += buf.getInt(dataOffset + f * frameBytes + c * 4).toLong()
                    }
                    mono[f] = (sum.toDouble() / channels / 2147483648.0).toFloat()
                }

                else -> throw WavFormatException("unsupported PCM bit depth $bitsPerSample")
            }

            else -> throw WavFormatException("unsupported format tag $formatTag")
        }

        return if (sampleRate == targetRate) {
            PcmSample(mono, targetRate)
        } else {
            PcmSample(resampleLinear(mono, sampleRate, targetRate), targetRate)
        }
    }

    /**
     * Three little-endian bytes, sign-extended from 24 to 32 bits.
     *
     * Written with explicit parentheses because `shl`, `or` and `shr` are infix
     * functions of EQUAL precedence in Kotlin and associate left to right — the
     * unparenthesised form silently evaluates in the wrong order. The
     * sign-extension is the usual trick: shift bit 23 up into the sign bit, then
     * arithmetic-shift back so it replicates.
     */
    private fun read24(buf: ByteBuffer, at: Int): Int {
        val b0 = buf.get(at).toInt() and 0xFF
        val b1 = buf.get(at + 1).toInt() and 0xFF
        val b2 = buf.get(at + 2).toInt() and 0xFF
        val raw = b0 or (b1 shl 8) or (b2 shl 16)
        return (raw shl 8) shr 8
    }

    private fun riffTag(buf: ByteBuffer, at: Int): String = buildString(4) {
        for (i in 0 until 4) append(buf.get(at + i).toInt().toChar())
    }

    /**
     * Linear-interpolating resampler.
     *
     * Linear interpolation is enough here and the choice is deliberate. The
     * bundled set is 44.1 kHz and devices are almost always 48 kHz, so this is
     * UPSAMPLING by 1.088 — there is no aliasing to prevent, only mild imaging
     * well above the drums' content. A windowed-sinc resampler would be more
     * correct and measurably slower at load; if a future sample set ships at a
     * rate far from the device's, or downsampling appears, this is the function
     * to replace.
     */
    internal fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLength = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val last = input.size - 1

        for (i in 0 until outLength) {
            val pos = i * ratio
            val i0 = pos.toInt()
            if (i0 >= last) {
                out[i] = input[last]
                continue
            }
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] + (input[i0 + 1] - input[i0]) * frac
        }
        return out
    }
}
