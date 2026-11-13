package com.kirtan.companion.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.R
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.SequenceEntry
import com.kirtan.companion.data.Sequences
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.SequenceState

/**
 * HOME'S SEQUENCER: the running order under the beat display.
 *
 * A header (the sequence's name, loop, open, save) over a row of entry chips —
 * each a beat and its bar count, the playing one lit with its bar — ending in an
 * Add chip. Tapping a chip opens [EntrySheet] for bars, order, beat and removal.
 *
 * Stateless apart from nothing at all: every edit is a callback into the
 * transport, which owns the sequence because it hands it to the engine.
 */
@Composable
internal fun SequencePanel(
    seq: SequenceState,
    lookup: (String) -> Beat?,
    onEntry: (Int) -> Unit,
    onAdd: () -> Unit,
    onToggleLoop: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val w = seq.working
    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.seq_title).uppercase(),
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    // A dot after the name marks unsaved changes, as on the Editor tab.
                    text = w.name.ifEmpty { stringResource(R.string.seq_untitled) } +
                        if (seq.dirty && w.name.isNotEmpty()) " •" else "",
                    color = PaletteToken.SYAHI.color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HairlineIconButton(
                icon = KcIcons.Loop,
                contentDescription = stringResource(if (w.loop) R.string.seq_loop_on else R.string.seq_loop_off),
                onClick = onToggleLoop,
                filled = w.loop,
            )
            HairlineIconButton(
                icon = KcIcons.Folder,
                contentDescription = stringResource(R.string.seq_open),
                onClick = onOpen,
            )
            HairlineIconButton(
                icon = KcIcons.Save,
                contentDescription = stringResource(R.string.seq_save),
                onClick = onSave,
                enabled = w.entries.isNotEmpty(),
            )
        }

        if (w.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.seq_empty),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            w.entries.forEachIndexed { i, entry ->
                val playing = seq.playingEntry == i
                EntryChip(
                    name = lookup(entry.beatId)?.name ?: stringResource(R.string.seq_missing),
                    detail = if (playing && seq.playingBar != null) "${seq.playingBar}/${entry.bars}"
                    else pluralStringResource(R.plurals.seq_bars, entry.bars, entry.bars),
                    a11y = if (playing && seq.playingBar != null)
                        stringResource(R.string.seq_bar_of, seq.playingBar, entry.bars) else null,
                    playing = playing,
                    missing = lookup(entry.beatId) == null,
                    onClick = { onEntry(i) },
                )
            }
            AddChip(onClick = onAdd)
        }
    }
}

