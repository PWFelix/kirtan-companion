package com.kirtan.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.AppContainer
import com.kirtan.companion.container
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.ShareIntake
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.storage.EqPrefsSnapshot
import com.kirtan.companion.ui.account.AccountSection
import com.kirtan.companion.ui.account.MigrationPromptSheet
import com.kirtan.companion.ui.beats.BeatsScreen
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.editor.BeatEditorScreen
import com.kirtan.companion.ui.home.HomeScreen
import com.kirtan.companion.ui.learn.LearnScreen
import com.kirtan.companion.ui.icons.CheckDot
import com.kirtan.companion.ui.library.LibraryViewModel
import com.kirtan.companion.ui.nav.BottomNav
import com.kirtan.companion.ui.nav.Tab
import com.kirtan.companion.ui.splash.SplashScreen
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.kirtan.companion.R

/**
 * The app shell: the splash gate, the current tab, and the one bottom nav.
 *
 * Ported from `src/App.jsx`, and the state division is the same: this composable
 * owns the two things that genuinely span screens — **whether the user has
 * entered** and **which beat is loaded** (the latter lives in
 * [LibraryViewModel], which is the same decision the web app makes when `App.jsx`
 * owns `beatId` and calls it "the one piece of state that spans everything else").
 *
 * THE NAV IS RENDERED ONCE, HERE, outside the screen switch. That is the Compose
 * equivalent of the web app building one `<BottomNav>` element and handing the
 * same instance to every screen: it is what keeps the bar from shifting when the
 * user changes tabs. Putting a nav inside each screen would reproduce the bug that
 * comment in `styles.js` warns about.
 *
 * Switching tabs does NOT stop playback — you can browse the library while a beat
 * plays — with the single exception of the editor, which takes the transport over
 * entirely while it is open.
 */
