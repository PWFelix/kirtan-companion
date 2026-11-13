package com.kirtan.companion.ui.editor

import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.Bols
import com.kirtan.companion.data.labelsFromGroups
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlin.math.ceil
import kotlin.math.min
import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * The beat editor's draft model, pure and Android-free.
 *
 * Ported from `src/BeatEditor.jsx`, whose header states the three zones and the
 * meter model; everything here is the state those zones edit, lifted out of the
 * component so it can be unit-tested without a compositor. The component owns
 * only presentation: which page is on screen, which sheet is open, and the
 * engine calls.
 *
 * METER MODEL, unchanged: a beat is a list of GROUPS — one entry per numbered
 * beat, its value the cells it spans — which is what makes uneven meters like
 * 7/8 = [2,2,3] expressible at all, where a single cellsPerGroup cannot. Cells
 * stay equal-duration; the bar's length in quarter notes is `steps / cpq` and may
 * be fractional (7/8 = 3.5), which is why [Beat.beatsPerBar] is a Double.
 */

/** One feel/meter preset: the bar it seeds, the unit ± adds or removes, and cpq. */
data class MeterPreset(
    val id: String,
    val label: String,
    val sig: String,
    val bar: List<Int>,
    val unit: List<Int>,
    val cpq: Int,
)

/**
 * The feel picker's entries, in the order the web app lists them. `cpq` is the
 * subdivision density: 2 eighths, 3 triplets, 4 sixteenths. The two uneven
 * presets carry a multi-value unit so ± grows the bar by one whole 3+3 or 2+2+3
 * cycle rather than by a single group, which would turn 6/8 into 9/8-shaped
 * nonsense on the first press of +.
 */
val METERS: List<MeterPreset> = listOf(
    MeterPreset("std", "Standard", "4/4 · eighths", listOf(2, 2, 2, 2), listOf(2), 2),
    MeterPreset("trip", "Triplets", "swing · 12/8", listOf(3, 3, 3, 3), listOf(3), 3),
    MeterPreset("dbl", "Double-time", "16ths", listOf(4, 4, 4, 4), listOf(4), 4),
    MeterPreset("68", "Six-eight", "6/8 · 3+3", listOf(3, 3), listOf(3, 3), 2),
    MeterPreset("78", "Seven-eight", "7/8 · 2+2+3", listOf(2, 2, 3), listOf(2, 2, 3), 2),
)

/** The preset at k× its resolution: every group and cpq multiplied by [k]. */
fun MeterPreset.scaled(k: Int): MeterPreset =
    if (k == 1) this else copy(bar = bar.map { it * k }, unit = unit.map { it * k }, cpq = cpq * k)

/** The largest whole factor shared by every group and cpq (1 when there is none). */
fun resolutionFactor(groups: List<Int>, cpq: Int): Int {
    tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    return groups.fold(cpq) { acc, g -> gcd(acc, g) }.coerceAtLeast(1)
}

/** Does [arr] consist of whole repetitions of [pattern]? */
internal fun repeats(pattern: List<Int>, arr: List<Int>): Boolean {
    if (arr.isEmpty() || arr.size % pattern.size != 0) return false
    return arr.indices.all { i -> arr[i] == pattern[i % pattern.size] }
}

/**
 * Recover the meter preset — or a synthesized "custom" one — from a beat's groups
 * and cpq, so editing an existing beat lands on the right picker entry instead of
 * resetting the user's feel to Standard.
 */
fun meterFor(groups: List<Int>, cpq: Int): MeterPreset {
    val uniform = groups.all { it == groups[0] }
    if (uniform && groups.isNotEmpty() && groups[0] == cpq) {
        METERS.firstOrNull { it.cpq == cpq && it.unit.size == 1 && it.unit[0] == cpq }
            ?.let { return it }
    }
    // Odd meters at a finer resolution (6/8 ×2 is [6,6] at cpq 4) are still that
    // meter: divide the common factor out, match, and scale the preset back up so
    // ± keeps adding a whole 3+3 or 2+2+3 cycle.
    val k = resolutionFactor(groups, cpq)
    val base = groups.map { it / k }
    if (cpq / k == 2) {
        for (id in listOf("68", "78")) {
            val preset = METERS.first { it.id == id }
            if (repeats(preset.unit, base)) return preset.scaled(k)
        }
    }
    return MeterPreset(
        id = "custom",
        label = "Custom",
        sig = "",
        bar = groups,
        unit = if (uniform && groups.isNotEmpty()) listOf(groups[0]) else groups.toList(),
        cpq = cpq,
    )
}

