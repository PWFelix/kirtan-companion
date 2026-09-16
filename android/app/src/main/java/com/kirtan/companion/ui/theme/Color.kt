package com.kirtan.companion.ui.theme

import androidx.compose.ui.graphics.Color
import com.kirtan.companion.data.PaletteToken

/**
 * The palette, resolved to Compose colours.
 *
 * Ported from `src/index.css` `:root`, which is authoritative and declares
 * every token in OKLCH. Each hex below is that OKLCH triple converted to sRGB
 * rather than picked out of a design tool. `res/values/colors.xml` repeats the
 * first nine for the platform window theme, which paints before Compose exists
 * — keep the two in step.
 *
 * The materials metaphor in the CSS header is worth carrying across, because
 * the token NAMES are the design: the app is built from the materials of a
 * mridanga. `head` is the rawhide drumhead (the warm ivory surfaces), `syahi`
 * the black tuning paste at its centre (ink, playhead, and — the one poetic
 * inversion — the step that is SOUNDING, because on a real head the black
 * circle is where the sound comes from), `clay` the fired terracotta shell
 * (actions), `strap` the leather lacing (accents and closed strokes).
 *
 * Everything is flat matte material colour: no gradients, no glass, no glow,
 * no drop shadows. Elevation is a 1px warm hairline ([Rule]), never a shadow —
 * which is why [KirtanColorScheme] zeroes Material3's `surfaceTint`.
 */

// ── Materials ──

/** rawhide — page background. oklch(0.94 0.02 90) */
internal val Head = Color(0xFFF0EBDC)

/** played-in head — cards, tracks, beat rows. oklch(0.90 0.025 85) */
internal val HeadWorn = Color(0xFFE6DDCC)

/** inset areas — grids, wells, the BPM wheel's selection band. oklch(0.86 0.03 82) */
internal val HeadSunken = Color(0xFFDBD0BB)

/** ink / playhead / the sounding step — ~12:1 on [Head]. oklch(0.24 0.02 60) */
internal val Syahi = Color(0xFF261D16)

/** secondary text — ≥4.5:1 on [Head]. oklch(0.45 0.02 60) */
internal val SyahiSoft = Color(0xFF5E534A)

/**
 * actions — warm white text passes AA on it. oklch(0.54 0.12 40).
 *
 * That OKLCH triple converts to #A85334 to the nearest 1/255, but the value
 * used everywhere else in this repo (`res/values/colors.xml`, and the web
 * app's own design notes) is #A85335. Kept at #A85335 so the Compose palette
 * and the platform window theme agree byte for byte; the difference is one
 * part in 255 of blue and not visible.
 */
internal val Clay = Color(0xFFA85335)

/** pressed / emphasis. oklch(0.47 0.12 38) */
internal val ClayDeep = Color(0xFF913E23)

/** leather tan — the secondary accent. oklch(0.66 0.09 75) */
internal val Strap = Color(0xFFB38A50)

/** warm white text on clay. oklch(0.97 0.01 85) */
internal val OnClay = Color(0xFFF8F5EE)

/** destructive confirm — a deeper red than clay. oklch(0.47 0.15 25) */
internal val Danger = Color(0xFF9E2C2C)

/** the warm hairline that stands in for shadows everywhere. oklch(0.82 0.03 80) */
internal val Rule = Color(0xFFCEC2AF)

/** tertiary text / disabled glyphs. oklch(0.62 0.02 65) */
internal val Faint = Color(0xFF8F847A)

// ── Lane colours: one per instrument row on the strip ──
// Adding an instrument means adding a token in index.css, a LANES entry, and
// one arm in the `when` below — the same three-step pattern the web app uses.

/** the right drum head; `--lane-dayan` is literally `var(--clay)`. */
internal val LaneDayan = Clay

/** the bass head — a deeper umber of the clay family. oklch(0.42 0.08 45) */
internal val LaneBayan = Color(0xFF713E26)

