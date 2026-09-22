package com.kirtan.companion.ui.beats

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.storage.BUILTIN_CATEGORY
import com.kirtan.companion.storage.CUSTOM_CATEGORY
import com.kirtan.companion.storage.LibraryRepository
import com.kirtan.companion.storage.ShippedSource
import com.kirtan.companion.storage.ShippedStatus
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.ScreenFrame
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.community.CommunityScreen
import com.kirtan.companion.ui.community.CommunityViewModel
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.library.LibraryViewModel
import com.kirtan.companion.ui.share.ImportConfirmSheet
import com.kirtan.companion.ui.share.ImportSheet
import com.kirtan.companion.ui.share.ShareSheet
import com.kirtan.companion.ui.share.ShareTarget
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel

/** Where the Beats screen has drilled to. Dies with the screen, as on the web. */
private sealed interface BeatsPage {
    data object Landing : BeatsPage
    data class Category(val id: String) : BeatsPage

    /** The community library. A page, not a sheet: it is a list you browse and
     *  search, and a sheet's fixed height would cram it. */
    data object Community : BeatsPage
}

/**
 * The library screen.
 *
 * Ported from `src/views/BeatsView.jsx`. The structural decision worth preserving
 * is that this is a SHORT LIST OF SECTIONS the user drills into, not a tab bar
 * over one long list: a library of saved beats and playlists is browsed by
 * narrowing, and a flat tab strip over everything would make the built-ins, the
 * user's own work and their progressions compete for the same visual weight.
 *
 * NOT PORTED, and the two largest gaps in this screen:
 *  - **Browse** — the community library (published beats, search, publish). The
 *    client exists in `storage/CommunityClient.kt`; only its UI is missing.
 *  - **Share and import** — the share sheet, the paste-a-code field and the
 *    import preview. The codec and the deep-link intake are both complete and
 *    tested ([com.kirtan.companion.data.ShareCodec],
 *    [com.kirtan.companion.data.ShareIntake]); what is missing is the sheet that
 *    calls them. Inbound links arriving through the manifest intent ARE handled,
 *    in [com.kirtan.companion.MainActivity].
 */
