import Foundation

// The editor grid's SHAPE, kept apart from the view so its maths is testable.
//
// Two levers change it, and they mean different things:
//   • Beats: how many numbered beats the bar has. ± adds or removes ONE beat: the
//     next group of the feel's `unit` pattern, so 6/8 grows by a 3 and 7/8 by its
//     next 2 or 3 (a whole cycle at once kept 6/8 from landing on 4 bars).
//   • Resolution: how finely each beat is divided. In a SIMPLE meter (every beat
//     one quarter, groups == cpq) it is cells per beat, 1–8. In an ODD meter it is
//     a multiplier on the meter's own grouping, so 7/8's 2+2+3 becomes 4+4+6 and
//     keeps its shape.
// Changing resolution keeps the rhythm: see `resample`. Mirrors Android's EditorDraft.

struct EditorShape: Equatable {
    /// Cells in each numbered beat; their sum is the bar's length in cells.
    var groups: [Int]
    /// Cells per quarter note, which sets the timing.
    var cpq: Int
    /// The feel's group pattern; + appends its next entry.
    var unit: [Int]

    static let simpleResolutions = [1, 2, 3, 4, 6, 8]
    static let multipliers = [1, 2, 3]

    var steps: Int { groups.reduce(0, +) }
    var beatCount: Int { groups.count }
    var beatsPerBar: Double { Double(steps) / Double(cpq) }

    /// The beat + appends: the next group of the feel's pattern.
    var nextGroup: Int { unit.isEmpty ? cpq : unit[groups.count % unit.count] }

    /// ± is refused past MAX_STEPS (384 cells) or MAX_GROUPS beats. Versions before 0.4.35 / iOS 0.4.29 capped beats at 64 cells and drop longer ones.
    var canAddBeat: Bool {
        steps + nextGroup <= ShareCodec.MAX_STEPS && groups.count + 1 <= ShareCodec.MAX_GROUPS
    }
    var canRemoveBeat: Bool { groups.count > 1 }

    func addingBeat() -> EditorShape {
        guard canAddBeat else { return self }
        var s = self; s.groups.append(nextGroup); return s
    }

    func removingBeat() -> EditorShape {
        guard canRemoveBeat else { return self }
        var s = self; s.groups.removeLast(); return s
    }

    /// The tala-style feels' beat sizes: every beat 5, 7 or 9 cells (matras).
    static let talaBases = [5, 7, 9]

    /// The 5/7/9 a cells-per-beat count is a whole multiple (1–3×) of, or nil.
    static func talaBase(_ cpq: Int) -> Int? {
        talaBases.last { cpq % $0 == 0 && (1...3).contains(cpq / $0) }
    }

    // MARK: resolution

    /// True for an odd meter, where the lever is a multiplier.
    var isMultiplier: Bool { groups.isEmpty || !groups.allSatisfy { $0 == cpq } }

    /// The lever's current value: cells per beat, or the multiplier.
    var resolution: Int { isMultiplier ? EditorShape.factor(groups, cpq) : cpq }

    var resolutionOptions: [Int] {
        // A tala feel only ever divides into whole multiples of its beat (7 · 14 · 21).
        if !isMultiplier, let b = EditorShape.talaBase(cpq) { return [b, 2 * b, 3 * b] }
        return Array(Set((isMultiplier ? EditorShape.multipliers : EditorShape.simpleResolutions) + [resolution])).sorted()
    }

    /// The shape at [option] on the lever, or nil when it would pass the format's
    /// caps (MAX_STEPS cells in all, MAX_GROUP_CELLS in one beat).
    func atResolution(_ option: Int) -> EditorShape? {
        let k = resolution
        let shape: EditorShape
        if isMultiplier {
            shape = EditorShape(groups: groups.map { $0 / k * option }, cpq: cpq / k * option,
                                unit: unit.map { $0 / k * option })
        } else {
            shape = EditorShape(groups: Array(repeating: option, count: groups.count), cpq: option,
                                unit: unit.map { $0 / cpq * option })
        }
        guard shape.steps <= ShareCodec.MAX_STEPS, (shape.groups.max() ?? 0) <= ShareCodec.MAX_GROUP_CELLS else { return nil }
        return shape
    }

    /// The largest whole factor shared by every group and cpq.
    static func factor(_ groups: [Int], _ cpq: Int) -> Int {
        func gcd(_ a: Int, _ b: Int) -> Int { b == 0 ? a : gcd(b, a % b) }
        return max(1, groups.reduce(cpq, gcd))
    }

    /// The ± unit a saved beat reopens with: one beat for a uniform bar, a whole
    /// 3+3 / 2+2+3 cycle for 6/8 and 7/8 at any resolution, else the whole bar.
    static func seeded(groups: [Int], cpq: Int) -> EditorShape {
        if let first = groups.first, groups.allSatisfy({ $0 == first }) {
            // 6/8 ×2 is [6,6] at cpq 4: uniform, but still a 3+3 meter.
            let k = factor(groups, cpq)
            if cpq / k == 2, first / k == 3 {
                return EditorShape(groups: groups, cpq: cpq, unit: [3 * k, 3 * k])
            }
            return EditorShape(groups: groups, cpq: cpq, unit: [first])
        }
        let k = factor(groups, cpq)
        let base = groups.map { $0 / k }
        if cpq / k == 2, repeats([2, 2, 3], base) {
            return EditorShape(groups: groups, cpq: cpq, unit: [2 * k, 2 * k, 3 * k])
        }
        return EditorShape(groups: groups, cpq: cpq, unit: groups)
    }

    private static func repeats(_ pattern: [Int], _ arr: [Int]) -> Bool {
        !arr.isEmpty && arr.count % pattern.count == 0
            && arr.indices.allSatisfy { arr[$0] == pattern[$0 % pattern.count] }
    }
}

/// What a resolution change did to the hits.
struct ResampleReport: Equatable {
    var moved: Int
    var dropped: Int
}

/// Move one lane's hits from a grid of `fromCpq` to `toCpq` cells per quarter,
/// keeping each at its moment: it lands on the nearest new cell. A hit that rounds
/// past the end wraps to the downbeat (the bar loops). When two hits meet in one
/// cell the nearer one wins and the other counts as dropped.
func resample(_ cells: [Stroke?], fromCpq: Int, toCpq: Int, steps: Int,
              report: inout ResampleReport) -> [Stroke?] {
    var out = [Stroke?](repeating: nil, count: steps)
    var error = [Double](repeating: .infinity, count: steps)
    for (j, stroke) in cells.enumerated() {
        guard let stroke else { continue }
        let exact = Double(j * toCpq) / Double(fromCpq)
        let target = Int((exact + 0.5).rounded(.down)) % steps
        let err = abs(exact - exact.rounded())
        if (j * toCpq) % fromCpq != 0 { report.moved += 1 }
        if out[target] != nil {
            report.dropped += 1
            if err >= error[target] { continue }
        }
        out[target] = stroke
        error[target] = err
    }
    return out
}
