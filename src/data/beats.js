/**
 * beats.js
 * --------
 * Pure beat data. No logic, no dependencies.
 * Transcribed from Sita-pati das, "The Art and Science of Harinam Sankirtan Yajna".
 *
 * FORMAT:
 *   id, name, note, bpm, steps, beatsPerBar, cellsPerGroup
 *   dayan  - small end (right hand): "O" open, "X" closed, null silent
 *   bayan  - big end   (left hand):  "O" open, "X" closed, null silent
 *   kartal - karatalas (hand cymbals), OPTIONAL: "O" ring, "X" damped, null.
 *            Here the standard "1-2-3": ring on the first three pulses of the
 *            bar, silent on the fourth — the congregation's timekeeper. A beat
 *            without a kartal line simply has no cymbal row.
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
    id: "double_time_2", group: "Sixteenths", name: "Double Time 2", note: "4 beats",
    bpm: 140, steps: 16, beatsPerBar: 4, cellsPerGroup: 4, groups: [4, 4, 4, 4],
    description: null,
    dayan: ["X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O"],
    bayan: ["O", null, null, "X", null, "O", "O", null, "O", null, null, "X", null, "O", "O", null],
    kartal: ["O", null, "X", "X", "O", null, "X", "X", "O", null, "X", "X", "O", null, "X", "X"],
  },
  {
    id: "daspahir_taal", group: "Sixteenths", name: "daspahir taal", note: "8 beats",
    bpm: 90, steps: 32, beatsPerBar: 8, cellsPerGroup: 4, groups: [4, 4, 4, 4, 4, 4, 4, 4],
    description: null,
    dayan: [null, null, null, null, "X", null, "O", null, "X", null, "X", "X", "X", "X", "O", null, null, null, null, null, "O", null, "O", null, null, null, "O", null, null, null, "O", null],
    bayan: ["O", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "X", null, null, "X", null, "O", "O", null, "O", null, "O", null, "O", null, "O", null],
  },
  {
    id: "tehai", group: "Sixteenths", name: "tehai", note: "4 beats",
    bpm: 90, steps: 16, beatsPerBar: 4, cellsPerGroup: 4, groups: [4, 4, 4, 4],
    description: null,
    dayan: [null, "O", null, "O", "O", null, null, "O", null, "O", "O", null, null, "O", null, "O"],
    bayan: ["X", null, "X", null, "O", null, "X", null, "X", null, "O", null, "X", null, "X", null],
  },
  {
    id: "pick_up", group: "Sixteenths", name: "pick up", note: "4 beats",
    bpm: 90, steps: 16, beatsPerBar: 4, cellsPerGroup: 4, groups: [4, 4, 4, 4],
    description: null,
    dayan: ["O", null, "O", "O", null, "O", null, "O", "O", null, "O", "O", null, "O", null, "O"],
    bayan: [null, "X", null, "X", null, null, "X", null, null, "X", null, "X", null, null, "X", null],
  },
  {
    id: "bhajani_taal", group: "Sixteenths", name: "bhajani taal", note: "4 beats",
    bpm: 90, steps: 16, beatsPerBar: 4, cellsPerGroup: 4, groups: [4, 4, 4, 4],
    description: null,
    dayan: [null, null, "O", null, null, null, "O", null, null, null, "O", null, null, null, "O", null],
    bayan: ["O", "O", null, "O", null, "O", null, null, "X", "X", null, "X", null, "X", null, "O"],
  },
  {
    id: "matan", group: "Straight", name: "matan", note: "16 beats",
    bpm: 157, steps: 48, beatsPerBar: 24, cellsPerGroup: 3, groups: [3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3],
    description: null,
    dayan: ["O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", null, "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", "O", null],
    bayan: ["O", "O", "O", "O", "O", null, "O", "O", "O", "O", "O", null, "O", "O", "O", "O", "O", null, "O", "O", null, "O", null, null, "X", "X", "X", "X", "X", null, "X", "X", "X", "X", "X", null, "X", "X", "X", "X", "X", null, "X", "X", null, "X", null, null],
  },
  {
    id: "lofa_taal_two_beat_damodarastakam", group: "Straight", name: "lofa taal two beat damodarastakam", note: "8 beats",
    bpm: 90, steps: 24, beatsPerBar: 12, cellsPerGroup: 3, groups: [3, 3, 3, 3, 3, 3, 3, 3],
    description: null,
    dayan: ["O", null, "X", "X", "X", "X", "O", null, "X", "X", "X", "X", "O", null, "X", "X", "X", "X", "O", null, null, null, "O", "O"],
    bayan: ["O", null, "O", null, "O", null, "O", null, "O", null, "O", null, null, null, null, null, null, null, null, null, "X", "X", null, null],
  },
  {
    id: "iskcon_smasher", group: "Sixteenths", name: "Iskcon smasher", note: "4 beats",
    bpm: 90, steps: 16, beatsPerBar: 4, cellsPerGroup: 4, groups: [4, 4, 4, 4],
    description: null,
    dayan: ["O", null, null, "O", null, null, "O", null, "O", null, null, "O", null, null, "O", null],
    bayan: ["O", null, null, null, "O", null, "O", null, null, null, "O", null, "O", null, "O", null],
  },
  {
    id: "keherva_medium_speed", group: "Straight", name: "keherva medium speed", note: "4 beats",
    bpm: 90, steps: 8, beatsPerBar: 4, cellsPerGroup: 2, groups: [2, 2, 2, 2],
    description: null,
    dayan: ["O", null, "X", "O", "O", null, "X", "O"],
    bayan: ["O", "O", null, "O", null, "O", "O", null],
  },
];
