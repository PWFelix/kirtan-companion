package com.kirtan.companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.kirtan.companion.R

/**
 * The type scale.
 *
 * Ported from the `--font-*` and `--text-*` tokens in `src/index.css` `:root`.
 * Two families do all the work, and the split is the design:
 *
 *  - **Fraunces** (`--font-display` and `--font-numeric` — the same face for
 *    both) is signage. Slightly wonky and hand-printed, it carries the beat
 *    names, the screen titles and the big BPM numerals.
 *  - **Hind Madurai** (`--font-body`) is the Indian Type Foundry Tamil-companion
 *    sans that carries every label, button and piece of meta text.
 *
 * A third family, **Noto Sans Devanagari**, is used by exactly one thing: the
 * कीर्तन line of the wordmark (see `ui/Wordmark.kt`).
 *
 * Sizes are the CSS `rem` values × 16 (the browser's root font size), carried
 * into `sp` unchanged: 1.625rem → 26sp, 0.78rem → 12.5sp, and so on. They are
 * small for a phone, deliberately — this is a dense instrument panel meant to
 * be read at arm's length while playing, and `bodyXs` documents the floor.
 */

/**
 * Hind Madurai, as bundled: STATIC per-weight TTFs, one per weight the CSS
 * imports (`wght@400;500;600;700`). Declaring each weight against its own file
 * is what lets `fontWeight = FontWeight.Bold` resolve to the real Bold cut
 * instead of a synthesised one.
 */
internal val HindMadurai = FontFamily(
    Font(R.font.hind_madurai_regular, FontWeight.Normal),
    Font(R.font.hind_madurai_medium, FontWeight.Medium),
    Font(R.font.hind_madurai_semibold, FontWeight.SemiBold),
    Font(R.font.hind_madurai_bold, FontWeight.Bold),
)

/**
 * Noto Sans Devanagari, as bundled: the VARIABLE font — `google/fonts` ships no
 * static instances of it. Its two axes default to `wght` 400 and `wdth` 100, so
 * the width needs nothing (the CSS never sets `font-stretch` either) but the
 * wordmark's 500 does. Hence the pinned entries below; see [frauncesFamily] for
 * why pinning is not optional, and for the `@OptIn`.
 */
@OptIn(ExperimentalTextApi::class)
internal val NotoSansDevanagari = FontFamily(
    Font(R.font.noto_sans_devanagari, FontWeight.Normal, variationSettings = axis(400)),
    Font(R.font.noto_sans_devanagari, FontWeight.Medium, variationSettings = axis(500)),
)

private fun axis(weight: Int): FontVariation.Settings =
    FontVariation.Settings(FontVariation.weight(weight))

/**
 * Fraunces at ONE weight and ONE optical size.
 *
 * Fraunces is bundled as the variable font; `google/fonts` ships no static
 * instances of it. Its `fvar` declares four axes — `opsz` 9–144, `wght`
 * 100–900, `SOFT` 0–100, `WONK` 0–1 — and its DEFAULT instance is `wght` 900 at
 * `opsz` 9 (`SOFT` 0, `WONK` 1). Two things follow.
 *
 * **The axes have to be pinned, not left to the matcher.** Compose resolves a
 * requested `fontWeight` by MATCHING it to one of the family's `Font` entries
 * and then loading that entry; it does not translate the weight into a `'wght'`
 * axis afterwards. So a family declared as a single unweighted `Font(…)` of this
 * face loads the default instance — every title, beat name and BPM numeral in
 * the app would come out as 900-weight black at the smallest optical size. The
 * browser never sees that default, because the CSS `@import` asks for
 * `opsz,wght@9..144,400..700` and supplies both per element. Declaring the
 * weight AND its axis per entry is what gets the real cut. `SOFT` and `WONK`
 * are deliberately left at their defaults, which is also what the web does.
 *
 * **`opsz` has to be pinned per size.** CSS `font-optical-sizing: auto` makes
 * the browser set `opsz` continuously from the font size, so the 40sp BPM
 * numerals and the 12.5sp labels each get an optical size suited to them.
 * Android has no equivalent, and in this version of Compose the variation
 * settings hang off the FONT RESOURCE (`Font(…, variationSettings = …)`) rather
 * than the text style — so the only way to make `opsz` follow the size is to
 * make the family itself depend on the size. Hence a factory rather than one
 * shared `FontFamily`.
 *
 * The `@OptIn` is the price of that: this is the only overload of `Font` that
 * takes `variationSettings`, and it is marked experimental. It is opted into
 * here and nowhere else, so a future Compose that changes it breaks this one
 * function rather than every screen. [HindMadurai] needs none of it — it is
 * bundled as static per-weight TTFs.
 *
 * Call this once per style, as [KirtanType] does. Two calls with equal
 * arguments produce `equals()`-equal families, so Compose's typeface cache
 * still hits — but building one per recomposition is churn for nothing.
 */
