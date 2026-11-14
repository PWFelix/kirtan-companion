package com.kirtan.companion.engine

import com.kirtan.companion.engine.SequenceCursor.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** Counting a sequence through its downbeats. */
class SequenceCursorTest {

    private fun run(c: SequenceCursor, n: Int) = List(n) { c.onDownbeat() }

    @Test fun eachEntryHoldsForItsBarsThenSwitchesOnTheOne() {
        val c = SequenceCursor(listOf(2, 1, 3), loop = false)
        assertEquals(
            listOf(
                Outcome.Stay, Outcome.Stay,          // entry 0: bars 1, 2
                Outcome.Switch(1),                   // entry 1: bar 1
                Outcome.Switch(2), Outcome.Stay, Outcome.Stay, // entry 2: bars 1-3
                Outcome.End,                         // the 1 after: stop, unplayed
            ),
            run(c, 7),
        )
    }

    @Test fun loopGoesBackToTheTop() {
        val c = SequenceCursor(listOf(1, 1), loop = true)
        assertEquals(listOf(Outcome.Stay, Outcome.Switch(1), Outcome.Switch(0), Outcome.Switch(1)), run(c, 4))
    }

    @Test fun loopCanBeTurnedOnWhilePlaying() {
        val c = SequenceCursor(listOf(1, 1), loop = false)
        run(c, 2)
        c.loop = true
        assertEquals(Outcome.Switch(0), c.onDownbeat())
    }

    @Test fun barIsCountedWithinTheEntry() {
        val c = SequenceCursor(listOf(3), loop = true)
        c.onDownbeat(); assertEquals(0, c.bar)
        c.onDownbeat(); assertEquals(1, c.bar)
        c.onDownbeat(); assertEquals(2, c.bar)
        assertEquals(Outcome.Switch(0), c.onDownbeat()); assertEquals(0, c.bar)
    }

    @Test fun resetStartsAgainFromBarOne() {
        val c = SequenceCursor(listOf(1, 2), loop = false)
        run(c, 3)
        c.reset()
        assertEquals(Outcome.Stay, c.onDownbeat())
        assertEquals(0, c.index)
    }

    @Test fun anEditKeepsThePlaceWhereItStillExists() {
        val old = SequenceCursor(listOf(4, 4), loop = false)
        run(old, 7) // entry 1, bar index 2
        val edited = SequenceCursor(listOf(4, 2), loop = false)
        edited.adopt(old)
        assertEquals(1, edited.index)
        assertEquals(1, edited.bar) // clamped into the shorter entry
        assertEquals(Outcome.End, edited.onDownbeat())

        val shrunk = SequenceCursor(listOf(4), loop = false)
        shrunk.adopt(old) // entry 1 is gone: from the top
        assertEquals(0, shrunk.index)
    }

    @Test fun zeroBarsCountsAsOne() {
        val c = SequenceCursor(listOf(0, 1), loop = false)
        assertEquals(listOf(Outcome.Stay, Outcome.Switch(1), Outcome.End), run(c, 3))
    }
}
