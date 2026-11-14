/**
 * syncCalibration — turns the sync test's taps into a display delay.
 *
 * Each tap arrives as its offset (ms) from the nearest click, measured
 * against what the browser SAYS is being heard. A listener taps on the sound,
 * so the typical offset is the delay the browser left out.
 *
 * MEDIAN, not mean: one slip of 300 ms would drag a mean by 25 ms over
 * twelve taps. The spread is the median absolute deviation for the same
 * reason, and taps spread wider than MAX_SPREAD_MS are refused ("try again")
 * rather than turned into a confident wrong answer. The first WARM_UP taps
 * are dropped while the listener locks on.
 *
 * Mirrors SyncCalibration in the Android and iOS apps, constant for constant.
 */

export const WARM_UP = 2;
export const TAPS_NEEDED = 10;
export const MAX_SPREAD_MS = 45;
export const ROUND_TO_MS = 5;

/** Bounds for a display delay; negative is for an output that over-reports. */
export const DISPLAY_DELAY_MIN_MS = -200;
export const DISPLAY_DELAY_MAX_MS = 600;

/** The sync test's tempo: slow, so ±430 ms of delay is measurable. */
export const SYNC_TEST_BPM = 70;

/** One closed dayan stroke on every beat: the sharpest attack in the kit. */
export const SYNC_TEST_BEAT = {
  id: "sync-test",
  name: "Sync test",
  note: "",
  bpm: SYNC_TEST_BPM,
  steps: 4,
  beatsPerBar: 4,
  dayan: ["X", "X", "X", "X"],
  bayan: [null, null, null, null],
};

export function clampDelay(ms) {
  const n = Math.round(Number(ms));
  if (!Number.isFinite(n)) return 0;
  return Math.min(DISPLAY_DELAY_MAX_MS, Math.max(DISPLAY_DELAY_MIN_MS, n));
}

export function isComplete(offsets) {
  return offsets.length >= WARM_UP + TAPS_NEEDED;
}

/**
 * `{ delay: ms }`, `{ tooUneven: true }`, or null until enough taps.
 */
export function estimate(offsets) {
  if (!isComplete(offsets)) return null;
  const taps = offsets.slice(WARM_UP);
  const mid = median(taps);
  const spread = median(taps.map((t) => Math.abs(t - mid)));
  if (spread > MAX_SPREAD_MS) return { tooUneven: true };
  return { delay: Math.round(mid / ROUND_TO_MS) * ROUND_TO_MS };
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const m = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
}
