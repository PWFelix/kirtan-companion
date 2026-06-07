/**
 * beats.js
 * --------
 * Pure beat data. No logic, no dependencies.
 * The sequencer RECEIVES beats from here — it never contains them itself.
 *
 * Transcribed from the notation in Sita-pati das,
 * "The Art and Science of Harinam Sankirtan Yajna".
 *
 * BEAT FORMAT:
 *   id     - unique key
 *   name   - human-readable name
 *   note   - short descriptor shown under the name on the card
 *   bpm    - sensible default tempo
 *   steps  - positions in one loop (8 = "1 + 2 + 3 + 4 +")
 *   dayan  - small end (right hand): "O" open, "X" closed, null silent
 *   bayan  - big end   (left hand):  "O" open, "X" closed, null silent
 *
 * NOTATION REMINDER (from the book):
 *   The count is "1 + 2 + 3 + 4 +"  — 8 evenly spaced positions.
 *   On the dayan, "X" is the muted "te" and "O" is the ringing "ta".
 *
 * These are all 8-step (standard 4/4) beats so they work with the
 * current sequencer. 16-step (double-time) and 12-step (dadra taal)
 * beats come later, once the sequencer supports flexible step timing.
 */

export const BEATS = [
  {
    id: "te_ta",
    name: "Te Ta",
    note: "Foundational",
    bpm: 80,
    steps: 8,
    // Book beat #7 — "Te Ta standard beat"
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  null, null, "X",  null,  "O", "O",  null],
  },
  {
    id: "forward",
    name: "Forward",
    note: "Everyday",
    bpm: 90,
    steps: 8,
    // Book beat #8 — "Forward standard beat"
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "O",  "X",  "O",  "O",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "backward",
    name: "Backward",
    note: "Variation",
    bpm: 90,
    steps: 8,
    // Book beat #9 — "Backward standard beat"
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "X",  "O",  "O",  "X",  "O",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "funky_swing",
    name: "Funky Swing",
    note: "Lively",
    bpm: 95,
    steps: 8,
    // Book beat #10 — "Funky swing standard beat"
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "X",  "X",  "O",  "X",  "X",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "da_ge_te_te",
    name: "Da Ge Te Te",
    note: "Build up",
    bpm: 110,
    steps: 8,
    // Book beat #12 — "Da ge te te take dhena" (off-beat top end)
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  "O",  "X",  null, "O",  null, "O",  null],
  },
  {
    id: "prabhupada",
    name: "Prabhupada",
    note: "Gentle",
    bpm: 65,
    steps: 8,
    // Book beat #47 area — "Prabhupada" beat, Te Ta top end, simplified to 8 steps
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: [null, null, null, null, "X",  "X",  "O",  "O"],
  },
];
