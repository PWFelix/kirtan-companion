/**
 * beats.js
 * --------
 * Pure beat data. No logic, no dependencies.
 * Transcribed from Sita-pati das, "The Art and Science of Harinam Sankirtan Yajna".
 *
 * FORMAT:
 *   id, name, note, bpm, steps
 *   dayan - small end (right hand): "O" open, "X" closed, null silent
 *   bayan - big end   (left hand):  "O" open, "X" closed, null silent
 *
 * STEP COUNTS:
 *   8  = standard 4/4
 *   16 = double-time
 *   12 = dadra taal (6/8 swing)
 */

export const BEATS = [
  {
    id: "te_ta", name: "Te Ta", note: "Foundational", bpm: 80, steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "forward", name: "Forward", note: "Everyday", bpm: 90, steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "O",  "X",  "O",  "O",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "backward", name: "Backward", note: "Variation", bpm: 90, steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "X",  "O",  "O",  "X",  "O",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "funky_swing", name: "Funky Swing", note: "Lively", bpm: 95, steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "X",  "X",  "O",  "X",  "X",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "da_ge_te_te", name: "Da Ge Te Te", note: "Build up", bpm: 110, steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  "O",  "X",  null, "O",  null, "O",  null],
  },
  {
    id: "prabhupada", name: "Prabhupada", note: "Gentle", bpm: 65, steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: [null, null, null, null, "X",  "X",  "O",  "O"],
  },

  // ── Double-time sample (16 steps) ──
  {
    id: "double_time", name: "Double Time", note: "Fast", bpm: 140, steps: 16,
    // Forwards top end at double subdivision.
    //        1    e    +    a    2    e    +    a    3    e    +    a    4    e    +    a
    dayan: ["X", "O", "O", "X", "O", "O", "X", "O", "O", "X", "O", "O", "X", "O", "O", "X"],
    bayan: ["O", null,"X", null,"O", null,null,null,"O", null,"X", null,"O", null,null,null],
  },

  // ── Dadra taal sample (12 steps) ──
  {
    id: "dadra", name: "Dadra Taal", note: "Swing", bpm: 105, steps: 12,
    // 6/8 galloping swing feel.
    //        1    +    2    +    3    +    4    +    5    +    6    +
    dayan: ["X", "O", "O", "X", "O", "O", "X", "O", "O", "X", "O", "O"],
    bayan: ["O", null,"X", null,null,null,"O", null,"X", null,null,null],
  },
];
