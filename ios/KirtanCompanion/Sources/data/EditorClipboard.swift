import Foundation

/// Copy and paste in the beat editor, as pure functions over the lane arrays.
///
/// A run of cells is copied across EVERY lane at once — what was played at those
/// moments, not one hand of it. The common case is a beat whose second half
/// repeats the first with the bayan closed (a guru half, then its laghu half),
/// so pasting has a variant that turns the bayan's open strokes closed. The same
/// rules as Android's EditorDraft and the web's data/clipboard.js.
enum EditorClipboard {
    /// One copied run: every lane's cells, all the same length.
    struct Clip: Equatable {
        var lanes: [LaneId: [Stroke?]]
        var count: Int { lanes.values.first?.count ?? 0 }
    }

    /// The run between two cells, whichever way round they are given.
    static func run(_ a: Int, _ b: Int) -> ClosedRange<Int> { min(a, b)...max(a, b) }

    static func copy(_ lanes: [LaneId: [Stroke?]], _ range: ClosedRange<Int>) -> Clip {
        Clip(lanes: lanes.mapValues { cells in
            range.map { $0 < cells.count ? cells[$0] : nil }
        })
    }

    /// Write `clip` over the cells from `at` on, stopping at the end of the beat
    /// (nothing wraps round). Returns the new lanes and how many cells were written.
    static func paste(_ clip: Clip, into lanes: [LaneId: [Stroke?]], at: Int, closeBayan: Bool = false)
        -> (lanes: [LaneId: [Stroke?]], written: Int)
    {
        var out = lanes
        var written = 0
        for (lane, cells) in lanes {
            guard let src = clip.lanes[lane] else { continue }
            var next = cells
            let n = max(0, min(src.count, cells.count - at))
            for i in 0..<n {
                let v = src[i]
                next[at + i] = closeBayan && lane == .bayan && v == .open ? .closed : v
            }
            out[lane] = next
            written = max(written, n)
        }
        return (out, written)
    }
}
