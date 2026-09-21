package com.kirtan.companion.ui.editor

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's draft model, tested without a compositor.
 *
 * Everything the web editor does to state lives in [EditorDraft], so these tests
 * are the editor's real safety net: meter math, the undo stack, pad writes and
 * what gets persisted. The Compose file owns only presentation.
 */
class EditorDraftTest {

    // ── Seeding ────────────────────────────────────────────────────────────

    @Test
    fun `a blank draft is one bar of the default feel`() {
        val draft = EditorDraft.from(null)
        assertEquals(listOf(2, 2, 2, 2), draft.groups)
        assertEquals("std", draft.meter.id)
        assertEquals(8, draft.steps)
        assertEquals(4.0, draft.beatsPerBar, 0.0)
        assertEquals(0, draft.cursor)
        assertEquals("", draft.name)
        assertEquals(90, draft.bpm)
        assertFalse(draft.canUndo)
    }

    @Test
    fun `seeding from a built-in recovers its groups and feel`() {
        // The one 4/4-in-eighths beat in the shipped set.
        val keherva = EditorDraft.from(BEATS.first { it.id == "keherva_medium_speed" })
        assertEquals(listOf(2, 2, 2, 2), keherva.groups)
        assertEquals("std", keherva.meter.id)
        assertEquals("keherva medium speed", keherva.name)
        assertEquals(Stroke.OPEN, keherva.pattern(LaneId.DAYAN)[0])

        // The compound one: 16 groups of 3 at eighth subdivision, so 48 cells over
        // 24 quarters — cells-per-group (3) is NOT cells-per-quarter (2).
        val matan = EditorDraft.from(BEATS.first { it.id == "matan" })
        assertEquals(List(16) { 3 }, matan.groups)
        assertEquals(48, matan.steps)
        assertEquals(2, matan.cpq)
    }

    @Test
    fun `a triplet beat recovers the Triplets feel`() {
        // No shipped beat uses triplets any more, so build one: meter recovery
        // depends on the groups and cpq, not on which beats happen to ship.
        val base = BEATS.first()
        val triplet = base.copy(
            id = null,
            steps = 12,
            beatsPerBar = 4.0,
            cellsPerGroup = 3,
            groups = listOf(3, 3, 3, 3),
            lanePatterns = base.lanePatterns.mapValues { (_, cells) ->
                List(12) { cells[it % cells.size] }
            },
        )
        val draft = EditorDraft.from(triplet)
        assertEquals("trip", draft.meter.id)
        assertEquals(3, draft.cpq)
        assertEquals(listOf(3, 3, 3, 3), draft.groups)
    }

    @Test
    fun `seeding from a beat without stored groups reconstructs them`() {
        val base = BEATS.first()
        val beat = base.copy(id = null, groups = null)
        val groups = EditorDraft.from(beat).groups
        // Reconstructed uniformly from cellsPerGroup, one group per quarter-note
        // pulse, summing back to the same number of cells.
        assertEquals(List(base.beatsPerBar.toInt()) { base.cellsPerGroup }, groups)
        assertEquals(base.steps, groups.sum())
    }

    // ── Meter recovery ─────────────────────────────────────────────────────

    @Test
    fun `uneven meters are recovered rather than reset to standard`() {
        assertEquals("68", meterFor(listOf(3, 3), 2).id)
        assertEquals("68", meterFor(listOf(3, 3, 3, 3), 2).id)
        assertEquals("78", meterFor(listOf(2, 2, 3), 2).id)
        assertEquals("78", meterFor(listOf(2, 2, 3, 2, 2, 3), 2).id)
        assertEquals("std", meterFor(listOf(2, 2, 2, 2), 2).id)
        assertEquals("trip", meterFor(listOf(3, 3, 3, 3), 3).id)
        assertEquals("dbl", meterFor(listOf(4, 4, 4, 4), 4).id)
    }

    @Test
    fun `an unrecognised meter becomes custom carrying its own unit`() {
        val custom = meterFor(listOf(2, 3), 2)
        assertEquals("custom", custom.id)
        assertEquals(listOf(2, 3), custom.unit)
        // And ± grows the bar by one whole 2+3 cycle, not by a single group.
        assertEquals(5, custom.unit.sum())
    }

    // ── Length ─────────────────────────────────────────────────────────────

    @Test
    fun `adding a group appends the meter's unit and pads every lane`() {
        val draft = EditorDraft.from(null).addGroup()
        assertEquals(listOf(2, 2, 2, 2, 2), draft.groups)
        assertEquals(10, draft.steps)
        for (lane in LaneId.ORDERED) {
            assertEquals(10, draft.pattern(lane).size)
            assertNull("new cells must be rests", draft.pattern(lane)[8])
        }
    }

