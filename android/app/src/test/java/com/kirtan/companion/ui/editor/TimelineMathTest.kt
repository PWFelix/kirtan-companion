package com.kirtan.companion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMathTest {
    @Test fun rowsWrapRoundTheLoopBothWays() {
        assertEquals(0, TimelineMath.wrap(8, 8))
        assertEquals(7, TimelineMath.wrap(-1, 8))
        assertEquals(-1, TimelineMath.cycleOf(-1, 8))
        assertEquals(1, TimelineMath.cycleOf(8, 8))
    }

    @Test fun aPointAboveTheLineIsALaterCell() {
        assertEquals(5, TimelineMath.rowAt(3f, 88f, 44f))
        assertEquals(1, TimelineMath.rowAt(3f, -88f, 44f))
        assertEquals(3, TimelineMath.rowAt(3f, 10f, 44f))
    }

    @Test fun theCursorIsReachedTheShortWayRound() {
        assertEquals(8f, TimelineMath.nearest(0, 7f, 8), 0f)   // 7 → next cycle's 0, not back seven
        assertEquals(-1f, TimelineMath.nearest(7, 0f, 8), 0f)  // 0 → previous cycle's 7
        assertEquals(3f, TimelineMath.nearest(3, 2.6f, 8), 0f)
    }
}
