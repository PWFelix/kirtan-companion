package com.kirtan.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.R
import com.kirtan.companion.data.TagVocabulary
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.Strings
import com.kirtan.companion.data.PaletteToken

/*
 * Tags in the UI: one pill, the picker sheet, the summary line with its Edit
 * button, and the filter row. The vocabulary is fixed (see TagVocabulary), so
 * the picker is a set of toggles, never a text field.
 */

/** A selectable pill, the shape every tag and filter uses. */
@Composable
internal fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) PaletteToken.CLAY.color else Color.Transparent)
            .border(BorderStroke(dimens.hairline, if (selected) PaletteToken.CLAY.color else KirtanTheme.colors.rule), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.35f)
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

/** Pick tags from the vocabulary, grouped. Unknown ids already set are kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagPickerSheet(
    title: String,
    hint: String?,
    selected: List<String>,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    var picked by remember { mutableStateOf(selected.toSet()) }
    KcSheet(title = title, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            hint?.let { Text(text = it, color = PaletteToken.SYAHI_SOFT.color, fontSize = 13.4.sp) }
            for (group in TagVocabulary.groups) {
                Text(
                    text = stringResource(group.label),
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    for (tag in group.tags) {
                        val on = tag.id in picked
                        PillChip(stringResource(tag.label), on, onClick = {
                            picked = if (on) picked - tag.id else picked + tag.id
                        })
                    }
                }
            }
            PrimaryButton(
                label = stringResource(R.string.common_done),
                icon = KcIcons.Check,
                // Vocabulary order for the known ones, then any this build doesn't know.
                onClick = { onDone(TagVocabulary.known(picked).map { it.id } + picked.filter { TagVocabulary.tag(it) == null }) },
            )
        }
    }
}

/** "Tags: Keherwa · Kirtan" with an Edit pill; [onEdit] null makes it read-only. */
@Composable
internal fun TagLine(label: String, tags: List<String>, onEdit: (() -> Unit)?) {
    val known = TagVocabulary.known(tags)
    if (known.isEmpty() && onEdit == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KirtanTheme.dimens.space2)) {
        Text(
            text = label + ": " + if (known.isEmpty()) stringResource(R.string.tags_none) else known.joinToString(" · ") { Strings.get(it.label) },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.4.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        onEdit?.let { PillChip(stringResource(R.string.common_edit), false, onClick = it) }
    }
}

/**
 * One-tap tag filter: "All" plus [tags] (vocabulary order). Scrolls sideways
 * rather than wrapping so it stays one line above the list.
 */
@Composable
internal fun TagFilterRow(tags: Collection<String>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val known = TagVocabulary.known(tags)
    if (known.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KirtanTheme.dimens.space2),
    ) {
        PillChip(stringResource(R.string.tags_all), selected == null, onClick = { onSelect(null) })
        for (tag in known) {
            PillChip(stringResource(tag.label), selected == tag.id, onClick = { onSelect(if (selected == tag.id) null else tag.id) })
        }
    }
}
