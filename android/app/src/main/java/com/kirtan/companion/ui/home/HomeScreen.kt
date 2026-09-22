package com.kirtan.companion.ui.home

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.EQ_BANDS
import com.kirtan.companion.data.END_BASE_FREQ
import com.kirtan.companion.data.MAX_BPM
import com.kirtan.companion.data.MIN_BPM
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.TUNE_MAX_CENTS
import com.kirtan.companion.data.TUNE_MIN_CENTS
import com.kirtan.companion.data.centsToRate
import com.kirtan.companion.data.colorToken
import com.kirtan.companion.data.freqToNoteName
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.ui.Wordmark
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.KcSlider
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.components.ScreenFrame
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.library.LibraryViewModel
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel

/** Which of the home screen's three sheets is open, if any. */
private enum class HomeSheet { PICKER, MIXER, TEMPO }

/**
 * Below this screen height the wordmark is dropped to give the strip and the
 * transport the room (a Nexus 5X is ~698dp; a Pixel-class phone ~915dp).
 */
private const val WORDMARK_MIN_HEIGHT_DP = 800

/**
 * The playing screen.
 *
 * Ported from `src/views/HomeView.jsx`. The layout is unchanged in substance:
 * wordmark, a beat switcher row, the strip, the tempo block, and a full-width play
 * bar at thumb height.
 *
 * STATE THIS SCREEN OWNS: only which sheet is open, and the raw text in the tempo
 * field. Everything else is the transport's or the library's. That division is the
 * one the web app draws — "if a piece of state is only meaningful inside one
 * screen, it lives in that screen" — and it is why this file has no idea how a
 * beat gets saved or how the engine is wired.
 *
 * NOT PORTED: the landscape layout, which collapses the entire transport into a
 * single 44dp rail so the strip can take the rest of a short viewport. It is a
 * real feature (a phone propped up during a kirtan is the common case) but it is
 * a second layout, and shipping one correct layout beats shipping two half-done
 * ones. The portrait layout below is what the web app shows to the overwhelming
 * majority of its users.
 *
 * ALSO NOT PORTED: the tempo sheet's iOS-style snapping BPM wheel. The field and
 * slider below cover the same job; the wheel is a nicer way to reach a value, not
 * a different capability.
 */
