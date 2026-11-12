package com.kirtan.companion.ui.editor

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.model.LaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSessionTest {
    private val keherva = BEATS.first { it.id == "keherva_medium_speed" }

    @Test
    fun `a fresh session is clean, new or seeded`() {
        assertFalse(EditorSession(null, false, null).dirty)
        assertFalse(EditorSession(keherva, false, null).dirty)
    }

    @Test
    fun `dirty is about content, so undoing back clears it`() {
        val s = EditorSession(keherva, false, null)
        s.draft = s.draft.setCursor(3) // moving the cursor is not a change
        assertFalse(s.dirty)
        val d = s.draft
        s.draft = d.tapPad(padSetFor(LaneId.DAYAN).first { d.tapPad(it).pattern(LaneId.DAYAN) != d.pattern(LaneId.DAYAN) })
        assertTrue(s.dirty)
        s.draft = s.draft.undoLast()
        assertFalse(s.dirty)
        s.draft = s.draft.setBpm(keherva.bpm + 5)
        assertTrue(s.dirty)
    }

    @Test
    fun `name and tags count, surrounding spaces do not`() {
        val s = EditorSession(keherva, false, null)
        s.draft = s.draft.setName(keherva.name + "  ")
        assertFalse(s.dirty)
        s.draft = s.draft.setName("Mine")
        assertTrue(s.dirty)
        val t = EditorSession(null, false, null)
        t.tags = listOf("slow")
        assertTrue(t.dirty)
    }

    @Test
    fun `fresh goes back to the seed and keeps where it came from`() {
        val s = EditorSession(keherva, true, null)
        s.draft = s.draft.setBpm(200)
        val f = s.fresh()
        assertFalse(f.dirty)
        assertEquals(keherva, f.seed)
        assertTrue(f.forEveryone)
    }

    @Test
    fun `a dirty session survives a round trip to disk`() {
        val s = EditorSession(keherva, false, null)
        s.draft = s.draft.tapPad(padSetFor(LaneId.BAYAN)[0]).setName("Variation").setCursor(5)
        s.tags = listOf("slow", "practice")
        val back = EditorDraftStore.decode(EditorDraftStore.encode(s))
        assertNotNull(back)
        back!!
        assertEquals(keherva.id, back.seed?.id)
        assertTrue(EditorSession.sameContent(s.draft, back.draft))
        assertEquals("Variation", back.draft.name)
        assertEquals(s.draft.cursor, back.draft.cursor)
        assertEquals(s.tags, back.tags)
        assertTrue(back.dirty)
    }

    @Test
    fun `a new beat round trips with no seed, and junk decodes to nothing`() {
        val s = EditorSession(null, false, null)
        s.draft = s.draft.tapPad(padSetFor(LaneId.DAYAN)[0]).setName("New one")
        val back = EditorDraftStore.decode(EditorDraftStore.encode(s))!!
        assertNull(back.seed)
        assertTrue(EditorSession.sameContent(s.draft, back.draft))
        assertNull(EditorDraftStore.decode("not json"))
        assertNull(EditorDraftStore.decode(null))
    }
}
