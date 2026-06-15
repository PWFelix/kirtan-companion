/**
 * strokes.js
 * ----------
 * The single source of truth for how each stroke type LOOKS.
 *
 * Every visual indicator reads from here, so the whole app agrees
 * on what an "open" or "closed" stroke looks like. To add a new
 * stroke type later (duggi, nak, kartal...), add ONE entry here and
 * it appears correctly everywhere — no other file needs changing.
 *
 * Each stroke defines:
 *   shape : one of "circle" | "square" | "diamond" | "triangle" | "ring"
 *   color : the fill colour (a CSS variable from our palette)
 *   label : human-readable name (for accessibility / tooltips)
 *
 * Shapes are drawn by BeatIndicator. If you add a brand-new shape
 * name here, add its drawing case in BeatIndicator's renderShape().
 */

export const STROKES = {
  O: { shape: "circle",   color: "var(--saffron)",   label: "open" },
  X: { shape: "square",   color: "var(--gold)",      label: "closed" },

  // ── Ready for when these sounds are added ──
  // Uncomment (and add matching sounds + beat data) to activate.
  // D: { shape: "diamond",  color: "var(--duggi)",  label: "duggi" },
  // N: { shape: "triangle", color: "var(--nak)",    label: "nak" },
  // K: { shape: "ring",     color: "var(--kartal)", label: "kartal" },
};

// The look of an empty step (a rest — no stroke).
export const REST = { shape: "rest", color: "transparent", label: "rest" };

/**
 * Look up the visual for a stroke value.
 * @param {string|null} value - e.g. "O", "X", or null
 * @returns the stroke definition, or REST if empty/unknown.
 */
export function strokeVisual(value) {
  if (value && STROKES[value]) return STROKES[value];
  return REST;
}
