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
    description: "The first pattern every player drills: “te ta te ta” on the small head over the standard off-beat, moving the kirtan along in a bopping fashion. The ringing open “ta” is the heart of it — the closed “te” is just a touch that stops the ring, not a slap. A steady, roomy beat for learning and for kirtans that should bounce gently rather than drive.",
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  null, null,  "X", null,  "O", "O",  null],
  },
  {
    id: "forward", name: "Forward", note: "Everyday", bpm: 90, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    description: "The everyday “Forwards” beat — “te tata, te tata”, with the double open strike pushing each phrase ahead. A reliable default for congregational chanting at a walking tempo, and the same pattern becomes the fast double-time beat when the kirtan takes off.",
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  null,  "O",  "O",  "X",  null,  "O", "O"],
    bayan: ["O",  null, null,  "X", null,  "O", "O",  null],
  },
  {
    id: "backward", name: "Backward", note: "Variation", bpm: 90, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    description: "The reverse of Forward — “ta te tata” — a phrasing common in North Indian tabla playing. Swap it in against Forward to keep a long kirtan fresh without changing the feel; at double speed it becomes the top end of a fired-up Vrindavan-mellows style beat.",
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "O",  "O",  "O",  "X",  "O",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "funky_swing", name: "Funky Swing", note: "Lively", bpm: 95, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    description: "From a Vrindavan-mellows beat “that has a really funky swing to it” — the pair of closed strokes after each open one gives the bounce. Good for long stretches of chanting the same melody, such as when the microphone is being passed around the kirtan.",
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "X",  "X",  "O",  "X",  "X",  null, null],
    bayan: ["O",  null, "X",  null, "O",  null, "O",  null],
  },
  {
    id: "da_ge_te_te", name: "Da Ge Te Te", note: "Build up", bpm: 110, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    description: "“Da ge te te take dhena” — a beat from Bablu das, used when you want to ramp the kirtan up. The extra open bass at the top of the bar builds momentum: start with Forward, and move to this as the energy climbs toward the fast section.",
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: ["O",  "O",  "X",  null, "O",  null, "O",  null],
  },
  {
    id: "prabhupada", name: "Prabhupada", note: "Gentle", bpm: 65, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
    description: "A slow, spacious beat in the style of Srila Prabhupada’s own playing: the bass head sits silent through the first half of the cycle, then answers. For early-morning programs, bhajans, and chanting that should stay meditative — let it breathe at a low tempo.",
    //        1     +     2     +     3     +     4     +
    dayan: ["X",  "O",  "X",  "O",  "X",  "O",  "X",  "O"],
    bayan: [null, null, null, null, "X",  "X",  "O",  "O"],
  },

  // ── Double-time sample (16 steps) ──
  {
    id: "double_time", name: "Double Time", note: "Fast", bpm: 140, steps: 16, beatsPerBar: 4, cellsPerGroup: 4,
    description: "Forward doubled into sixteenths — the double-time beat used for the Nrsimha prayers and the Pancha-tattva mantra. For the fast section of kirtan when the chant doubles up; keep it controlled so the singers can stay with you.",
    // Forwards top end at double subdivision.
    //        1    e    +    a    2    e    +    a    3    e    +    a    4    e    +    a
    dayan: ["X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O"],
    bayan: ["O", null, null, "X",null, "O","O",null,"O", null,null, "X",null, "O","O",null],
  },

  // ── Dadra taal sample (12 steps, felt as 4/4 with triplets) ──
  {
    id: "dadra", name: "Dadra Taal", note: "Swing", bpm: 105, steps: 6, beatsPerBar: 2, cellsPerGroup: 3,
    description: "A 6/8 dadra-taal pattern that lands as a triplet “gallop” against the usual four-beat kirtan, making everything swing. Lovely under swaying melodies and Vrindavan-mellows moods — use it as seasoning rather than the whole meal, or open a kirtan in dadra and switch to double time as it builds.",
    // 4 quarter-note pulses, each split into 3 eighth-triplets ("trip-let") —
    // gives the galloping feel without changing the bar count.
    //        1   trip let   2   trip let   3   trip let   4   trip let
    dayan: ["X", null, "O", null, "O", null, "X", null, "O", null, "O", null],
    bayan: ["O", null,null, "X",null,null,null, null,null, "O",null,null],
  },
];
