package com.kirtan.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrahmalokaTest {

    @Test fun normalRangeTopsOutAt200() {
        assertEquals(40..200, tempoRange(false))
        assertEquals(190..500, tempoRange(true))
    }

    @Test fun below200AlwaysLeaves() {
        assertFalse(brahmalokaFor(199, true))
        assertFalse(brahmalokaFor(120, false))
    }

    @Test fun above200AlwaysEnters() {
        // A loaded 300 BPM beat has to be shown on a slider that reaches it.
        assertTrue(brahmalokaFor(300, false))
        assertTrue(brahmalokaFor(201, true))
    }

    @Test fun exactly200KeepsTheMode() {
        assertTrue(brahmalokaFor(200, true))
        assertFalse(brahmalokaFor(200, false))
    }

    @Test fun launchRestoresAtMost200() {
        assertEquals(200, restoredBpm(350))
        assertEquals(96, restoredBpm(96))
        assertEquals(40, restoredBpm(5))
    }
}
