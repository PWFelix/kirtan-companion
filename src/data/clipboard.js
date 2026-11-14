/**
 * Copy and paste in the beat editor, as pure functions over the lane arrays.
 *
 * A run of cells is copied across EVERY lane at once — what was played at
 * those moments, not one hand of it. The common case is a beat whose second
 * half repeats the first with the bayan closed (a guru half, then its laghu
 * half), so pasting has a variant that turns the bayan's open strokes closed.
 * Android's EditorDraft and iOS's EditorDraft do the same.
 */

/** The run between two cells, in order, whichever way round they are given. */
export function runBetween(a, b) {
  return [Math.min(a, b), Math.max(a, b)];
}

/** Every lane's cells from `from` to `to` inclusive. */
export function copyRun(lanes, from, to) {
  const clip = {};
  for (const [lane, cells] of Object.entries(lanes)) clip[lane] = cells.slice(from, to + 1);
  return clip;
}

export function clipLength(clip) {
  return clip ? (Object.values(clip)[0]?.length ?? 0) : 0;
}

/**
 * Write `clip` over the cells from `at` on, stopping at the end of the beat
 * (nothing wraps round). Returns { lanes, written }. With `closeBayan`, the
 * bayan's opens land as closed.
 */
export function pasteRun(lanes, clip, at, closeBayan = false) {
  const out = {};
  let written = 0;
  for (const [lane, cells] of Object.entries(lanes)) {
    const src = clip?.[lane];
    if (!src) { out[lane] = cells; continue; }
    const next = [...cells];
    const n = Math.max(0, Math.min(src.length, cells.length - at));
    for (let i = 0; i < n; i++) {
      const v = src[i];
      next[at + i] = closeBayan && lane === "bayan" && v === "O" ? "X" : v;
    }
    out[lane] = next;
    written = Math.max(written, n);
  }
  return { lanes: out, written };
}
