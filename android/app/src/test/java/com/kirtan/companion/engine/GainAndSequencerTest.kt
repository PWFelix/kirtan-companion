package com.kirtan.companion.engine

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gain ramping and the sequencer's duplicate guard — the two behaviours that
 * produce audible artefacts rather than wrong numbers, and so are the two most
 * worth pinning down.
 */
class GainAndSequencerTest {

    private val rate = 48_000

    /** A block of ones, so whatever the ramp did to it is directly readable. */
    private fun ones(count: Int) = FloatArray(count) { 1f }

    // ── GainRamp ───────────────────────────────────────────────────────────

    @Test
    fun `a settled ramp multiplies by exactly its value`() {
        val ramp = GainRamp(rate.toDouble(), seconds = 0.05, initial = 0.5f)
        val buffer = ones(64)
        ramp.applyTo(buffer, buffer.size)
        for (sample in buffer) assertEquals(0.5f, sample, 1e-6f)
    }

    @Test
    fun `unity is a genuine no-op, not a multiply`() {
        // The fast path matters: every block of every lane passes through a ramp,
        // and most of the time nothing is moving.
        val ramp = GainRamp(rate.toDouble(), initial = 1f)
        val buffer = ones(256)
        ramp.applyTo(buffer, buffer.size)
        assertEquals(1f, buffer[255], 0f)
    }

    @Test
    fun `a ramp reaches its target within the stated duration`() {
        // 50 ms matches `gain.rampTo(target, 0.05)` in the web engine. If this
        // drifts, a slider move lands at a different time on Android than on the
        // web, and the mixer stops feeling like the same instrument.
        val ramp = GainRamp(rate.toDouble(), seconds = 0.05, initial = 0f)
        ramp.target = 1f
        ramp.retarget()

        val rampFrames = (0.05 * rate).toInt()
        val buffer = ones(rampFrames * 2)
        ramp.applyTo(buffer, buffer.size)

        assertEquals("the ramp did not settle by 50 ms", 1f, ramp.value, 1e-6f)
        assertEquals(1f, buffer[rampFrames], 1e-5f)
        assertEquals(1f, buffer[buffer.size - 1], 0f)
        // And it was still moving before then.
        assertTrue("the ramp jumped straight to the target", buffer[rampFrames / 2] < 0.9f)
    }

    @Test
    fun `a ramp is monotonic and never overshoots`() {
        // Overshoot on a gain ramp is a click. The snap-on-crossing in
        // GainRamp.applyTo exists precisely to prevent it, so it is worth
        // asserting rather than trusting.
        val ramp = GainRamp(rate.toDouble(), seconds = 0.05, initial = 1f)
        ramp.target = 0.2f
        ramp.retarget()

        val buffer = ones(rate) // a full second, far past the 50 ms ramp
        ramp.applyTo(buffer, buffer.size)

        var previous = 1f
        for (i in 0 until buffer.size) {
            assertTrue(
                "gain rose at frame $i ($previous -> ${buffer[i]}) during a downward ramp",
                buffer[i] <= previous + 1e-6f,
            )
            assertTrue("gain undershot the target at frame $i", buffer[i] >= 0.2f - 1e-6f)
            previous = buffer[i]
        }
        assertEquals(0.2f, buffer[buffer.size - 1], 1e-6f)
    }

    @Test
    fun `re-targeting mid-ramp does not jump`() {
        // A slider dragged while a previous move is still settling. Restarting
        // from the CURRENT value is what keeps this continuous; restarting from
        // the original would produce a step.
        val ramp = GainRamp(rate.toDouble(), seconds = 0.05, initial = 0f)
        ramp.target = 1f
        ramp.retarget()

        val first = ones(1200) // 25 ms in
        ramp.applyTo(first, first.size)
        val midValue = first[1199]
        assertTrue("the ramp had not started moving", midValue > 0f)

        ramp.target = 0f
        ramp.retarget()

        val second = ones(1200)
        ramp.applyTo(second, second.size)
        // Continuity: the first frame of the second block must be adjacent to the
        // last frame of the first, not back at zero.
        assertTrue(
            "re-targeting jumped from $midValue to ${second[0]}",
            kotlin.math.abs(second[0] - midValue) < 0.05f,
        )

        // A re-target restarts the 50 ms ramp from wherever the gain currently is,
        // so reaching zero takes another full ramp, not the remainder of the old
        // one. Render past it and the channel is silent.
        val settle = ones(rate)
        ramp.applyTo(settle, settle.size)
        assertEquals(0f, settle[settle.size - 1], 1e-6f)
    }

    @Test
    fun `a mute ramp silences the block completely`() {
        val ramp = GainRamp(rate.toDouble(), seconds = 0.05, initial = 1f)
        ramp.target = 0f
        ramp.retarget()

        val buffer = ones(rate)
        ramp.applyTo(buffer, buffer.size)
        assertEquals(0f, buffer[buffer.size - 1], 0f)
        assertEquals(0f, ramp.value, 0f)
    }

    // ── The sequencer's duplicate guard ────────────────────────────────────

    /** Drive a clock+sequencer over a frame range the way the renderer does. */
    private fun eventsOver(
        beat: Beat,
        bpm: Int,
        totalFrames: Long,
        blockSize: Int = 256,
        onBeatSwitch: ((Sequencer, MusicalClock) -> Unit)? = null,
        switchAtFrame: Long = Long.MAX_VALUE,
    ): List<StepEvent> {
        val clock = MusicalClock(rate).apply {
            setBpm(bpm.toDouble(), 0); setMeter(beat.steps, beat.beatsPerBar); start(0)
        }
        val sequencer = Sequencer(clock).apply { this.beat = beat }
        val out = ArrayList<StepEvent>()
        val scratch = ArrayList<StepEvent>()
        var switched = false

        var from = 0L
        while (from < totalFrames) {
            val count = minOf(blockSize.toLong(), totalFrames - from).toInt()
            if (!switched && from + count > switchAtFrame) {
                onBeatSwitch?.invoke(sequencer, clock)
                switched = true
            }
            scratch.clear()
            sequencer.collectStepEvents(from, from + count, scratch)
            out.addAll(scratch)
            from += count
        }
        return out
    }