@Composable
internal fun HomeScreen(
    transport: TransportViewModel,
    library: LibraryViewModel,
    beat: Beat?,
    onEditBeat: (Beat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by transport.state.collectAsState()
    val libraryState by library.state.collectAsState()
    val dimens = KirtanTheme.dimens
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }

    // The wordmark is the first thing sacrificed when vertical space is scarce.
    //
    // It is pure brand: the splash already introduced the app and the bottom nav
    // orients the user, so on a tall screen it is lovely and costs nothing, but
    // on a short one it buys nothing and pushes a row of the strip — or the tempo
    // controls — into the scroll. Hiding it below a height threshold reclaims
    // ~50dp exactly where it is needed, and leaves tall screens untouched.
    //
    // The threshold scales WITH the interface scale: a compact interface occupies
    // less height, so it earns the wordmark back on the same physical screen.
    // Without this, choosing "85%" in Settings would free space and then leave it
    // empty, which would read as a bug rather than a win.
    val showWordmark = LocalConfiguration.current.screenHeightDp >=
        WORDMARK_MIN_HEIGHT_DP * state.settings.uiScale

    // The tempo field edits a DRAFT string, never the clamped value: binding the
    // field straight to `bpm` means backspacing "140" to "1" immediately clamps to
    // 40 and fights the user. The draft commits on confirm or blur.
    var bpmDraft by remember(state.bpm) { mutableStateOf(state.bpm.toString()) }

    ScreenFrame(modifier = modifier) {
        // SCROLLABLE, and that is a correctness requirement, not a nicety.
        //
        // A Compose Column measures each child against the space REMAINING after
        // the previous ones; when that reaches zero, later children are handed
        // zero-height constraints and collapse to nothing — silently. On a
        // 2400px-tall emulator the transport chrome left room for the strip, but
        // on a 1920px phone it did not, and the bayan and kartal rows simply
        // vanished (turning bols on made the dayan row taller and moved the cut
        // point up, which is how it was diagnosed). Giving the content its own
        // scroll region guarantees every child its natural height on any screen.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.space4),
        ) {
            if (showWordmark) {
                Wordmark(size = 22.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            if (beat == null) {
                Text(
                    text = "Loading beats…",
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 15.2.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                BeatSwitcherRow(
                    beat = beat,
                    onPrevious = { library.cycleBeat(-1) },
                    onNext = { library.cycleBeat(1) },
                    onOpenPicker = { sheet = HomeSheet.PICKER },
                    onEdit = { onEditBeat(beat) },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimens.space2),
                    contentAlignment = Alignment.Center,
                ) {
                    BeatStrip(
                        beat = beat,
                        step = state.step,
                        playing = state.playing,
                        getPhase = { transport.getPhase() },
                        mutedEnds = LaneId.ORDERED.associateWith { state.settings.isMuted(it) },
                        onToggleMute = { transport.toggleMute(it) },
                        showBols = state.settings.showBols,
                    )
                }

                TransportControls(
                    bpm = state.bpm,
                    tempoLocked = state.settings.tempoLocked,
                    onNudge = { transport.nudgeBpm(it) },
                    onOpenTempo = {
                        bpmDraft = state.bpm.toString()
                        sheet = HomeSheet.TEMPO
                    },
                    onSliderChange = { transport.changeBpm(it.toInt()) },
                    onToggleLock = { transport.setTempoLocked(!state.settings.tempoLocked) },
                    onTapTempo = { transport.tapTempo() },
                    onOpenMixer = { sheet = HomeSheet.MIXER },
                )
            }

            if (state.outputFailed) {
                Text(
                    text = "Could not open the audio output. Check that another app is not holding it.",
                    color = PaletteToken.DANGER.color,
                    fontSize = 13.4.sp,
                )
            }
        }

        // Pinned outside the scroll region: the one control that must never
        // require scrolling to reach.
        if (beat != null) {
            PrimaryButton(
                label = if (state.playing) "Pause" else "Play",
                icon = if (state.playing) KcIcons.Pause else KcIcons.Play,
                enabled = state.ready,
                onClick = { transport.togglePlay(beat) },
            )
        }
    }

    when (sheet) {
        HomeSheet.PICKER -> BeatPickerSheet(
            library = library,
            activeCategoryId = libraryState.activeCategoryId,
            selectedBeatId = beat?.id,
            onDismiss = { sheet = null },
            onPick = { picked, fromCategory ->
                library.selectBeat(picked, fromCategory)
                transport.loadBeat(picked)
                sheet = null
            },
        )

        HomeSheet.MIXER -> MixerSheet(transport = transport, onDismiss = { sheet = null })

        HomeSheet.TEMPO -> TempoSheet(
            bpm = state.bpm,
            draft = bpmDraft,
            onDraftChange = { draft ->
                bpmDraft = draft
                // An in-range draft commits live after the user pauses typing, so
                // the tempo can be heard while it is being entered. Out-of-range
                // or partial input waits for the confirm, or "8" on the way to
                // "80" would clamp to 40 and be heard as a mistake.
                draft.toIntOrNull()?.takeIf { it in MIN_BPM..MAX_BPM }
                    ?.let { transport.changeBpm(it) }
            },
            onCommit = {
                bpmDraft.toIntOrNull()?.let { transport.commitBpm(it) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        null -> Unit
    }
}

/** The ‹ name › pencil row that switches beats and forks them into the editor. */
@Composable
private fun BeatSwitcherRow(
    beat: Beat,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
    onEdit: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        HairlineIconButton(
            icon = KcIcons.Back,
            contentDescription = "Previous beat",
            onClick = onPrevious,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(dimens.radiusIconButton))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenPicker,
                )
                .padding(vertical = dimens.space1),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = beat.name,
                    color = PaletteToken.SYAHI.color,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "▾",
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 14.sp,
                )
            }
            Text(
                text = "${beat.note} · ${beat.steps} cells",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HairlineIconButton(
            icon = KcIcons.ChevronRight,
            contentDescription = "Next beat",
            onClick = onNext,
        )
        HairlineIconButton(
            icon = KcIcons.Pencil,
            contentDescription = if (beat.isBuiltIn) "Customize this beat" else "Edit this beat",
            onClick = onEdit,
        )
    }
}

/** The BPM signage, the slider, the tempo lock and tap tempo. */
@Composable
private fun TransportControls(
    bpm: Int,
    tempoLocked: Boolean,
    onNudge: (Int) -> Unit,
    onOpenTempo: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onToggleLock: () -> Unit,
    onTapTempo: () -> Unit,
    onOpenMixer: () -> Unit,
) {
    val dimens = KirtanTheme.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space4)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space4, Alignment.CenterHorizontally),
        ) {
            HairlineIconButton(
                icon = KcIcons.Back,
                contentDescription = "Slower",
                onClick = { onNudge(-1) },
                enabled = bpm > MIN_BPM,
                size = 48.dp,
                tint = PaletteToken.CLAY.color,
            )

            // BPM as signage: the number IS the label. Fixed 3-character width so
            // moving between 2- and 3-digit values does not resize the button and
            // jitter the flanking steppers.
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(dimens.radiusIconButton))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenTempo,
                    )
                    .padding(horizontal = dimens.space2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = bpm.toString().padStart(3, ' '),
                        color = PaletteToken.SYAHI.color,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "BPM",
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(text = "▾", color = PaletteToken.SYAHI_SOFT.color, fontSize = 11.sp)
            }

            HairlineIconButton(
                icon = KcIcons.ChevronRight,
                contentDescription = "Faster",
                onClick = { onNudge(1) },
                enabled = bpm < MAX_BPM,
                size = 48.dp,
                tint = PaletteToken.CLAY.color,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            KcSlider(
                value = bpm.toFloat(),
                onValueChange = onSliderChange,
                valueRange = MIN_BPM.toFloat()..MAX_BPM.toFloat(),
                enabled = !tempoLocked,
                modifier = Modifier.weight(1f),
            )
            HairlineIconButton(
                icon = if (tempoLocked) KcIcons.LockClosed else KcIcons.LockOpen,
                contentDescription = if (tempoLocked) "Unlock tempo" else "Lock tempo",
                onClick = onToggleLock,
                filled = tempoLocked,
            )
            SecondaryButton(
                label = "Tap",
                onClick = onTapTempo,
                modifier = Modifier.width(84.dp),
                minHeight = 44.dp,
            )
        }

        SecondaryButton(label = "Mixer", icon = KcIcons.Mixer, onClick = onOpenMixer)
    }
}

