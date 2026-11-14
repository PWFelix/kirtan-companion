package com.kirtan.companion.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.R
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.theme.color

/*
 * A rhythm's notes: the author's prose about it — what it is, where it comes
 * from, when to play it, how, with which fingers. Stored as the beat's
 * `description` (the field the built-in beats always carried), so it saves,
 * syncs and publishes with the beat; share LINKS leave it out to stay short.
 * Written in the editor, read from the playing (Home) and Learn screens.
 */

/** Notes are prose, not an essay: long enough for history and fingering, short enough for a payload. */
const val MAX_NOTES = 4000

/** The editor's row: "Notes: first line…" and Edit, like the tag line above it. */
@Composable
internal fun NotesLine(notes: String, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KirtanTheme.dimens.space2)) {
        Text(
            text = stringResource(R.string.notes_title) + ": " +
                notes.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }.let { it ?: stringResource(R.string.tags_none) },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PillChip(stringResource(R.string.common_edit), false, onClick = onEdit)
    }
}

/** Writing the notes. Done hands back the text; dismissing keeps the old. */
@Composable
internal fun NotesEditorSheet(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    KcSheet(title = stringResource(R.string.notes_title), onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.notes_hint),
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.4.sp,
            lineHeight = 19.sp,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(MAX_NOTES) },
            cursorBrush = SolidColor(PaletteToken.CLAY.color),
            textStyle = TextStyle(color = PaletteToken.SYAHI.color, fontSize = 15.sp, lineHeight = 22.sp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 360.dp)
                .border(1.dp, PaletteToken.SYAHI_SOFT.color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
        PrimaryButton(label = stringResource(R.string.common_done), onClick = { onDone(text.trim()) })
    }
}

/** The link under a beat's name, shown only when it has notes. */
@Composable
internal fun NotesLink(notes: String?, onOpen: () -> Unit) {
    if (notes.isNullOrBlank()) return
    Text(
        text = stringResource(R.string.notes_open),
        color = PaletteToken.CLAY.color,
        fontSize = 13.4.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp, horizontal = 8.dp),
    )
}

/** Reading the notes. */
@Composable
internal fun NotesSheet(title: String, notes: String, onDismiss: () -> Unit) {
    KcSheet(title = title, onDismiss = onDismiss) {
        Box(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(text = notes, color = PaletteToken.SYAHI.color, fontSize = 15.sp, lineHeight = 23.sp)
        }
    }
}
