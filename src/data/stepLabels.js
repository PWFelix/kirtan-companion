/**
 * stepLabels.js
 * -------------
 * Pure helper. Given a step count, returns the count labels for each cell
 * in the traditional spoken style, plus which cells are "strong" beats.
 *
 *   8 steps  (standard 4/4):   1 + 2 + 3 + 4 +
 *   16 steps (double-time):    1 e + a 2 e + a 3 e + a 4 e + a
 *   12 steps (dadra taal 6/8): 1 + 2 + 3 + 4 + 5 + 6 +
 *
 * No logic about audio or UI — just turns a number into labels.
 * Both the editor and the beat indicator import this so they always agree.
 */

export function getStepLabels(steps) {
  if (steps === 16) {
    // Four subdivisions per beat: the number, then "e", "+", "a".
    const sub = ["", "e", "+", "a"];
    return Array.from({ length: 16 }, (_, i) => {
      const inBeat = i % 4;
      return {
        text: inBeat === 0 ? String(i / 4 + 1) : sub[inBeat],
        strong: inBeat === 0,            // the numbered beats are strong
      };
    });
  }

  if (steps === 12) {
    // Dadra taal: count as 6 beats, each split into beat-number then "+".
    return Array.from({ length: 12 }, (_, i) => {
      const isNum = i % 2 === 0;
      return {
        text: isNum ? String(i / 2 + 1) : "+",
        strong: isNum,
      };
    });
  }

  // Default: 8 steps — "1 + 2 + 3 + 4 +"
  return Array.from({ length: 8 }, (_, i) => {
    const isNum = i % 2 === 0;
    return {
      text: isNum ? String(i / 2 + 1) : "+",
      strong: isNum,
    };
  });
}
