/**
 * Hill climb: Learn's slow, automatic speed-up. "Go up N BPM every X minutes"
 * while the beat plays, so a learner starts where they're comfortable and is
 * walked up towards kirtan speed without touching the controls.
 *
 * Pure rules only — the choices offered, the defaults, and what one climb step
 * does. The countdown lives in LearnView (it only runs while playing). Same
 * numbers as the apps (HillClimb.kt / HillClimb.swift).
 */

/** BPM added per step. Small steps: a learner should barely notice each one. */
export const CLIMB_STEPS = [1, 2, 3, 5, 10];
/** Seconds between steps. */
export const CLIMB_INTERVALS = [30, 60, 120, 180, 300, 600];
export const DEFAULT_CLIMB_STEP = 5;
export const DEFAULT_CLIMB_INTERVAL = 120;

/** One step up, never past `ceiling` (the top of the slider's range, 200 or 500). */
export const climbNext = (bpm, step, ceiling) => Math.max(bpm, Math.min(bpm + step, ceiling));

/** A remembered value snapped to an offered choice (storage may hold anything). */
export const climbStepOf = (raw) => (CLIMB_STEPS.includes(raw) ? raw : DEFAULT_CLIMB_STEP);
export const climbIntervalOf = (raw) => (CLIMB_INTERVALS.includes(raw) ? raw : DEFAULT_CLIMB_INTERVAL);

/** "0:30", "2:00" — the countdown to the next step. */
export function climbClock(seconds) {
  const s = Math.max(0, seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
