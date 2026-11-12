/**
 * Hill climb: Learn's slow, automatic speed-up. "Go up N BPM every X minutes"
 * while the beat plays, so a learner starts where they're comfortable and is
 * walked up towards kirtan speed without touching the controls.
 *
 * Pure rules only — the choices offered, the defaults, and what one climb step
 * does. The countdown lives in LearnView (it only runs while playing). Same
 * numbers as the apps (HillClimb.kt / HillClimb.swift).
 */

/** BPM added per step: steps a learner can feel, from a nudge to a real climb. */
export const CLIMB_STEPS = [5, 10, 15, 20, 25];
/** Seconds between steps. */
export const CLIMB_INTERVALS = [30, 60, 120, 180, 300, 600];
export const DEFAULT_CLIMB_STEP = 5;
export const DEFAULT_CLIMB_INTERVAL = 120;

/** One step up, never past `ceiling` (the top of the slider's range, 200 or 500). */
export const climbNext = (bpm, step, ceiling) => Math.max(bpm, Math.min(bpm + step, ceiling));

/** A remembered value snapped to an offered choice (storage may hold anything). */
/**
 * SNAP TO BAR. A step doesn't land mid-phrase: the new tempo starts on the 1
 * nearest the moment the countdown runs out — up to half a bar before or after.
 * So it is handed to the engine this many whole seconds BEFORE the deadline
 * (half a bar rounded up, plus one for the countdown's own granularity), early
 * enough that the nearest 1 is still ahead. The engine picks that 1.
 */
export const climbLeadSeconds = (bpm, beatsPerBar = 4) =>
  Math.ceil((60 / Math.max(1, bpm)) * beatsPerBar / 2) + 1;

/**
 * Ticks from now to the bar line nearest a point `ahead` ticks away, given
 * `into` ticks already into a `bar`-tick bar — never one already played.
 */
export function ticksToNearestBar(into, ahead, bar) {
  let line = Math.round((into + ahead) / bar) * bar;
  while (line < into) line += bar;
  return line - into;
}

export const climbStepOf = (raw) => (CLIMB_STEPS.includes(raw) ? raw : DEFAULT_CLIMB_STEP);
export const climbIntervalOf = (raw) => (CLIMB_INTERVALS.includes(raw) ? raw : DEFAULT_CLIMB_INTERVAL);

/** "0:30", "2:00" — the countdown to the next step. */
export function climbClock(seconds) {
  const s = Math.max(0, seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
