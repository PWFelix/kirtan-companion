package com.kirtan.companion.ui.learn

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighwayTest {

    private val doubleTime = BEATS.first { it.id == "double_time_2" }
    private val daspahir = BEATS.first { it.id == "daspahir_taal" }

    @Test
    fun columnsAreHeadedByTheirBols() {
        assertEquals(listOf("Ge", "Khe", "Te", "Ta"), HighwayColumn.entries.map { it.bol })
    }

    @Test
    fun aNoteSitsOnTheHitLineAtItsStep() {
        // Step 0 of Double Time 2: dayan X (Te) and bayan O (Ge).
        val frame = Highway.frame(doubleTime, 0.0, 8.0)
        val now = frame.notes.filter { it.rel == 0.0 }.map { it.column }.toSet()
        assertEquals(setOf(HighwayColumn.DAYAN_CLOSED, HighwayColumn.BAYAN_OPEN), now)
        assertEquals(0, frame.currentStep)
    }

    @Test
    fun everyStrokeInTheWindowAppearsExactlyOnce() {
        val pos = 5.25
        val look = 10.0
        val frame = Highway.frame(doubleTime, pos, look)
        for (lane in listOf(LaneId.DAYAN, LaneId.BAYAN)) {
            val pattern = doubleTime.pattern(lane)!!
            for (abs in 6..15) {
                val stroke = pattern[abs % 16] ?: continue
                val hits = frame.notes.filter {
                    it.column == HighwayColumn.of(lane, stroke) && it.rel == abs - pos
                }
                assertEquals("lane $lane step $abs", 1, hits.size)
            }
        }
        assertTrue(frame.notes.none { it.rel < -Highway.TRAIL_STEPS - 1 })
    }

    @Test
    fun theWindowWrapsIntoTheNextBar() {
        // Near the end of the loop the next bar's sam must already be falling.
        val frame = Highway.frame(daspahir, 30.0, 6.0)
        val sam = frame.lines.filter { it.sam }
        assertTrue(sam.any { it.rel == 2.0 })
        val bar2Downbeat = frame.notes.filter { it.step == 0 && it.rel == 2.0 }
        assertEquals(listOf(HighwayColumn.BAYAN_OPEN), bar2Downbeat.map { it.column })
    }

    @Test
    fun beatLinesFallOnGroupBoundaries() {
        assertEquals(setOf(0, 4, 8, 12), Highway.groupBoundaries(doubleTime))
        assertEquals((0 until 32 step 4).toSet(), Highway.groupBoundaries(daspahir))
    }

    @Test
    fun lookaheadIsTimeBased() {
        // 140 BPM, 4 cells per quarter: 9.33 cells per second, 1.8 s ahead.
        assertEquals(16.8, Highway.lookaheadSteps(140, 4), 1e-9)
        // Never fewer than four cells, however slow.
        assertEquals(4.0, Highway.lookaheadSteps(40, 1), 1e-9)
    }

    @Test
    fun bolsNameTwoHandStrokes() {
        // daspahir step 16: bayan X alone -> Khe; step 4: dayan X alone -> Te.
        assertEquals("Khe", Highway.bolAt(daspahir, 16))
        assertEquals("Te", Highway.bolAt(daspahir, 4))
        // Double Time 2 step 0: dayan X + bayan O -> Gi.
        assertEquals("Gi", Highway.bolAt(doubleTime, 0))
        assertEquals(Stroke.OPEN, daspahir.pattern(LaneId.BAYAN)!![0])
    }

    @Test
    fun leftHandedMirrorsTheColumns() {
        val right = Highway.columnsFor(leftHanded = false)
        val left = Highway.columnsFor(leftHanded = true)
        assertEquals(listOf("Ge", "Khe", "Te", "Ta"), right.map { it.bol })
        // Dayan under the left hand, open strokes still on the outside.
        assertEquals(listOf("Ta", "Te", "Khe", "Ge"), left.map { it.bol })
        assertEquals(LaneId.DAYAN, left.first().lane)
        assertEquals(Stroke.OPEN, left.last().stroke)
    }
}
