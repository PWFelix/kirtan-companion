package com.kirtan.companion.ui.editor

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kirtan.companion.EnglishStrings

/**
 * The editor's draft model, tested without a compositor.
 *
 * Everything the web editor does to state lives in [EditorDraft], so these tests
 * are the editor's real safety net: meter math, the undo stack, pad writes and
 * what gets persisted. The Compose file owns only presentation.
 */
class EditorDraftTest {
    // Storage and editor text comes from values/strings.xml; read it as English.
    init { EnglishStrings.install() }


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
        draft = draft.tapPad(padSetFor(LaneId.DAYAN)[0])
        assertTrue(draft.canUndo)
        draft = draft.addGroup()
        assertFalse("undo must not survive a resize", draft.canUndo)
    }

    // ── Pads and cursor ────────────────────────────────────────────────────

    @Test
    fun `a pad writes the cursor column and advances it`() {
        var draft = EditorDraft.from(null)
        val ta = padSetFor(LaneId.DAYAN).first { it.key == "ta" }
        draft = draft.tapPad(ta)

        assertEquals(Stroke.OPEN, draft.pattern(LaneId.DAYAN)[0])
        assertNull("a dayan-only pad must not touch the bayan", draft.pattern(LaneId.BAYAN)[0])
        assertEquals(1, draft.cursor)
    }

    @Test
    fun `a two-hand bol is both drum rows filled in one column`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
        draft = draft.pickCell(LaneId.BAYAN, 0)
        draft = draft.tapPad(padSetFor(draft.cursorLane).first { it.key == "ge" })

        assertEquals(Stroke.OPEN, draft.pattern(LaneId.DAYAN)[0])
        assertEquals(Stroke.OPEN, draft.pattern(LaneId.BAYAN)[0])
        // Entry carries on along the bayan row.
        assertEquals(LaneId.BAYAN, draft.cursorLane)
        assertEquals(1, draft.cursor)
    }

    @Test
    fun `the cursor wraps around the end of the beat`() {
        var draft = EditorDraft.from(null).setCursor(7)
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "rest" })
        assertEquals(0, draft.cursor)
    }

    @Test
    fun `a rest pad writes rests, not absences`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "rest" })
        assertNull(draft.pattern(LaneId.DAYAN)[1])
    }

    @Test
    fun `each row's palette is its open, closed and rest, writing only that row`() {
        assertEquals(listOf("Ta", "Te", "Rest"), padSetFor(LaneId.DAYAN).map { it.label })
        assertEquals(listOf("Ge", "Khe", "Rest"), padSetFor(LaneId.BAYAN).map { it.label })
        assertEquals(listOf("Ching", "Chip", "Rest"), padSetFor(LaneId.KARTAL).map { it.label })
        for (lane in LaneId.ORDERED) {
            assertTrue(padSetFor(lane).all { it.write.keys == setOf(lane) })
        }
    }

    @Test
    fun `tapping a cell selects it and entry advances along that row`() {
        var draft = EditorDraft.from(null)
        assertEquals(LaneId.DAYAN, draft.cursorLane)
        draft = draft.pickCell(LaneId.KARTAL, 3)
        assertEquals(LaneId.KARTAL, draft.cursorLane)
        assertEquals(3, draft.cursor)
        draft = draft.tapPad(padSetFor(draft.cursorLane).first { it.key == "kch" })
        draft = draft.tapPad(padSetFor(draft.cursorLane).first { it.key == "kdm" })
        assertEquals(Stroke.OPEN, draft.pattern(LaneId.KARTAL)[3])
        assertEquals(Stroke.CLOSED, draft.pattern(LaneId.KARTAL)[4])
        assertEquals(LaneId.KARTAL, draft.cursorLane)
        assertEquals(5, draft.cursor)
        // The drum rows are untouched.
        assertTrue(draft.pattern(LaneId.DAYAN).all { it == null })
        // Undo puts the cursor back on the cell, row included.
        draft = draft.pickCell(LaneId.BAYAN, 0).undoLast()
        assertEquals(LaneId.KARTAL, draft.cursorLane)
        assertEquals(4, draft.cursor)
    }

    // ── Undo ──────────────────────────────────────────────────────────────

    @Test
    fun `undo restores the patterns and the cursor`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
        draft = draft.tapPad(padSetFor(LaneId.BAYAN).first { it.key == "ge" })
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
        val ta = padSetFor(LaneId.DAYAN).first { it.key == "ta" }
        repeat(EditorDraft.UNDO_LIMIT + 10) { draft = draft.tapPad(ta) }
        assertEquals(EditorDraft.UNDO_LIMIT, draft.undo.size)
    }

    @Test
    fun `clear empties every lane and is undoable`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
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
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
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
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
        val beat = draft.toBeat(null)

        assertFalse(beat.lanePatterns.containsKey(LaneId.KARTAL))
        assertTrue(beat.lanePatterns.containsKey(LaneId.DAYAN))
        assertTrue(beat.lanePatterns.containsKey(LaneId.BAYAN))
    }

    @Test
    fun `drawing cymbals adds the kartal lane`() {
        var draft = EditorDraft.from(null)
        draft = draft.pickCell(LaneId.KARTAL, draft.cursor)
        draft = draft.tapPad(padSetFor(LaneId.KARTAL).first { it.key == "kch" })
        assertTrue(draft.toBeat(null).lanePatterns.containsKey(LaneId.KARTAL))
    }

    @Test
    fun `an unnamed draft saves as Custom Beat and a new beat has no id`() {
        var draft = EditorDraft.from(null)
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
        val beat = draft.toBeat(null)
        assertEquals("Custom Beat", beat.name)
        assertNull(beat.id)
        assertEquals("Custom", beat.note)
    }

    @Test
    fun `an uneven draft persists a fractional bar length`() {
        var draft = EditorDraft.from(null).selectMeter(METERS.first { it.id == "78" })
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
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
        draft = draft.tapPad(padSetFor(LaneId.DAYAN).first { it.key == "ta" })
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

    @Test
    fun padsSoundOnlyTheStrokesTheyWriteNeverTheRests() {
        val pads = padSetFor(LaneId.DAYAN).associateBy { it.key }
        // Ta writes a bayan REST; it must sound the open dayan alone, not a Khe too.
        assertEquals(listOf(LaneId.DAYAN to true), pads.getValue("ta").sounds())
        assertEquals(listOf(LaneId.BAYAN to false), padSetFor(LaneId.BAYAN).first { it.key == "khe" }.sounds())
        assertTrue(pads.getValue("rest").sounds().isEmpty())
        assertTrue(padSetFor(LaneId.KARTAL).first { it.rest }.sounds().isEmpty())
    }

    // ── Resolution lever ───────────────────────────────────────────────────

    private fun withDayan(draft: EditorDraft, vararg hits: Int): EditorDraft {
        val cells = MutableList<Stroke?>(draft.steps) { null }
        hits.forEach { cells[it] = Stroke.OPEN }
        return draft.copy(lanes = draft.lanes + (LaneId.DAYAN to cells))
    }

    private fun dayanHits(draft: EditorDraft) =
        draft.pattern(LaneId.DAYAN).withIndex().filter { it.value != null }.map { it.index }

    @Test
    fun `a simple meter offers cells per beat, an odd meter a multiplier`() {
        val std = EditorDraft.from(null).resolution
        assertFalse(std.multiplier)
        assertEquals(2, std.current)
        assertEquals(listOf(1, 2, 3, 4, 6, 8), std.options)

        val seven = EditorDraft.from(null).selectMeter(METERS.first { it.id == "78" }).resolution
        assertTrue(seven.multiplier)
        assertEquals(1, seven.current)
        assertEquals(listOf(1, 2, 3), seven.options)
    }

    @Test
    fun `going finer keeps every hit at its moment and the beat count`() {
        val draft = withDayan(EditorDraft.from(null), 0, 3, 5).setResolution(4)
        assertEquals(listOf(4, 4, 4, 4), draft.groups)
        assertEquals(4, draft.cpq)
        assertEquals(4, draft.beatCount)
        assertEquals(listOf(0, 6, 10), dayanHits(draft))
        assertNull(draft.resampleReport) // nothing moved
        assertEquals("dbl", draft.meter.id)
    }

    @Test
    fun `going coarser snaps to the nearest cell and reports what was lost`() {
        val fine = withDayan(EditorDraft.from(null).setResolution(4), 0, 1, 2, 4)
        val coarse = fine.setResolution(2)
        assertEquals(listOf(2, 2, 2, 2), coarse.groups)
        // 0 stays, 1 (a sixteenth) rounds onto 1 along with 2, 4 lands on 2.
        assertEquals(listOf(0, 1, 2), dayanHits(coarse))
        assertEquals(ResampleReport(moved = 1, dropped = 1), coarse.resampleReport)
    }

    @Test
    fun `undo restores the grid a resolution change replaced`() {
        val before = withDayan(EditorDraft.from(null).setResolution(4), 1, 3)
        val after = before.setResolution(3).undoLast()
        assertEquals(before.groups, after.groups)
        assertEquals(before.cpq, after.cpq)
        assertEquals(dayanHits(before), dayanHits(after))
    }

    @Test
    fun `an odd meter keeps its shape under the multiplier`() {
        val seven = withDayan(EditorDraft.from(null).selectMeter(METERS.first { it.id == "78" }), 0, 4, 6)
        val doubled = seven.setResolution(2)
        assertEquals(listOf(4, 4, 6), doubled.groups)
        assertEquals(4, doubled.cpq)
        assertEquals(listOf(0, 8, 12), dayanHits(doubled))
        assertEquals("78", doubled.meter.id)
        assertEquals(2, doubled.resolution.current)
        // ± still adds a whole 2+2+3 cycle, at the new resolution.
        assertEquals(listOf(4, 4, 6, 4, 4, 6), doubled.addGroup().groups)
        // And a saved ×2 seven-eight reopens as seven-eight.
        assertEquals("78", meterFor(listOf(4, 4, 6), 4).id)
    }

    @Test
    fun `thirty-two beats fit at every resolution, and plus stops there`() {
        var draft = EditorDraft.from(null)
        repeat(28) { draft = draft.addGroup() } // 32 beats of 2
        assertEquals(64, draft.steps)
        assertTrue(draft.resolutionFits(8))
        assertTrue(draft.resolutionFits(12))
        draft = draft.setResolution(8)
        assertEquals(256, draft.steps)
        assertFalse(draft.canAddBeat)  // the 33rd beat is refused
        assertEquals(256, draft.addGroup().steps)
        // At 12 per beat, 32 beats is exactly the cell bound.
        assertEquals(ShareCodec.MAX_STEPS, draft.setResolution(12).steps)
    }

    @Test
    fun `pages hold whole beats`() {
        var draft = EditorDraft.from(null).setResolution(3) // 4 beats of 3
        assertEquals(listOf(0 until 6, 6 until 12), draft.pages)
        draft = draft.setPagePage(1)
        assertEquals(6, draft.windowStart)
    }

    // ── Copy and paste ─────────────────────────────────────────────────────

    /** An 8-cell draft whose first half is drawn: dayan O . O . / bayan O O . X */
    private fun guruHalf(): EditorDraft {
        val d = EditorDraft.from(null)
        return d.copy(lanes = d.lanes + mapOf(
            LaneId.DAYAN to listOf(Stroke.OPEN, null, Stroke.OPEN, null, null, null, null, null),
            LaneId.BAYAN to listOf(Stroke.OPEN, Stroke.OPEN, null, Stroke.CLOSED, null, null, null, null),
        ))
    }

    @Test
    fun `the selection runs from the anchor to the cursor either way round`() {
        val d = guruHalf().setCursor(3).startSelect().setCursor(1)
        assertEquals(1..3, d.selection)
        assertEquals(3..5, d.setCursor(5).selection)
        assertNull(d.cancelSelect().selection)
    }

    @Test
    fun `copy takes every lane and pastes at the cursor`() {
        val d = guruHalf().startSelect().setCursor(3).copySelection()
        assertNull(d.selection)
        assertEquals(4, d.clipLength)
        val p = d.setCursor(4).paste()
        assertEquals(listOf(Stroke.OPEN, null, Stroke.OPEN, null), p.pattern(LaneId.DAYAN).subList(4, 8))
        assertEquals(listOf(Stroke.OPEN, Stroke.OPEN, null, Stroke.CLOSED), p.pattern(LaneId.BAYAN).subList(4, 8))
        assertEquals(0, p.cursor)  // after the run, wrapped
        assertEquals(d.setCursor(4).lanes, p.undoLast().lanes)
    }

    @Test
    fun `paste with the bayan closed turns only bayan opens to closed`() {
        val p = guruHalf().startSelect().setCursor(3).copySelection().setCursor(4).paste(closeBayan = true)
        assertEquals(listOf(Stroke.CLOSED, Stroke.CLOSED, null, Stroke.CLOSED), p.pattern(LaneId.BAYAN).subList(4, 8))
        assertEquals(listOf(Stroke.OPEN, null, Stroke.OPEN, null), p.pattern(LaneId.DAYAN).subList(4, 8))
        // The first half is untouched.
        assertEquals(Stroke.OPEN, p.pattern(LaneId.BAYAN)[0])
    }

    @Test
    fun `paste stops at the end of the beat rather than wrapping`() {
        val p = guruHalf().startSelect().setCursor(3).copySelection().setCursor(6).paste()
        assertEquals(listOf(Stroke.OPEN, null), p.pattern(LaneId.DAYAN).subList(6, 8))
        assertEquals(Stroke.OPEN, p.pattern(LaneId.DAYAN)[0])
        assertEquals(null, p.pattern(LaneId.DAYAN)[1])
        assertEquals(0, p.cursor)
    }

    @Test
    fun `the clip survives a length change and choosing stops`() {
        val d = guruHalf().startSelect().setCursor(3).copySelection().startSelect().addGroup()
        assertNull(d.selection)
        assertEquals(4, d.clipLength)
        val p = d.setCursor(8).paste()
        assertEquals(Stroke.OPEN, p.pattern(LaneId.DAYAN)[8])
    }

    @Test
    fun `paste with nothing copied does nothing`() {
        val d = guruHalf()
        assertEquals(d, d.paste())
    }

    // ── Tap-to-cycle ──

    @Test
    fun `a tap on an empty cell writes the first stroke, then re-taps cycle in place`() {
        var d = EditorDraft.from(null)
        d = d.tapCell(LaneId.BAYAN, 3)
        assertEquals(3, d.cursor)
        assertEquals(LaneId.BAYAN, d.cursorLane)
        assertEquals(Stroke.OPEN, d.pattern(LaneId.BAYAN)[3])
        d = d.tapCell(LaneId.BAYAN, 3)
        assertEquals(Stroke.CLOSED, d.pattern(LaneId.BAYAN)[3])
        d = d.tapCell(LaneId.BAYAN, 3)
        assertNull(d.pattern(LaneId.BAYAN)[3])
        d = d.tapCell(LaneId.BAYAN, 3)
        assertEquals(Stroke.OPEN, d.pattern(LaneId.BAYAN)[3])
        assertEquals(3, d.cursor)
        assertTrue(d.pattern(LaneId.DAYAN).all { it == null })
        assertNull(d.undoLast().pattern(LaneId.BAYAN)[3])
    }

    @Test
    fun `a filled cell is only selected, and choosing a run never writes`() {
        var d = EditorDraft.from(null).tapCell(LaneId.DAYAN, 0).tapCell(LaneId.DAYAN, 0) // closed
        d = d.tapCell(LaneId.BAYAN, 5).tapCell(LaneId.DAYAN, 0)
        assertEquals(Stroke.CLOSED, d.pattern(LaneId.DAYAN)[0])
        d = d.startSelect().tapCell(LaneId.BAYAN, 7)
        assertNull(d.pattern(LaneId.BAYAN)[7])
    }
}
