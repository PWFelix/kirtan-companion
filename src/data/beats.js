/**
 * beats.js
 * --------
 * Pure beat data. No logic, no dependencies.
 * Transcribed from Sita-pati das, "The Art and Science of Harinam Sankirtan Yajna".
 *
 * FORMAT:
 *   id, name, note, bpm, steps, beatsPerBar, cellsPerGroup
 *   dayan - small end (right hand): "O" open, "X" closed, null silent
 *   bayan - big end   (left hand):  "O" open, "X" closed, null silent
 *
 * BPM = quarter-note PULSE (the 1-2-3-4 you'd clap along to).
 * beatsPerBar = how many of those pulses make one bar.
 *
 * The sequencer derives the per-step interval from steps / beatsPerBar:
 *    8 in 4 → 2 per beat → eighth notes
 *   12 in 4 → 3 per beat → eighth-triplets (dadra "galloping" feel)
 *   16 in 4 → 4 per beat → sixteenth notes
 * Keeping both fields explicit means a future 3/4 or 6/8 beat slots in
 * with no engine changes.
 *
 * cellsPerGroup = the "musical unit" for growing/shrinking a beat: the
 * number of cells in one numbered pulse (= steps / beatsPerBar). The editor
 * adds/removes cells one group at a time, and holds the INVARIANT
 *      steps = beatsPerBar × cellsPerGroup
 * so that each cell's DURATION (PPQ / cellsPerGroup ticks) stays fixed no
 * matter how many cells there are — adding cells makes the loop longer in
 * real time without changing the tempo (subdivision-locked, not bar-locked).
 * For existing beats this is just their true subdivision: 2 for the
 * eighth-note beats, 3 for dadra's triplets, 4 for double-time's sixteenths.
 */

export const BEATS = [
  {
    id: "te_ta", name: "Te Ta", note: "Foundational", bpm: 80, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  null, null,  "X", null,  "O", "O",  null],
  },
  {
    id: "forward", name: "Forward", note: "Everyday", bpm: 90, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  null,  "O",  "O",  "X",  null,  "O", "O"],
    bayan: ["O",  null, null,  "X", null,  "O", "O",  null],
  },
  {
    id: "backward", name: "Backward", note: "Variation", bpm: 90, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "O",  "O",  "O",  "X",  "O",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "funky_swing", name: "Funky Swing", note: "Lively", bpm: 95, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "X",  "X",  "O",  "X",  "X",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "da_ge_te_te", name: "Da Ge Te Te", note: "Build up", bpm: 110, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  "O",  "X",  null, "O",  null, "O",  null],
  },
  {
    id: "prabhupada", name: "Prabhupada", note: "Gentle", bpm: 65, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: [null, null, null, null, "X",  "X",  "O",  "O"],
  },

  // ── Double-time sample (16 steps) ──
  {
    id: "double_time", name: "Double Time", note: "Fast", bpm: 140, steps: 16, beatsPerBar: 4, cellsPerGroup: 4,
    // Forwards top end at double subdivision.
    //        1    e    +    a    2    e    +    a    3    e    +    a    4    e    +    a
    dayan: ["X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O"],
    bayan: ["O", null, null, "X",null, "O","O",null,"O", null,null, "X",null, "O","O",null],
  },

  // ── Dadra taal sample (12 steps, felt as 4/4 with triplets) ──
  {
    id: "dadra", name: "Dadra Taal", note: "Swing", bpm: 105, steps: 6, beatsPerBar: 2, cellsPerGroup: 3,
    // 4 quarter-note pulses, each split into 3 eighth-triplets ("trip-let") —
    // gives the galloping feel without changing the bar count.
    //        1   trip let   2   trip let   3   trip let   4   trip let
    dayan: ["X", null, "O", null, "O", null, "X", null, "O", null, "O", null],
    bayan: ["O", null,null, "X",null,null,null, null,null, "O",null,null],
  },
];
