package com.kirtan.companion.data

/**
 * The palette, as semantic tokens rather than raw colours.
 *
 * The web app stores `"var(--saffron)"` in its data files and resolves the
 * variable in CSS. This enum is the same indirection: the data layer names a
 * MEANING, and `ui/theme/Color.kt` maps each meaning to a value. Keeping the
 * tokens in the data package (not the UI one) means the pure data modules stay
 * free of Compose imports and remain JVM-unit-testable, while an exhaustive
 * `when` in the theme makes adding a token a compile error until it is coloured.
 *
 * The materials metaphor is from index.css and is worth preserving: the app is
 * built from the materials of a mridanga — head (rawhide), syahi (the black
 * tuning paste), clay (the fired shell), strap (the leather lacing). Everything
 * is flat matte material colour: no gradients, no glass, no glow. The one
 * poetic inversion is that the SOUNDING step turns syahi-black, because on a
 * real head the black circle is where the sound comes from.
 */
enum class PaletteToken {
    /** rawhide — page background */
    HEAD,

    /** played-in head — cards, tracks, beat rows */
    HEAD_WORN,

    /** inset areas — grids, wells, selection band */
    HEAD_SUNKEN,

    /** ink / playhead / active step */
    SYAHI,

    /** secondary text */
    SYAHI_SOFT,

    /** actions */
    CLAY,

    /** pressed / emphasis */
    CLAY_DEEP,

    /** leather tan — secondary accent */
    STRAP,

    /** warm white text on clay */
    ON_CLAY,

    /** destructive confirm — deeper red than clay */
    DANGER,

    /** warm hairline, used in place of shadows */
    RULE,

    /** tertiary text / disabled glyphs */
    FAINT,

    /** right drum head */
    LANE_DAYAN,

    /** bass head — a deeper umber of the clay family */
    LANE_BAYAN,

    /** bell brass — the karatalas */
    LANE_KARTAL,

    /** harmonium reed teal — future, unused */
    LANE_MELODY,

    /** reserved stroke colours, not yet activated */
    DUGGI,
    NAK,
}
