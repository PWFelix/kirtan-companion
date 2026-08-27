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
 *
 * `primary` lanes are a beat's rhythmic identity (the mridanga). MINI
 * strips (beat lists, quick-pick) render primary lanes only — at 8px
 * marks, four stacked lanes are noise, and a melody line is unreadable.
 * Non-primary tracks will show as small instrument chips beside the
 * mini instead; the full strip and the info sheet render every lane.
 */

export const LANES = [
  { id: "dayan", label: "Dayan", color: "var(--lane-dayan)", primary: true },
  { id: "bayan", label: "Bayan", color: "var(--lane-bayan)", primary: true },

  // Karatalas (hand cymbals) — the congregation's timekeeper. NOT primary:
  // they colour a beat, they don't define its rhythmic identity, so mini
  // strips leave them out and the full strip only draws the row when a beat
  // actually uses it (see BeatStrip's lane filter).
  { id: "kartal", label: "Kartal", color: "var(--lane-kartal)", primary: false },

  // ── Ready for when this instrument is added ──
  // { id: "melody", label: "Melody", color: "var(--lane-melody)", primary: false },
];
