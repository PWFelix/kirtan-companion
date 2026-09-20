package com.kirtan.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kirtan.companion.R
import com.kirtan.companion.ui.theme.Clay
import com.kirtan.companion.ui.theme.NotoSansDevanagari
import com.kirtan.companion.ui.theme.Strap
import com.kirtan.companion.ui.theme.fraunces

/**
 * The wordmark — the live "Kirtan Companion" lockup.
 *
 * Ported from `src/Wordmark.jsx`, which replaced a static SVG file for a reason
 * that carries straight across: a rasterised or pre-baked logo cannot use the
 * app's real fonts, and the old file always rendered in fallbacks. This is live
 * text in the bundled Fraunces, coloured from the design tokens (clay wordmark,
 * strap Devanagari and tilak), so it re-tints with any future theme exactly as
 * the JSX does.
 *
 * Structure, top to bottom: कीर्तन in Noto Sans Devanagari at 0.46em in the
 * strap colour, then the wordmark in Fraunces bold in clay, in which the
 * Vaishnava TILAK stands in as the "i" of "Companion" — the same trick as the
 * original logo. `Kirtan Compan` + tilak + `on`, with the tilak set as inline
 * content so it sits on the text baseline where the dot and stem of the "i"
 * would be.
 *
 * **Everything is sized from the single [size] parameter**, which is the CSS
 * `--wm-size`: 1em = the wordmark line, and every other measurement is a
 * fraction of it (`0.46em`, `0.74em`, `0.06em`, `0.015em`) exactly as in
 * `.kc-wm-*`. Callers pick one number and the whole lockup follows.
 *
 * That also settles a question the web never had to ask: **the wordmark does
 * not scale with the user's font-size setting.** [size] is a [Dp], and the text
 * sizes are converted through `Density.toSp()`, which divides by the font scale
 * — so a 44dp wordmark renders at 44dp whether the device is at 100% or 200%
 * text. That is not an accessibility oversight: the tilak is drawn to sit at a
 * fixed fraction of the line, and if the text grew while the tilak did not the
 * mark would drift off the "i" and the lockup would come apart. A logotype is a
 * graphic, and the web sizes it in px (`clamp(30px, 8.5vw, 44px)`) for the same
 * reason. Body text elsewhere in the app uses `sp` as normal.
 *
 * The lockup is announced to TalkBack as a single image labelled with the app
 * name, matching the JSX's `role="img" aria-label="Kirtan Companion"` with both
 * inner lines `aria-hidden` — reading "कीर्तन" and then "Kirtan Compan on"
 * would be noise, and neither is meaningful without the other.
 */
@Composable
internal fun Wordmark(size: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val label = stringResource(R.string.app_name)

    // 1em, in sp that renders at exactly `size` dp (see the class KDoc).
    val em = with(density) { size.toSp() }
    val devaSize = with(density) { (size * DevaEm).toSp() }

    // Both styles are built once per size. The Fraunces one carries a
    // FontFamily keyed on (weight, optical size) — see `theme/Type.kt` — and
    // rebuilding that per recomposition is churn for nothing.
    val devaStyle = remember(devaSize) { devanagari(devaSize) }
    val lineStyle = remember(em) { wordmarkLine(em) }

    // The tilak keeps the SVG's own 12:42 aspect, so `advance` is its width
    // plus the 0.015em margin the CSS puts on either side of it.
    val tilakHeight = size * TilakHeightEm
    val tilakWidth = tilakHeight * (TilakViewWidth / TilakViewHeight)
    val tilakMargin = size * TilakMarginEm
    val advance = with(density) { (tilakWidth + tilakMargin * 2).toSp() }
    val tilakHeightSp = with(density) { tilakHeight.toSp() }

    val tilak = InlineTextContent(
        Placeholder(
            width = advance,
            height = tilakHeightSp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        ),
    ) {
        // The margin, which an InlineTextContent has no way to express: the
        // placeholder's width IS the advance, so the drawing is inset inside it
        // and the space falls out on both sides.
        Box(Modifier.fillMaxSize().padding(horizontal = tilakMargin)) {
            Image(
                painter = rememberVectorPainter(Tilak),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Strap),
            )
        }
    }

    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            role = Role.Image
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * GapEm),
    ) {
        Text(
            text = DevanagariLine,
            style = devaStyle,
            color = Strap,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = WordmarkLine,
            style = lineStyle,
            color = Clay,
            inlineContent = mapOf(TilakSlot to tilak),
            maxLines = 1,
            softWrap = false,
        )
    }
}

