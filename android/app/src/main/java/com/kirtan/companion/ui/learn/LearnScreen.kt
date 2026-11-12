package com.kirtan.companion.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.kirtan.companion.data.HillClimb
import com.kirtan.companion.data.tempoRange
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PillChip
import com.kirtan.companion.ui.components.SectionLabel
import kotlinx.coroutines.delay
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
 * Each stroke drops down its hand's column (bayan and dayan, or the karatalas'
 * one), drawn as its stroke's shape with its bol beside it, headed by its
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
    val libraryState by library.state.collectAsState()
    // Tapping the name opens Home's quick-pick: built-ins, your beats, your lists.
    var pickerOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (pickerOpen) {
        com.kirtan.companion.ui.home.BeatPickerSheet(
            library = library,
            activeCategoryId = libraryState.activeCategoryId,
            selectedBeatId = beat?.id,
            onDismiss = { pickerOpen = false },
            onPick = { picked, fromCategory ->
                library.selectBeat(picked, fromCategory)
                transport.loadBeat(picked)
                pickerOpen = false
            },
        )
    }

    // ── Hill climb ──────────────────────────────────────────────────────────
    // On/off is per session (a lesson never speeds up unasked); the step and
    // interval are remembered, so turning it back on offers the last choice.
    val climbStep = state.settings.hillClimbStep
    val climbEvery = state.settings.hillClimbSeconds
    var climbOn by rememberSaveable { mutableStateOf(false) }
    var climbLeft by rememberSaveable { mutableIntStateOf(climbEvery) }
    var climbSheet by rememberSaveable { mutableStateOf(false) }
    val ceiling = tempoRange(state.brahmaloka).last
    val climbTop = HillClimb.atTop(state.bpm, ceiling)
    val liveBpm by rememberUpdatedState(state.bpm)
    val liveCeiling by rememberUpdatedState(ceiling)
    val liveBeatsPerBar by rememberUpdatedState(beat?.beatsPerBar ?: 4.0)
    // The countdown runs on PLAYING time: pausing holds it, and Play carries on.
    LaunchedEffect(climbOn, state.playing, climbStep, climbEvery) {
        if (!climbOn || !state.playing) return@LaunchedEffect
        // Handed over a little early so it can land on the 1 nearest the deadline
        // (HillClimb.leadSeconds); `handed` keeps it to once per step.
        var handed = false
        while (true) {
            delay(1000)
            if (HillClimb.atTop(liveBpm, liveCeiling)) continue
            climbLeft -= 1
            val lead = HillClimb.leadSeconds(liveBpm, liveBeatsPerBar)
            if (!handed && climbLeft <= lead) {
                transport.changeBpmAtBar(HillClimb.next(liveBpm, climbStep, liveCeiling), climbLeft.coerceAtLeast(0).toDouble())
                handed = true
            }
            if (climbLeft <= 0) {
                climbLeft = climbEvery
                handed = false
            }
        }
    }
    if (climbSheet) {
        HillClimbSheet(
            step = climbStep,
            seconds = climbEvery,
            onDismiss = { climbSheet = false },
            onAccept = { step, seconds ->
                transport.setHillClimb(step, seconds)
                climbLeft = seconds
                climbOn = true
                climbSheet = false
            },
        )
    }

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
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.home_choose_beat)) { pickerOpen = true },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = beat.name + " ▾",
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
        val instrument = if (state.settings.learnKartal) LearnInstrument.KARTAL else LearnInstrument.MRDANGA
        InstrumentSwitch(instrument = instrument, onPick = { transport.setLearnKartal(it == LearnInstrument.KARTAL) })

        // Handedness is about which side the drum's heads sit; karatalas have none.
        if (instrument == LearnInstrument.MRDANGA) {
            SecondaryButton(
                label = if (leftHanded) stringResource(R.string.learn_left_handed) else stringResource(R.string.learn_right_handed),
                onClick = { transport.setLearnLeftHanded(!leftHanded) },
                minHeight = 38.dp,
            )
        }

        HighwayCanvas(
            beat = beat,
            bpm = state.bpm,
            playing = state.playing,
            instrument = instrument,
            emptyMessage = if (Highway.hasPart(beat, instrument)) null else stringResource(R.string.learn_no_kartal),
            columns = Highway.columnsFor(leftHanded, instrument),
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
            // Off: opens the choice (last one pre-selected). On: one tap turns it off.
            HairlineIconButton(
                icon = KcIcons.Stairs,
                contentDescription = if (climbOn) stringResource(R.string.learn_climb_off) else stringResource(R.string.learn_climb),
                onClick = { if (climbOn) climbOn = false else climbSheet = true },
                filled = climbOn,
            )
            PrimaryButton(
                label = if (state.playing) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                icon = if (state.playing) KcIcons.Pause else KcIcons.Play,
                enabled = state.ready,
                onClick = { transport.togglePlay(beat) },
                modifier = Modifier.width(124.dp),
                minHeight = 48.dp,
            )
        }
        if (climbOn) {
            val every = intervalLabel(climbEvery)
            Text(
                text = when {
                    climbTop -> stringResource(R.string.learn_climb_top, state.bpm)
                    state.playing -> stringResource(R.string.learn_climb_status, climbStep, every, HillClimb.clock(climbLeft))
                    else -> stringResource(R.string.learn_climb_paused, climbStep, every)
                },
                color = PaletteToken.CLAY.color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun intervalLabel(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.learn_climb_sec, seconds)
    else stringResource(R.string.learn_climb_min, seconds / 60)

/** The hill-climb choice: N BPM every X. Accept turns it on; cancel leaves it off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HillClimbSheet(
    step: Int,
    seconds: Int,
    onDismiss: () -> Unit,
    onAccept: (step: Int, seconds: Int) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    var pickedStep by rememberSaveable { mutableIntStateOf(step) }
    var pickedSeconds by rememberSaveable { mutableIntStateOf(seconds) }
    KcSheet(title = stringResource(R.string.learn_climb), onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.learn_climb_desc),
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.sp,
        )
        SectionLabel(stringResource(R.string.learn_climb_step), modifier = Modifier.padding(top = dimens.space3))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.space2), verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
            for (n in HillClimb.STEPS) {
                PillChip(label = stringResource(R.string.learn_climb_bpm, n), selected = n == pickedStep, onClick = { pickedStep = n })
            }
        }
        SectionLabel(stringResource(R.string.learn_climb_every), modifier = Modifier.padding(top = dimens.space3))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.space2), verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
            for (s in HillClimb.INTERVALS_SEC) {
                PillChip(label = intervalLabel(s), selected = s == pickedSeconds, onClick = { pickedSeconds = s })
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = dimens.space4),
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            SecondaryButton(label = stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton(
                label = stringResource(R.string.learn_climb_start),
                icon = KcIcons.Stairs,
                onClick = { onAccept(pickedStep, pickedSeconds) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Mrdanga | Kartals: which part the highway teaches. */
@Composable
private fun InstrumentSwitch(instrument: LearnInstrument, onPick: (LearnInstrument) -> Unit) {
    val desc = stringResource(R.string.learn_instrument)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = desc }
            .background(PaletteToken.HEAD_WORN.color, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in LearnInstrument.entries) {
            val on = option == instrument
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) PaletteToken.HEAD.color else Color.Transparent)
                    .border(if (on) 1.dp else 0.dp, if (on) PaletteToken.RULE.color else Color.Transparent, RoundedCornerShape(10.dp))
                    .selectable(selected = on, role = Role.RadioButton) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (option == LearnInstrument.KARTAL) R.string.learn_kartal else R.string.learn_mrdanga),
                    color = if (on) PaletteToken.SYAHI.color else PaletteToken.SYAHI_SOFT.color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
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
    instrument: LearnInstrument,
    emptyMessage: String?,
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
    val frame = Highway.frame(beat, position, lookahead, instrument)

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
            val laneColor = note.column.colorToken.color
            val landed = note.rel <= 0.0
            val alpha = if (landed) (1f + note.rel.toFloat() / Highway.TRAIL_STEPS.toFloat()).coerceIn(0f, 1f) else 1f
            if (playing && landed && note.rel > -0.6) {
                drawCircle(laneColor.copy(alpha = 0.25f * alpha), radius = noteR * 2f, center = Offset(cx, hitY))
            }
            drawNote(note.stroke, laneColor.copy(alpha = alpha), Offset(cx, y), noteR)
            // Its bol beside it: one column per hand, so the stroke is named here.
            // (Not once it has passed the line: it would run into the hand's label.)
            if (y <= hitY) {
                val bol = measurer.measure(note.bol, TextStyle(color = syahi.copy(alpha = alpha), fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                drawText(bol, topLeft = Offset(cx + noteR + 6.dp.toPx(), y - bol.size.height / 2f))
            }
        }

        // Targets on the hit line, with the hand (lane) underneath.
        columns.forEachIndexed { i, column ->
            val cx = colX(i) + colW / 2
            val laneColor = column.colorToken.color
            val hot = frame.notes.any { it.column == column && it.rel <= 0.0 && it.rel > -0.5 } && playing
            drawCircle(
                color = if (hot) laneColor else laneColor.copy(alpha = 0.35f),
                radius = noteR + 4.dp.toPx(),
                center = Offset(cx, hitY),
                style = DrawStroke(width = if (hot) 4.dp.toPx() else 2.dp.toPx()),
            )
            val label = measurer.measure(
                column.label,
                TextStyle(color = syahi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, size.height - labelH + 4.dp.toPx()))
        }

        // A beat with nothing for this instrument says so, rather than leaving an
        // empty highway that reads as broken.
        if (!playing || emptyMessage != null) {
            val hint = measurer.measure(emptyMessage ?: Strings.get(R.string.learn_press_play), TextStyle(color = soft, fontSize = 12.sp))
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

/** Open = a filled disc, closed = an X: the same language as the beat strip. */
private fun DrawScope.drawNote(stroke: Stroke, color: Color, center: Offset, r: Float) {
    when (stroke) {
        Stroke.OPEN -> drawCircle(color, radius = r, center = center)
        Stroke.CLOSED -> {
            val d = (r - 2.dp.toPx()) * 0.75f
            val w = 4.dp.toPx()
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(color, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), w, cap)
            drawLine(color, Offset(center.x + d, center.y - d), Offset(center.x - d, center.y + d), w, cap)
        }
    }
}
