/**
 * bols.js
 * -------
 * The spoken names of the strokes — the drum's language.
 *
 * Maps lane id -> stroke value -> bol syllable. The strip shows these
 * when the user turns bols on in Settings, and the future editor's pads
 * are labelled from here (including two-hand combos).
 *
 * Names follow the stroke legend in Sita-pati das, "The Art and Science
 * of Harinam Sankirtan Yajna" (p. 43 of the PDF in reference/):
 *   Ta = top open, Te = top closed, Ge = bottom open, Khe = bottom closed,
 *   Da = bottom open + top open (Dha when struck strong together),
 *   Gi = bottom open + top closed.
 * Combinations the book leaves unnamed are labelled by their parts.
 */

export const BOLS = {
  dayan: { O: "Ta", X: "Te" },
  bayan: { O: "Ge", X: "Khe" },
  // Karatalas aren't in the book's mridanga legend, so these are descriptive
  // onomatopoeia rather than transcribed bols: "Ching" the open ring of the
  // two discs, "Chip" the damped/choked strike.
  kartal: { O: "Ching", X: "Chip" },
};

/**
 * Two-hand combinations (dayan+bayan sounding together) get their own
 * names. Keyed "dayanValue+bayanValue". Used by the editor's combo pads.
 */
export const COMBO_BOLS = {
  "O+O": "Da",       // book also lists "Dha" for a strong combined strike
  "X+O": "Gi",
  "O+X": "Ta·Khe",   // not named in the book — labelled by its parts
  "X+X": "Te·Khe",   // not named in the book — labelled by its parts
};