/**
 * The groups a draft starts from. A beat with stored groups uses them; an older
 * beat without them is reconstructed from cellsPerGroup exactly as
 * [com.kirtan.companion.data.groupsFor] does; no beat at all starts as one bar of
 * the default feel.
 */
fun seedGroups(beat: Beat?): List<Int> {
    if (beat == null) return METERS[0].bar.toList()
    beat.groups?.let { return it.toList() }
    val cpg = if (beat.cellsPerGroup > 0) {
        beat.cellsPerGroup
    } else {
        maxOf(1, kotlin.math.round(beat.steps.toDouble() / beat.beatsPerBar).toInt())
    }
    val beats = maxOf(1, kotlin.math.round(beat.steps.toDouble() / cpg).toInt())
    return List(beats) { cpg }
}

fun seedCpq(beat: Beat?): Int =
    if (beat == null) 2 else maxOf(1, kotlin.math.round(beat.steps / beat.beatsPerBar).toInt())

/**
 * One pad. [write] names the lane it touches and the stroke it leaves there; a
 * null stroke means "this pad sets the lane to a rest", which is distinct from
 * the pad not touching the lane at all.
 */
data class PadSpec(
    val key: String,
    val label: String,
    val write: Map<LaneId, Stroke?>,
    val rest: Boolean = false,
)

/**
 * The pad palette for one row, as `padsFor` in BeatEditor.jsx: its open stroke,
 * its closed stroke, and Rest. Editing is cell-driven, so the selected cell's row
 * picks the palette and every pad writes that row alone. A two-hand bol (Da,
 * Gi...) is simply both drum rows filled in the same column.
 */
fun padSetFor(lane: LaneId): List<PadSpec> {
    val id = lane.wireId
    val (open, closed, rest) = when (lane) {
        LaneId.DAYAN -> Triple("ta", "te", "rest")
        LaneId.BAYAN -> Triple("ge", "khe", "rb")
        LaneId.KARTAL -> Triple("kch", "kdm", "rk")
    }
    return listOf(
        PadSpec(open, Bols.forStroke(id, "O") ?: open, mapOf(lane to Stroke.OPEN)),
        PadSpec(closed, Bols.forStroke(id, "X") ?: closed, mapOf(lane to Stroke.CLOSED)),
        PadSpec(rest, "Rest", mapOf(lane to null), rest = true),
    )
}

/**
 * The samples a pad sounds when tapped: its non-rest writes, as (lane, open).
 * A null in [PadSpec.write] means "make this lane a rest" and must stay SILENT;
 * sounding it as `stroke == OPEN` played every rest as that lane's closed stroke,
 * so Ta also rang a bayan Khe (the wrong-sample-on-insert bug).
 */
fun PadSpec.sounds(): List<Pair<LaneId, Boolean>> =
    write.mapNotNull { (lane, stroke) -> stroke?.let { lane to (it == Stroke.OPEN) } }

/**
 * What an undo entry restores: the patterns, the cursor, and the grid's shape.
 * Shape is null for pad edits (which never change it) and set by a resolution
 * change, so undoing one restores the old grid along with the hits it moved.
 */
data class DraftSnapshot(
    val lanes: Map<LaneId, List<Stroke?>>,
    val cursor: Int,
    val cursorLane: LaneId? = null,
    val groups: List<Int>? = null,
    val meter: MeterPreset? = null,
)

/**
 * The resolution lever. In a SIMPLE meter (every beat the same, one beat per
 * quarter) it is literally cells per beat; in an odd meter (6/8, 7/8, anything
 * whose groups differ from cpq) it is a multiplier on the meter's own grouping,
 * so 7/8's 2+2+3 becomes 4+4+6 and keeps its shape.
 */
data class ResolutionLever(val multiplier: Boolean, val current: Int, val options: List<Int>)

/** What a resolution change did to the hits, for the notice under the grid. */
data class ResampleReport(val moved: Int, val dropped: Int)

/**
 * The whole draft. Immutable: every operation returns a new draft, which is what
 * makes the undo stack a list of previous values rather than a journal of inverse
 * operations — simpler, and impossible to replay out of order.
 */