/**
 * The mixer sheet: master fader, then one block per lane.
 *
 * Each lane gets a fader and a mute; only the two MRIDANGA ends get an EQ and a
 * tuning panel, because the karatalas carry no tone chain by design (see
 * [com.kirtan.companion.engine.EndChannel]). Asking the engine for `hasEq` rather
 * than hard-coding a lane list keeps this honest if an instrument is ever added.
 */
@Composable
private fun MixerSheet(transport: TransportViewModel, onDismiss: () -> Unit) {
    val state by transport.state.collectAsState()
    val settings = state.settings
    val dimens = KirtanTheme.dimens

    KcSheet(title = "Mixer", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
            SectionLabel("Master")
            KcSlider(
                value = settings.masterVolume,
                onValueChange = { transport.changeVolume(it) },
                valueRange = 0f..1f,
            )
            Text(
                text = "${(settings.masterVolume * 100).toInt()}%",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        for (lane in LaneId.ORDERED) {
            val accent = lane.colorToken.color
            val end = settings.end(lane)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.radiusSectionCard))
                    .background(PaletteToken.HEAD_WORN.color)
                    .padding(dimens.space4),
                verticalArrangement = Arrangement.spacedBy(dimens.space3),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = lane.label,
                        color = accent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.1.sp,
                        modifier = Modifier.weight(1f),
                    )
                    HairlineIconButton(
                        icon = if (settings.isMuted(lane)) KcIcons.SpeakerMuted else KcIcons.Speaker,
                        contentDescription = if (settings.isMuted(lane)) {
                            "Unmute ${lane.label}"
                        } else {
                            "Mute ${lane.label}"
                        },
                        onClick = { transport.toggleMute(lane) },
                        size = 40.dp,
                        tint = accent,
                    )
                }

                KcSlider(
                    value = settings.volume(lane),
                    onValueChange = { transport.changeEndVolume(lane, it) },
                    valueRange = 0f..1f,
                    accent = accent,
                )

                if (end != null) {
                    LaneTonePanels(
                        lane = lane,
                        accent = accent,
                        eqOpen = end.eqPanelOpen,
                        tuneOpen = end.tunePanelOpen,
                        bands = end.bands,
                        tuneCents = end.tuneCents,
                        onToggleEq = { transport.toggleEqPanel(lane) },
                        onToggleTune = { transport.toggleTunePanel(lane) },
                        onBand = { i, db -> transport.changeEqBand(lane, i, db) },
                        onTune = { transport.changeTune(lane, it) },
                    )
                }
            }
        }

        Text(
            text = "100% is balanced for phone speakers — the bass end is already boosted.",
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 12.sp,
        )
    }
}

