import Foundation

/// Swing: every second cell of a beat played late, so straight 1-and-2-and
/// becomes long-short, long-short.
///
/// Stored as the share of a PAIR of cells the first one takes: 50 is straight,
/// 67 is the triplet lilt (two thirds, one third). Kept per beat (`Beat.swing`)
/// so a beat carries its own feel when shared; the mixer can nudge it live for
/// the beat that is playing.
///
/// Only beats split into an EVEN number of cells swing — the 2nd, 4th… cell of
/// each. A beat of three cells is already a triplet; delaying one of them would
/// just be wrong, so those play as written. The same rules as Android and web.
enum Swing {
    static let straight = 50
    static let max = 67

    static func clamp(_ value: Int) -> Int { Swift.min(Swift.max(value, straight), Swing.max) }

    /// For each cell, true when it is a swung (late) one: an odd position inside
    /// a beat with an even number of cells. Built once per beat, off the audio
    /// thread's hot path.
    static func offbeats(_ groups: [Int]) -> [Bool] {
        var out: [Bool] = []
        out.reserveCapacity(groups.reduce(0, +))
        for g in groups {
            for i in 0..<Swift.max(g, 0) { out.append(g % 2 == 0 && i % 2 == 1) }
        }
        return out
    }

    /// How late a swung cell plays, as a fraction of one cell: a pair lasting 2
    /// gives the first `2 × swing%`, so the second starts `2 × swing% − 1` of a
    /// cell late. 0 when straight.
    static func lateFraction(_ swing: Int) -> Double { 2 * Double(clamp(swing)) / 100 - 1 }
}