@Composable
internal fun BeatsScreen(
    library: LibraryViewModel,
    transport: TransportViewModel,
    beat: Beat?,
    onStart: () -> Unit,
    onEditBeat: (Beat) -> Unit,
    /**
     * Open the editor for a BUILT-IN beat in the mode that saves back onto its own
     * row, for every install. Separate from [onEditBeat] because the two saves go
     * to different places and only the caller can tell the editor which one this
     * session is — see [com.kirtan.companion.ui.KirtanApp].
     */
    onEditShippedBeat: (Beat) -> Unit,
    /**
     * Retire a built-in beat from the shipped set for every install. Separate from
     * [onEditBeat] and [onEditShippedBeat] because it is a DELETE rather than an
     * edit — there is no editor to open, just a confirm and a write.
     */
    onRemoveShippedBeat: (Beat) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A share payload that arrived through a deep link, to be previewed here
     * rather than written straight to the library. Handed over by
     * [com.kirtan.companion.ui.KirtanApp], which consumes it from
     * [com.kirtan.companion.data.ShareIntake] exactly once.
     */
    pendingImport: ShareCodec.SharePayload? = null,
    onPendingImportHandled: () -> Unit = {},
) {
    val state by library.state.collectAsState()
    val allBeats by library.allBeats.collectAsState()
    val builtIns by library.builtInBeats.collectAsState()
    val shipped by library.shippedStatus.collectAsState()
    val isMaintainer by library.isMaintainer.collectAsState()
    val transportState by transport.state.collectAsState()
    val communityVm: CommunityViewModel = viewModel(factory = CommunityViewModel.Factory)
    var page by remember { mutableStateOf<BeatsPage>(BeatsPage.Landing) }
    var search by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Beat?>(null) }
    var shareTarget by remember { mutableStateOf<ShareTarget?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ShareCodec.SharePayload?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }

    // A deep-linked payload joins the same confirm-then-write path as a pasted
    // one, so an inbound link can never land in the library unreviewed.
    LaunchedEffect(pendingImport) {
        pendingImport?.let {
            importPreview = it
            onPendingImportHandled()
        }
    }

    fun acceptImport(payload: ShareCodec.SharePayload) {
        importPreview = null
        library.importShared(payload) { result ->
            importResult = when {
                result.beats.isEmpty() -> "Nothing was imported."
                result.categoryId != null ->
                    "Added ${result.beats.size} " +
                        (if (result.beats.size == 1) "beat" else "beats") +
                        " as a new list."

                else -> "Added to Your beats."
            }
            // A shared list arrives as its own playlist; land the user on it so
            // they can see what came in rather than guessing where it went.
            result.categoryId?.let { page = BeatsPage.Category(it) }
        }
    }

    ScreenFrame(modifier = modifier) {
        // The sub-header: a back button on drilled-in pages, a spacer on the
        // landing so the title stays centred in both.
        //
        // "Drilled in" is EVERY page but the landing, not just a category. This
        // read `page is BeatsPage.Category` until Community was added, which left
        // that page with no way back at all: the header had a spacer where the
        // button belonged, and the Beats tab does not reset the sub-page — it is
        // already the selected tab, so tapping it again is a no-op. The only exit
        // was to leave for Home and come back, which disposes the screen and loses
        // the search and the scroll with it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page != BeatsPage.Landing) {
                HairlineIconButton(
                    icon = KcIcons.Back,
                    contentDescription = "Back",
                    onClick = { page = BeatsPage.Landing },
                )
            } else {
                Spacer(Modifier.size(44.dp))
            }
            Text(
                text = when (val p = page) {
                    BeatsPage.Landing -> "Beats"
                    is BeatsPage.Category -> library.categoryName(p.id)
                    BeatsPage.Community -> "Community"
                },
                color = PaletteToken.SYAHI.color,
                fontSize = 26.sp,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(44.dp))
        }

        state.error?.let { message ->
            ErrorStrip(message = message, onDismiss = { library.dismissError() })
        }

        importResult?.let { message ->
            ResultStrip(message = message, onDismiss = { importResult = null })
        }

        when (val p = page) {
            BeatsPage.Landing -> LandingPage(
                modifier = Modifier.fillMaxWidth().weight(1f),
                search = search,
                onSearchChange = { search = it },
                library = library,
                state = state,
                allBeats = allBeats,
                builtInMeta = shippedSourceLine(shipped.source, builtIns.size),
                onOpenCategory = { page = BeatsPage.Category(it) },
                onOpenDetail = { detail = it },
                onOpenImport = { importOpen = true },
                onOpenCommunity = { page = BeatsPage.Community },
            )

            BeatsPage.Community -> CommunityScreen(
                vm = communityVm,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            is BeatsPage.Category -> CategoryPage(
                modifier = Modifier.fillMaxWidth().weight(1f),
                categoryId = p.id,
                categoryName = library.categoryName(p.id),
                // Keyed on the built-in set as well as the library state: a check
                // for updates replaces the built-ins WITHOUT touching `state`, so
                // without it this `remember` would keep serving the set that was
                // on screen when the page opened.
                beats = remember(p.id, state, builtIns) { library.categoryBeats(p.id) },
                library = library,
                shipped = shipped,
                canCheckForUpdates = library.canCheckForUpdates,
                onCheckForUpdates = { library.checkForBeatUpdates() },
                onOpenDetail = { detail = it },
                onShareList = { name, beats ->
                    shareTarget = ShareTarget.CategoryTarget(name, beats)
                },
                onPublishList = { name, beats ->
                    communityVm.publishCategory(name, beats) { message -> importResult = message }
                },
            )
        }

        PrimaryButton(
            label = if (beat != null) "Start · ${beat.name}" else "Start",
            icon = KcIcons.Start,
            enabled = transportState.ready && beat != null,
            radius = KirtanTheme.dimens.radiusSectionCard,
            weight = FontWeight.ExtraBold,
            onClick = { onStart() },
        )
    }

    detail?.let { target ->
        BeatDetailSheet(
            beat = target,
            library = library,
            onDismiss = { detail = null },
            onPlay = {
                library.selectBeat(target)
                transport.play(target)
                detail = null
                onStart()
            },
            onEdit = {
                onEditBeat(target)
                detail = null
            },
            // A maintainer looking at a built-in gets both: forking a private copy
            // is still what you want when you are experimenting, and correcting the
            // shipped beat is what you want when it is simply wrong.
            canEditShipped = isMaintainer && target.isBuiltIn,
            onEditShipped = {
                onEditShippedBeat(target)
                detail = null
            },
            // Retiring a beat is the third shipped-set write, alongside promote and
            // edit-for-everyone. Same confirm-first pattern: the blast radius is
            // named in the confirm sheet before the write happens.
            canRemoveShipped = isMaintainer && target.isBuiltIn,
            onRemoveShipped = {
                onRemoveShippedBeat(target)
                detail = null
            },
            onShare = {
                shareTarget = ShareTarget.BeatTarget(target)
                detail = null
            },
            onPublish = {
                detail = null
                communityVm.publishBeat(target) { message -> importResult = message }
            },
        )
    }

    shareTarget?.let { target ->
        ShareSheet(target = target, onDismiss = { shareTarget = null })
    }

    if (importOpen) {
        ImportSheet(
            onDismiss = { importOpen = false },
            onDecoded = { payload ->
                importOpen = false
                importPreview = payload
            },
        )
    }

    importPreview?.let { payload ->
        ImportConfirmSheet(
            payload = payload,
            onDismiss = { importPreview = null },
            onAccept = { acceptImport(payload) },
        )
    }
}

