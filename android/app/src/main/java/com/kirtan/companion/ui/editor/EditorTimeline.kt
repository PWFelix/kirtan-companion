package com.kirtan.companion.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.colorToken
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.ui.theme.color
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The editor's TIMELINE view: the beat as a vertical lane, like the Learn
 * highway, read past a fixed line in the MIDDLE. Time runs upward — what is
 * coming sits above the line, what has passed below — so playing scrolls the
 * cells down through it exactly as Learn's notes fall.
 *
 * The centre line IS the cursor. Dragging scrubs (and sounds each cell as it
 * crosses the line); letting go snaps to the nearest cell and puts the cursor
 * there, so the pads below write on the line. Tapping a cell edits it with the
 * same rules as the strip view (EditorDraft.tapCell). Touching it while the
 * preview plays pauses the preview: you have stopped to edit.
 *
 * The bar loops, so the lane never ends: rows past either end are the next or
 * previous cycle, drawn faint, which makes the join between cycles visible.
 * Cells keep one height at every resolution — a 21-cell beat gets taller, not
 * smaller — and heavier rules mark where each beat begins.
 *
 * The strip view (OverviewZone + ZoomZone) stays as an option; see EditorViewPref.
 */

/** The timeline's arithmetic, kept pure so it is testable. Positions are in cells, unwrapped. */
internal object TimelineMath {
    /** The cell index (0 until steps) of unwrapped row [row]. */
    fun wrap(row: Int, steps: Int): Int = if (steps <= 0) 0 else row.mod(steps)

    /** The unwrapped row a point [dyUp] pixels ABOVE the centre line falls in, at [pos]. */
    fun rowAt(pos: Float, dyUp: Float, cellPx: Float): Int = (pos + dyUp / cellPx).roundToInt()

    /** The unwrapped position nearest [from] that shows cell [index] on the line. */
    fun nearest(index: Int, from: Float, steps: Int): Float {
        if (steps <= 0) return 0f
        val cycle = floor((from - index) / steps + 0.5f)
        return index + cycle * steps
    }

    /**
     * The columns left to right, as on the Learn highway: a right-handed player's
     * bayan (left hand) on the left and dayan on the right; [leftHanded] mirrors the
     * drum. Kartal has no side, so it stays on the far right either way.
     */
    fun columns(leftHanded: Boolean): List<LaneId> {
        val drum = if (leftHanded) listOf(LaneId.DAYAN, LaneId.BAYAN) else listOf(LaneId.BAYAN, LaneId.DAYAN)
        return drum + LaneId.ORDERED.filter { it !in drum }
    }

    /** Which loop [row] belongs to, counting the one on the line as 0. */
    fun cycleOf(row: Int, steps: Int): Int = if (steps <= 0) 0 else Math.floorDiv(row, steps)
}

private val CELL_HEIGHT = 44.dp
private val GUTTER = 34.dp
private val TIMELINE_HEIGHT = 242.dp // five and a half cells: the pads stay in reach below

