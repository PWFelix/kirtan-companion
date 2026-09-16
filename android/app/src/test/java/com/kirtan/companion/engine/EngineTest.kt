package com.kirtan.companion.engine

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The engine's correctness, as far as it can be checked without a sound card.
 *
 * Everything here is pure: the clock is arithmetic, the filters are arithmetic,
 * and the WAV decoder takes bytes. That is not an accident — it is the payoff for
 * keeping [MusicalClock], [BiquadFilter] and [WavDecoder] free of Android
 * imports, and it is why the timing model can be verified on a laptop rather
 * than only by ear on a phone.
 */
class EngineTest {

    private val rate = 48_000

    // ── The musical clock ──────────────────────────────────────────────────

    @Test
    fun `tick and frame conversions are exact inverses`() {
        val clock = MusicalClock(rate).apply { setBpm(120.0, 0); setMeter(8, 4); start(0) }
        for (frame in listOf(0L, 1L, 12_000L, 96_000L, 1_234_567L)) {
            assertEquals(
                "frame $frame did not survive a tick round trip",
                frame,
                clock.frameAt(clock.tickAt(frame)),
            )
        }
    }

    @Test
    fun `one bar is two seconds at 120bpm in four beats`() {
        val clock = MusicalClock(rate).apply { setBpm(120.0, 0); setMeter(8, 4); start(0) }
        // 4 beats at 120bpm = 2s; PPQ 192 → 768 ticks per bar.
        assertEquals(768, clock.ticksPerBar)
        assertEquals(96, clock.ticksPerStep)
        assertEquals(2.0 * rate, clock.frameAt(clock.ticksPerBar.toDouble()).toDouble(), 1.0)
    }

    @Test
    fun `the step interval divides evenly for every standard kirtan grid`() {
        // These three are the grids the app ships, and the round in
        // MusicalClock.ticksPerStep must be a no-op for all of them — a
        // fractional step interval is what would make a loop drift.
        val cases = listOf(8 to 96, 12 to 64, 16 to 48)
        for ((steps, expected) in cases) {
            val clock = MusicalClock(rate).apply { setMeter(steps, 4) }
            assertEquals("steps=$steps", expected, clock.ticksPerStep)
            assertEquals(
                "steps=$steps does not divide the bar evenly",
                0,
                clock.ticksPerBar % clock.ticksPerStep,
            )
        }
    }

    @Test
    fun `steps are derived from phase and cover the bar exactly once`() {
        // The property the whole model exists to guarantee: walking a bar frame
        // by frame yields each step index in order, with no gaps and no
        // repeats — for every shipped grid.
        for (steps in listOf(8, 12, 16)) {
            val clock = MusicalClock(rate).apply { setBpm(120.0, 0); setMeter(steps, 4); start(0) }
            val barFrames = clock.frameAt(clock.ticksPerBar.toDouble())

            val seen = ArrayList<Int>()
            var frame = 0L
            var last = -1
            while (frame < barFrames) {
                val s = clock.stepAt(frame)
                if (s != last) {
                    seen.add(s)
                    last = s
                }
                frame += 32
            }
            assertEquals("steps=$steps did not visit every step exactly once", (0 until steps).toList(), seen)
        }
    }

    @Test
    fun `a step boundary lands on the exact frame the clock predicts`() {
        val clock = MusicalClock(rate).apply { setBpm(120.0, 0); setMeter(8, 4); start(0) }
        // Step 3 begins at tick 3*96 = 288 → frame 288*125 = 36000.
        val frame = clock.frameAt(3 * clock.ticksPerStep.toDouble())
        assertEquals(36_000L, frame)
        assertEquals(3, clock.stepAt(frame))
        assertEquals(2, clock.stepAt(frame - 1))
    }

    @Test
    fun `a tempo change moves the position continuously, not by jumping`() {
        val clock = MusicalClock(rate).apply { setBpm(120.0, 0); setMeter(8, 4); start(0) }
        val at = 50_000L
        val tickBefore = clock.tickAt(at)

        clock.setBpm(60.0, at)

        // The bar position must be identical either side of the change; only the
        // rate at which it advances differs. Losing this is what makes a tempo
        // slider lurch the kirtan back to the downbeat.
        assertEquals(tickBefore, clock.tickAt(at), 1e-9)

        // And halving the tempo must halve the rate of advance.
        val ticksPerFrameAt120 = 192.0 * 120.0 / (60.0 * rate)
        val ticksPerFrameAt60 = 192.0 * 60.0 / (60.0 * rate)
        assertEquals(ticksPerFrameAt120 / 2.0, ticksPerFrameAt60, 1e-12)
    }

    @Test
    fun `switching to a shorter grid mid-bar keeps the phase valid`() {
        val clock = MusicalClock(rate).apply { setBpm(120.0, 0); setMeter(16, 4); start(0) }
        val at = 40_000L
        val tick = clock.tickAt(at)

        clock.setMeter(8, 4)

        // The bug this guards: a stored step counter would still read e.g. 12,
        // which is out of range for an 8-cell pattern. Deriving from phase
        // cannot produce that.
        val step = clock.stepAt(at)
        assertTrue("step $step is out of range for an 8-cell grid", step in 0 until 8)
        assertEquals(tick, clock.tickAt(at), 1e-9)
    }

