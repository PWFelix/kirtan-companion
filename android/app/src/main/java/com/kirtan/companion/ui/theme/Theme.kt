package com.kirtan.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The palette tokens Material 3 has no slot for.
 *
 * [KirtanColorScheme] gets the app's materials into the M3 roles it does
 * understand, which covers most of a screen. What is left over is the lane
 * colours (one per instrument row — M3 has no idea what an instrument is), the
 * hairline and faint text, and the two reserved stroke colours. Those live
 * here, reachable as `KirtanTheme.colors`, so a screen never has to go through
 * [PaletteToken] for something Material3 was simply never told about.
 *
 * Every value is resolved from `Color.kt`; this class adds no colours of its
 * own. It exists as a CompositionLocal value rather than as globals for the
 * same reason [KirtanDimens] does — it is the seam a future variant would be
 * handed through.
 */
@Immutable
internal data class KirtanColors(
    /** the right drum head — `--lane-dayan`, which is `var(--clay)` */
    val laneDayan: Color = LaneDayan,

    /** the bass head — `--lane-bayan`, a deeper umber of the clay family */
    val laneBayan: Color = LaneBayan,

    /** bell brass — `--lane-kartal`, the karatalas */
    val laneKartal: Color = LaneKartal,

    /** harmonium reed teal — `--lane-melody`, declared but not yet used */
    val laneMelody: Color = LaneMelody,

    /** the warm hairline that replaces shadows — `--rule` */
    val rule: Color = Rule,

    /** tertiary text and disabled glyphs — `--faint` */
    val faint: Color = Faint,

    /** reserved stroke colours, not yet activated */
    val duggi: Color = Duggi,
    val nak: Color = Nak,
) {
    companion object {
        val Default = KirtanColors()
    }
}

/**
 * `staticCompositionLocalOf` throughout, not `compositionLocalOf`: nothing here
 * changes while the app runs, so there is no point paying for invalidation
 * tracking on a read that happens in every composable.
 */
internal val LocalKirtanColors: ProvidableCompositionLocal<KirtanColors> =
    staticCompositionLocalOf { KirtanColors.Default }

internal val LocalKirtanDimens: ProvidableCompositionLocal<KirtanDimens> =
    staticCompositionLocalOf { KirtanDimens.Default }

/**
 * The Material 3 scheme, derived from the palette in `Color.kt`.
 *
 * Two decisions carry the design language across:
 *
 * **`surfaceTint` is transparent.** M3 tints every surface with the primary
 * colour as it gains elevation. This design is flat matte material — index.css
 * is explicit that there are no gradients, no glass, no glow and no drop
 * shadows, and that elevation is expressed as a 1px warm hairline ([Rule])
 * instead. Leaving the tint on would make every `Card` and `Dialog` glow faintly
 * terracotta as it rose, which is precisely the effect the web app refuses to
 * have. Pair this with the hairline border at the call site.
 *
 * **There are no tints in this palette, so containers collapse.** M3 expects a
 * family of lighter "container" colours per accent role. The mridanga has
 * exactly three surfaces — the rawhide head, the played-in head, and the sunken
 * inset — and that is what every container is mapped onto, with ink for the
 * text on it. Inventing clay tints to fill the slots would add colours the
 * design does not have. The expressive `*Fixed` roles collapse onto their
 * container equivalents for the same reason.
 *
 * The one place a real distinction survives is error: index.css's `errorStrip`
 * is a `head-worn` card with `danger` text and a `danger` border, so
 * `errorContainer`/`onErrorContainer` reproduce exactly that rather than a red
 * fill.
 */
