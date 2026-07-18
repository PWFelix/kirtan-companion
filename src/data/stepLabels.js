/**
 * stepLabels.js
 * -------------
 * Pure helper for the "Guided" notation style. Given the loop length and
 * group size, it returns one label per cell: the FIRST cell of every group
 * is a numbered downbeat ("1", "2", …) and every subdivision in between
 * carries a middle dot ("·") — visually lighter than "+", so the numbered
 * pulses read clearly for learners (Group B).
 *
 * The group size is `cellsPerGroup` — the number of cells in one numbered
 * pulse (e.g. 2 for straight eighths, 3 for a triplet/dadra feel).
 *
 * Both the editor and the beat indicator import this so they always agree
 * with what the engine is actually playing.
 */

/**
 * Generate step labels for the "Guided" notation style.
 * Main beats get numbers; subdivisions get a middle dot.
 *
 * @param {number} steps - total cells in the loop
 * @param {number} cellsPerGroup - cells per main beat
 * @returns {string[]} array of labels, one per cell
 */
export function generateGuidedLabels(steps, cellsPerGroup) {
  const labels = [];
  for (let i = 0; i < steps; i++) {
    if (i % cellsPerGroup === 0) {
      const beatNumber = i / cellsPerGroup + 1;
      labels.push(String(beatNumber));
    } else {
      labels.push("·");  // middle dot (U+00B7), visually lighter than "+"
    }
  }
  return labels;
}
