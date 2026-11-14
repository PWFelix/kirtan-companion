package com.kirtan.companion.data

/**
 * Pure helper for the "Guided" notation style.
 *
 * Ported from `src/data/stepLabels.js`. Given the loop length and group size it
 * returns one label per cell: the FIRST cell of every group is a numbered
 * downbeat ("1", "2", …) and every subdivision in between carries a middle dot
 * ("·") — visually lighter than "+", so the numbered pulses read clearly for
 * learners (Group B).
 *
 * Both the editor and the beat strip import this so they always agree with what
 * the engine is actually playing.
 */

/** The subdivision dot. U+00B7, deliberately lighter than "+". */
const val SUBDIVISION_LABEL = "·"

/**
 * Labels for a uniform meter.
 * @param steps total cells in the loop
 * @param cellsPerGroup cells per main beat (2 for straight eighths, 3 for a
 *   triplet/dadra feel)
 */
fun generateGuidedLabels(steps: Int, cellsPerGroup: Int): List<String> {
    val cpg = if (cellsPerGroup > 0) cellsPerGroup else 1
    return (0 until steps).map { i ->
        if (i % cpg == 0) (i / cpg + 1).toString() else SUBDIVISION_LABEL
    }
}

/**
 * Labels from an explicit GROUPS array — one entry per numbered beat, its value
 * how many cells it spans. Supports uneven meters like 7/8 [2,2,3] that a single
 * cellsPerGroup can't express.
 */
fun labelsFromGroups(groups: List<Int>): List<String> = buildList {
    groups.forEachIndexed { n, g ->
        add((n + 1).toString())
        repeat(g - 1) { add(SUBDIVISION_LABEL) }
    }
}
