package com.kirtan.companion.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.MAX_BPM
import com.kirtan.companion.data.MIN_BPM
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.SUBDIVISION_LABEL
import com.kirtan.companion.data.colorToken
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.engine.EngineEvent
import com.kirtan.companion.engine.KirtanEngine
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.KcSlider
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel
import kotlinx.coroutines.launch

/**
 * The beat editor: overview, zoom and pads in one scrolling region.
 *
 * Ported from `src/BeatEditor.jsx`. The three zones and their relationship are
 * unchanged:
 *
 *  1. OVERVIEW — a mini strip of the WHOLE beat with a frame showing which page
 *     the zoom is on; drag it to scrub the cursor. Doubles as the live visualiser
 *     while previewing.
 *  2. ZOOM — one page of at most eight cells, magnified, lanes stacked. The cursor
 *     is a COLUMN; tapping a cell moves it. A lane label above each row doubles as
 *     an "edit only this lane" toggle, which is how the cymbal row is authored,
 *     since the both-hands pads write the drum only.
 *  3. PADS — one pad per enterable stroke. Tapping writes the cursor column (or
 *     one lane when isolated), SOUNDS the sample, and advances the cursor.
 *
 * The draft lives in [EditorDraft], pure and immutable, so the undo stack is a
 * list of previous values and every rule (meter math, cursor wrap, kartal
 * persistence) is unit-tested without a compositor. This file owns presentation
 * and the engine calls only.
 *
 * The header, Save and the nav stay pinned and the middle scrolls as one region,
 * exactly as the web layout does: a squashed pad grid is unusable, so on a short
 * viewport the sections scroll rather than shrink, and pinning the nav keeps the
 * pill at the same y as every other screen.
 */
