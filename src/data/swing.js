/**
 * Swing: every second cell of a beat played late, so straight 1-and-2-and
 * becomes long-short, long-short.
 *
 * Stored on a beat as `swing`, the share of a PAIR of cells the first one
 * takes: 50 is straight, 67 the triplet lilt. Omitted when straight, so a
 * straight beat's stored and shared forms are unchanged. Only beats split into
 * an EVEN number of cells swing; a beat of three is already a triplet, so it
 * plays as written. The same rules as Android's data/Swing.kt and iOS.
 */
export const SWING_STRAIGHT = 50;
export const SWING_MAX = 67;

export function clampSwing(v) {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) return SWING_STRAIGHT;
  return Math.min(SWING_MAX, Math.max(SWING_STRAIGHT, n));
}

/** For each cell, true when it swings: an odd position in an even beat. */
export function swingOffbeats(groups) {
  const out = [];
  for (const g of groups) for (let i = 0; i < g; i++) out.push(g % 2 === 0 && i % 2 === 1);
  return out;
}

/** How late a swung cell plays, as a fraction of one cell (0 when straight). */
export function lateFraction(swing) {
  return (2 * clampSwing(swing)) / 100 - 1;
}
