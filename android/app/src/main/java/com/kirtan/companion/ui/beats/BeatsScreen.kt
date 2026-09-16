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
import com.kirtan.companion.data.BUILT_IN_GROUPS
import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.storage.BUILTIN_CATEGORY
import com.kirtan.companion.storage.CUSTOM_CATEGORY
import com.kirtan.companion.storage.LibraryRepository
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.ScreenFrame
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.library.LibraryViewModel
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel

/** Where the Beats screen has drilled to. Dies with the screen, as on the web. */
private sealed interface BeatsPage {
    data object Landing : BeatsPage
    data class Category(val id: String) : BeatsPage
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
    modifier: Modifier = Modifier,
) {
    val state by library.state.collectAsState()
    val allBeats by library.allBeats.collectAsState()
    val transportState by transport.state.collectAsState()
    var page by remember { mutableStateOf<BeatsPage>(BeatsPage.Landing) }
    var search by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Beat?>(null) }

    ScreenFrame(modifier = modifier) {
        // The sub-header: a back button on drilled-in pages, a spacer on the
        // landing so the title stays centred in both.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page is BeatsPage.Category) {
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

        when (val p = page) {
            BeatsPage.Landing -> LandingPage(
                modifier = Modifier.fillMaxWidth().weight(1f),
                search = search,
                onSearchChange = { search = it },
                library = library,
                state = state,
                allBeats = allBeats,
                onOpenCategory = { page = BeatsPage.Category(it) },
                onOpenDetail = { detail = it },
            )

            is BeatsPage.Category -> CategoryPage(
                modifier = Modifier.fillMaxWidth().weight(1f),
                categoryId = p.id,
                library = library,
                onOpenDetail = { detail = it },
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
    onOpenCategory: (String) -> Unit,
    onOpenDetail: (Beat) -> Unit,
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
                meta = "Ships with the app · ${BEATS.size} beats",
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
    library: LibraryViewModel,
    onOpenDetail: (Beat) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val beats = remember(categoryId) { library.categoryBeats(categoryId) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        if (categoryId == BUILTIN_CATEGORY) {
            // The built-ins are sub-divided by their `group` heading, in the order
            // the beats first declare them. Headings need not be contiguous in the
            // data — "Building up" holds both Da Ge Te Te and Double Time with
            // "Gentle" between them — so each section filters the whole list rather
            // than slicing it.
            BUILT_IN_GROUPS.forEach { group ->
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