@Composable
private fun EntryChip(
    name: String,
    detail: String,
    a11y: String?,
    playing: Boolean,
    missing: Boolean,
    onClick: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 76.dp, max = 150.dp)
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (playing) PaletteToken.CLAY.color else Color.Transparent)
            .border(BorderStroke(dimens.hairline, if (playing) PaletteToken.CLAY.color else KirtanTheme.colors.rule), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.space3, vertical = dimens.space2)
            .let { m -> if (a11y != null) m.semantics { contentDescription = "$name, $a11y" } else m },
    ) {
        Text(
            text = name,
            color = when {
                playing -> PaletteToken.ON_CLAY.color
                missing -> PaletteToken.SYAHI_SOFT.color
                else -> PaletteToken.SYAHI.color
            },
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            color = if (playing) PaletteToken.ON_CLAY.color else PaletteToken.SYAHI_SOFT.color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddChip(onClick: () -> Unit) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .border(BorderStroke(dimens.hairline, KirtanTheme.colors.rule), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(KcIcons.Plus, contentDescription = null, tint = PaletteToken.CLAY.color, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.seq_add),
            color = PaletteToken.CLAY.color,
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** One entry: its bars, its place in the order, its beat, or out. */
@Composable
internal fun EntrySheet(
    entry: SequenceEntry,
    index: Int,
    count: Int,
    beatName: String,
    onBars: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onChangeBeat: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    KcSheet(title = beatName, onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.seq_bars_label),
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.4.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space4, Alignment.CenterHorizontally),
        ) {
            HairlineIconButton(
                icon = KcIcons.Back,
                contentDescription = stringResource(R.string.seq_fewer_bars),
                onClick = { onBars(entry.bars - 1) },
                enabled = entry.bars > Sequences.MIN_BARS,
                size = 48.dp,
                tint = PaletteToken.CLAY.color,
            )
            Text(
                text = entry.bars.toString(),
                color = PaletteToken.SYAHI.color,
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(min = 56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            HairlineIconButton(
                icon = KcIcons.ChevronRight,
                contentDescription = stringResource(R.string.seq_more_bars),
                onClick = { onBars(entry.bars + 1) },
                enabled = entry.bars < Sequences.MAX_BARS,
                size = 48.dp,
                tint = PaletteToken.CLAY.color,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            SecondaryButton(
                label = stringResource(R.string.seq_move_earlier),
                onClick = { onMove(index - 1) },
                enabled = index > 0,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = stringResource(R.string.seq_move_later),
                onClick = { onMove(index + 1) },
                enabled = index < count - 1,
                modifier = Modifier.weight(1f),
            )
        }
        SecondaryButton(label = stringResource(R.string.seq_change_beat), onClick = onChangeBeat)
        SecondaryButton(label = stringResource(R.string.seq_remove), icon = KcIcons.Trash, onClick = onRemove)
    }
}

/** Name it and save: over the saved copy it came from, or as a new one. */
@Composable
internal fun SaveSequenceSheet(
    initialName: String,
    canOverwrite: Boolean,
    onSave: (name: String, asNew: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    // Straight to typing: naming is the whole job of this sheet.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    KcSheet(title = stringResource(R.string.seq_save), onDismiss = onDismiss) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            singleLine = true,
            label = { Text(stringResource(R.string.seq_name_hint)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        val ok = name.isNotBlank()
        if (canOverwrite) {
            PrimaryButton(label = stringResource(R.string.seq_save_changes), enabled = ok, onClick = { onSave(name, false) })
            SecondaryButton(label = stringResource(R.string.seq_save_as_new), enabled = ok, onClick = { onSave(name, true) })
        } else {
            PrimaryButton(label = stringResource(R.string.seq_save), enabled = ok, onClick = { onSave(name, true) })
        }
    }
}

/** The saved sequences: open one, delete one, or start a new one. */
@Composable
internal fun OpenSequenceSheet(
    seq: SequenceState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    KcSheet(title = stringResource(R.string.seq_saved_title), onDismiss = onDismiss) {
        if (seq.dirty) {
            Text(
                text = stringResource(R.string.seq_replace_warning),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
            )
        }
        if (seq.saved.isEmpty()) {
            Text(
                text = stringResource(R.string.seq_none_saved),
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                modifier = Modifier.padding(vertical = dimens.space2),
            )
        }
        seq.saved.forEach { item ->
            val current = item.id == seq.working.savedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(item.id) }
                    .padding(vertical = dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = if (current) PaletteToken.CLAY.color else PaletteToken.SYAHI.color,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.seq_beats, item.entries.size, item.entries.size) + " · " +
                            pluralStringResource(R.plurals.seq_bars, Sequences.totalBars(item.entries), Sequences.totalBars(item.entries)),
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 12.sp,
                    )
                }
                HairlineIconButton(
                    icon = KcIcons.Trash,
                    contentDescription = stringResource(R.string.common_delete) + " " + item.name,
                    onClick = { onDelete(item.id) },
                )
            }
        }
        SecondaryButton(label = stringResource(R.string.seq_new), icon = KcIcons.Plus, onClick = onNew)
    }
}

/** Keep the VM's beat lookup current with the library. */
@Composable
internal fun BindBeatLookup(beats: List<Beat>, bind: ((String) -> Beat?) -> Unit) {
    LaunchedEffect(beats) {
        val byId = beats.mapNotNull { b -> b.id?.let { it to b } }.toMap()
        bind { byId[it] }
    }
}
