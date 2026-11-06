package com.kirtan.companion.ui.editor

import com.kirtan.companion.data.Bols
import com.kirtan.companion.data.labelsFromGroups
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlin.math.ceil
import kotlin.math.min

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
    if (cpq == 2 && repeats(listOf(3, 3), groups)) return METERS.first { it.id == "68" }
    if (cpq == 2 && repeats(listOf(2, 2, 3), groups)) return METERS.first { it.id == "78" }
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

/** Which lanes the pads write: both mridanga heads, or one isolated lane. */
enum class EditLane { BOTH, DAYAN, BAYAN, KARTAL }

/**
 * One pad. [write] names exactly the lanes it touches, which is what lets a
 * combo pad write both heads in one gesture and an isolated pad write one, with
 * no branch at the call site. A null stroke in [write] means "this pad sets that
 * lane to a rest", which is distinct from the pad not touching the lane at all.
 */
data class PadSpec(
    val key: String,
    val label: String,
    val write: Map<LaneId, Stroke?>,
    val rest: Boolean = false,
)

/**
 * The pad palette per edit mode, transcribed from `PAD_SETS` in BeatEditor.jsx.
 *
 * The karatalas have NO "both" combo pads: a cymbal is not struck together with a
 * drum head under one bol, so they are authored by isolating the lane — which is
 * also the only way to draw them, since the both-hands pads write the drum only.
 */
fun padSetFor(lane: EditLane): List<PadSpec> = when (lane) {
    EditLane.BOTH -> listOf(
        PadSpec("ta", Bols.forStroke("dayan", "O") ?: "Ta", mapOf(LaneId.DAYAN to Stroke.OPEN, LaneId.BAYAN to null)),
        PadSpec("te", Bols.forStroke("dayan", "X") ?: "Te", mapOf(LaneId.DAYAN to Stroke.CLOSED, LaneId.BAYAN to null)),
        PadSpec("ge", Bols.forStroke("bayan", "O") ?: "Ge", mapOf(LaneId.DAYAN to null, LaneId.BAYAN to Stroke.OPEN)),
        PadSpec("khe", Bols.forStroke("bayan", "X") ?: "Khe", mapOf(LaneId.DAYAN to null, LaneId.BAYAN to Stroke.CLOSED)),
        PadSpec("da", Bols.combo("O", "O") ?: "Da", mapOf(LaneId.DAYAN to Stroke.OPEN, LaneId.BAYAN to Stroke.OPEN)),
        PadSpec("gi", Bols.combo("X", "O") ?: "Gi", mapOf(LaneId.DAYAN to Stroke.CLOSED, LaneId.BAYAN to Stroke.OPEN)),
        PadSpec("tk", Bols.combo("O", "X") ?: "Ta·Khe", mapOf(LaneId.DAYAN to Stroke.OPEN, LaneId.BAYAN to Stroke.CLOSED)),
        PadSpec("tek", Bols.combo("X", "X") ?: "Te·Khe", mapOf(LaneId.DAYAN to Stroke.CLOSED, LaneId.BAYAN to Stroke.CLOSED)),
        PadSpec("rest", "Rest", mapOf(LaneId.DAYAN to null, LaneId.BAYAN to null), rest = true),
    )

    EditLane.DAYAN -> listOf(
        PadSpec("ta", Bols.forStroke("dayan", "O") ?: "Ta", mapOf(LaneId.DAYAN to Stroke.OPEN)),
        PadSpec("te", Bols.forStroke("dayan", "X") ?: "Te", mapOf(LaneId.DAYAN to Stroke.CLOSED)),
        PadSpec("rd", "Rest", mapOf(LaneId.DAYAN to null), rest = true),
    )

    EditLane.BAYAN -> listOf(
        PadSpec("ge", Bols.forStroke("bayan", "O") ?: "Ge", mapOf(LaneId.BAYAN to Stroke.OPEN)),
        PadSpec("khe", Bols.forStroke("bayan", "X") ?: "Khe", mapOf(LaneId.BAYAN to Stroke.CLOSED)),
        PadSpec("rb", "Rest", mapOf(LaneId.BAYAN to null), rest = true),
    )

    EditLane.KARTAL -> listOf(
        PadSpec("kch", Bols.forStroke("kartal", "O") ?: "Ching", mapOf(LaneId.KARTAL to Stroke.OPEN)),
        PadSpec("kdm", Bols.forStroke("kartal", "X") ?: "Chip", mapOf(LaneId.KARTAL to Stroke.CLOSED)),
        PadSpec("rk", "Rest", mapOf(LaneId.KARTAL to null), rest = true),
    )
}

