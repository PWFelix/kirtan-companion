package com.kirtan.companion.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.strip.BeatStrip
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color

/**
 * The inbound path: paste a code or link, decode it, then CONFIRM before anything
 * is written.
 *
 * Ported from the import card and confirmation sheet in `src/views/BeatsView.jsx`.
 * The two-step shape is load-bearing rather than ceremony — this is the app's
 * first untrusted input, and [ShareCodec] is the trust boundary that decides what
 * a stranger's payload may become. Showing the decoded beat with its own strip
 * before the user commits means the confirmation is about something they can
 * actually see, not about a blob of base64.
 *
 * The confirm step lives in [ImportConfirmSheet], separate from decoding, so a
 * payload that arrived through a DEEP LINK (handled in MainActivity) reuses the
 * exact same preview rather than being written straight to the library.
 */
@Composable
internal fun ImportSheet(
    onDismiss: () -> Unit,
    onDecoded: (ShareCodec.SharePayload) -> Unit,
) {
    val dimens = KirtanTheme.dimens
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    KcSheet(title = "Import", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            Text(
                text = "Paste a share code or link. A whole link works — the code is " +
                    "read out of it either way.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                placeholder = {
                    Text(
                        text = "Paste a share code or link",
                        fontSize = 13.4.sp,
                        color = KirtanTheme.colors.faint,
                    )
                },
                minLines = 2,
                maxLines = 4,
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

            error?.let { message ->
                Text(
                    text = message,
                    color = PaletteToken.DANGER.color,
                    fontSize = 13.4.sp,
                )
            }

            PrimaryButton(
                label = "Preview",
                enabled = input.isNotBlank(),
                onClick = {
                    val payload = ShareCodec.decodeShare(ShareCodec.codeFromInput(input))
                    if (payload == null) {
                        error = "That is not a Kirtan share code or link. Nothing was imported."
                    } else {
                        onDecoded(payload)
                    }
                },
            )

            Text(
                text = "An imported beat is always saved under a NEW id, so it can never " +
                    "overwrite one of yours — a single beat lands in Your beats, a list " +
                    "arrives as its own playlist.",
                color = KirtanTheme.colors.faint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/**
 * The last stop before anything is written.
 *
 * One sheet, three shapes: a beat, a list, or a payload that decoded to nothing.
 * The invalid case is kept here rather than being dropped silently so the user
 * gets "can't open that link" instead of a button that appears to do nothing.
 */
@Composable
internal fun ImportConfirmSheet(
    payload: ShareCodec.SharePayload,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
) {
    val dimens = KirtanTheme.dimens

    when (payload) {
        ShareCodec.SharePayload.Invalid -> KcSheet(title = "Can't open that link", onDismiss = onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
                Text(
                    text = "The code was damaged, truncated, or made by a newer version of " +
                        "the app. Nothing was imported.",
                    color = PaletteToken.SYAHI_SOFT.color,
                    fontSize = 13.4.sp,
                    lineHeight = 19.sp,
                )
                PrimaryButton(label = "Close", onClick = onDismiss)
            }
        }

        is ShareCodec.SharePayload.BeatPayload -> {
            val beat = payload.beat
            KcSheet(title = "Someone shared this", onDismiss = onDismiss) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
                    Text(
                        text = beat.name,
                        color = PaletteToken.SYAHI.color,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${beat.steps} cells · suggested ${beat.bpm} BPM",
                        color = PaletteToken.SYAHI_SOFT.color,
                        fontSize = 13.4.sp,
                    )
                    BeatStrip(beat = beat)
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                        SecondaryButton(
                            label = "Cancel",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            minHeight = 46.dp,
                        )
                        PrimaryButton(
                            label = "Add beat",
                            onClick = onAccept,
                            modifier = Modifier.weight(1f),
                            minHeight = 46.dp,
                        )
                    }
                }
            }
        }

        is ShareCodec.SharePayload.CategoryPayload -> {
            KcSheet(title = "Someone shared a list", onDismiss = onDismiss) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
                    Text(
                        text = "Add ${payload.beats.size} " +
                            (if (payload.beats.size == 1) "beat" else "beats") +
                            " and the list “${payload.name}”?",
                        color = PaletteToken.SYAHI.color,
                        fontSize = 15.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 21.sp,
                    )
                    payload.beats.forEach { beat ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(dimens.space2),
                        ) {
                            Text(
                                text = beat.name,
                                color = PaletteToken.SYAHI.color,
                                fontSize = 13.4.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BeatStrip(beat = beat, mini = true)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                        SecondaryButton(
                            label = "Cancel",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            minHeight = 46.dp,
                        )
                        PrimaryButton(
                            label = "Add all",
                            onClick = onAccept,
                            modifier = Modifier.weight(1f),
                            minHeight = 46.dp,
                        )
                    }
                }
            }
        }
    }
}