internal val KirtanColorScheme = lightColorScheme(
    primary = Clay,
    onPrimary = OnClay,
    primaryContainer = HeadWorn,
    onPrimaryContainer = Syahi,
    inversePrimary = OnClay,

    secondary = Strap,
    onSecondary = Syahi,
    secondaryContainer = HeadWorn,
    onSecondaryContainer = Syahi,

    tertiary = LaneMelody,
    onTertiary = OnClay,
    tertiaryContainer = HeadWorn,
    onTertiaryContainer = Syahi,

    background = Head,
    onBackground = Syahi,
    surface = Head,
    onSurface = Syahi,
    surfaceVariant = HeadWorn,
    onSurfaceVariant = SyahiSoft,

    // head → played-in head → sunken inset, as the surfaces stack.
    surfaceContainerLowest = Head,
    surfaceContainerLow = Head,
    surfaceContainer = HeadWorn,
    surfaceContainerHigh = HeadWorn,
    surfaceContainerHighest = HeadSunken,
    surfaceDim = HeadSunken,
    surfaceBright = Head,
    surfaceTint = Color.Transparent,

    error = Danger,
    onError = OnClay,
    errorContainer = HeadWorn,
    onErrorContainer = Danger,

    // `outline` is the stronger of the two border roles and `outlineVariant`
    // the hairline — the same pairing as `--faint` and `--rule` in the CSS.
    outline = Faint,
    outlineVariant = Rule,

    // Opaque on purpose: M3 treats `scrim` as a solid colour and applies its
    // own alpha. The sheet backdrop the app actually paints is [SheetScrim]
    // (syahi at 35%), which is what `styles.js` `sheetBackdrop` declares.
    scrim = Syahi,
    inverseSurface = Syahi,
    inverseOnSurface = Head,

    primaryFixed = HeadWorn,
    onPrimaryFixed = Syahi,
    primaryFixedDim = HeadSunken,
    onPrimaryFixedVariant = ClayDeep,
    secondaryFixed = HeadWorn,
    onSecondaryFixed = Syahi,
    secondaryFixedDim = HeadSunken,
    onSecondaryFixedVariant = SyahiSoft,
    tertiaryFixed = HeadWorn,
    onTertiaryFixed = Syahi,
    tertiaryFixedDim = HeadSunken,
    onTertiaryFixedVariant = SyahiSoft,
)

/**
 * The app's theme.
 *
 * **The system dark-theme setting is deliberately ignored.** `KirtanTheme`
 * never branches on `isSystemInDarkTheme()`, and always provides
 * [KirtanColorScheme]. This is a warm-paper design: the whole point of the
 * palette is that it is the colour of a drumhead under a lamp, and there is no
 * dark variant of rawhide and terracotta to invert into — swapping surfaces and
 * ink would turn the syahi (which means "the place the sound comes from") into
 * the page and invert the one metaphor the design is built on. A device set to
 * dark therefore gets this same light app, which is also what the web app does:
 * index.css declares no `prefers-color-scheme` block at all.
 *
 * The status and navigation bars are already painted the head colour with light
 * icons by `res/values/themes.xml`, so nothing needs doing here for them.
 *
 * @param dimens the frame to lay out in. Defaults to the portrait frame; the
 *   landscape "propped-up" layout would pass a tighter one (see [KirtanDimens]).
 */
@Composable
internal fun KirtanTheme(
    dimens: KirtanDimens = KirtanDimens.Default,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKirtanColors provides KirtanColors.Default,
        LocalKirtanDimens provides dimens,
    ) {
        MaterialTheme(
            colorScheme = KirtanColorScheme,
            shapes = dimens.shapes,
            typography = KirtanMaterialTypography,
            content = content,
        )
    }
}

/**
 * Accessor, mirroring `MaterialTheme.colorScheme` so a screen reads
 * `KirtanTheme.colors.laneDayan` and `MaterialTheme.colorScheme.primary` the
 * same way.
 */
internal object KirtanTheme {
    val colors: KirtanColors
        @Composable @ReadOnlyComposable get() = LocalKirtanColors.current

    val dimens: KirtanDimens
        @Composable @ReadOnlyComposable get() = LocalKirtanDimens.current
}
