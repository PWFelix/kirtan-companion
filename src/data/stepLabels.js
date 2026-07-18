/**
 * stepLabels.js
 * -------------
 * Pure helper. Given (steps, beatsPerBar), returns the count labels for
 * each cell in the traditional spoken style, plus which cells are
 * "strong" beats (the numbered downbeats).
 *
 *   subdivPerBeat = steps / beatsPerBar
 *      2  →  number + "+"             ("1 + 2 + 3 + 4 +")
 *      3  →  number + "trip" + "let"  triplet feel (12/4 dadra)
 *      4  →  number + "e" + "+" + "a" (16/4 double-time)
 *
 * Both the editor and the beat indicator import this so they always agree
 * with what the engine is actually playing.
 */

export function getStepLabels(steps, beatsPerBar = 4) {
  const subdivPerBeat = steps / beatsPerBar;

  let subs;
  if (subdivPerBeat === 2) subs = ["", "+"];
  else if (subdivPerBeat === 3) subs = ["", "trip", "let"];
  else if (subdivPerBeat === 4) subs = ["", "e", "+", "a"];
  // Fallback for non-integer or unusual ratios: just mark downbeats.
  else subs = Array.from({ length: Math.max(1, Math.floor(subdivPerBeat)) }, (_, i) => (i === 0 ? "" : "·"));

  return Array.from({ length: steps }, (_, i) => {
    const inBeat = i % subdivPerBeat;
    const isDownbeat = inBeat === 0;
    return {
      text: isDownbeat ? String(Math.floor(i / subdivPerBeat) + 1) : (subs[inBeat] ?? ""),
      strong: isDownbeat,
    };
  });
}