@Composable
private fun LandingPage(
    modifier: Modifier = Modifier,
    search: String,
    onSearchChange: (String) -> Unit,
    library: LibraryViewModel,
    state: LibraryRepository.State,
    allBeats: List<Beat>,
    builtInMeta: String,
    onOpenCategory: (String) -> Unit,
    onOpenDetail: (Beat) -> Unit,
    onOpenImport: () -> Unit,
    onOpenCommunity: () -> Unit,
) {
    val dimens = KirtanTheme.dimens

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        item {
            SearchCard(
                query = search,
                onQueryChange = onSearchChange,
                allBeats = allBeats,
                onOpenDetail = onOpenDetail,
            )
        }

        item {
            SectionCard(
                title = "Built in",
                meta = builtInMeta,
                onClick = { onOpenCategory(BUILTIN_CATEGORY) },
            )
        }

        item {
            SectionCard(
                title = "Your beats",
                meta = if (state.customBeats.isEmpty()) {
                    "Nothing saved yet"
                } else {
                    "${state.customBeats.size} saved"
                },
                onClick = { onOpenCategory(CUSTOM_CATEGORY) },
            )
        }

        item {
            SectionCard(
                title = "Import",
                meta = "Paste a code or link someone sent you",
                onClick = onOpenImport,
            )
        }

        item {
            SectionCard(
                title = "Community",
                meta = "Browse and publish shared beats",
                onClick = onOpenCommunity,
            )
        }

        if (state.categories.isNotEmpty()) {
            item { SectionLabel("Playlists") }
            items(count = state.categories.size) { index ->
                val category = state.categories[index]
                SectionCard(
                    title = category.name,
                    meta = "${category.beatIds.size} beats",
                    onClick = { onOpenCategory(category.id) },
                )
            }
        }
    }
}

/**
 * Search, unfurling its matches inline beneath the field.
 *
 * Deliberately never leaves the landing page: a search is a question about the
 * whole library, and navigating away to answer it would lose the query the moment
 * the user went back.
 */