@Composable
internal fun KirtanApp(
    transport: TransportViewModel,
    library: LibraryViewModel,
    modifier: Modifier = Modifier,
    /**
     * Notified when the app crosses the splash boundary, so the activity can hide
     * or reveal the system bars. The activity owns the window; this composable
     * owns the only state that knows whether the user has entered.
     */
    onImmersiveChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val transportState by transport.state.collectAsState()
    val selectedBeat by library.selectedBeat.collectAsState()

    var entered by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    // Hoisted out of Settings so the Bluetooth banner can open it from any tab.
    var syncOpen by remember { mutableStateOf(false) }

    // ── Editor session ─────────────────────────────────────────────────────
    // The editor is opened with an optional seed beat (from Home's or Beats'
    // pencil) and remembers where to go back to. `editorSession` is a key: the
    // draft is `remember`ed inside the screen, so a new session must force a new
    // composition or the previous draft would survive into the next opening —
    // the Compose equivalent of the web editor unmounting on close.
    var editorInitial by remember { mutableStateOf<Beat?>(null) }
    var editorReturn by remember { mutableStateOf<Tab?>(null) }
    var editorSession by remember { mutableIntStateOf(0) }
    var prevTab by remember { mutableStateOf(Tab.HOME) }

    /**
     * True when the open editor session saves to the SHIPPED set rather than to
     * this user's library.
     *
     * State on the session rather than a property of the beat, because the same
     * beat can be opened both ways: "Customize" forks a private copy, "Edit for
     * everyone" corrects the row every install reads. The editor itself does not
     * know or care which — it drafts a beat and hands it back — so the distinction
     * lives with whoever opened it.
     */
    var editorForEveryone by remember { mutableStateOf(false) }

    /** A shipped save waiting on the maintainer's confirmation. */
    var pendingShippedSave by remember { mutableStateOf<PendingShippedSave?>(null) }
    var pendingShippedRemove by remember { mutableStateOf<PendingShippedRemove?>(null) }

    /** A decoded share payload awaiting the library screen's confirmation. */
    var pendingImport by remember { mutableStateOf<ShareCodec.SharePayload?>(null) }

    fun openEditor(beat: Beat?, from: Tab?, forEveryone: Boolean = false) {
        editorInitial = beat
        editorReturn = from
        editorForEveryone = forEveryone
        editorSession++
        tab = Tab.EDITOR
    }

    // Leaving the editor hands the transport back to whatever beat is loaded:
    // the preview replaced the engine's beat with the draft, and walking away
    // must not leave the draft sounding — or loaded — under the play screen.
    // stopPreview first: it clears the playing mirror, without which Home's Play
    // would no-op on the next tap.
    LaunchedEffect(tab) {
        if (prevTab == Tab.EDITOR && tab != Tab.EDITOR) {
            transport.stopPreview()
            selectedBeat?.let { transport.loadBeat(it) }
        }
        prevTab = tab
    }

    // SELECTION REACHES THE ENGINE, mirroring the web App.jsx where selectBeat
    // is ONE function that both records the choice and calls transport.loadBeat.
    // Here the choice (library) and the engine (transport) are separate
    // ViewModels, so this effect is the seam the web function provides: every
    // selection change — Home's ‹ › progression cycle, the picker, an accepted
    // import, the delete fallback — loads into the engine, and a mid-kirtan
    // switch happens live, bar phase preserved. Without it the chevrons update
    // the name on screen and the drum keeps playing the beat it no longer shows.
    //
    // Safe to re-fire: loadBeat dedupes per beat id (see TransportViewModel),
    // so a rotation re-launching this effect re-applies the pattern without
    // clobbering a tempo the user has nudged.
    LaunchedEffect(selectedBeat) {
        selectedBeat?.let { transport.loadBeat(it) }
    }

    // Immersive from the moment the user enters, and NOT immersive on the splash.
    // The splash is the one screen where an accidental launch must be escapable
    // with an ordinary Back press; once Begin is tapped the system bars retire and
    // an edge swipe brings them back transiently (see MainActivity.applyImmersive).
    LaunchedEffect(entered) { onImmersiveChange(entered) }

    // ── Inbound share links ────────────────────────────────────────────────
    // MainActivity pushes any link it receives into ShareIntake. Collected here
    // as a FLOW rather than read once at composition, because links arrive warm
    // too: `onNewIntent` fires on every re-tap while the app is already open, and
    // a one-shot read would silently drop all of them.
    //
    // Consuming immediately is what enforces the trust boundary's one-chance rule:
    // the intent is redelivered on relaunch, so a payload left pending would be
    // re-imported every time the user opens the app.
    val inbound by ShareIntake.pending.collectAsState()
    LaunchedEffect(inbound) {
        val payload = inbound ?: return@LaunchedEffect
        ShareIntake.consume()
        // Hand it to the library screen for PREVIEW rather than writing it
        // straight in: an inbound link is untrusted input, and the confirmation
        // step is the last part of that boundary. Writing here would import a
        // stranger's beat with nothing the user ever saw.
        pendingImport = payload
        // A shared link lands the user in the library so they can see what
        // arrived, rather than on Home with an unexplained beat loaded.
        tab = Tab.BEATS
    }

    // POST_NOTIFICATIONS is a runtime permission from API 33 up, and without it
    // the media notification is suppressed — which means no lock-screen controls
    // and, on some builds, no foreground-service promotion. Asking on entry
    // rather than on first play keeps the prompt away from the moment someone is
    // trying to start a kirtan.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* A refusal is survivable: playback works, the notification is hidden. */ }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!entered) {
        com.kirtan.companion.ui.splash.SplashScreen(
            ready = transportState.ready,
            onBegin = {
                scope.launch {
                    transport.begin()
                    entered = true
                }
            },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Inset-aware EVEN THOUGH immersive hides the bars. Immersive is
            // best-effort: Android re-reveals the bars on an edge swipe, on some
            // dialogs, on IME and on focus changes, and no app may remove them
            // permanently. Without this padding a revealed bar OVERLAYS the UI —
            // the nav pill and the bottom of the strip vanish under it, which is
            // exactly the occlusion this replaces. With it, a revealed bar pushes
            // the content up instead, and while the bars are hidden the insets
            // are zero so the canvas is still the whole screen.
            //
            // The cost is a reflow on each transient reveal. That is the correct
            // trade: content that moves is readable; content that is covered is
            // not.
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(PaletteToken.HEAD.color),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    transport = transport,
                    library = library,
                    beat = selectedBeat,
                    onEditBeat = { openEditor(it, Tab.HOME) },
                )

                Tab.BEATS -> BeatsScreen(
                    library = library,
                    transport = transport,
                    beat = selectedBeat,
                    onStart = {
                        // Start the currently-loaded beat WITHOUT re-selecting it,
                        // so a tempo the user nudged survives the trip to Home.
                        selectedBeat?.let { transport.play(it) }
                        tab = Tab.HOME
                    },
                    onEditBeat = { openEditor(it, Tab.BEATS) },
                    onEditShippedBeat = { openEditor(it, Tab.BEATS, forEveryone = true) },
                    onRemoveShippedBeat = { pendingShippedRemove = PendingShippedRemove(it) },
                    pendingImport = pendingImport,
                    onPendingImportHandled = { pendingImport = null },
                )

                Tab.EDITOR -> key(editorSession) {
                    BeatEditorScreen(
                        engine = transport.engine,
                        transport = transport,
                        initialBeat = editorInitial,
                        savesAsCopy = editorInitial?.isBuiltIn == true && !editorForEveryone,
                        onSave = { beat, callback ->
                            // Routed, not decided: a shipped save is confirmed by a
                            // sheet first, so the editor's callback is held until the
                            // maintainer has said yes — or abandoned, in which case
                            // the editor stays open with its draft intact.
                            if (editorForEveryone) {
                                pendingShippedSave = PendingShippedSave(beat, callback)
                            } else {
                                library.saveBeat(beat) { saved ->
                                    // A built-in can't be changed, so its edit lands as
                                    // a NEW beat. Select it, or Home and Learn keep
                                    // playing the untouched original and the save
                                    // looks like it did nothing.
                                    if (saved != null && saved.id != beat.id) library.selectBeat(saved)
                                    callback(saved)
                                }
                            }
                        },
                        onBack = editorReturn?.let { from -> { tab = from } },
                        onClose = { tab = editorReturn ?: Tab.HOME },
                    )
                }

                Tab.LEARN -> LearnScreen(
                    transport = transport,
                    library = library,
                    beat = selectedBeat,
                )

                Tab.SETTINGS -> SettingsScreen(
                    transport = transport,
                    container = container,
                    syncOpen = syncOpen,
                    onSyncOpenChange = { syncOpen = it },
                )
            }
        }

        // Once per Bluetooth device, while it plays: the moment the lag shows.
        transportState.syncPrompt?.let { route ->
            if (tab == Tab.HOME || tab == Tab.LEARN) {
                SyncPromptBanner(
                    deviceLabel = route.label,
                    onSync = {
                        transport.dismissSyncPrompt()
                        syncOpen = true
                        tab = Tab.SETTINGS
                    },
                    onDismiss = { transport.dismissSyncPrompt() },
                )
            }
        }

        BottomNav(
            active = tab,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = KirtanTheme.dimens.screenPaddingSide,
                    end = KirtanTheme.dimens.screenPaddingSide,
                    // Tight, not zero: the pill needs a little air from the system
                    // navigation bar below it, but space4 here was reading as a
                    // second band of whitespace under the menu.
                    bottom = KirtanTheme.dimens.space2,
                ),
            onHome = { tab = Tab.HOME },
            onBeats = { tab = Tab.BEATS },
            // The editor is a tab, so the pill parks on it while editing — but
            // opening it from the nav means a BLANK draft, not whatever was
            // loaded last, and nowhere to go back to.
            onOpenEditor = { openEditor(null, null) },
            onLearn = { tab = Tab.LEARN },
            onSettings = { tab = Tab.SETTINGS },
        )

        // App-level, not Settings-level: a fresh sign-in can complete while the
        // user is on any tab (the callback arrives through MainActivity), and the
        // offer to move their device library up must not wait for them to visit
        // Settings to exist.
        MigrationPromptSheet(container)

        pendingShippedSave?.let { pending ->
            ShippedSaveSheet(
                beat = pending.beat,
                library = library,
                onDismiss = {
                    // The callback MUST be answered even when nothing was saved.
                    // The editor sets `saving = true` before calling onSave and
                    // clears it only inside the callback, so abandoning this sheet
                    // without one would leave its Save button permanently dead —
                    // with the draft still on screen and no way to store it.
                    // Answering with null is the editor's own "failed, stay open".
                    pending.callback(null)
                    pendingShippedSave = null
                },
                onSaved = { saved ->
                    pendingShippedSave = null
                    pending.callback(saved)
                },
            )
        }

        pendingShippedRemove?.let { pending ->
            ShippedRemoveSheet(
                beat = pending.beat,
                library = library,
                onDismiss = { pendingShippedRemove = null },
                onRemoved = { pendingShippedRemove = null },
            )
        }
    }
}

