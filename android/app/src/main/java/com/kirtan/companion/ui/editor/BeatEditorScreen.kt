package com.kirtan.companion.ui.editor

import com.kirtan.companion.ui.components.PillChip
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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * The beat editor: overview, zoom and pads in one scrolling region.
 *
 * Ported from `src/BeatEditor.jsx`. The three zones and their relationship are
 * unchanged:
 *
 *  1. OVERVIEW — a mini strip of the WHOLE beat with a frame showing which page
 *     the zoom is on; drag it to scrub the cursor. Doubles as the live visualiser
 *     while previewing.
 *  2. ZOOM — one page of at most eight cells, magnified, lanes stacked. Editing
 *     is CELL-DRIVEN: the cursor is one cell (a row and a step); tapping any cell
 *     selects it and swaps the pads to that row's strokes.
 *     write (a drum row: the mrdanga; the kartal row: the karatalas). The other
 *     instrument's rows dim, and the pads carry a heading saying how to switch.
 *  3. PADS — one pad per enterable stroke of that instrument. Tapping writes the
 *     cursor column, SOUNDS the sample, and advances the cursor.
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
    /** The draft being edited, held by the app so it outlives this screen. */
    session: EditorSession,
    /** Back to [session]'s starting point (Revert / Start over). */
    onDiscard: () -> Unit,
    onSave: (Beat, (Beat?) -> Unit) -> Unit,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Saving will create the user's own copy rather than change [initialBeat]
     * (a built-in edited by anyone but a maintainer saving for everyone). Said
     * on the button, because otherwise Save looks like it silently did nothing.
     */
    savesAsCopy: Boolean = false,
) {
    val dimens = KirtanTheme.dimens
    val colors = KirtanTheme.colors
    val scope = rememberCoroutineScope()

    val initialBeat = session.seed
    // Delegated to the session, so every edit below lands in the app-held draft.
    var draft by session::draft
    // The editor's own Brahmaloka mode: a beat saved above 200 opens in it.
    var brahmaloka by remember { mutableStateOf(draft.bpm > com.kirtan.companion.data.NORMAL_MAX_BPM) }
    // The author's tags. A copy starts with the original's, as a suggestion.
    var tags by session::tags
    var discardOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var notes by session::notes
    var notesOpen by remember { mutableStateOf(false) }
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
        draft = draft.tapPad(pad)
        // Write-sound-advance: the pad sounds what it wrote (rests stay silent),
        // unless the preview loop is already sounding the grid (then the loop
        // says it, once).
        if (!previewing) {
            for ((lane, open) in pad.sounds()) engine.playStroke(lane, open)
        }
    }

    // Your own beat can also be saved as a NEW one, leaving the original as it
    // was — how a variation is made. (Read-only beats always save as a copy.)
    val canSaveAsNew = initialBeat != null && !initialBeat.readOnly && !savesAsCopy

    fun handleSave(asNew: Boolean = false) {
        if (saving) return
        if (previewing) {
            engine.stop()
            previewing = false
        }
        if (!draft.hasAnyHit) {
            saveError = Strings.get(R.string.editor_need_stroke)
            return
        }
        saveError = null
        saving = true
        // A copy goes out WITHOUT the original's id: a community beat is in the
        // library, so passing its id would overwrite it in place.
        val built = draft.toBeat(if (savesAsCopy || asNew) null else initialBeat?.id)
        // A variation left with the original's name would be two identical rows.
        val name = if (asNew && built.name == initialBeat?.name) {
            com.kirtan.companion.data.Names.clampName(Strings.get(R.string.editor_copy_name, built.name))
        } else {
            built.name
        }
        onSave(
            built.copy(
                name = name,
                tags = tags,
                description = notes.trim().ifEmpty { null },
                // Credit for an edited copy of a community beat (and what stops it
                // being re-shared until the music actually differs).
                forkedFrom = if (savesAsCopy) initialBeat?.sourceItemId else null,
            ),
        ) { saved ->
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
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(dimens.space2))
            }
            BasicTextField(
                value = draft.name,
                // Typing stops at the limit rather than failing later on save.
                onValueChange = { draft = draft.setName(com.kirtan.companion.data.Names.clampName(it)) },
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
                                text = stringResource(R.string.editor_name_placeholder),
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
            // Only when there is something to lose. The Editor tab's dot says the
            // same from anywhere else in the app.
            if (session.dirty) {
                Text(
                    text = stringResource(if (initialBeat == null) R.string.editor_start_over else R.string.editor_revert),
                    color = PaletteToken.CLAY.color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(dimens.radiusPad))
                        .clickable { discardOpen = true }
                        .padding(horizontal = dimens.space2, vertical = dimens.space2),
                )
            }
        }

        // ── Scrolling body ──
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.space4),
        ) {
            com.kirtan.companion.ui.components.TagLine(
                label = stringResource(R.string.tags_title),
                tags = tags,
                onEdit = { tagsOpen = true },
            )
            if (tagsOpen) {
                com.kirtan.companion.ui.components.TagPickerSheet(
                    title = stringResource(R.string.tags_title),
                    hint = stringResource(R.string.tags_author_hint),
                    selected = tags,
                    onDismiss = { tagsOpen = false },
                    onDone = { tags = it; tagsOpen = false },
                )
            }
            com.kirtan.companion.ui.components.NotesLine(notes = notes, onEdit = { notesOpen = true })
            if (notesOpen) {
                com.kirtan.companion.ui.components.NotesEditorSheet(
                    initial = notes,
                    onDismiss = { notesOpen = false },
                    onDone = { notes = it; notesOpen = false },
                )
            }
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
                onCellTap = { lane, index ->
                    val before = draft
                    draft = draft.tapCell(lane, index)
                    // A re-tap cycled the cell: sound what landed (a rest stays silent).
                    val landed = draft.pattern(lane).getOrNull(index)
                    if (!previewing && draft.lanes != before.lanes && landed != null) {
                        engine.playStroke(lane, landed == Stroke.OPEN)
                    }
                },
                onLaneTap = { lane -> draft = draft.pickCell(lane, draft.cursor) },
                onPage = { draft = draft.setPagePage(it) },
            )

            ClipZone(
                draft = draft,
                onStart = { draft = draft.startSelect() },
                onCopy = { draft = draft.copySelection() },
                onCancel = { draft = draft.cancelSelect() },
                onPaste = { closeBayan -> draft = draft.paste(closeBayan) },
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
                onResolution = { draft = draft.setResolution(it) },
                onSwing = { draft = draft.setSwing(it) },
                onBpm = {
                    draft = draft.setBpm(it)
                    brahmaloka = com.kirtan.companion.data.brahmalokaFor(it, brahmaloka)
                },
                brahmaloka = brahmaloka,
                onUnlock = {
                    brahmaloka = true
                    transport.celebrateBrahmaloka()
                },
                onLeave = {
                    brahmaloka = false
                    draft = draft.setBpm(draft.bpm.coerceAtMost(com.kirtan.companion.data.NORMAL_MAX_BPM))
                },
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
        if (savesAsCopy) {
            Text(
                text = stringResource(
                    if (initialBeat?.isCommunity == true) R.string.editor_community_copy_note else R.string.editor_builtin_copy_note,
                ),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = dimens.space2),
            )
        }
        PrimaryButton(
            label = when {
                saving -> stringResource(R.string.common_saving)
                savesAsCopy -> stringResource(R.string.editor_save_copy)
                else -> stringResource(R.string.editor_save)
            },
            enabled = !saving,
            onClick = { handleSave() },
        )
        if (canSaveAsNew) {
            Spacer(Modifier.height(dimens.space2))
            SecondaryButton(
                label = stringResource(R.string.editor_save_as_new),
                enabled = !saving,
                onClick = { handleSave(asNew = true) },
            )
        }
    }

    if (discardOpen) {
        val isNew = initialBeat == null
        KcSheet(
            title = stringResource(if (isNew) R.string.editor_start_over else R.string.editor_revert),
            onDismiss = { discardOpen = false },
        ) {
            Text(
                text = if (isNew) stringResource(R.string.editor_start_over_body)
                else stringResource(R.string.editor_revert_body, initialBeat?.name.orEmpty()),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = dimens.space4),
            )
            PrimaryButton(
                label = stringResource(if (isNew) R.string.editor_start_over else R.string.editor_revert),
                onClick = {
                    discardOpen = false
                    if (previewing) { transport.stopPreview(); previewing = false }
                    onDiscard()
                },
            )
            Spacer(Modifier.height(dimens.space2))
            SecondaryButton(label = stringResource(R.string.common_cancel), onClick = { discardOpen = false })
            Spacer(Modifier.height(dimens.space4))
        }
    }

    if (feelOpen) {
        KcSheet(title = stringResource(R.string.editor_feel_meter), onDismiss = { feelOpen = false }) {
            Text(
                text = stringResource(R.string.editor_feel_clears),
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
                        text = meterLabel(preset),
                        color = PaletteToken.SYAHI.color,
                        fontSize = 15.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = meterSig(preset),
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
            // The run being chosen for copying (it can span pages), painted
            // over the strip.
            .drawWithContent {
                drawContent()
                val sel = draft.selection
                if (sel != null && draft.steps > 0) {
                    val cell = size.width / draft.steps
                    drawRoundRect(
                        color = PaletteToken.CLAY.color.copy(alpha = 0.22f),
                        topLeft = androidx.compose.ui.geometry.Offset(sel.first * cell, 0f),
                        size = androidx.compose.ui.geometry.Size((sel.last + 1 - sel.first) * cell, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    )
                }
            }
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

/** Zone 2: one magnified page; the cursor is a cell. */
@Composable
private fun ZoomZone(
    draft: EditorDraft,
    step: Int,
    previewing: Boolean,
    onCellTap: (LaneId, Int) -> Unit,
    /** The row label: moves to that row, same column. Never edits. */
    onLaneTap: (LaneId) -> Unit,
    onPage: (Int) -> Unit,
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
                contentDescription = stringResource(R.string.editor_prev_page),
                onClick = { onPage(-1) },
                enabled = draft.page > 0,
                size = 36.dp,
            )
            Text(
                text = stringResource(R.string.editor_page_range, draft.windowStart + 1, draft.windowEnd, draft.steps),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            HairlineIconButton(
                icon = KcIcons.ChevronRight,
                contentDescription = stringResource(R.string.editor_next_page),
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
            val onRow = draft.cursorLane == lane
            val laneColor = lane.colorToken.color
            val cells = draft.pattern(lane)

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = lane.label.uppercase(),
                    color = laneColor,
                    fontSize = 10.sp,
                    fontWeight = if (onRow) FontWeight.ExtraBold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(dimens.radiusIconButton))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onLaneTap(lane) },
                        )
                        .padding(vertical = 2.dp),
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    for (index in window) {
                        val value = cells.getOrElse(index) { null }
                        val isCursor = onRow && index == draft.cursor
                        // The cursor's column, faintly, on the other rows: where the
                        // other hands fall at the same moment.
                        val sameMoment = !onRow && index == draft.cursor
                        val lit = previewing && index == step
                        val chosen = draft.selection?.contains(index) == true
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .padding(1.dp)
                                .clip(RoundedCornerShape(dimens.radiusStripCell))
                                .background(
                                    when {
                                        lit -> PaletteToken.HEAD_SUNKEN.color
                                        chosen -> PaletteToken.CLAY.color.copy(alpha = 0.16f)
                                        sameMoment -> PaletteToken.HEAD_WORN.color
                                        else -> PaletteToken.HEAD.color
                                    }
                                )
                                .border(
                                    width = if (isCursor) 2.dp else 1.dp,
                                    color = if (isCursor) PaletteToken.CLAY.color else colors.rule,
                                    shape = RoundedCornerShape(dimens.radiusStripCell),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onCellTap(lane, index) },
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

/**
 * Copy and paste. "Copy cells" starts a run at the cursor; tapping cells (any
 * row, any page) moves its other end; Copy keeps it. Paste writes it from the
 * cursor on; "bayan closed" pastes the bayan's opens as closed, which turns a
 * guru half into its laghu half in one go.
 */
@Composable
private fun ClipZone(
    draft: EditorDraft,
    onStart: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
    onPaste: (Boolean) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val sel = draft.selection
    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        if (sel != null) {
            Text(
                text = stringResource(R.string.editor_selection, sel.first + 1, sel.last + 1) +
                    " · " + stringResource(R.string.editor_select_hint),
                color = PaletteToken.CLAY.color,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                PrimaryButton(
                    label = stringResource(R.string.editor_copy),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                )
                SecondaryButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                SecondaryButton(
                    label = stringResource(R.string.editor_select),
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                )
                SecondaryButton(
                    label = stringResource(R.string.editor_paste),
                    onClick = { onPaste(false) },
                    enabled = draft.clip != null,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                )
            }
            if (draft.clip != null) {
                SecondaryButton(
                    label = stringResource(R.string.editor_paste_closed),
                    onClick = { onPaste(true) },
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 44.dp,
                )
                Text(
                    text = pluralStringResource(R.plurals.editor_clip, draft.clipLength, draft.clipLength) +
                        " · " + stringResource(R.string.editor_paste_closed_hint),
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Filled circle = open, X = closed, faint dot = rest — as on the main strip. */
@Composable
internal fun StrokeDot(value: Stroke?, color: Color, size: androidx.compose.ui.unit.Dp) {
    when (value) {
        Stroke.OPEN -> Box(
            Modifier.size(size).background(color, CircleShape)
        )

        Stroke.CLOSED -> com.kirtan.companion.ui.strip.ClosedMark(
            color = color,
            modifier = Modifier.size(size),
            width = 2.2.dp,
        )

        null -> Box(
            Modifier.size(4.dp).background(PaletteToken.RULE.color, CircleShape)
        )
    }
}

/** Zone 3: the selected cell's row's palette — its open and closed strokes, and Rest. */
@Composable
private fun PadsZone(draft: EditorDraft, onPad: (PadSpec) -> Unit) {
    val dimens = KirtanTheme.dimens
    val pads = padSetFor(draft.cursorLane)

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        // Which cell the pads write, and how to change it: tapping another cell
        // swaps the palette, which would otherwise be invisible.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = draft.cursorLane.label.uppercase(),
                color = draft.cursorLane.colorToken.color,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Text(
                text = "  · " + stringResource(R.string.editor_cell_n, draft.cursor + 1) +
                    "  · " + stringResource(R.string.editor_tap_any_cell),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
            )
        }
        pads.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                for (pad in row) {
                    PadButton(
                        pad = pad,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens

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
            text = if (pad.rest) stringResource(R.string.editor_rest) else pad.label,
            color = PaletteToken.SYAHI.color,
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!pad.rest) {
            val lane = pad.write.keys.first()
            StrokeDot(value = pad.write[lane], color = lane.colorToken.color, size = 11.dp)
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
    onResolution: (Int) -> Unit,
    onBpm: (Int) -> Unit,
    onSwing: (Int) -> Unit,
    brahmaloka: Boolean,
    onUnlock: () -> Unit,
    onLeave: () -> Unit,
) {
    val dimens = KirtanTheme.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            SecondaryButton(
                label = stringResource(R.string.editor_undo),
                onClick = onUndo,
                enabled = draft.canUndo,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            SecondaryButton(
                label = stringResource(R.string.editor_clear),
                onClick = onClear,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            SecondaryButton(
                label = if (previewing) stringResource(R.string.common_stop) else stringResource(R.string.editor_preview),
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
                label = meterLabel(draft.meter) + " ▾",
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
                    contentDescription = stringResource(R.string.editor_remove_group),
                    onClick = { onLength(false) },
                    enabled = draft.canRemoveBeat,
                    size = 40.dp,
                )
                Text(
                    text = pluralStringResource(R.plurals.editor_beat_count, draft.beatCount, draft.beatCount),
                    color = PaletteToken.SYAHI.color,
                    fontSize = 13.4.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                HairlineIconButton(
                    icon = KcIcons.ChevronRight,
                    contentDescription = stringResource(R.string.editor_add_group),
                    onClick = { onLength(true) },
                    enabled = draft.canAddBeat,
                    size = 40.dp,
                )
            }
        }

        ResolutionRow(draft = draft, onResolution = onResolution)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Text(
                text = draft.bpm.toString().padStart(3, ' '),
                color = PaletteToken.SYAHI.color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            // One fixed slot for "BPM" or, in Brahmaloka, the ✦ way back: the
            // slider beside it keeps its width either way.
            Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterStart) {
                if (brahmaloka) {
                    com.kirtan.companion.ui.components.BrahmalokaBadge(onLeave = onLeave)
                } else {
                    Text(
                        text = stringResource(R.string.tempo_bpm),
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
            com.kirtan.companion.ui.components.BpmSlider(
                bpm = draft.bpm,
                brahmaloka = brahmaloka,
                onChange = { onBpm(it.coerceIn(MIN_BPM, MAX_BPM)) },
                onUnlock = onUnlock,
                modifier = Modifier.weight(1f),
            )
        }

        com.kirtan.companion.ui.components.SwingControl(
            value = draft.swing,
            onChange = onSwing,
            hint = stringResource(R.string.swing_editor_hint),
        )
    }
}

/**
 * A meter's name in the reader's language. The presets carry English labels as
 * their identity in tests and logs; the screen looks the words up by id, and a
 * signature that is just digits ("6/8 · 3+3") passes through unchanged.
 */
@Composable
private fun meterLabel(preset: MeterPreset): String = when (preset.id) {
    "std" -> stringResource(R.string.meter_std)
    "trip" -> stringResource(R.string.meter_trip)
    "dbl" -> stringResource(R.string.meter_dbl)
    "68" -> stringResource(R.string.meter_68)
    "78" -> stringResource(R.string.meter_78)
    "t5" -> stringResource(R.string.meter_t5)
    "t7" -> stringResource(R.string.meter_t7)
    "t9" -> stringResource(R.string.meter_t9)
    "custom" -> stringResource(R.string.meter_custom)
    else -> preset.label
}

@Composable
private fun meterSig(preset: MeterPreset): String = when (preset.id) {
    "std" -> stringResource(R.string.meter_sig_std)
    "dbl" -> stringResource(R.string.meter_sig_dbl)
    "t5", "t7", "t9" -> stringResource(R.string.meter_sig_tala, preset.cpq)
    else -> preset.sig
}

/**
 * The second lever: how finely each beat is divided. A simple meter shows cells
 * per beat (1–8); an odd meter shows a multiplier on its own grouping, so 7/8
 * stays 2+2+3 in shape. Changing it keeps the rhythm (see
 * [EditorDraft.setResolution]); options that would pass MAX_STEPS cells are greyed.
 */
@Composable
private fun ResolutionRow(draft: EditorDraft, onResolution: (Int) -> Unit) {
    val dimens = KirtanTheme.dimens
    val lever = draft.resolution
    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Text(
            text = stringResource(if (lever.multiplier) R.string.editor_resolution else R.string.editor_cells_per_beat)
                .uppercase(),
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            lever.options.forEach { option ->
                PillChip(
                    label = if (lever.multiplier) "×$option" else option.toString(),
                    selected = option == lever.current,
                    enabled = option == lever.current || draft.resolutionFits(option),
                    onClick = { onResolution(option) },
                    modifier = Modifier.widthIn(min = 44.dp),
                )
            }
        }
        draft.resampleReport?.let { report ->
            val parts = buildList {
                if (report.moved > 0) add(pluralStringResource(R.plurals.editor_resample_moved, report.moved, report.moved))
                if (report.dropped > 0) add(pluralStringResource(R.plurals.editor_resample_dropped, report.dropped, report.dropped))
                add(stringResource(R.string.editor_resample_undo))
            }
            Text(
                text = parts.joinToString(" "),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
            )
        }
    }
}

