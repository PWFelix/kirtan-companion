import Foundation

/// The editor timeline's arithmetic (ui/editor/EditorTimeline.swift), kept in the data layer
/// so the tests can reach it. Positions are in cells, unwrapped: the bar loops, so row -1 is
/// the previous cycle's last cell and row `steps` the next cycle's first.
enum EditorTimelineMath {
    /// The cell index (0..<steps) of unwrapped row `row`.
    static func wrap(_ row: Int, steps: Int) -> Int {
        steps <= 0 ? 0 : ((row % steps) + steps) % steps
    }

    /// The unwrapped row a point `dyUp` points ABOVE the centre line falls in, at `pos`.
    static func row(at pos: Double, dyUp: Double, cell: Double) -> Int {
        Int((pos + dyUp / cell).rounded())
    }

    /// The unwrapped position nearest `from` that shows cell `index` on the line.
    static func nearest(_ index: Int, from: Double, steps: Int) -> Double {
        guard steps > 0 else { return 0 }
        return Double(index) + ((from - Double(index)) / Double(steps) + 0.5).rounded(.down) * Double(steps)
    }

    /// Which loop `row` belongs to, counting the one on the line as 0.
    static func cycle(of row: Int, steps: Int) -> Int {
        steps <= 0 ? 0 : Int((Double(row) / Double(steps)).rounded(.down))
    }

    /// The columns left to right, as on the Learn highway: a right-handed player's bayan
    /// (left hand) on the left and dayan on the right; `leftHanded` mirrors the drum.
    /// Kartal has no side, so it stays on the far right either way.
    static func columns(leftHanded: Bool) -> [LaneId] {
        let drum: [LaneId] = leftHanded ? [.dayan, .bayan] : [.bayan, .dayan]
        return drum + LaneId.ordered.filter { !drum.contains($0) }
    }
}