@Composable
internal fun TimelineZone(
    draft: EditorDraft,
    previewing: Boolean,
    getPhase: () -> Double,
    /** Shared with Learn's handedness setting: flipping it here flips it there. */
    leftHanded: Boolean,
    onFlip: () -> Unit,
    onCellTap: (LaneId, Int) -> Unit,
    onLaneTap: (LaneId) -> Unit,
    onCursor: (Int) -> Unit,
    onPause: () -> Unit,
    onScrubSound: (Int) -> Unit,
) {
    val steps = draft.steps
    val lanes = TimelineMath.columns(leftHanded)
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(draft.cursor.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val latestDraft by rememberUpdatedState(draft)
    val latestPreviewing by rememberUpdatedState(previewing)
    val measurer = rememberTextMeasurer()

    // At rest the line follows the cursor (a pad advancing it, a tap, an undo),
    // gliding the short way round the loop.
    LaunchedEffect(draft.cursor, steps, previewing, dragging) {
        if (!previewing && !dragging) {
            pos.animateTo(TimelineMath.nearest(draft.cursor, pos.value, steps), tween(160))
        }
    }
    // Playing: the lane runs under the line at the transport's own phase.
    LaunchedEffect(previewing, steps) {
        if (!previewing || steps <= 0) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (!dragging) pos.snapTo((getPhase() * steps).toFloat())
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // Column heads; tapping one moves to that row on the line, never edits.
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // ⇄ mirrors the drum, as Learn's handedness button does (the same setting).
            val flipLabel = androidx.compose.ui.res.stringResource(
                if (leftHanded) com.kirtan.companion.R.string.learn_left_handed else com.kirtan.companion.R.string.learn_right_handed,
            )
            Box(
                Modifier
                    .width(GUTTER)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = flipLabel, onClick = onFlip)
                    .then(androidx.compose.ui.Modifier.semantics { contentDescription = flipLabel }),
                contentAlignment = Alignment.Center,
            ) {
                Text("⇄", color = PaletteToken.CLAY.color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            for (lane in lanes) {
                val on = draft.cursorLane == lane
                Text(
                    text = lane.label.uppercase(),
                    color = lane.colorToken.color,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.8.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onLaneTap(lane) }
                        .padding(vertical = 6.dp),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TIMELINE_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(lanes) {
                    detectTapGestures { at ->
                        val d = latestDraft
                        val gutter = GUTTER.toPx()
                        if (at.x < gutter || d.steps <= 0) return@detectTapGestures
                        val col = ((at.x - gutter) / ((size.width - gutter) / lanes.size)).toInt().coerceIn(0, lanes.size - 1)
                        val row = TimelineMath.rowAt(pos.value, size.height / 2f - at.y, CELL_HEIGHT.toPx())
                        if (latestPreviewing) onPause()
                        onCellTap(lanes[col], TimelineMath.wrap(row, d.steps))
                    }
                }
                .pointerInput(Unit) {
                    var lastCell = Int.MIN_VALUE
                    var lastSoundAt = 0L
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            if (latestPreviewing) onPause()
                            lastCell = pos.value.roundToInt()
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            val d = latestDraft
                            // Content follows the finger: dragging down brings later cells to the line.
                            val next = pos.value + dy / CELL_HEIGHT.toPx()
                            scope.launch { pos.snapTo(next) }
                            val cell = next.roundToInt()
                            val now = System.nanoTime()
                            if (cell != lastCell) {
                                lastCell = cell
                                // Sound what crosses the line, but not a machine-gun on a fast fling.
                                if (now - lastSoundAt > 45_000_000L) {
                                    lastSoundAt = now
                                    onScrubSound(TimelineMath.wrap(cell, d.steps))
                                }
                            }
                        },
                        onDragEnd = {
                            val d = latestDraft
                            val snapped = pos.value.roundToInt()
                            scope.launch { pos.animateTo(snapped.toFloat(), tween(120)) }
                            onCursor(TimelineMath.wrap(snapped, d.steps))
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            drawTimeline(draft, pos.value, lanes, measurer)
        }
    }
}

private fun DrawScope.drawTimeline(
    draft: EditorDraft,
    pos: Float,
    lanes: List<LaneId>,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val steps = draft.steps
    drawRect(PaletteToken.HEAD_WORN.color)
    if (steps <= 0) return
    val cell = CELL_HEIGHT.toPx()
    val gutter = GUTTER.toPx()
    val colW = (size.width - gutter) / lanes.size
    val centerY = size.height / 2f
    val half = size.height / 2f / cell
    val onLine = pos.roundToInt()
    val lineCycle = TimelineMath.cycleOf(onLine, steps)
    val groupStarts = draft.groups.runningFold(0) { a, g -> a + g }.toSet()
    val selection = draft.selection
    val rule = PaletteToken.RULE.color

    // The band the line sits in: the cell the pads will write to.
    drawRect(
        PaletteToken.CLAY.color.copy(alpha = 0.10f),
        topLeft = Offset(0f, centerY - cell / 2f),
        size = Size(size.width, cell),
    )

    for (row in floor(pos - half - 1).toInt()..ceil(pos + half + 1).toInt()) {
        val idx = TimelineMath.wrap(row, steps)
        // Time runs UP: a later row sits higher.
        val y = centerY - (row - pos) * cell
        val faint = TimelineMath.cycleOf(row, steps) != lineCycle
        val alpha = if (faint) 0.35f else 1f

        if (selection != null && !faint && idx in selection) {
            drawRect(PaletteToken.CLAY.color.copy(alpha = 0.18f), Offset(gutter, y - cell / 2f), Size(size.width - gutter, cell))
        }
        // A beat begins at a cell's lower edge: a heavier rule there, a hairline between subdivisions.
        val beatStart = idx in groupStarts
        drawLine(
            color = if (beatStart) PaletteToken.SYAHI_SOFT.color.copy(alpha = 0.55f * alpha) else rule.copy(alpha = 0.6f * alpha),
            start = Offset(if (beatStart) 0f else gutter, y + cell / 2f),
            end = Offset(size.width, y + cell / 2f),
            strokeWidth = if (beatStart) 2.dp.toPx() else 1f,
        )
        val label = draft.labels.getOrNull(idx).orEmpty()
        if (label.isNotEmpty()) {
            val layout = measurer.measure(
                label,
                TextStyle(
                    color = PaletteToken.SYAHI_SOFT.color.copy(alpha = alpha),
                    fontSize = if (beatStart) 13.sp else 11.sp,
                    fontWeight = if (beatStart) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            drawText(layout, topLeft = Offset((gutter - layout.size.width) / 2f, y - layout.size.height / 2f))
        }
        lanes.forEachIndexed { i, lane ->
            val cx = gutter + colW * i + colW / 2f
            val color = lane.colorToken.color.copy(alpha = alpha)
            when (draft.pattern(lane).getOrNull(idx)) {
                Stroke.OPEN -> drawCircle(color, radius = cell * 0.26f, center = Offset(cx, y))
                Stroke.CLOSED -> {
                    val r = cell * 0.2f
                    val w = 2.6.dp.toPx()
                    drawLine(color, Offset(cx - r, y - r), Offset(cx + r, y + r), w)
                    drawLine(color, Offset(cx - r, y + r), Offset(cx + r, y - r), w)
                }
                null -> drawCircle(rule.copy(alpha = alpha), radius = 2.dp.toPx(), center = Offset(cx, y))
            }
            if (!faint && idx == draft.cursor && lane == draft.cursorLane && abs(row - pos) < 0.5f) {
                drawRoundRect(
                    PaletteToken.CLAY.color,
                    topLeft = Offset(gutter + colW * i + 4.dp.toPx(), y - cell / 2f + 3.dp.toPx()),
                    size = Size(colW - 8.dp.toPx(), cell - 6.dp.toPx()),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = DrawStroke(width = 2.dp.toPx()),
                )
            }
        }
    }
    // The line itself, across everything.
    drawLine(PaletteToken.CLAY.color, Offset(0f, centerY + cell / 2f), Offset(size.width, centerY + cell / 2f), 2.dp.toPx())
}

/** Which editor view this device last used. The timeline is the default; the strip is the fallback. */
internal class EditorViewPref(context: android.content.Context) {
    private val prefs = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
    var timeline: Boolean
        get() = prefs.getString(KEY, TIMELINE) != STRIP
        set(value) { prefs.edit().putString(KEY, if (value) TIMELINE else STRIP).apply() }

    companion object {
        const val FILE = "kirtan_editor_view"
        const val KEY = "view"
        const val TIMELINE = "timeline"
        const val STRIP = "strip"
    }
}

/** Timeline | Strip, above the grid. */
@Composable
internal fun EditorViewSwitch(timeline: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        com.kirtan.companion.ui.components.PillChip(
            androidx.compose.ui.res.stringResource(com.kirtan.companion.R.string.editor_view_timeline), timeline,
            onClick = { onChange(true) },
        )
        com.kirtan.companion.ui.components.PillChip(
            androidx.compose.ui.res.stringResource(com.kirtan.companion.R.string.editor_view_strip), !timeline,
            onClick = { onChange(false) },
        )
    }
}