/** What an undo entry restores: the three patterns and where the cursor was. */
data class DraftSnapshot(val lanes: Map<LaneId, List<Stroke?>>, val cursor: Int)

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
    val editLane: EditLane = EditLane.BOTH,
    val undo: List<DraftSnapshot> = emptyList(),
) {
    val cpq: Int get() = meter.cpq
    val steps: Int get() = groups.sum()
    val beatsPerBar: Double get() = steps.toDouble() / cpq
    val labels: List<String> get() = labelsFromGroups(groups)

    /** The zoom shows at most 8 cells at once, matching the main strip's width. */
    val zoomCells: Int get() = min(steps, 8)
    val pageCount: Int get() = ceil(steps.toDouble() / zoomCells).toInt()
    val page: Int get() = if (zoomCells > 0) cursor / zoomCells else 0
    val windowStart: Int get() = page * zoomCells
    val windowEnd: Int get() = min(windowStart + zoomCells, steps)

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
        undo = (undo + DraftSnapshot(lanes, cursor)).takeLast(UNDO_LIMIT)
    )

    fun undoLast(): EditorDraft {
        val prev = undo.lastOrNull() ?: return this
        return copy(lanes = prev.lanes, cursor = prev.cursor, undo = undo.dropLast(1))
    }

    // ── Meter and length ───────────────────────────────────────────────────

    /**
     * Append one unit of the current meter, padding every lane with rests.
     * Resets undo, as the web editor does: an undo entry records patterns of the
     * OLD length, so restoring one after a resize would write past the end.
     */
    fun addGroup(): EditorDraft {
        val pad = List(meter.unit.sum()) { null as Stroke? }
        return copy(
            groups = groups + meter.unit,
            lanes = lanes.mapValues { (_, cells) -> cells + pad },
            undo = emptyList(),
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
        )
    }

    /**
     * Change the feel. CLEARS THE GRID, and the sheet says so: a meter change
     * redefines what every existing cell means, so keeping the old marks would
     * silently relabel the user's rhythm rather than preserve it.
     */
    fun selectMeter(preset: MeterPreset): EditorDraft = copy(
        meter = preset,
        groups = preset.bar.toList(),
        lanes = LaneId.ORDERED.associateWith { List(preset.bar.sum()) { null as Stroke? } },
        cursor = 0,
        undo = emptyList(),
    )

    // ── Cursor and editing ─────────────────────────────────────────────────

    fun setCursor(index: Int): EditorDraft =
        copy(cursor = index.coerceIn(0, (steps - 1).coerceAtLeast(0)))

    /** Scrub the overview: a fraction of the whole beat becomes a cell index. */
    fun scrubToFraction(fraction: Float): EditorDraft =
        setCursor(((fraction.coerceIn(0f, 1f) * steps).toInt()).coerceAtMost(steps - 1))

    fun setPagePage(delta: Int): EditorDraft {
        val target = (windowStart + delta * zoomCells).coerceIn(0, (steps - 1).coerceAtLeast(0))
        return setCursor(target)
    }

    /**
     * Write one pad at the cursor and advance. Returns the lanes the pad touched
     * so the caller can sound exactly those samples — the pad's [PadSpec.write]
     * is the single source of truth for both.
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
            lanes = updated,
            cursor = (withHistory.cursor + 1).mod(steps.coerceAtLeast(1)),
        )
    }

    fun clearGrid(): EditorDraft = withUndo().copy(
        lanes = lanes.mapValues { List(steps) { null as Stroke? } },
        cursor = 0,
    )

    fun setName(value: String): EditorDraft = copy(name = value)

    fun setBpm(value: Int): EditorDraft = copy(bpm = value)

    fun setEditLane(lane: EditLane): EditorDraft = copy(editLane = lane)

    /** Tapping a lane label isolates it; tapping again returns to both hands. */
    fun toggleEditLane(lane: LaneId): EditorDraft {
        val target = when (lane) {
            LaneId.DAYAN -> EditLane.DAYAN
            LaneId.BAYAN -> EditLane.BAYAN
            LaneId.KARTAL -> EditLane.KARTAL
        }
        return copy(editLane = if (editLane == target) EditLane.BOTH else target)
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
            name = name.trim().ifEmpty { "Custom Beat" },
            note = "Custom",
            bpm = bpm,
            steps = steps,
            beatsPerBar = beatsPerBar,
            cellsPerGroup = if (uniform && groups.isNotEmpty()) groups[0] else cpq,
            groups = groups,
            description = null,
            lanePatterns = out,
        )
    }

    /** The draft as a previewable beat, including an empty cymbal row. */
    fun toPreviewBeat(): Beat {
        val out = LinkedHashMap<LaneId, List<Stroke?>>()
        for (lane in LaneId.ORDERED) out[lane] = pattern(lane)
        return Beat(
            id = "preview",
            name = name.trim().ifEmpty { "Preview" },
            note = "Custom",
            bpm = bpm,
            steps = steps,
            beatsPerBar = beatsPerBar,
            cellsPerGroup = cpq,
            groups = groups,
            description = null,
            lanePatterns = out,
        )
    }

    companion object {
        const val UNDO_LIMIT = 60

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
            )
        }
    }
}