// TODO(splash): the "KC unfurls into the full name" animation is deliberately
//   NOT here. `Wordmark.jsx` takes an `expandFromInitials` prop used only by
//   the splash: the lockup rises as "KC", holds a beat, then the letters after
//   the K and the C unfurl while the flex centring slides them apart
//   symmetrically, the strap line fading in alongside (`.kc-wm-anim` and the
//   `kc-wm-grow` / `kc-wm-fade` keyframes in index.css). Implementing it means
//   animating the width of two collapsing groups — in Compose, an
//   `Animatable` driving `Modifier.widthIn`/`clip` on each of the "irtan " and
//   "ompan…on" runs, with the tilak's advance growing with the second one. It
//   belongs with the splash screen, which owns the entrance choreography (the
//   wheel settling in, the two staggered rises, and the reduced-motion opt-out
//   that disables all of it); adding a parameter here that only the splash
//   passes would put half of that timing in this file. Until then the wordmark
//   renders in its final, fully-unfurled form everywhere, including the splash.

/** कीर्तन — the Devanagari line above the wordmark. */
private const val DevanagariLine = "कीर्तन"

/** The inline-content slot the tilak is registered under. */
private const val TilakSlot = "tilak"

/**
 * `Kirtan&nbsp;Compan` + tilak + `on`. The non-breaking space is the JSX's
 * `&nbsp;`: the two words must never be split across lines, and `maxLines = 1`
 * above makes that belt and braces rather than the only defence.
 */
private val WordmarkLine = buildAnnotatedString {
    append("Kirtan\u00A0Compan")
    appendInlineContent(TilakSlot)
    append("on")
}

// ── The lockup's em fractions, from `.kc-wordmark` / `.kc-wm-*` in index.css ──
private const val DevaEm = 0.46f
private const val DevaTrackingEm = 0.04f
private const val LineTrackingEm = 0.01f
private const val GapEm = 0.06f
private const val TilakHeightEm = 0.74f
private const val TilakMarginEm = 0.015f

// ── The tilak SVG's own box (viewBox "0 0 12 42") ──
private const val TilakViewWidth = 12f
private const val TilakViewHeight = 42f
private const val TilakStrokeWidth = 3f

/**
 * `line-height: 1` for both lines, without clipping.
 *
 * `.kc-wordmark` sets it and the two child divs inherit it, so each line box is
 * exactly its own font size — that is what makes the 0.06em `gap` the only
 * space between them, and Devanagari's natural leading is far too loose to rely
 * on. Compose would normally TRIM a line that tight and cut off कीर्तन's matras
 * above the headline, which a browser never does to inline content, so the trim
 * is switched off and the box centred on the glyphs instead.
 */
private val TightLeading = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * `.kc-wm-deva` — Noto Sans Devanagari 500 at 0.46em, tracked 0.04em.
 *
 * The family is variable (`wdth`/`wght`) and 500 is not its default instance,
 * so the axis is pinned on the font resource in `theme/Type.kt` rather than
 * left to the matcher.
 */
private fun devanagari(size: TextUnit): TextStyle = TextStyle(
    fontFamily = NotoSansDevanagari,
    fontSize = size,
    fontWeight = FontWeight.Medium,
    letterSpacing = DevaTrackingEm.em,
    lineHeight = size,
    lineHeightStyle = TightLeading,
)

/**
 * `.kc-wm-line` — Fraunces 700, tracked 0.01em, in clay.
 *
 * Built through the theme's `fraunces()` rather than as a bare `TextStyle` so
 * that the wordmark's own size drives Fraunces' `'opsz'` axis — at 44dp the
 * browser would have asked for a large optical size, and this reproduces that.
 */
private fun wordmarkLine(em: TextUnit): TextStyle = fraunces(
    size = em,
    weight = FontWeight.Bold,
    letterSpacing = LineTrackingEm.em,
    lineHeight = em,
    lineHeightStyle = TightLeading,
)

/**
 * The tilak, on its own 12×42 viewBox, drawn in black and tinted strap by the
 * caller — the SVG strokes and fills `currentColor` and inherits `--strap` from
 * `.kc-wm-tilak`, and a tint reproduces that without baking the colour in.
 *
 * Two paths, exactly as in `Wordmark.jsx`: the U-shaped forehead stroke
 * (stroked at 3, round cap, no fill) and the filled teardrop below it. Neither
 * has been converted into the other — the stroke stays a stroke.
 */
private val Tilak: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Tilak",
        defaultWidth = TilakViewWidth.dp,
        defaultHeight = TilakViewHeight.dp,
        viewportWidth = TilakViewWidth,
        viewportHeight = TilakViewHeight,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = TilakStrokeWidth,
            strokeLineCap = StrokeCap.Round,
        ) {
            // M 1.5 2 L 1.5 19 A 4.5 4.5 0 0 0 10.5 19 L 10.5 2
            moveTo(1.5f, 2f)
            lineTo(1.5f, 19f)
            arcTo(4.5f, 4.5f, 0f, false, false, 10.5f, 19f)
            lineTo(10.5f, 2f)
        }
        path(fill = SolidColor(Color.Black)) {
            // M 3 26 A 3 3 0 0 1 9 26 Q 8 32 6 36 Q 4 32 3 26 Z
            moveTo(3f, 26f)
            arcTo(3f, 3f, 0f, false, true, 9f, 26f)
            quadTo(8f, 32f, 6f, 36f)
            quadTo(4f, 32f, 3f, 26f)
            close()
        }
    }.build()
}