@OptIn(ExperimentalTextApi::class)
internal fun frauncesFamily(weight: FontWeight, opticalSize: TextUnit): FontFamily = FontFamily(
    Font(
        resId = R.font.fraunces,
        weight = weight,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.opticalSizing(opticalSize),
        ),
    ),
)

/**
 * The OpenType feature string for tabular figures.
 *
 * index.css sets `font-variant-numeric: tabular-nums` on every numeric style
 * and the reason is mechanical, not aesthetic: the big BPM readout is flanked
 * by two stepper buttons, and if "98" and "100" are different widths the whole
 * row jitters on every tick. Same for the mixer's −12…+12 dB readouts beside
 * their sliders. Fraunces is a proportional display face, so this is not
 * optional — without `'tnum'` the numerals WILL move.
 */
private const val TabularFigures = "tnum"

/**
 * Build a Fraunces [TextStyle] at one size and weight, with its variable axes
 * pinned by [frauncesFamily].
 *
 * `internal` so the screens can build the sizes index.css declares outside the
 * token scale — the beat-row name's 17px, the editor's 18px pads — without
 * giving up optical sizing or having to know how the axes work.
 */
internal fun fraunces(
    size: TextUnit,
    weight: FontWeight,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    lineHeightStyle: LineHeightStyle? = null,
    tabular: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = frauncesFamily(weight, size),
    fontSize = size,
    fontWeight = weight,
    letterSpacing = letterSpacing,
    lineHeight = lineHeight,
    lineHeightStyle = lineHeightStyle,
    fontFeatureSettings = if (tabular) TabularFigures else null,
)

/**
 * `--text-display-lg` (1.625rem) at a given weight.
 *
 * The 0.01em tracking belongs to the token, not to a caller: every web style
 * that sets it (`subTitle`, `beatRowName`, `cardTitle`) sets exactly 0.01em.
 */
private fun displayLg(weight: FontWeight): TextStyle =
    fraunces(26.sp, weight, letterSpacing = 0.26.sp)

/**
 * The seven styles the CSS token scale declares.
 *
 * Named after the tokens (`--text-display-lg` → [displayLg]) rather than after
 * Material's slots, because a ported screen should be able to look at its JSX
 * and know which one it wants. Where the web overrides a token per-use — the
 * beat name at 600, `subTitle` at 400, the primary buttons' 0.04em uppercase
 * tracking — the token carries the COMMON case and the override is spelled out
 * at the call site or in [KirtanMaterialTypography].
 */
internal object KirtanType {

    /**
     * `--text-display-lg` 1.625rem = 26sp — the beat name and every screen and
     * sheet title. SemiBold because that is what `beatName`, `sheetName`,
     * `sheetTitle` and the editor's `titleInput` all use; `styles.js`
     * `subTitle` is the one 400 case, and it asks for it explicitly.
     */
    val displayLg: TextStyle = displayLg(FontWeight.SemiBold)

    /**
     * `--text-display-md` 0.78rem = 12.5sp — section labels.
     *
     * Note the family: despite the `display-` name the token is defined in
     * index.css as "uppercase sans, semibold, tracked", so it is Hind Madurai
     * and not Fraunces. No web style currently references it (the screens that
     * need a section label spell out `--text-body-xs` at 0.1em instead), so
     * this is built from the token's own definition: 0.1em tracking, and the
     * uppercasing is left to the caller because Compose text has no
     * `text-transform`.
     */
    val displayMd: TextStyle = TextStyle(
        fontFamily = HindMadurai,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.25.sp,
    )

