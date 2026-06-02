/**
 * beats.js
 * --------
 * Pure beat data. No logic, no dependencies.
 * The sequencer RECEIVES beats from here — it never contains them itself.
 *
 * Later, beats could come from the user's device or the cloud instead.
 * Because beats live outside the sequencer, swapping the source
 * never touches the sequencer. (The "providers" principle.)
 *
 * BEAT FORMAT:
 *   name   - human-readable name
 *   steps  - how many positions in one loop (8 = "1 + 2 + 3 + 4 +")
 *   dayan  - top end (right hand): "O" = play, null = silent
 *   bayan  - bottom end (left hand): "O" = play, null = silent
 */

export const BEATS = [
  {
    name: "Te Ta",
    steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  null, "O",  null, "O",  null, "O",  null],
    bayan: ["O",  null, null, null, "O",  null, null, null],
  },
  {
    name: "Simple Roll",
    steps: 8,
    //        1     +     2     +     3     +     4     +
    dayan: ["O",  "O",  "O",  "O",  "O",  "O",  "O",  "O"],
    bayan: ["O",  null, "O",  null, "O",  null, "O",  null],
  },
];