/**
 * An editor save that has not been confirmed yet.
 *
 * Holds the editor's own callback so the sheet can hand the result back to it:
 * that callback is what closes the editor on success and keeps it open on
 * failure, and losing it would strand the user in an editor that no longer
 * responds to Save.
 */
private class PendingShippedSave(val beat: Beat, val callback: (Beat?) -> Unit)

/** A shipped remove waiting on the maintainer's confirmation. */
private class PendingShippedRemove(val beat: Beat)

/**
 * The confirm step for saving an edit of a built-in beat.
 *
 * Its own sheet rather than a plain save because this is the one write in the app
 * that reaches other people's devices: everyone playing this beat gets the edit on
 * their next launch. Naming the beat is the point — a maintainer deep in a grid of
 * cells has just spent a while looking at strokes, not at a title, and "Save for
 * everyone" on its own does not say which of the nine they are about to change.
 *
 * A refusal stays HERE rather than closing the sheet, so the maintainer reads it in
 * front of the work they were trying to save and can try again. Closing on failure
 * would return them to an editor whose draft they then have to re-verify.
 */
@Composable
private fun ShippedSaveSheet(
    beat: Beat,
    library: LibraryViewModel,
    onDismiss: () -> Unit,
    onSaved: (Beat) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    KcSheet(title = stringResource(R.string.maint_save_title), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            Text(
                text = beat.name,
                color = PaletteToken.SYAHI.color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.maint_save_body),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
            error?.let { message ->
                Text(
                    text = message,
                    color = PaletteToken.DANGER.color,
                    fontSize = 13.4.sp,
                    lineHeight = 19.sp,
                )
            }
            PrimaryButton(
                label = if (saving) stringResource(R.string.common_saving) else stringResource(R.string.maint_save_title),
                enabled = !saving,
                onClick = {
                    saving = true
                    error = null
                    library.saveShippedBeat(beat) { saved, message ->
                        saving = false
                        if (saved != null) onSaved(saved) else error = message
                    }
                },
            )
            SecondaryButton(label = stringResource(R.string.maint_keep_editing), onClick = onDismiss)
        }
    }
}

