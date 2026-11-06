package com.kirtan.companion.data

import com.kirtan.companion.data.model.LaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The inbound-link grammar and the consume-once rule.
 *
 * [ShareIntake] holds process-wide mutable state, so every test starts by
 * clearing it — otherwise a payload left behind by one test makes the next one
 * pass for the wrong reason.
 */
class ShareIntakeTest {

    private val realCode = ShareCodec.encodeBeat(BEATS.first())

    @Before
    fun reset() {
        ShareIntake.clear()
    }

    // ── The link grammar ───────────────────────────────────────────────────

    @Test
    fun `the web app's fragment form is recognised`() {
        // Links already in circulation use this shape, so it is the one that
        // must never regress.
        assertEquals(realCode, ShareIntake.codeFrom("https://kirtan.example/#b=$realCode"))
        assertEquals(realCode, ShareIntake.codeFrom("#b=$realCode"))
    }

    @Test
    fun `the native deep link's query form is recognised`() {
        assertEquals(realCode, ShareIntake.codeFrom("kirtan://beat?c=$realCode"))
        // A launcher or the Play Store may append its own parameters either side.
        assertEquals(realCode, ShareIntake.codeFrom("kirtan://beat?utm_source=wa&c=$realCode"))
        assertEquals(realCode, ShareIntake.codeFrom("kirtan://beat?c=$realCode&utm_source=wa"))
    }

    @Test
    fun `a bare code pasted into the import field is recognised`() {
        assertEquals(realCode, ShareIntake.codeFrom(realCode))
        assertEquals(realCode, ShareIntake.codeFrom("  $realCode  "))
    }

    @Test
    fun `the last marker wins, so a marker inside a query cannot hijack the code`() {
        val nested = "https://kirtan.example/?redirect=%23b%3DAAAAAAAA#b=$realCode"
        assertEquals(realCode, ShareIntake.codeFrom(nested))
    }

    @Test
    fun `ordinary text is not mistaken for a share link`() {
        // Returning null here is what lets a normal launch avoid showing a
        // spurious "that link didn't work" error.
        assertNull(ShareIntake.codeFrom(null))
        assertNull(ShareIntake.codeFrom(""))
        assertNull(ShareIntake.codeFrom("   "))
        assertNull(ShareIntake.codeFrom("Hare Krishna"))
        assertNull(ShareIntake.codeFrom("https://kirtan.example/beats"))
        // Too short to be a code, so it is text rather than a broken link.
        assertNull(ShareIntake.codeFrom("abc"))
    }

    @Test
    fun `a marker with nothing after it is not a code`() {
        assertNull(ShareIntake.codeFrom("#b="))
        assertNull(ShareIntake.codeFrom("kirtan://beat?c="))
    }

    // ── Decode on offer ────────────────────────────────────────────────────

    @Test
    fun `offering a real link decodes to that beat`() {
        ShareIntake.offer("kirtan://beat?c=$realCode")
        assertTrue(ShareIntake.hasPending)

        val payload = ShareIntake.consume()
        assertTrue(payload is ShareCodec.SharePayload.BeatPayload)
        val beat = (payload as ShareCodec.SharePayload.BeatPayload).beat

        assertEquals(BEATS.first().name, beat.name)
        assertEquals(BEATS.first().steps, beat.steps)
        // An id never comes from a payload; the library mints one on import.
        assertNull(beat.id)
    }

    @Test
    fun `a payload is delivered once and only once`() {
        // THE property this class exists for. An Intent is redelivered on every
        // onNewIntent and replayed across a process restart, so without an
        // explicit consume-and-clear a single poisoned link would wedge the app
        // on every launch, permanently.
        ShareIntake.offer("#b=$realCode")
        assertNotNull(ShareIntake.consume())
        assertNull("a second consume must return nothing", ShareIntake.consume())
        assertFalse(ShareIntake.hasPending)
    }

    @Test
    fun `a link that carries a marker but does not decode is reported as invalid`() {
        // Distinct from "no link at all": the user tapped something, so silence
        // would read as the app being broken.
        ShareIntake.offer("#b=AAAAAAAAAAAAAAAA")
        assertTrue(ShareIntake.hasPending)
        assertEquals(ShareCodec.SharePayload.Invalid, ShareIntake.consume())
    }

    @Test
    fun `a link with no marker leaves nothing pending`() {
        ShareIntake.offer("https://kirtan.example/beats")
        assertFalse(ShareIntake.hasPending)
        assertNull(ShareIntake.consume())
    }

    @Test
    fun `offering nothing at all is safe`() {
        ShareIntake.offer(null)
        assertFalse(ShareIntake.hasPending)
    }

    @Test
    fun `a later offer replaces an unconsumed earlier one`() {
        // Two links arriving before the UI collects either: the newest wins, and
        // only one payload is ever delivered. Delivering both would import a beat
        // the user never asked for.
        val other = ShareCodec.encodeBeat(BEATS.last())
        ShareIntake.offer("#b=$realCode")
        ShareIntake.offer("kirtan://beat?c=$other")

        val payload = ShareIntake.consume()
        assertTrue(payload is ShareCodec.SharePayload.BeatPayload)
        assertEquals(BEATS.last().name, (payload as ShareCodec.SharePayload.BeatPayload).beat.name)
        assertNull(ShareIntake.consume())
    }

    @Test
    fun `clear drops a payload without reporting it`() {
        ShareIntake.offer("#b=$realCode")
        ShareIntake.clear()
        assertFalse(ShareIntake.hasPending)
        assertNull(ShareIntake.consume())
    }

    @Test
    fun `a category link survives the same path`() {
        val code = ShareCodec.encodeCategory(
            "Morning Programme",
            listOf(BEATS[0], BEATS[1]),
        )
        ShareIntake.offer("kirtan://beat?c=$code")

        val payload = ShareIntake.consume()
        assertTrue(payload is ShareCodec.SharePayload.CategoryPayload)
        val category = payload as ShareCodec.SharePayload.CategoryPayload
        assertEquals("Morning Programme", category.name)
        assertEquals(2, category.beats.size)
        assertTrue(category.beats.all { it.id == null })
        assertTrue(category.beats.all { it.pattern(LaneId.DAYAN) != null })
    }
}