@Composable
internal fun BeatEditorScreen(
    engine: KirtanEngine,
    transport: TransportViewModel,
    initialBeat: Beat?,
    onSave: (Beat, (Beat?) -> Unit) -> Unit,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens
    val colors = KirtanTheme.colors
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(EditorDraft.from(initialBeat)) }
    var previewing by remember { mutableStateOf(false) }
    var feelOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(-1) }

    // Follow the engine's step so the zoom lights the sounding column while
    // previewing, and stop the preview whenever the editor leaves composition —
    // unmounting is how a draft is discarded here, and leaving the loop running
    // would keep sounding a beat the user has already walked away from.
    LaunchedEffect(engine) {
        engine.events.collect { event ->
            if (event is EngineEvent.Step) step = event.step
        }
    }
    DisposableEffect(engine) {
        // stopPreview, not engine.stop(): the preview sets the transport's playing
        // mirror, and Home's Play no-ops while that mirror is set. Stopping the
        // engine directly would leave the mirror claiming playback and make the
        // first Play tap on Home look dead.
        onDispose { transport.stopPreview() }
    }

    // Keep the preview in step with the draft while it is running. setBeat and
    // setBpm are synchronous engine calls with no start/stop involved, so there
    // is no binder race here — unlike starting, which goes through startPreview.
    LaunchedEffect(previewing, draft, engine) {
        if (previewing) {
            engine.setBeat(draft.toPreviewBeat())
            engine.setBpm(draft.bpm)
        }
    }

    fun togglePreview() {
        if (previewing) {
            transport.stopPreview()
            previewing = false
        } else {
            // startPreview sequences the controller stop against the engine start;
            // doing both inline here was the race that made preview silent on a
            // real device. See TransportViewModel.startPreview.
            scope.launch { transport.startPreview(draft.toPreviewBeat(), draft.bpm) }
            previewing = true
        }
    }

    fun tapPad(pad: PadSpec) {
        val touched = pad.write
        draft = draft.tapPad(pad)
        // Write-sound-advance: the pad sounds what it wrote, unless the preview
        // loop is already sounding the grid (then the loop says it, once).
        if (!previewing) {
            for ((lane, stroke) in touched) {
                engine.playStroke(lane, stroke == Stroke.OPEN)
            }
        }
    }

    fun handleSave() {
        if (saving) return
        if (previewing) {
            engine.stop()
            previewing = false
        }
        if (!draft.hasAnyHit) {
            saveError = "Add at least one stroke before saving."
            return
        }
        saveError = null
        saving = true
        onSave(draft.toBeat(initialBeat?.id)) { saved ->
            saving = false
            // Stay OPEN on failure: unmounting is how a draft is discarded, so
            // closing on a failed write would throw the user's work away and
            // leave them only an error message about it.
            if (saved != null) onClose()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = dimens.screenMaxWidth)
            .padding(
                start = dimens.screenPaddingSide,
                end = dimens.screenPaddingSide,
                top = dimens.space4,
                bottom = dimens.space4,
            ),
    ) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                HairlineIconButton(
                    icon = KcIcons.Back,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Spacer(Modifier.width(dimens.space2))
            }
            BasicTextField(
                value = draft.name,
                onValueChange = { draft = draft.setName(it) },
                singleLine = true,
                cursorBrush = SolidColor(PaletteToken.CLAY.color),
                textStyle = TextStyle(
                    color = PaletteToken.SYAHI.color,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                decorationBox = { inner ->
                    Box {
                        if (draft.name.isEmpty()) {
                            Text(
                                text = "Name your beat",
                                color = colors.faint,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        // ── Scrolling body ──
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.space4),
        ) {
            OverviewZone(
                draft = draft,
                step = step,
                previewing = previewing,
                getPhase = { engine.getPhase() },
                onScrub = { draft = draft.scrubToFraction(it) },
            )

            ZoomZone(
                draft = draft,
                step = step,
                previewing = previewing,
                onCellTap = { draft = draft.setCursor(it) },
                onPage = { draft = draft.setPagePage(it) },
                onToggleLane = { draft = draft.toggleEditLane(it) },
            )

            PadsZone(draft = draft, onPad = ::tapPad)

            ControlsZone(
                draft = draft,
                previewing = previewing,
                onUndo = { draft = draft.undoLast() },
                onClear = { draft = draft.clearGrid() },
                onPreview = ::togglePreview,
                onFeel = { feelOpen = true },
                onLength = { add -> draft = if (add) draft.addGroup() else draft.removeGroup() },
                onBpm = { draft = draft.setBpm(it) },
            )

            saveError?.let { message ->
                Text(
                    text = message,
                    color = PaletteToken.DANGER.color,
                    fontSize = 13.4.sp,
                )
            }
        }

        // ── Pinned Save ──
        Spacer(Modifier.height(dimens.space3))
        PrimaryButton(
            label = if (saving) "Saving…" else "Save beat",
            enabled = !saving,
            onClick = ::handleSave,
        )
    }

    if (feelOpen) {
        KcSheet(title = "Feel & meter", onDismiss = { feelOpen = false }) {
            Text(
                text = "Changing the feel clears the grid.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
            )
            METERS.forEach { preset ->
                val selected = preset.id == draft.meter.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(dimens.radiusPad))
                        .border(
                            BorderStroke(
                                width = if (selected) 2.dp else dimens.hairline,
                                color = if (selected) PaletteToken.CLAY.color else colors.rule,
                            ),
                            RoundedCornerShape(dimens.radiusPad),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            draft = draft.selectMeter(preset)
                            feelOpen = false
                        }
                        .padding(horizontal = dimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preset.label,
                        color = PaletteToken.SYAHI.color,
                        fontSize = 15.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = preset.sig,
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * Zone 1: the whole beat in miniature with a frame over the zoom's page.
 *
 * The frame is drawn only when there is more than one page, exactly as on the
 * web: with a single page the frame would outline the entire strip and say
 * nothing. Dragging anywhere on the overview scrubs the cursor, which is the
 * fastest way to reach cell 40 of a 48-cell beat.
 */
@Composable
private fun OverviewZone(
    draft: EditorDraft,
    step: Int,
    previewing: Boolean,
    getPhase: () -> Double,
    onScrub: (Float) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(draft.steps) {
                // Drag anywhere on the overview to scrub; onDragStart covers the
                // initial touch so a plain tap lands the cursor too.
                detectDragGestures(
                    onDragStart = { offset ->
                        if (widthPx > 0) onScrub(offset.x / widthPx)
                    },
                    onDrag = { change, _ ->
                        if (widthPx > 0) onScrub(change.position.x / widthPx)
                    },
                )
            }
            .padding(vertical = 6.dp),
    ) {
        BeatStrip(
            beat = draft.toPreviewBeat(),
            step = step,
            playing = previewing,
            getPhase = getPhase,
            mini = true,
        )

        // The frame over the zoom's window, as a fraction of the whole beat —
        // the same percentage offsets the web version uses. Drawn only when
        // there is more than one page: with a single page it would outline the
        // entire strip and say nothing.
        if (draft.pageCount > 1 && draft.steps > 0 && widthPx > 0) {
            val startDp = with(density) {
                (draft.windowStart.toFloat() / draft.steps * widthPx).toDp()
            }
            val widthDp = with(density) {
                ((draft.windowEnd - draft.windowStart).toFloat() / draft.steps * widthPx).toDp()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .padding(start = startDp)
                    .width(widthDp)
                    .border(
                        width = 2.dp,
                        color = PaletteToken.CLAY.color,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .background(PaletteToken.CLAY.color.copy(alpha = 0.08f)),
            )
        }
    }
}

/** Zone 2: one magnified page; the cursor is a column. */
@Composable
private fun ZoomZone(
    draft: EditorDraft,
    step: Int,
    previewing: Boolean,
    onCellTap: (Int) -> Unit,
    onPage: (Int) -> Unit,
    onToggleLane: (LaneId) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val colors = KirtanTheme.colors
    val window = draft.windowStart until draft.windowEnd

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HairlineIconButton(
                icon = KcIcons.Back,
                contentDescription = "Previous page",
                onClick = { onPage(-1) },
                enabled = draft.page > 0,
                size = 36.dp,
            )
            Text(
                text = "${draft.windowStart + 1}–${draft.windowEnd} of ${draft.steps}",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            HairlineIconButton(
                icon = KcIcons.ChevronRight,
                contentDescription = "Next page",
                onClick = { onPage(1) },
                enabled = draft.page < draft.pageCount - 1,
                size = 36.dp,
            )
        }

        // Numbered pulses for this page; subdivisions dimmed, as on the main strip.
        // A `for` loop rather than forEach: `weight` is a RowScope extension and a
        // forEach lambda would drop that receiver.
        Row(modifier = Modifier.fillMaxWidth()) {
            for (index in window) {
                val label = draft.labels.getOrElse(index) { SUBDIVISION_LABEL }
                val isDot = label == SUBDIVISION_LABEL
                Text(
                    text = label,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (isDot) 0.4f else 0.9f),
                    textAlign = TextAlign.Center,
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontWeight = if (isDot) FontWeight.Normal else FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }

        for (lane in LaneId.ORDERED) {
            val isolatedOut = draft.editLane != EditLane.BOTH &&
                draft.editLane != when (lane) {
                    LaneId.DAYAN -> EditLane.DAYAN
                    LaneId.BAYAN -> EditLane.BAYAN
                    LaneId.KARTAL -> EditLane.KARTAL
                }
            val active = draft.editLane == when (lane) {
                LaneId.DAYAN -> EditLane.DAYAN
                LaneId.BAYAN -> EditLane.BAYAN
                LaneId.KARTAL -> EditLane.KARTAL
            }
            val laneColor = lane.colorToken.color
            val cells = draft.pattern(lane)

            Column(
                modifier = Modifier.alpha(if (isolatedOut) 0.4f else 1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = lane.label.uppercase() + if (active) " · editing only" else "",
                    color = laneColor,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(dimens.radiusIconButton))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onToggleLane(lane) },
                        )
                        .padding(vertical = 2.dp),
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    for (index in window) {
                        val value = cells.getOrElse(index) { null }
                        val isCursor = index == draft.cursor
                        val lit = previewing && index == step
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .padding(1.dp)
                                .clip(RoundedCornerShape(dimens.radiusStripCell))
                                .background(
                                    if (lit) PaletteToken.HEAD_SUNKEN.color
                                    else PaletteToken.HEAD.color
                                )
                                .border(
                                    width = if (isCursor) 2.dp else 1.dp,
                                    color = if (isCursor) PaletteToken.CLAY.color else colors.rule,
                                    shape = RoundedCornerShape(dimens.radiusStripCell),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onCellTap(index) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            StrokeDot(value = value, color = laneColor, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

/** Filled circle = open, ring = closed, faint dot = rest — as on the main strip. */
@Composable
internal fun StrokeDot(value: Stroke?, color: Color, size: androidx.compose.ui.unit.Dp) {
    when (value) {
        Stroke.OPEN -> Box(
            Modifier.size(size).background(color, CircleShape)
        )

        Stroke.CLOSED -> Box(
            Modifier.size(size).border(2.5.dp, color, CircleShape)
        )

        null -> Box(
            Modifier.size(4.dp).background(PaletteToken.RULE.color, CircleShape)
        )
    }
}

/** Zone 3: one pad per enterable stroke for the current edit mode. */
@Composable
private fun PadsZone(draft: EditorDraft, onPad: (PadSpec) -> Unit) {
    val dimens = KirtanTheme.dimens
    val pads = padSetFor(draft.editLane)

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        pads.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                for (pad in row) {
                    PadButton(
                        pad = pad,
                        editLane = draft.editLane,
                        onClick = { onPad(pad) },
                        // weight at the CALL site: it is a RowScope extension.
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row's pads the same width as full rows.
                for (i in 0 until 3 - row.size) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PadButton(
    pad: PadSpec,
    editLane: EditLane,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens
    val both = editLane == EditLane.BOTH &&
        pad.write.containsKey(LaneId.DAYAN) && pad.write.containsKey(LaneId.BAYAN)

    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(dimens.radiusPad))
            .background(
                if (pad.rest) PaletteToken.HEAD_SUNKEN.color else PaletteToken.HEAD_WORN.color
            )
            .border(
                BorderStroke(dimens.hairline, KirtanTheme.colors.rule),
                RoundedCornerShape(dimens.radiusPad),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = dimens.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = pad.label,
            color = PaletteToken.SYAHI.color,
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!pad.rest) {
            if (both) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StrokeDot(pad.write[LaneId.DAYAN], PaletteToken.LANE_DAYAN.color, 9.dp)
                    StrokeDot(pad.write[LaneId.BAYAN], PaletteToken.LANE_BAYAN.color, 9.dp)
                }
            } else {
                val lane = pad.write.keys.first()
                StrokeDot(
                    value = pad.write[lane],
                    color = lane.colorToken.color,
                    size = 11.dp,
                )
            }
        }
    }
}

/** Undo / clear / preview, the feel picker, length and tempo. */
@Composable
private fun ControlsZone(
    draft: EditorDraft,
    previewing: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onPreview: () -> Unit,
    onFeel: () -> Unit,
    onLength: (Boolean) -> Unit,
    onBpm: (Int) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val canRemove = draft.groups.size > draft.meter.unit.size

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            SecondaryButton(
                label = "Undo",
                onClick = onUndo,
                enabled = draft.canUndo,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            SecondaryButton(
                label = "Clear",
                onClick = onClear,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            SecondaryButton(
                label = if (previewing) "Stop" else "Preview",
                onClick = onPreview,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            SecondaryButton(
                label = draft.meter.label + " ▾",
                onClick = onFeel,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                HairlineIconButton(
                    icon = KcIcons.Back,
                    contentDescription = "Remove a group",
                    onClick = { onLength(false) },
                    enabled = canRemove,
                    size = 40.dp,
                )
                Text(
                    text = "${draft.steps} cells",
                    color = PaletteToken.SYAHI.color,
                    fontSize = 13.4.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                HairlineIconButton(
                    icon = KcIcons.ChevronRight,
                    contentDescription = "Add a group",
                    onClick = { onLength(true) },
                    size = 40.dp,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Text(
                text = draft.bpm.toString(),
                color = PaletteToken.SYAHI.color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(
                text = "BPM",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            KcSlider(
                value = draft.bpm.toFloat(),
                onValueChange = { onBpm(it.toInt().coerceIn(MIN_BPM, MAX_BPM)) },
                valueRange = MIN_BPM.toFloat()..MAX_BPM.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