    @Test
    fun `contiguous blocks tile the timeline with no gap and no overlap`() {
        // The property that makes block-based pulling safe: half-open [from, to)
        // ranges, so a step landing exactly on a boundary belongs to exactly one
        // block. Get this wrong and every boundary step either drops or doubles.
        val beat = BEATS.first()
        val barFrames = MusicalClock(rate).let { c ->
            c.setBpm(beat.bpm.toDouble(), 0); c.setMeter(beat.steps, beat.beatsPerBar)
            c.frameAt(c.ticksPerBar.toDouble())
        }

        // Three bars, at a block size that does NOT divide the bar evenly — the
        // case that would expose an off-by-one at a boundary.
        val events = eventsOver(beat, beat.bpm, barFrames * 3, blockSize = 100)

        assertEquals(beat.steps * 3, events.size)
        assertEquals((0 until beat.steps * 3).map { it % beat.steps }, events.map { it.step })
        assertTrue("frames were not strictly increasing", events.map { it.frame }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `every block size yields the same events`() {
        // Block size is a latency/CPU tradeoff and must not change WHAT plays.
        // If it did, tuning BLOCK_SIZE would silently alter the rhythm.
        val beat = BEATS.first { it.steps == 16 }
        val reference = eventsOver(beat, beat.bpm, 60_000, blockSize = 256).map { it.step }

        for (size in listOf(1, 7, 64, 128, 512, 1024)) {
            val actual = eventsOver(beat, beat.bpm, 60_000, blockSize = size).map { it.step }
            assertEquals("block size $size changed the events", reference, actual)
        }
    }

    @Test
    fun `a beat switch mid-bar does not double-hit the step it lands on`() {
        // The case the guard exists for: re-gridding changes ticksPerStep, so a
        // boundary under the NEW grid can coincide with one already emitted under
        // the old. Without invalidateGuard() the drum double-hits; with a guard
        // that was never reset it instead DROPS the first step of the new beat.
        val from = BEATS.first { it.steps == 16 }
        val to = BEATS.first { it.steps == 8 }

        val events = eventsOver(
            from, from.bpm, 80_000,
            onBeatSwitch = { sequencer, clock ->
                sequencer.beat = to
                clock.setMeter(to.steps, to.beatsPerBar)
                sequencer.invalidateGuard()
            },
            switchAtFrame = 40_000,
        )

        // No two events at the same frame: that is the double-hit, stated directly.
        val frames = events.map { it.frame }
        assertEquals(
            "two steps fired on the same frame around the beat switch",
            frames.size,
            frames.distinct().size,
        )
        // And every step index after the switch is valid for the new 8-cell grid.
        val afterSwitch = events.filter { it.frame >= 40_000 }
        assertTrue("a step index escaped the new grid", afterSwitch.all { it.step in 0 until to.steps })
        assertTrue("no steps fired after the switch", afterSwitch.isNotEmpty())
    }

    @Test
    fun `a beat switch preserves the musical position`() {
        // The reason the step is derived from phase rather than counted: switching
        // from 16 cells to 8 mid-play must not index past the end of the new
        // pattern, and must not lurch back to the downbeat either.
        val clock = MusicalClock(rate).apply {
            setBpm(140.0, 0); setMeter(16, 4); start(0)
        }
        val at = 40_000L
        val phaseBefore = clock.phaseAt(at)

        clock.setMeter(8, 4)

        assertEquals("the position moved on a meter change", phaseBefore, clock.phaseAt(at), 1e-12)
        assertTrue("the derived step escaped the new grid", clock.stepAt(at) in 0 until 8)
    }

    @Test
    fun `no events are collected while stopped`() {
        val clock = MusicalClock(rate).apply { setBpm(90.0, 0); setMeter(8, 4) }
        val sequencer = Sequencer(clock).apply { beat = BEATS.first() }
        val out = ArrayList<StepEvent>()
        sequencer.collectStepEvents(0, 100_000, out)
        assertTrue("a stopped clock produced events", out.isEmpty())
    }

    @Test
    fun `no events are collected with no beat loaded`() {
        val clock = MusicalClock(rate).apply { setBpm(90.0, 0); setMeter(8, 4); start(0) }
        val sequencer = Sequencer(clock)
        val out = ArrayList<StepEvent>()
        sequencer.collectStepEvents(0, 100_000, out)
        assertTrue("a running clock with no beat produced events", out.isEmpty())
    }

    @Test
    fun `the strokes a step fires match the beat data for every shipped beat`() {
        // Cross-checks Sequencer.strokesAt (the test-facing helper) against the
        // same lane iteration the render path does inline. If the two ever diverge,
        // the tests would be asserting behaviour the engine does not have.
        for (beat in BEATS) {
            val sequencer = Sequencer(MusicalClock(rate))
            for (step in 0 until beat.steps) {
                val fromHelper = sequencer.strokesAt(beat, step).toSet()
                val inline = LaneId.ORDERED.mapNotNull { lane ->
                    beat.strokeAt(lane, step)?.let { lane to (it == Stroke.OPEN) }
                }.toSet()
                assertEquals("${beat.id} step $step", inline, fromHelper)
            }
        }
    }
}
