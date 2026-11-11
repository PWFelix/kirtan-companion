package com.kirtan.companion.ui.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.storage.CommunityClient
import com.kirtan.companion.storage.PublishedItem
import com.kirtan.companion.storage.PublishedKind
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * The Browse page: what the whole community has published.
 *
 * Reads need no identity, so this page works signed out; copying lands on device
 * storage in that case and in the cloud library once signed in, because both go
 * through the same repository write. Publishing and unpublishing are the two acts
 * that need a session, and their buttons say so when there isn't one.
 */
@Composable
internal fun CommunityScreen(
    vm: CommunityViewModel,
    transport: com.kirtan.companion.ui.transport.TransportViewModel,
    /** The beat Home has loaded; put back into the engine when a preview ends. */
    selectedBeat: com.kirtan.companion.data.model.Beat?,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val session by vm.session.collectAsState()
    val scope by vm.scope.collectAsState()
    val tag by vm.tag.collectAsState()
    val myVotes by vm.myVotes.collectAsState()
    val sort by vm.sort.collectAsState()
    val isMaintainer by vm.isMaintainer.collectAsState()
    val inLibrary by vm.inLibrary.collectAsState()
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    /** The card whose beat is about to become a built-in, while its sheet is open. */
    var promoteTarget by remember { mutableStateOf<PublishedItem?>(null) }

    // ── Preview ────────────────────────────────────────────────────────────
    // Listen before adding: the card's Preview plays its (first) beat through the
    // transport's preview path, the one the editor uses, without saving or
    // selecting anything. Ending it — Stop, another card's Preview, or leaving
    // this page — hands the engine back to Home's beat, so Play there still
    // plays what it shows.
    var previewingId by remember { mutableStateOf<String?>(null) }
    // True once startPreview has returned. Starting over a playing Home beat
    // briefly reports playing=false; that must not count as "stopped elsewhere".
    var previewLive by remember { mutableStateOf(false) }
    val previewScope = androidx.compose.runtime.rememberCoroutineScope()
    val transportState by transport.state.collectAsState()
    fun endPreview() {
        if (previewingId == null) return
        previewingId = null
        previewLive = false
        transport.stopPreview()
        selectedBeat?.let { transport.loadBeat(it) }
    }
    // Something else stopped the sound (the notification, another screen):
    // drop the "Stop" label so the card doesn't lie.
    LaunchedEffect(transportState.playing) {
        if (!transportState.playing && previewLive) endPreview()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { endPreview() }
    }

    // One debounced effect drives every load, including the first: typing or
    // switching scope re-queries after a pause rather than per keystroke, which
    // matters because each query is a network round trip.
    LaunchedEffect(query, scope, tag, sort) {
        delay(300)
        vm.refresh(query)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = dimens.screenMaxWidth)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        if (!vm.available) {
            Text(
                text = stringResource(R.string.community_not_configured),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
            return
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.community_search), fontSize = 13.4.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PaletteToken.CLAY.color,
                unfocusedBorderColor = KirtanTheme.colors.rule,
                focusedContainerColor = PaletteToken.HEAD.color,
                unfocusedContainerColor = PaletteToken.HEAD.color,
                focusedTextColor = PaletteToken.SYAHI.color,
                unfocusedTextColor = PaletteToken.SYAHI.color,
                cursorColor = PaletteToken.CLAY.color,
            ),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            ScopeChip(
                label = stringResource(R.string.community_scope_all),
                selected = scope == CommunityClient.BrowseScope.EVERYTHING,
                onClick = { vm.setScope(CommunityClient.BrowseScope.EVERYTHING) },
            )
            ScopeChip(
                label = stringResource(R.string.community_scope_names),
                selected = scope == CommunityClient.BrowseScope.NAMES,
                onClick = { vm.setScope(CommunityClient.BrowseScope.NAMES) },
            )
            ScopeChip(
                label = stringResource(R.string.community_scope_authors),
                selected = scope == CommunityClient.BrowseScope.AUTHORS,
                onClick = { vm.setScope(CommunityClient.BrowseScope.AUTHORS) },
            )
        }

        // Order: Top (votes, decayed by age), New, Most added.
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            CommunityClient.BrowseSort.entries.forEach { option ->
                ScopeChip(
                    label = stringResource(
                        when (option) {
                            CommunityClient.BrowseSort.TOP -> R.string.community_sort_top
                            CommunityClient.BrowseSort.NEW -> R.string.community_sort_new
                            CommunityClient.BrowseSort.COPIES -> R.string.community_sort_copies
                        },
                    ),
                    selected = sort == option,
                    onClick = { vm.setSort(option) },
                )
            }
        }

        // Every vocabulary tag: the server filters, so what's on this page
        // can't tell us which tags exist out there.
        com.kirtan.companion.ui.components.TagFilterRow(
            tags = com.kirtan.companion.data.TagVocabulary.groups.flatMap { g -> g.tags.map { it.id } },
            selected = tag,
            onSelect = { vm.setTag(it) },
        )

        notice?.let { message ->
            Text(
                text = message,
                color = PaletteToken.CLAY.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
        }

        error?.let { message ->
            Text(
                text = message,
                color = PaletteToken.DANGER.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
        }

        if (loading && items.isEmpty()) {
            Text(
                text = stringResource(R.string.community_loading),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
            )
        }

        if (!loading && items.isEmpty() && error == null) {
            Text(
                text = if (query.isBlank()) {
                    stringResource(R.string.community_empty)
                } else {
                    stringResource(R.string.community_no_match, query)
                },
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
        }

        items.forEach { item ->
            // Decoded ONCE per card and shared by the strip and the promote gate:
            // asking the view model twice would run the same snapshot through the
            // codec twice on every recomposition of the list.
            val preview = vm.preview(item)
            CommunityCard(
                item = item,
                beats = preview,
                mine = session?.userId != null && session?.userId == item.authorId,
                signedIn = session != null,
                voted = item.id in myVotes,
                inLibrary = item.id in inLibrary,
                // Gated on the flag AND on the snapshot decoding to a single beat:
                // a button that opens a sheet and then says "only a single beat can
                // become a built-in" is a button that should not have been there.
                canPromote = isMaintainer &&
                    item.kind == PublishedKind.BEAT &&
                    preview != null,
                onAdd = { vm.add(item) { notice = it } },
                onUnpublish = { vm.unpublish(item) { notice = it } },
                onPromote = { promoteTarget = item },
                onVote = { vm.toggleVote(item) { notice = it } },
                previewing = previewingId == item.id,
                onPreview = preview?.firstOrNull()?.let { beat ->
                    {
                        if (previewingId == item.id) {
                            endPreview()
                        } else {
                            previewingId = item.id
                            previewLive = false
                            previewScope.launch {
                                transport.startPreview(beat, beat.bpm)
                                previewLive = previewingId == item.id
                            }
                        }
                    }
                },
            )
        }
    }

    promoteTarget?.let { item ->
        PromoteSheet(
            item = item,
            onDismiss = { promoteTarget = null },
            onConfirm = { heading ->
                promoteTarget = null
                vm.promote(item, heading) { notice = it }
            },
        )
    }
}

@Composable
private fun CommunityCard(
    item: PublishedItem,
    beats: List<com.kirtan.companion.data.model.Beat>?,
    mine: Boolean,
    signedIn: Boolean,
    voted: Boolean,
    inLibrary: Boolean,
    canPromote: Boolean,
    onAdd: () -> Unit,
    onUnpublish: () -> Unit,
    onPromote: () -> Unit,
    onVote: () -> Unit,
    previewing: Boolean = false,
    /** Null when the snapshot has no playable beat. */
    onPreview: (() -> Unit)? = null,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusSectionCard)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaletteToken.HEAD_WORN.color)
            .border(BorderStroke(dimens.hairline, KirtanTheme.colors.rule), shape)
            .padding(dimens.space3),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = item.name,
            color = PaletteToken.SYAHI.color,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(Strings.get(R.string.community_by, item.authorName ?: Strings.get(R.string.community_a_devotee)))
                append(" · ")
                append(Strings.get(if (item.kind == PublishedKind.PLAYLIST) R.string.community_kind_list else R.string.community_kind_beat))
                if (item.copies > 0) append(" · " + Strings.plural(R.plurals.community_copies, item.copies.toInt(), item.copies))
            },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 12.sp,
        )
        // The author's tags, read straight off the snapshot.
        val tags = (item.payload["beat"] as? kotlinx.serialization.json.JsonObject)?.get("tags")
            .let { it as? kotlinx.serialization.json.JsonArray }
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
        val known = com.kirtan.companion.data.TagVocabulary.known(tags)
        if (known.isNotEmpty()) {
            Text(
                text = known.joinToString(" · ") { Strings.get(it.label) },
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Upvotes: the count for everyone, tappable when signed in. Hidden when
        // the database predates votes (no count to show).
        item.voteCount?.let { count ->
            VoteButton(count = count, voted = voted, enabled = signedIn, onClick = onVote)
        }

        beats?.firstOrNull()?.let { beat -> BeatStrip(beat = beat, mini = true) }

        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            if (onPreview != null) {
                SecondaryButton(
                    label = stringResource(if (previewing) R.string.community_preview_stop else R.string.community_preview),
                    icon = if (previewing) com.kirtan.companion.ui.icons.KcIcons.Pause else com.kirtan.companion.ui.icons.KcIcons.Play,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                    sidePadding = dimens.space3,
                    onClick = onPreview,
                )
            }
            // Disabled AND relabelled once a copy exists: a greyed-out "Add" with
            // no reason looks broken. Deleting the copy brings Add back (#11).
            SecondaryButton(
                label = if (inLibrary) stringResource(R.string.community_in_library) else stringResource(R.string.community_add),
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
                enabled = !inLibrary,
                onClick = onAdd,
            )
            if (mine) {
                SecondaryButton(
                    label = stringResource(R.string.community_unpublish),
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                    onClick = onUnpublish,
                )
            }
        }

        // Its own row rather than a third button beside the other two: this one
        // changes what every installed app ships with, and a control that looks
        // like "Add to library" but does that is a mis-tap waiting to happen.
        if (canPromote) {
            SecondaryButton(
                label = stringResource(R.string.promote_title),
                icon = KcIcons.Cap,
                minHeight = 44.dp,
                onClick = onPromote,
            )
        }

        if (!signedIn) {
            Text(
                text = stringResource(R.string.community_device_only),
                color = KirtanTheme.colors.faint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/** What the search matches against. A row of pills, one selected. */
@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
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
            )
            .padding(horizontal = dimens.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) PaletteToken.ON_CLAY.color else PaletteToken.SYAHI.color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The upvote pill: arrow plus count. Filled clay once you've voted; tapping
 * again takes the vote back. Signed out it still shows the count, untappable.
 */
@Composable
private fun VoteButton(count: Long, voted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    val tint = if (voted) PaletteToken.CLAY.color else PaletteToken.SYAHI_SOFT.color
    val description = if (voted) {
        Strings.plural(R.plurals.community_unvote, count.toInt(), count)
    } else {
        Strings.plural(R.plurals.community_upvote, count.toInt(), count)
    }
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .border(BorderStroke(dimens.hairline, if (voted) PaletteToken.CLAY.color else KirtanTheme.colors.rule), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ArrowUpward,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(text = count.toString(), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The confirm step for making a community beat one of the built-ins.
 *
 * A sheet rather than a single tap because this is the one action in the app whose
 * blast radius is other people's apps: it writes a row every install reads on its
 * next launch. The heading it asks for is the part that cannot be guessed — without
 * it every promoted beat lands in one section, and the Beats screen derives its
 * sections from the set, so a bucket nobody named is a set nobody can browse.
 */
@Composable
private fun PromoteSheet(
    item: PublishedItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    // Keyed on the item so promoting a second beat does not inherit the first
    // one's half-typed heading.
    var heading by remember(item.id) { mutableStateOf(CommunityViewModel.DEFAULT_HEADING) }

    KcSheet(title = stringResource(R.string.promote_title), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            Text(
                text = item.name,
                color = PaletteToken.SYAHI.color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.promote_body),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )

            SectionLabel(stringResource(R.string.promote_heading))
            OutlinedTextField(
                value = heading,
                onValueChange = { heading = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PaletteToken.CLAY.color,
                    unfocusedBorderColor = KirtanTheme.colors.rule,
                    focusedContainerColor = PaletteToken.HEAD.color,
                    unfocusedContainerColor = PaletteToken.HEAD.color,
                    focusedTextColor = PaletteToken.SYAHI.color,
                    unfocusedTextColor = PaletteToken.SYAHI.color,
                    cursorColor = PaletteToken.CLAY.color,
                ),
            )
            Text(
                text = stringResource(R.string.promote_heading_hint),
                color = KirtanTheme.colors.faint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            PrimaryButton(label = stringResource(R.string.promote_confirm), onClick = { onConfirm(heading) })
            SecondaryButton(label = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    }
}
