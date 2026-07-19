/**
 * lanes.js
 * --------
 * The single source of truth for the INSTRUMENT ROWS on the beat strip.
 *
 * A lane = one horizontal row of the visualizer/editor: an instrument
 * (or one end of the mridanga). Adding an instrument later (kartal,
 * harmonium melody, ...) is ONE entry here — the strip, the mixer and
 * the editor all read this list; the colour comes from the matching
 * --lane-* token in index.css and the sounds route by the id prefix
 * (see SoundPlayer's routing convention).
 *
 * Order matters: lanes render top-to-bottom in this order.
 */

export const LANES = [
  { id: "dayan", label: "Dayan", color: "var(--lane-dayan)" },
  { id: "bayan", label: "Bayan", color: "var(--lane-bayan)" },

  // ── Ready for when these instruments are added ──
  // { id: "kartal", label: "Kartal", color: "var(--lane-kartal)" },
  // { id: "melody", label: "Melody", color: "var(--lane-melody)" },
];
