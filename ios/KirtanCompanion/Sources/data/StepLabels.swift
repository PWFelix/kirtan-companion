// Pure helper for the "Guided" notation style.
//
// Ported from src/data/stepLabels.js. Given the loop length and group size it returns
// one label per cell: the FIRST cell of every group is a numbered downbeat
// ("1", "2", …) and every subdivision in between carries a middle dot ("·") — visually
// lighter than "+", so the numbered pulses read clearly for learners (Group B).
//
// Both the editor and the beat strip import this so they always agree with what the
// engine is actually playing.

/// The subdivision dot. U+00B7, deliberately lighter than "+".
let SUBDIVISION_LABEL = "·"

/// Labels for a uniform meter.
/// - steps: total cells in the loop
/// - cellsPerGroup: cells per main beat (2 for straight eighths, 3 for a triplet feel)
func generateGuidedLabels(steps: Int, cellsPerGroup: Int) -> [String] {
    let cpg = cellsPerGroup > 0 ? cellsPerGroup : 1
    return (0..<steps).map { i in
        i % cpg == 0 ? String(i / cpg + 1) : SUBDIVISION_LABEL
    }
}

/// Labels from an explicit GROUPS array — one entry per numbered beat, its value how
/// many cells it spans. Supports uneven meters like 7/8 [2,2,3] that a single
/// cellsPerGroup can't express.
func labelsFromGroups(_ groups: [Int]) -> [String] {
    var out: [String] = []
    for (n, g) in groups.enumerated() {
        out.append(String(n + 1))
        for _ in 0..<(g - 1) { out.append(SUBDIVISION_LABEL) }
    }
    return out
}
