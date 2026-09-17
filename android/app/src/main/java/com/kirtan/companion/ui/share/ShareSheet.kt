package com.kirtan.companion.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.kirtan.companion.BuildConfig
import com.kirtan.companion.data.BeatSourceExport
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import kotlinx.coroutines.launch

/** What is being shared out: one beat, or a whole progression. */
internal sealed interface ShareTarget {
    data class BeatTarget(val beat: Beat) : ShareTarget
    data class CategoryTarget(val name: String, val beats: List<Beat>) : ShareTarget

    val title: String
        get() = when (this) {
            is BeatTarget -> beat.name
            is CategoryTarget -> name
        }
}

/**
 * Where an outbound share link points.
 *
 * The WEB origin, not a deep link into this app. A recipient almost certainly has
 * the site rather than this app, and a `kirtan://` URL would simply fail to open
 * for them — the web app derives its links from `location.origin`, which is why
 * nothing in the repo records the URL and it is configured through
 * `SHARE_WEB_BASE` instead. With no origin configured, the native scheme is the
 * honest fallback: the link at least works between two installs of this app.
 */
internal object ShareLink {
    val webBase: String get() = BuildConfig.SHARE_WEB_BASE.trim().trimEnd('/')

    fun forCode(code: String): String =
        if (webBase.isNotEmpty()) "$webBase/#b=$code" else "kirtan://beat?c=$code"

    /** True when outbound links open the site; false when they only open this app. */
    val pointsAtWeb: Boolean get() = webBase.isNotEmpty()
}

/**
 * The outbound share sheet.
 *
 * Ported from the share sheet in `src/views/BeatsView.jsx`, which offers the same
 * payload three ways — a native share, a copyable link, and a copyable bare code —
 * because the code is what survives a messenger that mangles URLs, and the link is
 * what survives a recipient who has no app installed.
 *
 * Plus one thing the web has no equivalent of: **Export source**, which is how a
 * corrected beat gets back into the compiled-in set. See [ExportSheet].
 */
@Composable
internal fun ShareSheet(
    target: ShareTarget,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val dimens = KirtanTheme.dimens

    val code = remember(target) {
        when (target) {
            is ShareTarget.BeatTarget -> ShareCodec.encodeBeat(target.beat)
            is ShareTarget.CategoryTarget ->
                ShareCodec.encodeCategory(target.name, target.beats)
        }
    }
    val link = remember(code) { ShareLink.forCode(code) }
    var copied by remember { mutableStateOf<String?>(null) }
    var exportOpen by remember { mutableStateOf(false) }

    // Declared AFTER the state it writes: a local function can only capture
    // variables declared above it. The modern clipboard API is suspend and takes
    // a ClipData, so copies go through a scope and a plain-text clip.
    fun copy(text: String, label: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
        }
        copied = label
    }

    KcSheet(title = "Share ${target.title}", onDismiss = onDismiss) {
        Text(
            text = when (target) {
                is ShareTarget.BeatTarget ->
                    "The whole beat travels inside the link. Nothing is uploaded."
                is ShareTarget.CategoryTarget ->
                    "All ${target.beats.size} beats in this list travel inside the link. Nothing is uploaded."
            },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.4.sp,
            lineHeight = 19.sp,
        )

        if (!ShareLink.pointsAtWeb) {
            Text(
                text = "No web origin is configured, so this link opens the Android app " +
                    "rather than the site. Set SHARE_WEB_BASE at build time to share a link " +
                    "anyone can open in a browser.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        SelectionContainer {
            Text(
                text = link,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.radiusPad))
                    .background(PaletteToken.HEAD_SUNKEN.color)
                    .padding(dimens.space3)
                    .horizontalScroll(rememberScrollState()),
                color = PaletteToken.SYAHI.color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            SecondaryButton(
                label = "Copy link",
                onClick = { copy(link, "Link copied") },
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            SecondaryButton(
                label = "Copy code",
                onClick = { copy(code, "Code copied") },
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
        }

        PrimaryButton(
            label = "Share…",
            icon = KcIcons.Share,
            onClick = { nativeShare(context, target.title, link) },
        )

        copied?.let { message ->
            Text(
                text = message,
                color = PaletteToken.CLAY.color,
                fontSize = 12.sp,
            )
        }

        // Only a single beat can replace a baked-in entry; a list has no id.
        if (target is ShareTarget.BeatTarget) {
            SecondaryButton(
                label = "Export source…",
                onClick = { exportOpen = true },
                minHeight = 44.dp,
            )
        }
    }

    if (exportOpen && target is ShareTarget.BeatTarget) {
        ExportSheet(beat = target.beat, onDismiss = { exportOpen = false })
    }
}

/**
 * Hands the link to the system share sheet.
 *
 * `ACTION_SEND` with `text/plain` rather than a chooser we build ourselves: every
 * messaging app on the device already handles it, and inventing our own target
 * list would be a worse version of the platform's.
 */
private fun nativeShare(context: Context, subject: String, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, link)
    }
    val chooser = Intent.createChooser(send, "Share beat").apply {
        // Started from a Compose context that may not be an Activity.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/**
 * The maintainer export: a beat as ready-to-paste SOURCE, in both languages.
 *
 * This is not a share. [ShareCodec] strips `id`, `group` and `description`
 * because it is a trust boundary — an id from a stranger's link could silently
 * overwrite one of your beats — and those three fields are exactly what replacing
 * a baked-in entry needs. So the export keeps them, and is only reachable from a
 * beat the user already has, never from an inbound link.
 *
 * The workflow it serves: open a built-in beat in the editor, correct the cells,
 * export, and paste over the entry in BOTH `data/Beats.kt` and
 * `src/data/beats.js`. Both files must change together, because a shared link
 * carries no id and each client resolves built-in ids against its own compiled
 * list — divergent patterns would make the same link play a different beat on
 * each platform.
 */
@Composable
private fun ExportSheet(beat: Beat, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val kotlinSource = remember(beat) { BeatSourceExport.toKotlinSource(beat) }
    val jsSource = remember(beat) { BeatSourceExport.toJsSource(beat) }

    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Kirtan beat source", text)))
        }
    }

    KcSheet(title = "Export ${beat.name}", onDismiss = onDismiss) {
        Text(
            text = "Source for replacing the baked-in beat. Paste into BOTH files so the " +
                "two platforms stay identical — see the note in BeatSourceExport.",
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        SourceBlock(
            label = "android/…/data/Beats.kt",
            source = kotlinSource,
            onCopy = { copy(kotlinSource) },
        )
        SourceBlock(
            label = "src/data/beats.js",
            source = jsSource,
            onCopy = { copy(jsSource) },
        )
    }
}

@Composable
private fun SourceBlock(label: String, source: String, onCopy: () -> Unit) {
    val dimens = KirtanTheme.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SectionLabel(label, modifier = Modifier.weight(1f))
            HairlineIconButton(
                icon = KcIcons.Share,
                contentDescription = "Copy $label",
                onClick = onCopy,
                size = 36.dp,
            )
        }
        SelectionContainer {
            Text(
                text = source,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(dimens.radiusPad))
                    .background(PaletteToken.HEAD_SUNKEN.color)
                    .padding(dimens.space3)
                    // Vertical, not horizontal: the source must WRAP to the sheet's
                    // width. A horizontal scroll would give the text unbounded
                    // width, so every line would run off the edge instead.
                    .verticalScroll(rememberScrollState()),
                color = PaletteToken.SYAHI.color,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
