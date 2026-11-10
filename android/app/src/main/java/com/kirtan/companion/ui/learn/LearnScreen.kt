package com.kirtan.companion.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.colorToken
import com.kirtan.companion.data.cpqFor
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.library.LibraryViewModel
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel
import androidx.compose.ui.res.stringResource
import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * The Learn tab: a "falling notes" view of the loaded beat.
 *
 * Each stroke drops down its own column (one per hand and stroke, headed by its
 * bol) and lands on the hit line at the instant the drum sounds it, so a learner
 * can watch what is coming and play along on a real mridanga. Watch-only by
 * design: there is no scoring, because the point is to follow the drum, not to
 * tap a phone.
 *
 * It drives the SAME transport as Home — the beat, tempo and play state are
 * shared — so a learner can pick a beat on Home, slow it down here, and go back
 * without anything resetting. The engine is only read (getPhase) and commanded
 * through TransportViewModel, never touched directly.
 */
@Composable
internal fun LearnScreen(
    transport: TransportViewModel,
    library: LibraryViewModel,
    beat: Beat?,
) {
    val dimens = KirtanTheme.dimens
    val state by transport.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = dimens.screenMaxWidth)
            .padding(horizontal = dimens.screenPaddingSide, vertical = dimens.space3),
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        if (beat == null) {
            Text(
                text = stringResource(R.string.learn_pick_beat),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            HairlineIconButton(
                icon = KcIcons.Back,
                contentDescription = stringResource(R.string.common_prev_beat),
                onClick = { library.cycleBeat(-1) },
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = beat.name,
                    color = PaletteToken.SYAHI.color,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.learn_subtitle),
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 12.sp,
                )
            }
            HairlineIconButton(
                icon = KcIcons.ChevronRight,
                contentDescription = stringResource(R.string.common_next_beat),
                onClick = { library.cycleBeat(1) },
            )
        }

        val leftHanded = state.settings.learnLeftHanded
        SecondaryButton(
            label = if (leftHanded) stringResource(R.string.learn_left_handed) else stringResource(R.string.learn_right_handed),
            onClick = { transport.setLearnLeftHanded(!leftHanded) },
            minHeight = 38.dp,
        )

        HighwayCanvas(
            beat = beat,
            bpm = state.bpm,
            playing = state.playing,
            columns = Highway.columnsFor(leftHanded),
            getPhase = { transport.getPhase() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            // Learning wants SLOWER more than anything, so the steps are 5 BPM —
            // a 1-BPM nudge is a performer's adjustment, not a learner's.
            HairlineIconButton(
                icon = KcIcons.Back,
                contentDescription = stringResource(R.string.common_slower),
                onClick = { transport.nudgeBpm(-5) },
            )
            Text(
                text = "${state.bpm} BPM",
                color = PaletteToken.SYAHI.color,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = dimens.space1),
            )
            HairlineIconButton(
                icon = KcIcons.ChevronRight,
                contentDescription = stringResource(R.string.common_faster),
                onClick = { transport.nudgeBpm(5) },
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                label = if (state.playing) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                icon = if (state.playing) KcIcons.Pause else KcIcons.Play,
                enabled = state.ready,
                onClick = { transport.togglePlay(beat) },
                modifier = Modifier.width(148.dp),
                minHeight = 48.dp,
            )
        }
    }
}

/**
 * The highway itself. Redraws every display frame while playing, reading the
 * engine's phase inside withFrameNanos — the same technique as the strip's
 * playhead — and holds the first bar at rest while stopped, so the learner can
 * read what is coming before pressing Play.
 */