@Composable
private fun SearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    allBeats: List<Beat>,
    onOpenDetail: (Beat) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusSectionCard)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaletteToken.HEAD_WORN.color)
            .border(BorderStroke(dimens.hairline, KirtanTheme.colors.rule), shape)
            .padding(dimens.space4),
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        SectionLabel("Search")

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text("Find a beat by name", fontSize = 15.2.sp, color = KirtanTheme.colors.faint)
            },
            leadingIcon = {
                Icon(
                    imageVector = KcIcons.Search,
                    contentDescription = null,
                    tint = PaletteToken.SYAHI_SOFT.color,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    HairlineIconButton(
                        icon = KcIcons.Back,
                        contentDescription = "Clear search",
                        onClick = { onQueryChange("") },
                        size = 28.dp,
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(dimens.radiusPad),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PaletteToken.CLAY.color,
                unfocusedBorderColor = KirtanTheme.colors.rule,
                focusedContainerColor = PaletteToken.HEAD.color,
                unfocusedContainerColor = PaletteToken.HEAD.color,
                focusedTextColor = PaletteToken.SYAHI.color,
                unfocusedTextColor = PaletteToken.SYAHI.color,
                cursorColor = PaletteToken.CLAY.color,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            query.isBlank() -> Hint("Search every beat in your library by name.")

            else -> {
                val matches = remember(query, allBeats) {
                    val needle = query.trim().lowercase()
                    allBeats.filter { it.name.lowercase().contains(needle) }
                }
                if (matches.isEmpty()) {
                    Hint("No beat is named “$query”.")
                } else {
                    matches.forEach { beat -> BeatRow(beat = beat, onClick = { onOpenDetail(beat) }) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = PaletteToken.SYAHI_SOFT.color,
        fontSize = 13.4.sp,
    )
}

/**
 * Which of the three built-in sets is on screen, in one line.
 *
 * Worth saying out loud because "Built in" stopped meaning "compiled into this
 * APK": a user who was told a pattern had been corrected should be able to see
 * whether the app is showing the server's answer, the one it cached on an earlier
 * launch, or the fallback it shipped with.
 */
private fun shippedSourceLine(source: ShippedSource, count: Int): String = when (source) {
    ShippedSource.SERVER -> "Up to date with the server · $count beats"
    ShippedSource.CACHED -> "From the last check · $count beats"
    ShippedSource.COMPILED -> "Ships with the app · $count beats"
}

/**
 * The line under the update button.
 *
 * A manual check's answer stays until the next check: this is a status line, not a
 * toast, and "already up to date" is worth still being there when the user looks
 * back up from the button. An AUTOMATIC check sets no notice at all, so a launch
 * never announces itself.
 */
private fun shippedLine(status: ShippedStatus, canCheck: Boolean): String = when {
    !canCheck -> shippedSourceLine(ShippedSource.COMPILED, status.count)
    status.checking -> "Checking the server…"
    status.notice != null -> status.notice!!
    else -> shippedSourceLine(status.source, status.count)
}

@Composable
private fun SectionCard(title: String, meta: String, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusSectionCard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(PaletteToken.HEAD_WORN.color)
            .border(BorderStroke(dimens.hairline, KirtanTheme.colors.rule), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PaletteToken.SYAHI.color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            // A chevron on the right says "this opens a page", which is how a
            // section card is told apart from a beat row at a glance.
            imageVector = KcIcons.ChevronRight,
            contentDescription = null,
            tint = PaletteToken.SYAHI_SOFT.color,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CategoryPage(
    modifier: Modifier = Modifier,
    categoryId: String,
    categoryName: String,
    beats: List<Beat>,
    library: LibraryViewModel,
    shipped: ShippedStatus,
    canCheckForUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
    onOpenDetail: (Beat) -> Unit,
    onShareList: (String, List<Beat>) -> Unit,
    onPublishList: (String, List<Beat>) -> Unit,
) {
    val dimens = KirtanTheme.dimens

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        // Sharing a progression is what turns it into something a friend can
        // actually play. The built-in set is excluded: everyone already has it,
        // and its link would be enormous for nothing.
        if (categoryId != BUILTIN_CATEGORY && beats.isNotEmpty()) {
            item(key = "share-list") {
                SecondaryButton(
                    label = "Share list",
                    icon = KcIcons.Share,
                    onClick = { onShareList(categoryName, beats) },
                )
            }
            // Publishing uploads a SNAPSHOT: editing the private copy later never
            // rewrites what the community already took, which is the same rule a
            // share link follows.
            item(key = "publish-list") {
                SecondaryButton(
                    label = "Publish to community",
                    icon = KcIcons.Globe,
                    onClick = { onPublishList(categoryName, beats) },
                )
            }
        }

        if (categoryId == BUILTIN_CATEGORY) {
            // The one page that can say where its beats came from. The set is
            // served rather than compiled in, so "Built in" no longer means "in
            // this APK", and a user looking at a beat they were told had been
            // corrected deserves to be able to look again themselves.
            item(key = "beat-updates") {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
                    if (canCheckForUpdates) {
                        SecondaryButton(
                            label = if (shipped.checking) {
                                "Checking…"
                            } else {
                                "Check for beat updates"
                            },
                            icon = KcIcons.Globe,
                            enabled = !shipped.checking,
                            onClick = onCheckForUpdates,
                        )
                    }
                    Hint(shippedLine(shipped, canCheckForUpdates))
                }
            }

            // The built-ins are sub-divided by their `group` heading, in the order
            // the beats first declare them. DERIVED FROM THIS LIST rather than read
            // from a constant: a beat promoted after this APK was built carries a
            // heading that appears in no compiled list, and a heading nobody uses
            // any more must stop rendering as an empty section.
            //
            // Headings need not be contiguous in the data — "Building up" holds both
            // Da Ge Te Te and Double Time with "Gentle" between them — so each
            // section filters the whole list rather than slicing it.
            beats.mapNotNull { it.group }.distinct().forEach { group ->
                val inGroup = beats.filter { it.group == group }
                if (inGroup.isNotEmpty()) {
                    item(key = "heading-$group") { SectionLabel(group) }
                    items(count = inGroup.size, key = { i -> "beat-${inGroup[i].id}" }) { index ->
                        BeatRow(beat = inGroup[index], onClick = { onOpenDetail(inGroup[index]) })
                    }
                }
            }
        } else if (beats.isEmpty()) {
            item { Hint("Nothing here yet.") }
        } else {
            items(count = beats.size, key = { i -> "beat-${beats[i].id}" }) { index ->
                BeatRow(beat = beats[index], onClick = { onOpenDetail(beats[index]) })
            }
        }
    }
}

@Composable
private fun BeatRow(beat: Beat, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPad)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaletteToken.HEAD_WORN.color)
            .border(BorderStroke(dimens.hairline, KirtanTheme.colors.rule), shape)
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
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${beat.note} · ${beat.steps} cells",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
            )
        }
        BeatStrip(beat = beat, mini = true)
    }
}