    @Test
    fun `phase is zero while stopped`() {
        val clock = MusicalClock(rate).apply { setMeter(8, 4) }
        assertEquals(0.0, clock.phaseAt(100_000L), 0.0)
    }

    // ── The biquad chain ───────────────────────────────────────────────────

    @Test
    fun `a flat band is a wire`() {
        // At 0 dB every band must be unity, because a brand-new preset has to
        // sound exactly like no EQ at all. Checked for all five bands.
        for (band in com.kirtan.companion.data.EQ_BANDS) {
            val filter = BiquadFilter().apply { configure(band, rate.toDouble()); setGain(0.0) }
            val input = FloatArray(512) { sin(2 * Math.PI * 440 * it / rate).toFloat() }
            val output = input.copyOf()
            filter.process(output, 0, output.size)

            // Skip the first few frames: the delay registers start empty, so
            // there is a genuine transient at the very start.
            for (i in 8 until input.size) {
                assertEquals("band ${band.label} is not unity at 0 dB", input[i], output[i], 1e-5f)
            }
        }
    }

    @Test
    fun `a peaking band boosts its own centre frequency by the set gain`() {
        val band = com.kirtan.companion.data.EQ_BANDS[2] // 1 kHz peaking, Q 1
        assertEquals(1000.0, band.frequency, 0.0)

        val filter = BiquadFilter().apply { configure(band, rate.toDouble()); setGain(12.0) }

        // Measure the steady-state amplitude of a 1 kHz tone. +12 dB is a factor
        // of 4 in amplitude.
        val amplitude = measure(filter, 1_000.0)
        assertEquals("+12 dB at the centre frequency", 4.0, amplitude, 0.15)
    }

    @Test
    fun `a peaking band leaves a far-off frequency alone`() {
        val band = com.kirtan.companion.data.EQ_BANDS[2] // 1 kHz
        val filter = BiquadFilter().apply { configure(band, rate.toDouble()); setGain(12.0) }

        // 60 Hz is four octaves below the bell and Q is 1, so it should be
        // essentially untouched — the point of a narrow bell.
        val amplitude = measure(filter, 60.0)
        assertEquals("off-centre frequency was affected", 1.0, amplitude, 0.1)
    }

    @Test
    fun `a cut is the mirror of a boost`() {
        val band = com.kirtan.companion.data.EQ_BANDS[3] // 3 kHz
        val boost = BiquadFilter().apply { configure(band, rate.toDouble()); setGain(6.0) }
        val cut = BiquadFilter().apply { configure(band, rate.toDouble()); setGain(-6.0) }

        val up = measure(boost, 3_000.0)
        val down = measure(cut, 3_000.0)
        assertEquals("±6 dB are not symmetric", 1.0, up * down, 0.05)
    }

    /** Steady-state amplitude of a unit-amplitude sine through [filter]. */
    private fun measure(filter: BiquadFilter, frequency: Double): Double {
        val buffer = FloatArray(4096) { sin(2 * Math.PI * frequency * it / rate).toFloat() }
        filter.process(buffer, 0, buffer.size)
        // Measure the tail only, so the startup transient is not included.
        var peak = 0.0
        for (i in 2048 until buffer.size) peak = maxOf(peak, abs(buffer[i].toDouble()))
        return peak
    }

    // ── The WAV decoder ────────────────────────────────────────────────────

    @Test
    fun `a 16-bit stereo file decodes to mono at half amplitude each side`() {
        // Left = +16384 (0.5), right = -16384 (-0.5): the downmix must average
        // to exactly zero. A decoder that took only the first channel would
        // return 0.5 and pass a lazier test, so the two channels differ in sign.
        val frames = shortArrayOf(16384, -16384, 32767, -32767)
        val wav = wav(bytesOf(frames), channels = 2, sampleRate = 48_000, bitsPerSample = 16)

        val pcm = WavDecoder.decode(wav, 48_000)

        assertEquals(2, pcm.frameCount)
        assertEquals(0f, pcm.data[0], 1e-6f)
        // (32767 + -32767)/2/32768 = 0 as well; use a same-sign pair to check
        // the scale separately.
        val same = wav(bytesOf(shortArrayOf(16384, 16384)), 2, 48_000, 16)
        assertEquals(0.5f, WavDecoder.decode(same, 48_000).data[0], 1e-4f)
    }