    /** `--text-body-md` 0.95rem = 15.2sp — the default body text. */
    val bodyMd: TextStyle = TextStyle(
        fontFamily = HindMadurai,
        fontSize = 15.2.sp,
        fontWeight = FontWeight.Normal,
    )

    /** `--text-body-sm` 0.84rem = 13.4sp — captions and beat meta. */
    val bodySm: TextStyle = TextStyle(
        fontFamily = HindMadurai,
        fontSize = 13.4.sp,
        fontWeight = FontWeight.Medium,
    )

    /**
     * `--text-body-xs` 0.75rem = 12sp — utility and meta text.
     *
     * 12sp is a deliberate floor, not a leftover: index.css annotates the token
     * "12px floor for legibility". Nothing in the app is allowed to go below
     * it, including the strip's 10px bol labels and lane labels, which are
     * exceptions the web makes only because they sit inside a fixed-height
     * cell — on Android they should be re-measured against this floor rather
     * than copied across.
     */
    val bodyXs: TextStyle = TextStyle(
        fontFamily = HindMadurai,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
    )

    /**
     * `--text-numeric-xl` 2.5rem = 40sp — the BPM signage, sized to be
     * glanceable at arm's length. `lineHeight: 1` because the tempo row is laid
     * out around it (`st.bpmNum`) and any extra leading pushes the flanking
     * steppers off centre.
     */
    val numericXl: TextStyle = fraunces(
        size = 40.sp,
        weight = FontWeight.SemiBold,
        lineHeight = 40.sp,
        tabular = true,
    )

    /**
     * `--text-numeric-md` 1rem = 16sp — counts. Tabular for the same reason as
     * [numericXl], but with no pinned leading: the web declares `line-height: 1`
     * only on the BPM readout, and inventing one here would change how a count
     * sits inside its row.
     */
    val numericMd: TextStyle = fraunces(
        size = 16.sp,
        weight = FontWeight.SemiBold,
        tabular = true,
    )
}

/**
 * Material 3's fifteen slots, filled from the seven styles above.
 *
 * This exists so that `Button`, `TextField`, `Card`, `Dialog` and the rest do
 * not fall back to Roboto — every screen composes over Material3 components at
 * some point. M3 has fifteen slots and this scale has seven styles, so the
 * mapping is by ROLE and several slots deliberately share one style; nothing
 * here invents a size index.css does not declare. A screen that wants an exact
 * token should use [KirtanType] directly and treat this as the fallback.
 *
 * Fraunces weights go through [displayLg] rather than `.copy(fontWeight = …)`,
 * because the weight is baked into the family's variation axes (see
 * [frauncesFamily]) and copying the style alone would not change it. The Hind
 * Madurai styles are static per-weight TTFs, so `.copy(fontWeight = …)` is safe
 * for them.
 */
internal val KirtanMaterialTypography = Typography(
    // ── Display: the signage sizes, biggest first ──
    displayLarge = KirtanType.numericXl,
    displayMedium = KirtanType.displayLg,
    displaySmall = KirtanType.numericMd,

    // ── Headline: the beat name and the screen/sheet titles ──
    headlineLarge = KirtanType.displayLg,
    headlineMedium = displayLg(FontWeight.Normal),
    headlineSmall = displayLg(FontWeight.Normal),

    // ── Title: TopAppBar and sheet headers ──
    titleLarge = KirtanType.displayLg,
    titleMedium = KirtanType.bodyMd.copy(fontWeight = FontWeight.Bold),
    titleSmall = KirtanType.bodySm.copy(fontWeight = FontWeight.Bold),

    // ── Body: the three Hind Madurai sizes, largest to smallest ──
    bodyLarge = KirtanType.bodyMd,
    bodyMedium = KirtanType.bodySm,
    bodySmall = KirtanType.bodyXs,

    // ── Labels: buttons and section headers ──
    // 0.04em tracking is what every primary button in the app uses
    // (`st.playBtn`, `sheetStartBtn`, the AuthSheet's submit).
    labelLarge = KirtanType.bodyMd.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.61.sp),
    labelMedium = KirtanType.bodySm.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.54.sp),
    labelSmall = KirtanType.displayMd,
)