    @Test
    fun `adding on an uneven meter appends the whole cycle`() {
        val draft = EditorDraft.from(null).selectMeter(METERS.first { it.id == "78" })
        assertEquals(7, draft.steps)
        val grown = draft.addGroup()
        assertEquals(listOf(2, 2, 3, 2, 2, 3), grown.groups)
        assertEquals(14, grown.steps)
    }

    @Test
    fun `removing stops at one unit's worth of groups`() {
        var draft = EditorDraft.from(null)
        draft = draft.removeGroup()
        draft = draft.removeGroup()
        draft = draft.removeGroup()
        // One unit left: [2].
        assertEquals(listOf(2), draft.groups)
        // Refused: cannot drop below one unit.
        assertEquals(listOf(2), draft.removeGroup().groups)
    }

    @Test
    fun `resizing clamps a cursor that would fall off the end`() {
        var draft = EditorDraft.from(null).addGroup()
        draft = draft.setCursor(9)
        assertEquals(9, draft.cursor)
        draft = draft.removeGroup()
        assertTrue("cursor ${draft.cursor} is past the new end", draft.cursor < draft.steps)
    }

    @Test
    fun `resizing resets undo because old snapshots have the old length`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH)[0])
        assertTrue(draft.canUndo)
        draft = draft.addGroup()
        assertFalse("undo must not survive a resize", draft.canUndo)
    }

    // ── Pads and cursor ────────────────────────────────────────────────────

    @Test
    fun `a pad writes the cursor column and advances it`() {
        var draft = EditorDraft.from(null)
        val ta = padSetFor(EditLane.BOTH).first { it.key == "ta" }
        draft = draft.tapPad(ta)

        assertEquals(Stroke.OPEN, draft.pattern(LaneId.DAYAN)[0])
        assertNull("a dayan-only pad must not touch the bayan", draft.pattern(LaneId.BAYAN)[0])
        assertEquals(1, draft.cursor)
    }

    @Test
    fun `a combo pad writes both heads in one gesture`() {
        var draft = EditorDraft.from(null)
        val da = padSetFor(EditLane.BOTH).first { it.key == "da" }
        draft = draft.tapPad(da)

        assertEquals(Stroke.OPEN, draft.pattern(LaneId.DAYAN)[0])
        assertEquals(Stroke.OPEN, draft.pattern(LaneId.BAYAN)[0])
    }

    @Test
    fun `the cursor wraps around the end of the beat`() {
        var draft = EditorDraft.from(null).setCursor(7)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "rest" })
        assertEquals(0, draft.cursor)
    }

    @Test
    fun `a rest pad writes rests, not absences`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "rest" })
        assertNull(draft.pattern(LaneId.DAYAN)[1])
        assertNull(draft.pattern(LaneId.BAYAN)[1])
    }

    @Test
    fun `isolated pads write exactly one lane`() {
        assertEquals(setOf(LaneId.DAYAN), padSetFor(EditLane.DAYAN).first().write.keys)
        assertEquals(setOf(LaneId.BAYAN), padSetFor(EditLane.BAYAN).first().write.keys)
        assertEquals(setOf(LaneId.KARTAL), padSetFor(EditLane.KARTAL).first().write.keys)
        // And the both-hands set has no kartal pads: cymbals are authored isolated.
        assertTrue(
            padSetFor(EditLane.BOTH).none { it.write.containsKey(LaneId.KARTAL) }
        )
    }

    @Test
    fun `toggling a lane isolates it and toggling again returns to both`() {
        var draft = EditorDraft.from(null)
        draft = draft.toggleEditLane(LaneId.KARTAL)
        assertEquals(EditLane.KARTAL, draft.editLane)
        draft = draft.toggleEditLane(LaneId.KARTAL)
        assertEquals(EditLane.BOTH, draft.editLane)
    }

    // ── Undo ──────────────────────────────────────────────────────────────

    @Test
    fun `undo restores the patterns and the cursor`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ge" })
        assertEquals(2, draft.cursor)

        draft = draft.undoLast()
        assertEquals(1, draft.cursor)
        assertNull(draft.pattern(LaneId.BAYAN)[1])
        assertEquals(Stroke.OPEN, draft.pattern(LaneId.DAYAN)[0])

        draft = draft.undoLast()
        assertEquals(0, draft.cursor)
        assertNull(draft.pattern(LaneId.DAYAN)[0])
        assertFalse(draft.canUndo)
        // Undoing past the beginning is a no-op, not a crash.
        assertEquals(0, draft.undoLast().cursor)
    }

    @Test
    fun `the undo stack is capped`() {
        var draft = EditorDraft.from(null)
        val ta = padSetFor(EditLane.BOTH).first { it.key == "ta" }
        repeat(EditorDraft.UNDO_LIMIT + 10) { draft = draft.tapPad(ta) }
        assertEquals(EditorDraft.UNDO_LIMIT, draft.undo.size)
    }

    @Test
    fun `clear empties every lane and is undoable`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "da" })
        draft = draft.clearGrid()

        for (lane in LaneId.ORDERED) {
            assertTrue(draft.pattern(lane).all { it == null })
        }
        draft = draft.undoLast()
        assertEquals(Stroke.OPEN, draft.pattern(LaneId.DAYAN)[0])
    }

    // ── Feel changes ───────────────────────────────────────────────────────

    @Test
    fun `changing the feel clears the grid and resets cursor and undo`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        draft = draft.selectMeter(METERS.first { it.id == "78" })

        assertEquals(listOf(2, 2, 3), draft.groups)
        assertEquals(7, draft.steps)
        assertEquals(3.5, draft.beatsPerBar, 0.0)
        assertEquals(0, draft.cursor)
        assertFalse(draft.canUndo)
        for (lane in LaneId.ORDERED) {
            assertTrue(draft.pattern(lane).all { it == null })
        }
    }

    // ── Paging and scrubbing ───────────────────────────────────────────────

    @Test
    fun `the zoom pages the beat in eight-cell windows`() {
        var draft = EditorDraft.from(null)
        repeat(4) { draft = draft.addGroup() }   // 16 cells
        assertEquals(16, draft.steps)
        assertEquals(8, draft.zoomCells)
        assertEquals(2, draft.pageCount)
        assertEquals(0, draft.windowStart)
        assertEquals(8, draft.windowEnd)

        draft = draft.setCursor(12)
        assertEquals(1, draft.page)
        assertEquals(8, draft.windowStart)
        assertEquals(16, draft.windowEnd)
    }

    @Test
    fun `a beat of eight cells or fewer is a single page with no frame`() {
        val draft = EditorDraft.from(null)
        assertEquals(1, draft.pageCount)
    }

    @Test
    fun `scrubbing maps a fraction of the beat to a cell`() {
        val draft = EditorDraft.from(null)
        assertEquals(0, draft.scrubToFraction(0f).cursor)
        assertEquals(4, draft.scrubToFraction(0.5f).cursor)
        assertEquals(7, draft.scrubToFraction(1f).cursor)
        // Out-of-range fractions clamp rather than indexing past the end.
        assertEquals(7, draft.scrubToFraction(3f).cursor)
        assertEquals(0, draft.scrubToFraction(-1f).cursor)
    }

    // ── Output ─────────────────────────────────────────────────────────────

    @Test
    fun `a beat with no cymbals stays a two-lane beat`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        val beat = draft.toBeat(null)

        assertFalse(beat.lanePatterns.containsKey(LaneId.KARTAL))
        assertTrue(beat.lanePatterns.containsKey(LaneId.DAYAN))
        assertTrue(beat.lanePatterns.containsKey(LaneId.BAYAN))
    }

    @Test
    fun `drawing cymbals adds the kartal lane`() {
        var draft = EditorDraft.from(null)
        draft = draft.toggleEditLane(LaneId.KARTAL)
        draft = draft.tapPad(padSetFor(EditLane.KARTAL).first { it.key == "kch" })
        assertTrue(draft.toBeat(null).lanePatterns.containsKey(LaneId.KARTAL))
    }

    @Test
    fun `an unnamed draft saves as Custom Beat and a new beat has no id`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        val beat = draft.toBeat(null)
        assertEquals("Custom Beat", beat.name)
        assertNull(beat.id)
        assertEquals("Custom", beat.note)
    }

    @Test
    fun `an uneven draft persists a fractional bar length`() {
        var draft = EditorDraft.from(null).selectMeter(METERS.first { it.id == "78" })
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        val beat = draft.toBeat(null)

        assertEquals(7, beat.steps)
        assertEquals(3.5, beat.beatsPerBar, 0.0)
        assertEquals(listOf(2, 2, 3), beat.groups)
        // Non-uniform groups persist cpq as cellsPerGroup, as the web editor does.
        assertEquals(2, beat.cellsPerGroup)
    }

    @Test
    fun `a uniform draft persists the group size as cellsPerGroup`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(EditLane.BOTH).first { it.key == "ta" })
        val beat = draft.toBeat(null)
        assertEquals(2, beat.cellsPerGroup)
        assertEquals(4.0, beat.beatsPerBar, 0.0)
    }

    @Test
    fun `the preview beat always carries all three lanes`() {
        // The strip renders whatever lanes the beat has; a preview with an absent
        // kartal row would make the cymbal lane vanish mid-authoring.
        val preview = EditorDraft.from(null).toPreviewBeat()
        assertEquals(LaneId.ORDERED, preview.activeLanes())
        assertEquals("Preview", preview.name)
    }

    @Test
    fun `editing an existing custom beat keeps its id`() {
        val existing = BEATS.first().copy(id = "mine", name = "Mine")
        val draft = EditorDraft.from(existing)
        assertEquals("mine", draft.toBeat(existing.id).id)
    }
}
