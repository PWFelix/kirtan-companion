package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.storage.LibraryJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwingTest {
    @Test
    fun `the second cell of each even beat swings, odd beats stay straight`() {
        val off = Swing.offbeats(listOf(2, 2, 3, 4))
        assertEquals(
            listOf(false, true, false, true, false, false, false, false, true, false, true),
            off.toList(),
        )
    }

    @Test
    fun `lateness runs from none to a third of a cell`() {
        assertEquals(0.0, Swing.lateFraction(50), 1e-9)
        assertEquals(0.34, Swing.lateFraction(67), 1e-9)
        assertEquals(0.34, Swing.lateFraction(90), 1e-9) // clamped
    }

    private val beat: Beat = BEATS.first().copy(id = "custom_1", swing = 60)

    @Test
    fun `swing survives storage and share links, and straight beats are written as before`() {
        val stored = LibraryJson.encodeBody(beat)
        assertEquals(60, LibraryJson.decodeBeat(kotlinx.serialization.json.JsonObject(stored + ("id" to kotlinx.serialization.json.JsonPrimitive("x"))))?.swing)
        assertFalse(LibraryJson.encodeBody(beat.copy(swing = 50)).containsKey("swing"))

        val shared = ShareCodec.decodeShare(ShareCodec.encodeBeat(beat)) as ShareCodec.SharePayload.BeatPayload
        assertEquals(60, shared.beat.swing)
        val straight = ShareCodec.decodeShare(ShareCodec.encodeBeat(beat.copy(swing = 50))) as ShareCodec.SharePayload.BeatPayload
        assertEquals(50, straight.beat.swing)
        assertTrue(ShareCodec.encodeBeat(beat).length > ShareCodec.encodeBeat(beat.copy(swing = 50)).length)
    }
}