/**
 * The confirm step for retiring a built-in beat.
 *
 * Same blast radius as saving an edit for everyone — every install gets the
 * change on its next launch — but this one is destructive: the beat disappears
 * from the Beats screen entirely. Playlists that used it keep working because
 * its id remains addressable, which is why the confirm sheet names that fact.
 */
@Composable
private fun ShippedRemoveSheet(
    beat: Beat,
    library: LibraryViewModel,
    onDismiss: () -> Unit,
    onRemoved: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    KcSheet(title = stringResource(R.string.maint_remove_title), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            Text(
                text = beat.name,
                color = PaletteToken.SYAHI.color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.maint_remove_body),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
            error?.let { message ->
                Text(
                    text = message,
                    color = PaletteToken.DANGER.color,
                    fontSize = 13.4.sp,
                    lineHeight = 19.sp,
                )
            }
            PrimaryButton(
                label = if (removing) stringResource(R.string.common_removing) else stringResource(R.string.maint_remove_title),
                enabled = !removing,
                onClick = {
                    removing = true
                    error = null
                    library.removeShippedBeat(beat) { success, message ->
                        removing = false
                        if (success) onRemoved() else error = message
                    }
                },
            )
            SecondaryButton(label = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    }
}

/**
 * Settings: the two display toggles that are actually implemented.
 *
 * The web app's SettingsView is a placeholder shell too, but the two toggles here
 * are real, because both drive something the strip already renders — the bol
 * syllables and the numbered step labels — and both are persisted.
 */
@Composable
private fun SettingsScreen(
    transport: TransportViewModel,
    container: AppContainer,
    syncOpen: Boolean,
    onSyncOpenChange: (Boolean) -> Unit,
) {
    val state by transport.state.collectAsState()
    val dimens = KirtanTheme.dimens
    var aboutOpen by remember { mutableStateOf(false) }
    if (aboutOpen) {
        com.kirtan.companion.ui.about.AboutScreen(onBack = { aboutOpen = false })
        return
    }
    if (syncOpen) {
        com.kirtan.companion.ui.sync.SyncScreen(transport = transport, onBack = { onSyncOpenChange(false) })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = dimens.screenMaxWidth)
            .padding(dimens.screenPaddingSide)
            // The scroll container, for the same reason Home is one: a Column
            // that runs out of vertical space hands zero-height constraints to
            // whatever comes next and those children vanish silently. On a short
            // phone with the interface scaled UP this screen is exactly the one
            // that would overflow.
            .verticalScroll(rememberScrollState()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(dimens.space4),
    ) {
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = stringResource(R.string.tab_settings),
            color = PaletteToken.SYAHI.color,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // Account first: signing in changes what every other part of this screen
        // implies about where your beats live, so it should not be buried below
        // the notation toggles.
        AccountSection(container)

        com.kirtan.companion.ui.components.SectionLabel(stringResource(R.string.settings_notation))

        SettingsToggle(
            title = stringResource(R.string.settings_show_bols),
            subtitle = stringResource(R.string.settings_show_bols_sub),
            checked = state.settings.showBols,
            onCheckedChange = { transport.setShowBols(it) },
        )
        SettingsToggle(
            title = stringResource(R.string.settings_show_steps),
            subtitle = stringResource(R.string.settings_show_steps_sub),
            checked = state.settings.showStepLabels,
            onCheckedChange = { transport.setShowStepLabels(it) },
        )

        SectionLabel(stringResource(R.string.settings_sound))
        SettingsLink(
            title = stringResource(R.string.sync_row_title),
            subtitle = stringResource(R.string.sync_row_sub) + " " +
                "${state.route.label}: " +
                (if (state.displayDelayMs == 0) stringResource(R.string.sync_row_no_delay)
                else stringResource(R.string.sync_row_delay, state.displayDelayMs)),
            onClick = { onSyncOpenChange(true) },
        )

        SectionLabel(stringResource(R.string.settings_ui_size))
        Text(
            text = stringResource(R.string.settings_ui_size_hint),
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            EqPrefsSnapshot.UI_SCALE_PRESETS.forEach { preset ->
                ScaleChip(
                    label = "${(preset * 100).roundToInt()}%",
                    selected = state.settings.uiScale == preset,
                    onClick = { transport.setUiScale(preset) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        com.kirtan.companion.ui.components.SecondaryButton(
            label = stringResource(R.string.about_title),
            icon = com.kirtan.companion.ui.icons.KcIcons.Info,
            onClick = { aboutOpen = true },
        )

        Spacer(Modifier.height(dimens.space4))
        Text(
            text = stringResource(R.string.settings_audio_output, LocalContext.current.container.sampleRate),
            color = KirtanTheme.colors.faint,
            fontSize = 12.sp,
        )
    }
}

/**
 * One interface-scale preset.
 *
 * A chip row rather than a slider: the choice is made once per device, and four
 * labelled stops are glanceable and each has been looked at laid out, whereas a
 * slider offers intermediate values nobody has ever seen. The selected chip is
 * filled clay, matching the nav pill and the play bar, so "which one am I on"
 * needs no reading.
 */
@Composable
private fun ScaleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) PaletteToken.CLAY.color else Color.Transparent)
            .border(
                BorderStroke(
                    width = dimens.hairline,
                    color = if (selected) PaletteToken.CLAY.color else KirtanTheme.colors.rule,
                ),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
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

/**
 * "Playing through JBL Flip 5 · Sync the display?" Shown above the nav the first
 * time a Bluetooth device plays, because that is when the lag is visible and the
 * fix is a screen away. Either button retires it for that device.
 */
@Composable
private fun SyncPromptBanner(deviceLabel: String, onSync: () -> Unit, onDismiss: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val dismissLabel = stringResource(R.string.common_dismiss)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPaddingSide, vertical = dimens.space1)
            .clip(RoundedCornerShape(dimens.radiusPad))
            .background(PaletteToken.SYAHI.color)
            .padding(start = dimens.space4, end = dimens.space2, top = dimens.space2, bottom = dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.route_playing_through, deviceLabel),
                color = PaletteToken.HEAD.color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.route_prompt),
                color = PaletteToken.HEAD.color.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
        }
        Text(
            text = stringResource(R.string.sync_title),
            color = PaletteToken.HEAD.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(onClick = onSync)
                .padding(horizontal = dimens.space3, vertical = dimens.space3),
        )
        Text(
            text = "✕",
            color = PaletteToken.HEAD.color.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = dismissLabel }
                .padding(horizontal = dimens.space3, vertical = dimens.space3),
        )
    }
}

/** A settings row that opens a screen rather than toggling. */
@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PaletteToken.SYAHI.color,
                fontSize = 15.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.width(dimens.space3))
        Text(text = "›", color = PaletteToken.SYAHI_SOFT.color, fontSize = 24.sp)
    }
}

/**
 * One settings row.
 *
 * The WHOLE ROW is the toggle target, not just the check dot — a 24dp dot is
 * below Android's 48dp touch-target guidance, and this app is used mid-kirtan,
 * often standing up, often without looking at the screen.
 */
@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PaletteToken.SYAHI.color,
                fontSize = 15.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.width(dimens.space3))
        CheckDot(checked = checked)
    }
}
