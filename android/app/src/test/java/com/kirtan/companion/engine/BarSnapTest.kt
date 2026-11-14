package com.kirtan.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hill climb's snap-to-bar: which 1 a tempo change aimed at a moment lands on. */
class BarSnapTest {
    private val sr = 48_000
    // 60 bpm in 4/4: one bar is 4 s (768 ticks).
    private fun clock() = MusicalClock(sr).apply {
        setMeter(8, 4.0)
        setBpm(60.0, 0)
        start(0)
    }
    private fun s(seconds: Double) = (seconds * sr).toLong()

    @Test fun picksTheNearestDownbeatEitherSide() {
        val c = clock()
        assertEquals(768.0, c.barTickNear(s(5.0), s(1.0)), 1e-9)   // 5 s → the 1 at 4 s (before)
        assertEquals(1536.0, c.barTickNear(s(7.0), s(1.0)), 1e-9)  // 7 s → the 1 at 8 s (after)
        assertEquals(768.0, c.barTickNear(s(4.0), s(1.0)), 1e-9)   // on the line: that one
    }

    @Test fun neverADownbeatAlreadyPlayed() {
        val c = clock()
        // Nearest to 5 s is 4 s, but it's now 4.5 s: the next 1 stands in.
        assertEquals(1536.0, c.barTickNear(s(5.0), s(4.5)), 1e-9)
    }

    @Test fun theTempoChangesExactlyOnThatOne() {
        val c = clock()
        val bar = c.barTickNear(s(5.0), s(1.0))
        val at = c.frameAt(bar)
        assertEquals(s(4.0), at)
        c.setBpm(120.0, at)
        // The 1 stays put, and the next bar is now 2 s away rather than 4.
        assertEquals(768.0, c.tickAt(at), 1e-6)
        assertEquals(s(6.0), c.frameAt(1536.0))
    }
}
