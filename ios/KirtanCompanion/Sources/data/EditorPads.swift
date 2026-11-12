import Foundation

/// Cell-driven entry for the beat editor — the pure rules, shared with
/// `padsFor` in BeatEditor.jsx and `padSetFor` in Android's EditorDraft.kt.
///
/// The cursor is one CELL: a row (dayan, bayan or kartal) and a step. Tapping a
/// cell selects it, and the pads become that row's strokes: its open stroke, its
/// closed stroke, and Rest. Tapping a pad writes the selected cell and moves on to
/// the next cell along the same row, so a row can be typed in one go. A two-hand
/// bol (Da, Gi...) is simply both drum rows filled in the same column.
struct EditorPad: Equatable {
    let lane: LaneId
    /// The stroke written; nil writes a rest.
    let stroke: Stroke?
    /// The bol on the pad (Ta, Khe, Ching...), or "Rest".
    let label: String

    var isRest: Bool { stroke == nil }
}

enum EditorPads {
    /// The palette for one row, left to right: open, closed, rest.
    static func forLane(_ lane: LaneId) -> [EditorPad] {
        [Stroke.open, Stroke.closed].map {
            EditorPad(lane: lane, stroke: $0,
                      label: Bols.forStroke(laneId: lane.wireId, code: $0.rawValue) ?? $0.rawValue)
        } + [EditorPad(lane: lane, stroke: nil, label: "Rest")]
    }

    /// Where entry goes after writing `step`: the next cell on the row, wrapping
    /// at the end of the bar.
    static func nextStep(after step: Int, steps: Int) -> Int {
        steps > 0 ? (step + 1) % steps : 0
    }
}