@Composable
private fun BeatDetailSheet(
    beat: Beat,
    library: LibraryViewModel,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    canEditShipped: Boolean,
    onEditShipped: () -> Unit,
    canRemoveShipped: Boolean,
    onRemoveShipped: () -> Unit,
    onShare: () -> Unit,
    onPublish: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    com.kirtan.companion.ui.components.KcSheet(title = beat.name, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            Text(
                text = "${beat.note} · ${beat.bpm} BPM · ${beat.steps} cells",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                fontWeight = FontWeight.Medium,
            )
            BeatStrip(beat = beat)
            beat.description?.let { description ->
                Text(
                    text = description,
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 13.4.sp,
                    lineHeight = 20.sp,
                )
            }
            PrimaryButton(label = "Play this beat", icon = KcIcons.Play, onClick = onPlay)
            com.kirtan.companion.ui.components.SecondaryButton(
                label = if (beat.isBuiltIn) "Customize" else "Edit",
                icon = KcIcons.Pencil,
                onClick = onEdit,
            )
            if (canEditShipped) {
                // Wording is the safety mechanism here: the button above it edits
                // one person's library and this one edits every install of the app,
                // and the two look identical apart from the label. Same cap icon as
                // Community's promote, so "affects what ships" reads the same way in
                // both places.
                com.kirtan.companion.ui.components.SecondaryButton(
                    label = "Edit for everyone",
                    icon = KcIcons.Cap,
                    onClick = onEditShipped,
                )
            }
            if (canRemoveShipped) {
                // Retiring a beat is the third shipped-set write. Same cap icon as
                // edit-for-everyone and promote, so "affects what ships" reads the
                // same way in all three places. The confirm sheet names the blast
                // radius before the write happens.
                com.kirtan.companion.ui.components.SecondaryButton(
                    label = "Remove from built-ins",
                    icon = KcIcons.Cap,
                    onClick = onRemoveShipped,
                )
            }
            com.kirtan.companion.ui.components.SecondaryButton(
                label = "Share",
                icon = KcIcons.Share,
                onClick = onShare,
            )
            com.kirtan.companion.ui.components.SecondaryButton(
                label = "Publish to community",
                icon = KcIcons.Globe,
                onClick = onPublish,
            )
            if (!beat.isBuiltIn && beat.id != null) {
                com.kirtan.companion.ui.components.SecondaryButton(
                    label = "Delete",
                    onClick = {
                        library.deleteBeat(beat.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * A success strip, shown after an import lands.
 *
 * Separate from [ErrorStrip] rather than reusing it with a different colour: an
 * import writes to the user's library, and saying nothing about it leaves them
 * guessing whether the paste worked. Clay rather than danger, same shape and
 * dismissal as the error case so the two read as one family.
 */
@Composable
private fun ResultStrip(message: String, onDismiss: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPad)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaletteToken.CLAY.color.copy(alpha = 0.10f))
            .border(BorderStroke(dimens.hairline, PaletteToken.CLAY.color), shape)
            .padding(dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PaletteToken.CLAY.color,
            fontSize = 13.4.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(dimens.space2))
        HairlineIconButton(
            icon = KcIcons.Check,
            contentDescription = "Dismiss",
            onClick = onDismiss,
            size = 28.dp,
            tint = PaletteToken.CLAY.color,
        )
    }
}

@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPad)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaletteToken.DANGER.color.copy(alpha = 0.10f))
            .border(BorderStroke(dimens.hairline, PaletteToken.DANGER.color), shape)
            .padding(dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PaletteToken.DANGER.color,
            fontSize = 13.4.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(dimens.space2))
        HairlineIconButton(
            icon = KcIcons.Back,
            contentDescription = "Dismiss",
            onClick = onDismiss,
            size = 28.dp,
            tint = PaletteToken.DANGER.color,
        )
    }
}