data class EditorDraft(
    val groups: List<Int>,
    val meter: MeterPreset,
    val lanes: Map<LaneId, List<Stroke?>>,
    val bpm: Int,
    val name: String,
    val cursor: Int,
    /** The row the cursor is on: the cursor is a CELL (cursorLane, cursor). Picks the pads. */
    val cursorLane: LaneId = LaneId.DAYAN,
    val undo: List<DraftSnapshot> = emptyList(),
    /** Set by the last resolution change while any hit moved; cleared by the next edit. */
    val resampleReport: ResampleReport? = null,
    /**
     * Non-null while choosing a run of cells to copy: the cell where the run
     * started. The run is anchor..cursor, so tapping cells (on any page)
     * moves its other end.
     */
    val selectAnchor: Int? = null,
    /** The last copied run: every lane's cells, the same length. */
    val clip: Map<LaneId, List<Stroke?>>? = null,
    /** Swing saved with the beat, 50–67; see [com.kirtan.companion.data.Swing]. */
    val swing: Int = com.kirtan.companion.data.Swing.STRAIGHT,
) {
    val cpq: Int get() = meter.cpq
    val steps: Int get() = groups.sum()
    val beatsPerBar: Double get() = steps.toDouble() / cpq
    val labels: List<String> get() = labelsFromGroups(groups)

    /**
     * The zoom's pages: whole beats packed up to 8 cells (the main strip's width),
     * so a page never starts mid-beat. A single beat wider than 8 (12 cells at
     * 6/8 ×2…) gets a page to itself.
     */
    val pages: List<IntRange> get() {
        val out = mutableListOf<IntRange>()
        var start = 0
        var end = 0
        for (g in groups) {
            if (end > start && end - start + g > ZOOM_CELLS) {
                out += start until end
                start = end
            }
            end += g
        }
        if (end > start) out += start until end
        return out
    }
    val pageCount: Int get() = pages.size
    val page: Int get() = pages.indexOfFirst { cursor in it }.coerceAtLeast(0)
    val windowStart: Int get() = pages.getOrNull(page)?.first ?: 0
    val windowEnd: Int get() = pages.getOrNull(page)?.let { it.last + 1 } ?: steps
    val zoomCells: Int get() = windowEnd - windowStart

    /** ± is refused past MAX_STEPS (384 cells, 32 beats at 12 per beat). Versions before 0.4.35 / iOS 0.4.29 capped beats at 64 cells and drop longer ones. */
    val canAddBeat: Boolean get() =
        steps + meter.unit.sum() <= ShareCodec.MAX_STEPS && groups.size + meter.unit.size <= ShareCodec.MAX_GROUPS
    val canRemoveBeat: Boolean get() = groups.size > meter.unit.size

    /** How many beats the bar has — one per numbered group. */
    val beatCount: Int get() = groups.size

    val resolution: ResolutionLever get() {
        if (groups.isNotEmpty() && groups.all { it == cpq }) {
            return ResolutionLever(false, cpq, (SIMPLE_RESOLUTIONS + cpq).distinct().sorted())
        }
        val k = resolutionFactor(groups, cpq)
        return ResolutionLever(true, k, (MULTIPLIERS + k).distinct().sorted())
    }

    /** Whether [option] fits: the format caps beats at MAX_STEPS cells and 12 per beat. */
    fun resolutionFits(option: Int): Boolean {
        val (newGroups, _) = reshape(option)
        return newGroups.sum() <= ShareCodec.MAX_STEPS && newGroups.max() <= ShareCodec.MAX_GROUP_CELLS
    }

    /** Groups and cpq the grid would have at [option] on the resolution lever. */
    private fun reshape(option: Int): Pair<List<Int>, Int> {
        val lever = resolution
        return if (!lever.multiplier) {
            List(groups.size) { option } to option
        } else {
            groups.map { it / lever.current * option } to cpq / lever.current * option
        }
    }

    /**
     * Change the resolution, KEEPING THE RHYTHM: every hit stays at its moment in
     * the bar, landing on the nearest new cell. Going finer by a whole factor
     * loses nothing; going coarser (or 2 → 3) moves hits that fall between new
     * cells, and when two hits in a lane land on one cell the nearer one wins.
     * Undo restores the old grid exactly.
     */
    fun setResolution(option: Int): EditorDraft {
        if (option == resolution.current || !resolutionFits(option)) return this
        val (newGroups, newCpq) = reshape(option)
        val newSteps = newGroups.sum()
        var moved = 0
        var dropped = 0
        val newLanes = lanes.mapValues { (_, cells) ->
            val out = arrayOfNulls<Stroke>(newSteps)
            val error = DoubleArray(newSteps) { Double.MAX_VALUE }
            cells.forEachIndexed { j, stroke ->
                if (stroke == null) return@forEachIndexed
                val exact = j.toDouble() * newCpq / cpq
                // The bar loops, so a hit that rounds past the end is the next downbeat.
                val target = kotlin.math.floor(exact + 0.5).toInt() % newSteps
                val err = kotlin.math.abs(exact - kotlin.math.round(exact))
                if (j * newCpq % cpq != 0) moved++
                if (out[target] != null) {
                    dropped++
                    if (err >= error[target]) return@forEachIndexed
                }
                out[target] = stroke
                error[target] = err
            }
            out.toList()
        }
        val scaledMeter = meterFor(newGroups, newCpq).let {
            if (it.id == "custom") it.copy(unit = meter.unit.map { u -> u * newCpq / cpq }) else it
        }
        return copy(
            groups = newGroups,
            meter = scaledMeter,
            lanes = newLanes,
            cursor = (cursor * newCpq / cpq).coerceIn(0, newSteps - 1),
            undo = (undo + DraftSnapshot(lanes, cursor, cursorLane, groups, meter)).takeLast(UNDO_LIMIT),
            resampleReport = if (moved > 0 || dropped > 0) ResampleReport(moved, dropped) else null,
            selectAnchor = null,
        )
    }

    val canUndo: Boolean get() = undo.isNotEmpty()

    val hasAnyHit: Boolean get() = lanes.values.any { cells -> cells.any { it != null } }

    /** Whether the user drew any cymbals at all — see [toBeat]. */
    val hasKartal: Boolean get() = lanes[LaneId.KARTAL]?.any { it != null } == true

    fun pattern(lane: LaneId): List<Stroke?> =
        lanes[lane] ?: List(steps) { null }

    // ── Undo ───────────────────────────────────────────────────────────────

    /**
     * Snapshot before a destructive edit. Capped at 60 entries, as in the web
     * editor: enough to back out of a long authoring run, bounded enough that a
     * session of pad taps cannot grow memory without limit.
     */
    fun withUndo(): EditorDraft = copy(
        undo = (undo + DraftSnapshot(lanes, cursor, cursorLane)).takeLast(UNDO_LIMIT)
    )

    fun undoLast(): EditorDraft {
        val prev = undo.lastOrNull() ?: return this
        return copy(
            lanes = prev.lanes,
            cursor = prev.cursor,
            cursorLane = prev.cursorLane ?: cursorLane,
            groups = prev.groups ?: groups,
            meter = prev.meter ?: meter,
            undo = undo.dropLast(1),
            resampleReport = null,
        )
    }

    // ── Meter and length ───────────────────────────────────────────────────

    /**
     * Append one unit of the current meter, padding every lane with rests.
     * Resets undo, as the web editor does: an undo entry records patterns of the
     * OLD length, so restoring one after a resize would write past the end.
     */
    fun addGroup(): EditorDraft {
        if (!canAddBeat) return this
        val pad = List(meter.unit.sum()) { null as Stroke? }
        return copy(
            groups = groups + meter.unit,
            lanes = lanes.mapValues { (_, cells) -> cells + pad },
            undo = emptyList(),
            selectAnchor = null,
            resampleReport = null,
        )
    }

    /** Drop the last unit; refused when only one unit's worth of groups remains. */
    fun removeGroup(): EditorDraft {
        if (groups.size <= meter.unit.size) return this
        val drop = meter.unit.sum()
        return copy(
            groups = groups.dropLast(meter.unit.size),
            lanes = lanes.mapValues { (_, cells) -> cells.dropLast(drop) },
            cursor = min(cursor, steps - drop - 1).coerceAtLeast(0),
            undo = emptyList(),
            selectAnchor = null,
            resampleReport = null,
        )
    }

    /**
     * Change the feel. CLEARS THE GRID, and the sheet says so: a meter change
     * redefines what every existing cell means, so keeping the old marks would
     * silently relabel the user's rhythm rather than preserve it.
     */
    fun selectMeter(preset: MeterPreset): EditorDraft = copy(
        resampleReport = null,
        meter = preset,
        groups = preset.bar.toList(),
        lanes = LaneId.ORDERED.associateWith { List(preset.bar.sum()) { null as Stroke? } },
        cursor = 0,
        undo = emptyList(),
        selectAnchor = null,
    )

    // ── Cursor and editing ─────────────────────────────────────────────────

    fun setCursor(index: Int): EditorDraft =
        copy(cursor = index.coerceIn(0, (steps - 1).coerceAtLeast(0)))

    /** Scrub the overview: a fraction of the whole beat becomes a cell index. */
    fun scrubToFraction(fraction: Float): EditorDraft =
        setCursor(((fraction.coerceIn(0f, 1f) * steps).toInt()).coerceAtMost(steps - 1))

    fun setPagePage(delta: Int): EditorDraft {
        val target = pages.getOrNull((page + delta).coerceIn(0, pageCount - 1)) ?: return this
        return setCursor(target.first)
    }

    /**
     * Write one pad into the selected cell and advance along the same row. The
     * pad's [PadSpec.write] is the single source of truth for what is written
     * and (via [sounds]) what the caller plays.
     */
    fun tapPad(pad: PadSpec): EditorDraft {
        val withHistory = withUndo()
        val updated = withHistory.lanes.toMutableMap()
        for ((lane, stroke) in pad.write) {
            val cells = (withHistory.lanes[lane] ?: List(steps) { null }).toMutableList()
            if (cursor in cells.indices) cells[cursor] = stroke
            updated[lane] = cells
        }
        return withHistory.copy(
            resampleReport = null,
            lanes = updated,
            cursor = (withHistory.cursor + 1).mod(steps.coerceAtLeast(1)),
        )
    }

    fun clearGrid(): EditorDraft = withUndo().copy(
        lanes = lanes.mapValues { List(steps) { null as Stroke? } },
        cursor = 0,
    )

    // ── Copy and paste ─────────────────────────────────────────────────────
    //
    // A run of cells is copied across EVERY lane at once — what was played at
    // those moments, not one hand of it. The common use is a beat whose second
    // half repeats the first with the bayan closed (guru then laghu), so paste
    // has a variant that turns the bayan's open strokes into closed ones.

    /** The run being chosen, in cell order; null when not choosing. */
    val selection: IntRange? get() = selectAnchor?.let { a ->
        val b = cursor
        minOf(a, b).coerceIn(0, steps - 1)..maxOf(a, b).coerceIn(0, steps - 1)
    }

    val clipLength: Int get() = clip?.values?.firstOrNull()?.size ?: 0

    fun startSelect(): EditorDraft = copy(selectAnchor = cursor)

    fun cancelSelect(): EditorDraft = copy(selectAnchor = null)

    /** Keep the chosen run as the clip and stop choosing; the cursor goes to its start. */
    fun copySelection(): EditorDraft {
        val range = selection ?: return this
        return copy(
            clip = LaneId.ORDERED.associateWith { lane -> pattern(lane).slice(range) },
            selectAnchor = null,
            cursor = range.first,
        )
    }

    /**
     * Write the clip over the cells from the cursor on, every lane, stopping at
     * the end of the beat (nothing wraps round to the start). With
     * [closeBayan], the bayan's open strokes land closed. The cursor moves to
     * the cell after the pasted run, ready to paste again.
     */
    fun paste(closeBayan: Boolean = false): EditorDraft {
        val c = clip ?: return this
        val n = minOf(clipLength, steps - cursor)
        if (n <= 0) return this
        val withHistory = withUndo()
        val updated = withHistory.lanes.toMutableMap()
        for (lane in LaneId.ORDERED) {
            val src = c[lane] ?: continue
            val cells = pattern(lane).toMutableList()
            for (i in 0 until n) {
                val v = src[i]
                cells[cursor + i] = if (closeBayan && lane == LaneId.BAYAN && v == Stroke.OPEN) Stroke.CLOSED else v
            }
            updated[lane] = cells
        }
        return withHistory.copy(
            lanes = updated,
            resampleReport = null,
            selectAnchor = null,
            cursor = (cursor + n).mod(steps),
        )
    }

    fun setName(value: String): EditorDraft = copy(name = value)

    fun setBpm(value: Int): EditorDraft = copy(bpm = value)

    fun setSwing(value: Int): EditorDraft = copy(swing = com.kirtan.companion.data.Swing.clamp(value))

    /** Tapping a cell in [lane]'s row: select it, which also swaps the pads to that row's. */
    fun pickCell(lane: LaneId, index: Int): EditorDraft =
        setCursor(index).copy(cursorLane = lane)

    /**
     * A tap on a grid cell. The first tap selects it (and so picks the pads); a
     * tap on the cell ALREADY selected steps it through its row's palette —
     * rest → open → closed → rest — so a cell can be edited without moving.
     * The cursor stays put. While choosing a run to copy, taps only move its end.
     */
    fun tapCell(lane: LaneId, index: Int): EditorDraft =
        if (selectAnchor == null && lane == cursorLane && index == cursor) cycleCell()
        else pickCell(lane, index)

    /** The selected cell, one step on through its palette; undoable like a pad. */
    fun cycleCell(): EditorDraft {
        if (cursor !in 0 until steps) return this
        val order: List<Stroke?> = listOf(null) + padSetFor(cursorLane).mapNotNull { it.write[cursorLane] }
        val now = pattern(cursorLane)[cursor]
        val next = order[(order.indexOf(now) + 1).mod(order.size)]
        val withHistory = withUndo()
        val cells = withHistory.pattern(cursorLane).toMutableList()
        cells[cursor] = next
        return withHistory.copy(resampleReport = null, lanes = withHistory.lanes + (cursorLane to cells))
    }

    // ── Output ─────────────────────────────────────────────────────────────

    /**
     * The draft as a savable beat.
     *
     * [id] null means "new": the library mints ids on save and is now the only
     * place in the app they come from. Editing an existing custom beat passes its
     * id through and so overwrites in place.
     *
     * The kartal line is persisted ONLY when the user drew one, so a beat with no
     * cymbals stays a two-lane beat — matching beats.js's convention and keeping
     * the strip from carrying an all-rest brass row.
     */
    fun toBeat(id: String?): Beat {
        val uniform = groups.all { it == groups[0] }
        val out = LinkedHashMap<LaneId, List<Stroke?>>()
        out[LaneId.DAYAN] = pattern(LaneId.DAYAN)
        out[LaneId.BAYAN] = pattern(LaneId.BAYAN)
        if (hasKartal) out[LaneId.KARTAL] = pattern(LaneId.KARTAL)

        return Beat(
            id = id,
            name = com.kirtan.companion.data.Names.clampName(name.trim())
                .ifEmpty { Strings.get(R.string.editor_default_name) },
            note = "Custom",
            bpm = bpm,
            steps = steps,
            beatsPerBar = beatsPerBar,
            cellsPerGroup = if (uniform && groups.isNotEmpty()) groups[0] else cpq,
            groups = groups,
            description = null,
            lanePatterns = out,
            swing = swing,
        )
    }

    /** The draft as a previewable beat, including an empty cymbal row. */
    fun toPreviewBeat(): Beat {
        val out = LinkedHashMap<LaneId, List<Stroke?>>()
        for (lane in LaneId.ORDERED) out[lane] = pattern(lane)
        return Beat(
            id = "preview",
            name = name.trim().ifEmpty { Strings.get(R.string.editor_preview) },
            note = "Custom",
            bpm = bpm,
            steps = steps,
            beatsPerBar = beatsPerBar,
            cellsPerGroup = cpq,
            groups = groups,
            description = null,
            lanePatterns = out,
            swing = swing,
        )
    }

    companion object {
        const val UNDO_LIMIT = 60
        const val ZOOM_CELLS = 8
        /** Cells-per-beat choices for a simple meter; the lever greys any that would pass MAX_STEPS cells. */
        val SIMPLE_RESOLUTIONS = listOf(1, 2, 3, 4, 6, 8)
        /** Multipliers offered for an odd meter. */
        val MULTIPLIERS = listOf(1, 2, 3)

        /** A blank draft, or one seeded from an existing beat. */
        fun from(beat: Beat?): EditorDraft {
            val groups = seedGroups(beat)
            val cpq = seedCpq(beat)
            val steps = groups.sum()
            return EditorDraft(
                groups = groups,
                meter = meterFor(groups, cpq),
                lanes = mapOf(
                    LaneId.DAYAN to (beat?.pattern(LaneId.DAYAN)?.toList() ?: List(steps) { null }),
                    LaneId.BAYAN to (beat?.pattern(LaneId.BAYAN)?.toList() ?: List(steps) { null }),
                    LaneId.KARTAL to (beat?.pattern(LaneId.KARTAL)?.toList() ?: List(steps) { null }),
                ),
                bpm = beat?.bpm ?: 90,
                name = beat?.name ?: "",
                cursor = 0,
                swing = beat?.swing ?: com.kirtan.companion.data.Swing.STRAIGHT,
            )
        }
    }
}