/** The two disclosure panels — tone and pitch — for one mridanga end. */
@Composable
private fun LaneTonePanels(
    lane: LaneId,
    accent: androidx.compose.ui.graphics.Color,
    eqOpen: Boolean,
    tuneOpen: Boolean,
    bands: List<Double>,
    tuneCents: Int,
    onToggleEq: () -> Unit,
    onToggleTune: () -> Unit,
    onBand: (Int, Double) -> Unit,
    onTune: (Int) -> Unit,
) {
    val dimens = KirtanTheme.dimens

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        PanelToggle(
            icon = KcIcons.Eq,
            label = "Tone",
            open = eqOpen,
            accent = accent,
            onClick = onToggleEq,
            modifier = Modifier.weight(1f),
        )
        PanelToggle(
            icon = KcIcons.Tune,
            label = "Tune",
            open = tuneOpen,
            accent = accent,
            onClick = onToggleTune,
            modifier = Modifier.weight(1f),
        )
    }

    if (eqOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
            EQ_BANDS.forEachIndexed { index, band ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = band.label,
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 12.sp,
                        modifier = Modifier.width(56.dp),
                    )
                    KcSlider(
                        value = bands.getOrElse(index) { 0.0 }.toFloat(),
                        onValueChange = { onBand(index, it.toDouble()) },
                        valueRange = -12f..12f,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${bands.getOrElse(index) { 0.0 }.toInt()} dB",
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp),
                    )
                }
            }
        }
    }

    if (tuneOpen) {
        val base = END_BASE_FREQ[lane.wireId]
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space1)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcSlider(
                    value = tuneCents.toFloat(),
                    onValueChange = { onTune(it.toInt()) },
                    valueRange = TUNE_MIN_CENTS.toFloat()..TUNE_MAX_CENTS.toFloat(),
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(dimens.space2))
                // The readout names the pitch actually sounding — the measured base
                // shifted by the user's cents — not the offset alone, because a
                // player tuning to a harmonium wants a note name.
                Text(
                    text = if (base != null) {
                        freqToNoteName(base * centsToRate(tuneCents))
                    } else {
                        "$tuneCents¢"
                    },
                    color = PaletteToken.SYAHI.color,
                    fontSize = 13.4.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.width(84.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun PanelToggle(
    icon: ImageVector,
    label: String,
    open: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusIconButton)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (open) accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                BorderStroke(dimens.hairline, if (open) accent else KirtanTheme.colors.rule),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (open) PaletteToken.ON_CLAY.color else accent,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (open) PaletteToken.ON_CLAY.color else PaletteToken.SYAHI.color,
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The tempo sheet: a large entry field over a slider. */
@Composable
private fun TempoSheet(
    bpm: Int,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
) {
    KcSheet(
        title = "Tempo",
        onDismiss = onDismiss,
        headerAction = {
            HairlineIconButton(
                icon = KcIcons.Check,
                contentDescription = "Set tempo",
                onClick = onCommit,
                filled = true,
                tint = PaletteToken.ON_CLAY.color,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KirtanTheme.dimens.space3),
        ) {
            OutlinedTextField(
                value = draft,
                // Digits only, and capped at three characters: MAX_BPM is 200, so
                // nothing longer can ever be valid, and accepting it would invite
                // a value that is then silently clamped.
                onValueChange = { input ->
                    onDraftChange(input.filter { it.isDigit() }.take(3))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    fontFeatureSettings = "tnum",
                    color = PaletteToken.SYAHI.color,
                ),
                modifier = Modifier.width(140.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PaletteToken.CLAY.color,
                    unfocusedBorderColor = KirtanTheme.colors.rule,
                    focusedContainerColor = PaletteToken.HEAD_SUNKEN.color,
                    unfocusedContainerColor = PaletteToken.HEAD_SUNKEN.color,
                    disabledContainerColor = PaletteToken.HEAD_SUNKEN.color,
                    cursorColor = PaletteToken.CLAY.color,
                    focusedTextColor = PaletteToken.SYAHI.color,
                    unfocusedTextColor = PaletteToken.SYAHI.color,
                ),
            )

            Text(
                text = "BPM",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )

            KcSlider(
                value = bpm.toFloat(),
                onValueChange = { onDraftChange(it.toInt().toString()) },
                valueRange = MIN_BPM.toFloat()..MAX_BPM.toFloat(),
            )
        }
    }
}

/**
 * The quick-pick sheet: a tab row over a list of beats with mini strips.
 *
 * It opens on the ACTIVE category but carries its own tab row, so a player can
 * hop to another progression without visiting the Beats page — which is the point
 * of it existing separately from the library screen.
 */
@Composable
private fun BeatPickerSheet(
    library: LibraryViewModel,
    activeCategoryId: String,
    selectedBeatId: String?,
    onDismiss: () -> Unit,
    onPick: (Beat, String) -> Unit,
) {
    val libraryState by library.state.collectAsState()
    var tab by remember { mutableStateOf(activeCategoryId) }

    val tabs = remember(libraryState.categories) {
        listOf(
            com.kirtan.companion.storage.BUILTIN_CATEGORY,
            com.kirtan.companion.storage.CUSTOM_CATEGORY,
        ) + libraryState.categories.map { it.id }
    }

    KcSheet(title = "Choose a beat", onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KirtanTheme.dimens.space2),
        ) {
            tabs.forEach { id ->
                CategoryChip(
                    label = library.categoryName(id),
                    selected = id == tab,
                    onClick = { tab = id },
                )
            }
        }

        val beats = remember(tab, libraryState) { library.categoryBeats(tab) }
        if (beats.isEmpty()) {
            Text(
                text = "This category is empty — add beats to it on the Beats page.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                modifier = Modifier.padding(vertical = KirtanTheme.dimens.space4),
            )
        } else {
            beats.forEach { beat ->
                PickRow(
                    beat = beat,
                    selected = beat.id == selectedBeatId,
                    onClick = { onPick(beat, tab) },
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) PaletteToken.CLAY.color else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                BorderStroke(dimens.hairline, if (selected) PaletteToken.CLAY.color else dimens.let { KirtanTheme.colors.rule }),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = dimens.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) PaletteToken.ON_CLAY.color else PaletteToken.SYAHI.color,
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PickRow(beat: Beat, selected: Boolean, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPad)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaletteToken.HEAD_WORN.color)
            .border(
                BorderStroke(
                    width = if (selected) 2.dp else dimens.hairline,
                    color = if (selected) PaletteToken.CLAY.color else KirtanTheme.colors.rule,
                ),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = dimens.space3, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = beat.name,
                color = PaletteToken.SYAHI.color,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = beat.note,
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = dimens.space2),
            )
            com.kirtan.companion.ui.icons.RadioDot(selected = selected)
        }
        BeatStrip(beat = beat, mini = true)
    }
}
