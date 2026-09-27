import SwiftUI

extension Color {
    /// Build from a 0xRRGGBB integer. The values here come from `src/index.css`,
    /// where every token is declared in OKLCH and this is its sRGB rendering.
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity,
        )
    }
}

/// The palette, resolved to colours.
///
/// Ported from `ui/theme/Color.kt`, which ports the `:root` of `src/index.css`.
/// The token NAMES are the design: the app is built from the materials of a
/// mridanga. `head` is the rawhide drumhead (the warm ivory surfaces), `syahi`
/// the black tuning paste at its centre (ink, playhead, and — the one poetic
/// inversion — the step that is sounding, because on a real head the black
/// circle is where the sound comes from), `clay` the fired terracotta shell
/// (actions), `strap` the leather lacing (accents and closed strokes).
///
/// Everything is flat matte material colour: no gradients, no glass, no glow,
/// no drop shadows. Elevation is a 1 px warm hairline ([Rule]) never a shadow.
///
/// The system dark-theme setting is deliberately ignored: there is no dark
/// variant of rawhide and terracotta to invert into, and the web app declares
/// no `prefers-color-scheme` block at all.
enum Palette {

    // ── Materials ────────────────────────────────────────────────────────
    static let head = Color(hex: 0xF0EBDC)          // rawhide — page background
    static let headWorn = Color(hex: 0xE6DDCC)      // played-in head — cards, rows
    static let headSunken = Color(hex: 0xDBD0BB)    // inset — grids, wells
    static let syahi = Color(hex: 0x261D16)         // ink / playhead / sounding step
    static let syahiSoft = Color(hex: 0x5E534A)     // secondary text
    static let clay = Color(hex: 0xA85335)          // actions
    static let clayDeep = Color(hex: 0x913E23)      // pressed / emphasis
    static let strap = Color(hex: 0xB38A50)         // leather tan — accents
    static let onClay = Color(hex: 0xF8F5EE)        // warm white on clay
    static let danger = Color(hex: 0x9E2C2C)        // destructive confirm
    static let rule = Color(hex: 0xCEC2AF)          // hairline (stands in for shadow)
    static let faint = Color(hex: 0x8F847A)         // tertiary text / disabled

    // ── Lane colours: one per instrument row on the strip ───────────────
    static let laneDayan = clay                      // right head: `--lane-dayan` is `var(--clay)`
    static let laneBayan = Color(hex: 0x713E26)     // bass head, deeper umber
    static let laneKartal = Color(hex: 0xBFA14C)    // bell brass, the karatalas
    static let laneMelody = Color(hex: 0x298084)    // harmonium teal, declared unused
    static let duggi = Color(hex: 0xB95B3D)         // reserved stroke, not activated
    static let nak = Color(hex: 0xEbB16C)           // reserved stroke, not activated

    /// The bottom-sheet backdrop: syahi at 35%, so the page behind keeps its colour.
    static let sheetScrim = Color(hex: 0x261D16, opacity: 0.35)

    /// A semantic token → its colour. Exhaustive over [PaletteToken], so a new
    /// token in the data layer is a compile error here until it is given a colour.
    static func color(_ token: PaletteToken) -> Color {
        switch token {
        case .head: return head
        case .headWorn: return headWorn
        case .headSunken: return headSunken
        case .syahi: return syahi
        case .syahiSoft: return syahiSoft
        case .clay: return clay
        case .clayDeep: return clayDeep
        case .strap: return strap
        case .onClay: return onClay
        case .danger: return danger
        case .rule: return rule
        case .faint: return faint
        case .laneDayan: return laneDayan
        case .laneBayan: return laneBayan
        case .laneKartal: return laneKartal
        case .laneMelody: return laneMelody
        case .duggi: return duggi
        case .nak: return nak
        }
    }

    /// A lane's row colour.
    static func lane(_ laneId: LaneId) -> Color {
        switch laneId {
        case .dayan: return laneDayan
        case .bayan: return laneBayan
        case .kartal: return laneKartal
        }
    }

    /// A stroke's colour. Open and closed strokes take different materials; the
    /// lane then shades it. The strip and editor read this.
    static func stroke(_ stroke: Stroke, laneId: LaneId) -> Color {
        switch stroke {
        case .open: return lane(laneId)
        case .closed: return strap
        }
    }
}
