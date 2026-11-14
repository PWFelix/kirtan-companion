package com.kirtan.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HillClimbTest {
    @Test fun stepsUp() = assertEquals(85, HillClimb.next(80, 5, 200))

    @Test fun stopsAtTheTopOfTheRange() {
        assertEquals(200, HillClimb.next(198, 5, 200))
        assertEquals(200, HillClimb.next(200, 5, 200))
        assertTrue(HillClimb.atTop(200, 200))
        assertFalse(HillClimb.atTop(199, 200))
    }

    @Test fun inBrahmalokaClimbsPast200() = assertEquals(205, HillClimb.next(200, 5, 500))

    @Test fun neverPullsATempoAboveTheCeilingDown() = assertEquals(230, HillClimb.next(230, 5, 200))

    @Test fun defaultsAreOfferedChoices() {
        assertTrue(HillClimb.DEFAULT_STEP in HillClimb.STEPS)
        assertTrue(HillClimb.DEFAULT_INTERVAL_SEC in HillClimb.INTERVALS_SEC)
    }

    @Test fun rememberedValuesSnapToChoices() {
        assertEquals(10, HillClimb.stepOf(10))
        assertEquals(HillClimb.DEFAULT_STEP, HillClimb.stepOf(7))
        assertEquals(HillClimb.DEFAULT_STEP, HillClimb.stepOf(null))
        assertEquals(300, HillClimb.intervalOf(300))
        assertEquals(HillClimb.DEFAULT_INTERVAL_SEC, HillClimb.intervalOf(45))
    }

    @Test fun clock() {
        assertEquals("2:00", HillClimb.clock(120))
        assertEquals("0:05", HillClimb.clock(5))
        assertEquals("0:00", HillClimb.clock(-3))
    }

    @Test fun handsOverHalfABarPlusASecondEarly() {
        // 60 bpm, 4/4: a 4 s bar, so 2 s + 1.
        assertEquals(3, HillClimb.leadSeconds(60, 4.0))
        // 120 bpm, 4/4: a 2 s bar → 1 + 1.
        assertEquals(2, HillClimb.leadSeconds(120, 4.0))
        // 7/8 at 40 bpm: 5.25 s bar → ceil(2.625) + 1.
        assertEquals(4, HillClimb.leadSeconds(40, 3.5))
    }
}