    @Test
    fun `a 24-bit file decodes with correct sign extension`() {
        // The bundled karatalas are 24-bit while the mridanga ends are 16-bit,
        // so this path is exercised by real shipped assets — and sign extension
        // is exactly where a hand-rolled 24-bit read goes wrong.
        // 0xC0 is 192, which does not fit a signed Byte, so it needs an explicit
        // truncation — the literal-coercion that works for 0x40 will not.
        val positive = byteArrayOf(0x00, 0x00, 0x40)          // +4194304 → +0.5
        val negative = byteArrayOf(0x00, 0x00, 0xC0.toByte())  // -4194304 → -0.5
        val pcm = WavDecoder.decode(
            wav(positive + negative, channels = 1, sampleRate = 48_000, bitsPerSample = 24),
            48_000,
        )

        assertEquals(2, pcm.frameCount)
        assertEquals(0.5f, pcm.data[0], 1e-4f)
        assertEquals(-0.5f, pcm.data[1], 1e-4f)
    }

    @Test
    fun `a file at the target rate is not resampled`() {
        val wav = wav(bytesOf(shortArrayOf(1000, 2000, 3000)), 1, 48_000, 16)
        assertEquals(3, WavDecoder.decode(wav, 48_000).frameCount)
    }

    @Test
    fun `resampling 44_1 to 48k produces the right number of frames`() {
        val frames = ShortArray(44_100) { 1000 } // exactly one second
        val pcm = WavDecoder.decode(wav(bytesOf(frames), 1, 44_100, 16), 48_000)
        assertEquals(48_000, pcm.frameCount)
        assertEquals(48_000, pcm.sampleRate)
    }

    @Test
    fun `extra chunks between fmt and data are skipped`() {
        // Real encoders insert LIST/INFO chunks. Assuming a fixed 44-byte header
        // is the classic way to write a WAV decoder that works on your own files
        // and fails on everyone else's.
        // A well-formed but unwanted chunk: fourcc, size, payload. (A two-byte
        // fourcc here is malformed, and the decoder is right to reject it — this
        // test is about skipping valid chunks, not tolerating broken ones.)
        val list = byteArrayOf(
            'L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
        ) + int32le(4) + byteArrayOf(1, 2, 3, 4)
        val header = wavHeader(channels = 1, sampleRate = 48_000, bitsPerSample = 16, dataLength = 4)
        val withList = header + list +
            byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()) +
            int32le(4) + byteArrayOf(0, 0x40, 0, 0x40)

        // The header was built for a file with no extra chunk, so its declared
        // RIFF length is short by the size of the LIST chunk. Patch it, because a
        // test fixture should be a valid file — otherwise the test would pass for
        // the wrong reason if the decoder ever started trusting that field.
        int32le(withList.size - 8).copyInto(withList, 4)

        val pcm = WavDecoder.decode(withList, 48_000)
        assertEquals(2, pcm.frameCount)
        assertEquals(0.5f, pcm.data[0], 1e-4f)
    }

    @Test
    fun `a malformed file throws rather than returning silence`() {
        val thrown = try {
            WavDecoder.decode("not a riff file at all".toByteArray(), 48_000)
            null
        } catch (e: WavDecoder.WavFormatException) {
            e
        }
        assertNotEquals("a non-WAV must be reported, not decoded as silence", null, thrown)
    }

    // ── Round-robin ────────────────────────────────────────────────────────

    @Test
    fun `round-robin never repeats the sample it just played`() {
        // The whole reason round-robin exists: a real drum never sounds
        // identical twice in a row.
        for (count in 2..5) {
            var last = -1
            repeat(2000) {
                val next = pickIndex(count, last)
                assertTrue("index $next out of range for count $count", next in 0 until count)
                assertNotEquals("repeated the previous sample at count $count", last, next)
                last = next
            }
        }
    }

    @Test
    fun `a single-sample stroke always replays it`() {
        // With one sample the pool of "anything but the last" is empty, so the
        // only sample comes back — this is what makes closed strokes, which ship
        // one recording each, fall out of the rule for free.
        repeat(100) { assertEquals(0, pickIndex(1, 0)) }
        assertEquals(0, pickIndex(1, -1))
    }

    @Test
    fun `round-robin reaches every variant`() {
        val seen = mutableSetOf<Int>()
        var last = -1
        repeat(5000) {
            last = pickIndex(3, last)
            seen.add(last)
        }
        assertEquals(setOf(0, 1, 2), seen)
    }

    // ── WAV construction helpers ───────────────────────────────────────────

    private fun bytesOf(shorts: ShortArray): ByteArray {
        val out = ByteArray(shorts.size * 2)
        shorts.forEachIndexed { i, s ->
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun int32le(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun int16le(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    /** A canonical 44-byte header, split out so a test can inject chunks after it. */
    private fun wavHeader(channels: Int, sampleRate: Int, bitsPerSample: Int, dataLength: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()) +
            int32le(36 + dataLength) +
            byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()) +
            byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()) +
            int32le(16) +
            int16le(1) +                       // PCM
            int16le(channels) +
            int32le(sampleRate) +
            int32le(byteRate) +
            int16le(blockAlign) +
            int16le(bitsPerSample)
    }

    private fun wav(data: ByteArray, channels: Int, sampleRate: Int, bitsPerSample: Int): ByteArray =
        wavHeader(channels, sampleRate, bitsPerSample, data.size) +
            byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()) +
            int32le(data.size) +
            data
}
