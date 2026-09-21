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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.storage.CommunityClient
import com.kirtan.companion.storage.PublishedItem
import com.kirtan.companion.storage.PublishedKind
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import kotlinx.coroutines.delay

/**
 * The Browse page: what the whole community has published.
 *
 * Reads need no identity, so this page works signed out; copying lands on device
 * storage in that case and in the cloud library once signed in, because both go
 * through the same repository write. Publishing and unpublishing are the two acts
 * that need a session, and their buttons say so when there isn't one.
 */
@Composable
internal fun CommunityScreen(vm: CommunityViewModel, modifier: Modifier = Modifier) {
    val dimens = KirtanTheme.dimens
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val session by vm.session.collectAsState()
    val scope by vm.scope.collectAsState()
    val myRatings by vm.myRatings.collectAsState()
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    // One debounced effect drives every load, including the first: typing or
    // switching scope re-queries after a pause rather than per keystroke, which
    // matters because each query is a network round trip.
    LaunchedEffect(query, scope) {
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
                text = "The community library lives in the cloud, and this build has no " +
                    "cloud configured, so there is nothing to browse.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
            return
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search the community library", fontSize = 13.4.sp) },
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
                label = "Everything",
                selected = scope == CommunityClient.BrowseScope.EVERYTHING,
                onClick = { vm.setScope(CommunityClient.BrowseScope.EVERYTHING) },
            )
            ScopeChip(
                label = "Names",
                selected = scope == CommunityClient.BrowseScope.NAMES,
                onClick = { vm.setScope(CommunityClient.BrowseScope.NAMES) },
            )
            ScopeChip(
                label = "Authors",
                selected = scope == CommunityClient.BrowseScope.AUTHORS,
                onClick = { vm.setScope(CommunityClient.BrowseScope.AUTHORS) },
            )
        }

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
                text = "Loading the community library…",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
            )
        }

        if (!loading && items.isEmpty() && error == null) {
            Text(
                text = if (query.isBlank()) {
                    "Nothing published yet. Be the first: open one of your beats and " +
                        "choose Publish."
                } else {
                    "Nothing in the community library matches “$query”."
                },
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
        }

        items.forEach { item ->
            CommunityCard(
                item = item,
                beats = vm.preview(item),
                mine = session?.userId != null && session?.userId == item.authorId,
                signedIn = session != null,
                myStars = myRatings[item.id],
                onAdd = { vm.add(item) { notice = it } },
                onUnpublish = { vm.unpublish(item) { notice = it } },
                onRate = { stars -> vm.rate(item, stars) { notice = it } },
            )
        }
    }
}

@Composable
private fun CommunityCard(
    item: PublishedItem,
    beats: List<com.kirtan.companion.data.model.Beat>?,
    mine: Boolean,
    signedIn: Boolean,
    myStars: Int?,
    onAdd: () -> Unit,
    onUnpublish: () -> Unit,
    onRate: (Int) -> Unit,
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
                append("by ${item.authorName ?: "A devotee"}")
                append(" · ")
                append(if (item.kind == PublishedKind.PLAYLIST) "list" else "beat")
                if (item.copies > 0) append(" · ${item.copies} ${if (item.copies == 1L) "copy" else "copies"}")
            },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 12.sp,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            // "not rated yet" rather than "0 stars": zero ratings is absence of
            // opinion, and showing 0.0 would read as a verdict.
            val average = item.averageStars
            Text(
                text = if (average == null) {
                    "not rated yet"
                } else {
                    "%.1f ★ (%d)".format(average, item.ratingCount)
                },
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
            )
            if (signedIn) {
                StarRow(selected = myStars, onRate = onRate)
            }
        }

        beats?.firstOrNull()?.let { beat -> BeatStrip(beat = beat, mini = true) }

        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            SecondaryButton(
                label = "Add to library",
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
                onClick = onAdd,
            )
            if (mine) {
                SecondaryButton(
                    label = "Unpublish",
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                    onClick = onUnpublish,
                )
            }
        }

        if (!signedIn) {
            Text(
                text = "Added to this device only. Sign in to keep it in your account " +
                    "across devices.",
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
 * Five tappable stars showing the signed-in user's own rating.
 *
 * Tapping the star you already chose CLEARS it: the natural "take it back", and
 * the server stores the absence as a deleted row rather than a zero, so the
 * community average is never dragged down by a withdrawn opinion.
 */
@Composable
private fun StarRow(selected: Int?, onRate: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            val filled = selected != null && star <= selected
            Icon(
                imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (selected == star) {
                    "Remove your $star-star rating"
                } else {
                    "Rate $star ${if (star == 1) "star" else "stars"}"
                },
                tint = if (filled) PaletteToken.CLAY.color else KirtanTheme.colors.rule,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onRate(star) },
                    ),
            )
        }
    }
}