@Composable
private fun HighwayCanvas(
    beat: Beat,
    bpm: Int,
    playing: Boolean,
    columns: List<HighwayColumn>,
    getPhase: () -> Double,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(playing, beat.steps) {
        if (!playing) {
            position = 0.0
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { position = getPhase() * beat.steps }
        }
    }

    val measurer = rememberTextMeasurer()
    val lookahead = Highway.lookaheadSteps(bpm, cpqFor(beat))
    val frame = Highway.frame(beat, position, lookahead)

    val head = PaletteToken.HEAD_WORN.color
    val rule = PaletteToken.RULE.color
    val syahi = PaletteToken.SYAHI.color
    val soft = PaletteToken.SYAHI_SOFT.color

    Canvas(modifier = modifier) {
        val gap = 6.dp.toPx()
        val colW = (size.width - gap * (columns.size - 1)) / columns.size
        val labelH = 34.dp.toPx()
        val hitY = size.height - labelH - 28.dp.toPx()
        val pxPerStep = hitY / lookahead.toFloat()
        val noteR = (minOf(colW, pxPerStep) * 0.36f).coerceIn(7.dp.toPx(), 20.dp.toPx())

        fun colX(i: Int) = i * (colW + gap)
        fun yFor(rel: Double) = hitY - (rel * pxPerStep).toFloat()

        // Column tracks.
        columns.forEachIndexed { i, _ ->
            drawRoundRect(
                color = head,
                topLeft = Offset(colX(i), 0f),
                size = Size(colW, size.height - labelH),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
        }

        // Beat lines: a bolder one on sam, the first beat of the bar.
        for (line in frame.lines) {
            val y = yFor(line.rel)
            if (y < 0 || y > hitY) continue
            drawLine(
                color = if (line.sam) soft.copy(alpha = 0.55f) else rule,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (line.sam) 2.5.dp.toPx() else 1.dp.toPx(),
            )
        }

        // The hit line.
        drawLine(
            color = syahi,
            start = Offset(0f, hitY),
            end = Offset(size.width, hitY),
            strokeWidth = 3.dp.toPx(),
        )

        // Notes. One that has just landed glows its column's target.
        for (note in frame.notes) {
            val i = columns.indexOf(note.column)
            val cx = colX(i) + colW / 2
            val y = yFor(note.rel)
            if (y < -noteR || y > hitY + noteR * 3) continue
            val laneColor = note.column.lane.colorToken.color
            val landed = note.rel <= 0.0
            val alpha = if (landed) (1f + note.rel.toFloat() / Highway.TRAIL_STEPS.toFloat()).coerceIn(0f, 1f) else 1f
            if (playing && landed && note.rel > -0.6) {
                drawCircle(laneColor.copy(alpha = 0.25f * alpha), radius = noteR * 2f, center = Offset(cx, hitY))
            }
            drawNote(note.column.stroke, laneColor.copy(alpha = alpha), Offset(cx, y), noteR)
        }

        // Targets on the hit line, with the column's bol underneath.
        columns.forEachIndexed { i, column ->
            val cx = colX(i) + colW / 2
            val laneColor = column.lane.colorToken.color
            val hot = frame.notes.any { it.column == column && it.rel <= 0.0 && it.rel > -0.5 } && playing
            drawCircle(
                color = if (hot) laneColor else laneColor.copy(alpha = 0.35f),
                radius = noteR + 4.dp.toPx(),
                center = Offset(cx, hitY),
                style = DrawStroke(width = if (hot) 4.dp.toPx() else 2.dp.toPx()),
            )
            val label = measurer.measure(
                column.bol,
                TextStyle(color = syahi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, size.height - labelH + 4.dp.toPx()))
            val sub = measurer.measure(
                "${column.lane.label} · " + Strings.get(if (column.stroke == Stroke.OPEN) R.string.learn_open else R.string.learn_closed),
                TextStyle(color = soft, fontSize = 9.sp),
            )
            drawText(sub, topLeft = Offset(cx - sub.size.width / 2f, size.height - sub.size.height.toFloat()))
        }

        if (!playing) {
            val hint = measurer.measure(Strings.get(R.string.learn_press_play), TextStyle(color = soft, fontSize = 12.sp))
            val pad = 6.dp.toPx()
            val at = Offset(size.width / 2 - hint.size.width / 2f, hitY - pxPerStep / 2 - hint.size.height / 2f)
            drawRoundRect(
                color = PaletteToken.HEAD.color.copy(alpha = 0.92f),
                topLeft = Offset(at.x - pad * 2, at.y - pad),
                size = Size(hint.size.width + pad * 4, hint.size.height + pad * 2),
                cornerRadius = CornerRadius(pad * 2),
            )
            drawText(hint, topLeft = at)
        }
    }
}

/** Open = a filled disc, closed = a ring: the same language as the beat strip. */
private fun DrawScope.drawNote(stroke: Stroke, color: Color, center: Offset, r: Float) {
    when (stroke) {
        Stroke.OPEN -> drawCircle(color, radius = r, center = center)
        Stroke.CLOSED -> drawCircle(color, radius = r - 2.dp.toPx(), center = center, style = DrawStroke(width = 4.dp.toPx()))
    }
}
