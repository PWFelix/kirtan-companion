// The palette, as semantic tokens rather than raw colours.
//
// The web app stores "var(--saffron)" in its data files and resolves the variable in
// CSS. This enum is the same indirection: the data layer names a MEANING, and
// ui/theme/Colors maps each meaning to a value. Keeping the tokens in the data layer
// means the pure data modules stay free of SwiftUI imports, and an exhaustive `switch`
// in the theme makes adding a token a compile error until it is coloured.
//
// The materials metaphor (from index.css): the app is built from the materials of a
// mridanga — head (rawhide), syahi (the black tuning paste), clay (the fired shell),
// strap (the leather lacing). Everything is flat matte colour: no gradients, no glass,
// no glow. The one poetic inversion: the SOUNDING step turns syahi-black, because on a
// real head the black circle is where the sound comes from.
enum PaletteToken: String, CaseIterable, Hashable {
    case head            // rawhide — page background
    case headWorn        // played-in head — cards, tracks, beat rows
    case headSunken      // inset areas — grids, wells, selection band
    case syahi           // ink / playhead / active step
    case syahiSoft       // secondary text
    case clay            // actions
    case clayDeep        // pressed / emphasis
    case strap           // leather tan — secondary accent
    case onClay          // warm white text on clay
    case danger          // destructive confirm — deeper red than clay
    case rule            // warm hairline, used in place of shadows
    case faint           // tertiary text / disabled glyphs
    case laneDayan       // right drum head
    case laneBayan       // bass head — a deeper umber of the clay family
    case laneKartal      // bell brass — the karatalas
    case laneMelody      // harmonium reed teal — future, unused
    case duggi           // reserved stroke colour, not yet activated
    case nak             // reserved stroke colour, not yet activated
}