/** bell brass — the karatalas. oklch(0.72 0.11 90) */
internal val LaneKartal = Color(0xFFBFA14C)

/** harmonium reed teal — declared, not yet used. oklch(0.55 0.08 200) */
internal val LaneMelody = Color(0xFF298084)

/** reserved stroke colours, not yet activated. oklch(0.58 0.13 38) */
internal val Duggi = Color(0xFFB95B3D)

/** reserved stroke colours, not yet activated. oklch(0.80 0.11 70) */
internal val Nak = Color(0xFFEBB16C)

/**
 * The sheet backdrop: syahi at 35%.
 *
 * `styles.js` `sheetBackdrop` sets `oklch(0.24 0.02 60 / 0.35)` — the same
 * syahi, translucent, so the page behind a bottom sheet keeps its warm colour
 * instead of going grey. Material3's `ColorScheme.scrim` slot is deliberately
 * left opaque (see [KirtanColorScheme]); this is the value to actually paint.
 */
internal val SheetScrim: Color = Syahi.copy(alpha = 0.35f)

/**
 * Resolve a semantic token to its colour.
 *
 * The `when` is exhaustive over [PaletteToken] and returns, so adding a token
 * to the enum is a COMPILE error here until somebody decides what colour it
 * is — the point of keeping the tokens in `data/` and the mapping in `ui/`.
 * There is no `else` arm on purpose: one would silently paint a new stroke
 * whatever the fallback happened to be.
 */
internal val PaletteToken.color: Color
    get() = when (this) {
        PaletteToken.HEAD -> Head
        PaletteToken.HEAD_WORN -> HeadWorn
        PaletteToken.HEAD_SUNKEN -> HeadSunken
        PaletteToken.SYAHI -> Syahi
        PaletteToken.SYAHI_SOFT -> SyahiSoft
        PaletteToken.CLAY -> Clay
        PaletteToken.CLAY_DEEP -> ClayDeep
        PaletteToken.STRAP -> Strap
        PaletteToken.ON_CLAY -> OnClay
        PaletteToken.DANGER -> Danger
        PaletteToken.RULE -> Rule
        PaletteToken.FAINT -> Faint
        PaletteToken.LANE_DAYAN -> LaneDayan
        PaletteToken.LANE_BAYAN -> LaneBayan
        PaletteToken.LANE_KARTAL -> LaneKartal
        PaletteToken.LANE_MELODY -> LaneMelody
        PaletteToken.DUGGI -> Duggi
        PaletteToken.NAK -> Nak
    }

/**
 * The transitional bridge aliases.
 *
 * Ported from the `TRANSITIONAL BRIDGE` block in `src/index.css` `:root`. The
 * web app keeps the pre-redesign token names alive so its older screens still
 * resolve while they are rebuilt, and the Android port inherits that: a screen
 * ported straight across will be written against the name its JSX used
 * (`--ink-primary`, `--accent-action`) rather than the material underneath it.
 *
 * Every entry is an ALIAS onto a material colour above — never a new value —
 * which is the whole point of the block. New code should prefer the material
 * name; these exist so ported code does not have to be reinterpreted first.
 */
internal object Bridge {
    val cream = Head
    val cream2 = HeadWorn
    val surface = Head
    val surfacePaper = Head
    val surfaceRaised = HeadWorn
    val surfaceSunken = HeadSunken
    val cell = HeadSunken

    val ink = Syahi
    val inkPrimary = Syahi
    val muted = SyahiSoft
    val inkSecondary = SyahiSoft
    val faint = Faint

    val saffron = Clay
    val saffronD = ClayDeep
    val kumkum = Clay
    val accentSaffron = Clay
    val accentAction = Clay
    val accentSaffronDim = Strap
    val gold = Strap

    val onAction = OnClay
    val line = Rule

    val kartal = LaneKartal
    val duggi = Duggi
    val nak = Nak
}
