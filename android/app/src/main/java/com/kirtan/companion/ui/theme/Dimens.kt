package com.kirtan.companion.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every dimension the design system fixes, in one place.
 *
 * The spacing scale comes straight from `src/index.css` (`--space-1` …
 * `--space-8`, a 4px base). The corner radii do NOT: index.css tokenises
 * nothing about corners, so they have been collected out of the inline style
 * objects in `src/ui/styles.js`, the files under `src/views`, and
 * `src/BeatEditor.jsx`, where each one is a literal. Every entry is named after
 * the thing it belongs to rather than being a numbered tier, because the web
 * never had tiers — the same 14px turns up on editor pads, the mixer button,
 * sheet action buttons and text inputs, and calling that "radius 3" would hide
 * the fact that they are meant to match each other.
 *
 * These live behind a value in a CompositionLocal (see `Theme.kt`) rather than
 * as bare top-level constants for one reason: the web app has a second, tighter
 * set of numbers for its landscape "propped-up" layout (`st.landScreen` in
 * `HomeView.jsx` — a wider frame, smaller cells). A screen that reads
 * `KirtanTheme.dimens` can be handed either set; one that reads a global
 * constant cannot.
 */
@Immutable
internal data class KirtanDimens(

    // ── Spacing scale (index.css `--space-1` … `--space-8`, 4px base) ──
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 20.dp,
    val space6: Dp = 24.dp,
    val space7: Dp = 32.dp,
    val space8: Dp = 40.dp,

    // ── Corner radii, collected from the style objects ──

    /**
     * The pill — category tabs, the bottom nav and its sliding indicator, the
     * loop-position bar. 999 is CSS's idiom for "round it all the way"; Skia
     * clamps an over-large radius to half the short side exactly like a browser
     * does, so the value carries over literally and any height comes out a
     * pill.
     */
    val radiusPill: Dp = 999.dp,

    /** Bottom sheets — the top two corners only (`styles.js` `sheet`). */
    val radiusSheet: Dp = 20.dp,

    /** The Beats page's section cards and its search card. */
    val radiusSectionCard: Dp = 18.dp,

    /** The clay play bar (`st.playBtn`) and the splash's Begin bar. */
    val radiusPlayBar: Dp = 16.dp,

    /** Editor pads, the mixer button, sheet action buttons, text inputs. */
    val radiusPad: Dp = 14.dp,

    /** Every 44×44 icon button: back, chevron, lock, mute, info, delete. */
    val radiusIconButton: Dp = 12.dp,

    /** The editor's zoom cells (`st.zoomCell`). */
    val radiusZoomCell: Dp = 10.dp,

    /** Beat-strip cells (index.css `.ks-cell`). */
    val radiusStripCell: Dp = 9.dp,

    /** `CheckDot`, the editor's overview frame, the error strip's close. */
    val radiusCheckDot: Dp = 8.dp,

    /**
     * `--rule-hairline`: 1px. The app expresses elevation as this warm hairline
     * and never as a shadow, so it is a token like any other rather than an
     * ad-hoc `1.dp` scattered through the screens.
     */
    val hairline: Dp = 1.dp,

    // ── Screen frame (`styles.js` `screen` / `screenFixed`) ──

    /**
     * The whole app is one column at most this wide, centred; above it the
     * margins simply grow. That is what keeps the portrait layout legible on a
     * tablet without needing a second design.
     */
    val screenMaxWidth: Dp = 430.dp,

    /**
     * `--space-6` / `--space-5` / `--space-4`. On Android the safe-area insets
     * are ADDED to these (from `WindowInsets`), not substituted for them, or a
     * device without a notch loses the frame's rhythm.
     *
     * THE BOTTOM-NAV INVARIANT: these three values must be identical on every
     * screen, so the nav sits at the exact same position and switching tabs
     * moves the pill and nothing else. `styles.js` says so above `screen` —
     * "keep this padding identical to BeatEditor's st.screen so the bottom nav
     * sits at the exact same spot on every page and never shifts on switch" —
     * and the Editor deliberately keeps its own copy of the frame so it can
     * tighten [screenGap] without touching the padding. In Compose: every
     * screen is a centred `Column` with `widthIn(max = screenMaxWidth)`, this
     * padding plus insets, and the nav as its last child. The only value a
     * screen may vary for itself is [screenGap]. If the nav ever appears to
     * jump on a tab switch, one of these was changed on one screen.
     */
    val screenPaddingTop: Dp = 24.dp,
    val screenPaddingSide: Dp = 20.dp,
    val screenPaddingBottom: Dp = 16.dp,

    /** The `gap` between the frame's children (`--space-5`). */
    val screenGap: Dp = 20.dp,

    // ── Bottom nav (`ui/BottomNav.jsx`) ──

    /** `minHeight: 60` — 52dp of pill plus 4dp of breathing room each side. */
    val navCellHeight: Dp = 60.dp,

    /** How far the sliding pill sits inside the bar on every side (`GAP / 2`). */
    val navPillInset: Dp = 4.dp,

    /**
     * The whole gap between two adjacent pills (`GAP`), which is why one step
     * of the slide is exactly `cellWidth + navPillGap` and needs no measuring
     * and no refs.
     */
    val navPillGap: Dp = 8.dp,

    /** Icon-to-label gap inside a nav cell. */
    val navLabelGap: Dp = 2.dp,

    /**
     * The minimum target the nav's five cells were sized against:
     * (430 − 2 × [screenPaddingSide]) / 5 is 78dp, and even on a 320dp-wide
     * phone a cell stays above this. A sixth tab would drop below it on small
     * screens — that is the stated ceiling on the tab count.
     */
    val touchTarget: Dp = 44.dp,
) {

    /**
     * The bottom sheet's shape: [radiusSheet] on the top two corners and
     * nothing on the bottom, because a sheet is flush with the screen edge.
     */
    val sheetShape: CornerBasedShape
        get() = RoundedCornerShape(topStart = radiusSheet, topEnd = radiusSheet)

    /**
     * Material 3's five shape slots, mapped onto these radii so that `Card`,
     * `Button`, `Dialog` and `BottomSheet` come out looking like this app
     * without every call site restating a corner.
     *
     * The mapping is by size, nearest first: 8 → 10 → 14 → 18 → 20. The 999
     * pill is deliberately absent — M3 has no slot for one, and a fully-rounded
     * shape always means something specific here (a tab, or the nav), so it is
     * asked for explicitly as `RoundedCornerShape(radiusPill)`.
     */
    val shapes: Shapes
        get() = Shapes(
            extraSmall = RoundedCornerShape(radiusCheckDot),
            small = RoundedCornerShape(radiusZoomCell),
            medium = RoundedCornerShape(radiusPad),
            large = RoundedCornerShape(radiusSectionCard),
            extraLarge = RoundedCornerShape(radiusSheet),
        )

    companion object {
        /** The portrait frame — the only one the app has today. */
        val Default = KirtanDimens()
    }
}
