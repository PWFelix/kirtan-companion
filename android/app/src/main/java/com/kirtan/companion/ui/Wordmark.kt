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
 * strap Devanagari), so it re-tints with any future theme exactly as the JSX
 * does.
 *
 * Structure, top to bottom: कीर्तन in Noto Sans Devanagari at 0.46em in the
 * strap colour, then "Kirtanero" in Fraunces bold in clay.
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
 * text. That is not an accessibility oversight: the two lines are proportioned
 * against each other, and a logotype is a
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
//   "rtanero" run. It
//   belongs with the splash screen, which owns the entrance choreography (the
//   wheel settling in, the two staggered rises, and the reduced-motion opt-out
//   that disables all of it); adding a parameter here that only the splash
//   passes would put half of that timing in this file. Until then the wordmark
//   renders in its final, fully-unfurled form everywhere, including the splash.

/** कीर्तन (kīrtana) — the Devanagari line above the wordmark. */
private const val DevanagariLine = "कीर्तन"

/** The name, in plain letters. */
private const val WordmarkLine = "Kirtanero"

// ── The lockup's em fractions, from `.kc-wordmark` / `.kc-wm-*` in index.css ──
private const val DevaEm = 0.46f
private const val DevaTrackingEm = 0.04f
private const val LineTrackingEm = 0.01f
private const val GapEm = 0.06f

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
