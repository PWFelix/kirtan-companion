// The single source of truth for how each stroke type LOOKS.
//
// Ported from src/data/strokes.js. Every visual indicator reads from here, so the
// whole app agrees on what an "open" or "closed" stroke looks like. To add a new
// stroke type (duggi, nak…) add ONE entry here and it appears everywhere — no other
// file needs changing, provided the shape is already drawable.

/// Shapes the strip and editor can draw.
enum StrokeShape {
    case circle, square, diamond, triangle, ring, rest
}

struct StrokeVisual {
    var shape: StrokeShape
    var color: PaletteToken?
    var label: String
}

private let STROKES: [Stroke: StrokeVisual] = [
    .open:   StrokeVisual(shape: .circle, color: .clay,  label: "open"),
    .closed: StrokeVisual(shape: .square, color: .strap, label: "closed"),
    // Ready for when these sounds are added:
    // .duggi:  StrokeVisual(shape: .diamond,  color: .duggi, label: "duggi"),
    // .nak:    StrokeVisual(shape: .triangle, color: .nak,   label: "nak"),
]

/// The look of an empty step (a rest — no stroke).
let REST_VISUAL = StrokeVisual(shape: .rest, color: nil, label: "rest")

/// Look up the visual for a stroke value. Unknown and nil both fall back to
/// REST_VISUAL, so a pattern array can be shorter than the loop without throwing.
func strokeVisual(_ value: Stroke?) -> StrokeVisual {
    if let value, let visual = STROKES[value] { return visual }
    return REST_VISUAL
}

/// The colour token for a lane. Lives beside the stroke visuals because both are
/// "how does the strip paint this" facts; adding an instrument means one entry in each.
extension LaneId {
    var colorToken: PaletteToken {
        switch self {
        case .dayan:  .laneDayan
        case .bayan:  .laneBayan
        case .kartal: .laneKartal
        }
    }
}
