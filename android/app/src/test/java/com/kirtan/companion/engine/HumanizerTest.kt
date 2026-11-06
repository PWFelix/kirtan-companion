package com.kirtan.companion.engine

import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.storage.testBeat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanizerTest {

    private val rate = 48_000
    private fun msToFrames(ms: Double) = (ms * rate / 1000.0).toInt()

    private fun humanizer(feel: Feel) = Humanizer(rate).apply {
        setBeat(testBeat())
        this.feel = feel
        reset()
    }

    /** One bar's worth of (delay, gain) for every lane, as the engine would ask. */
    private fun run(h: Humanizer, steps: Int = 64): Pair<IntArray, FloatArray> {
        val delays = ArrayList<Int>()
        val gains = ArrayList<Float>()
        for (i in 0 until steps) {
            h.advance()
            for (lane in LaneId.ORDERED) {
                delays.add(h.delayFrames(lane, i % 8))
                gains.add(h.gain(i % 8))
            }
        }
        return delays.toIntArray() to gains.toFloatArray()
    }

    @Test
    fun `off changes nothing`() {
        val h = humanizer(Feel.OFF)
        val (delays, gains) = run(h)
        assertTrue(delays.all { it == 0 })
        assertTrue(gains.all { it == 1f })
        assertEquals(0, h.padFrames())
    }

    @Test
    fun `delays are never negative and stay inside the window`() {
        for (feel in Feel.entries) {
            val (delays, _) = run(humanizer(feel), steps = 2_000)
            val max = msToFrames(Humanizer.PAD_MS + Humanizer.MAX_LATE_MS) + 1
            assertTrue("$feel", delays.all { it in 0..max })
        }
    }

    @Test
    fun `gain shades a stroke but never loses it or boosts it`() {
        val (_, gains) = run(humanizer(Feel.LOOSE), steps = 2_000)
        assertTrue(gains.all { it in Humanizer.MIN_GAIN..1f })
    }

    @Test
    fun `the bayan lands after the dayan on average`() {
        val h = humanizer(Feel.HUMAN)
        var dayan = 0L
        var bayan = 0L
        repeat(2_000) { i ->
            h.advance()
            dayan += h.delayFrames(LaneId.DAYAN, i % 8)
            bayan += h.delayFrames(LaneId.BAYAN, i % 8)
        }
        val lagMs = (bayan - dayan).toDouble() / 2_000 / rate * 1000
        assertTrue("lag was $lagMs ms", lagMs in 7.0..14.0)
    }

    @Test
    fun `the downbeat is the loudest step on average`() {
        val h = humanizer(Feel.LOOSE)
        val sums = DoubleArray(8)
        repeat(4_000) { i -> sums[i % 8] += h.gain(i % 8).toDouble() }
        assertTrue(sums.drop(1).all { sums[0] > it })
    }

    @Test
    fun `the same beat plays the same feel every time it starts`() {
        val h = humanizer(Feel.HUMAN)
        val first = run(h)
        h.reset()
        val second = run(h)
        assertArrayEquals(first.first, second.first)
        assertArrayEquals(first.second, second.second, 0f)
    }

    @Test
    fun `a voice can wait longer than a block before it starts`() {
        val sample = PcmSample(FloatArray(1_000) { 1f }, 48_000)
        val voice = Voice(sample, startOffset = 300, gain = 0.5f)
        val out = FloatArray(256)

        voice.render(out, 256, 1.0)
        assertTrue("silent through the first block", out.all { it == 0f })

        voice.render(out, 256, 1.0)
        assertEquals(0f, out[43])
        assertEquals(0.5f, out[44])
    }
}
