/**
 * bols.js
 * -------
 * The spoken names of the strokes — the drum's language.
 *
 * Maps lane id -> stroke value -> bol syllable. The strip shows these
 * when the user turns bols on in Settings, and the future editor's pads
 * are labelled from here (including two-hand combos).
 *
 * NOTE: bol naming varies between traditions. These follow common ISKCON
 * mridanga usage but should be verified against Sita-pati das's "The Art
 * and Science of Harinam Sankirtan Yajna" (the source of beats.js).
 */

export const BOLS = {
  dayan: { O: "Ta", X: "Ti" },
  bayan: { O: "Ge", X: "Ka" },
};

/**
 * Two-hand combinations (dayan+bayan sounding together) get their own
 * names. Keyed "dayanValue+bayanValue". Used by the editor's combo pads.
 */
export const COMBO_BOLS = {
  "O+O": "Dha",
  "X+O": "Dhi",
  "O+X": "Tra",
  "X+X": "Tri",
};
