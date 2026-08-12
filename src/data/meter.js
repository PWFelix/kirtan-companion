/**
 * meter.js
 * --------
 * The pure meter + tempo facts, with no React and no audio behind them.
 *
 * WHY THIS FILE EXISTS: the built-in beats predate the groups model. They
 * carry `steps` / `beatsPerBar` / `cellsPerGroup` but no `groups` array,
 * while editor-made beats carry `groups` and treat it as the truth. Anything
 * that needs a beat's real meter — the editor when it opens one, the share
 * codec when it packs one — has to be able to RECONSTRUCT the missing half.
 * That reconstruction is subtle enough (see cpqFor below) that it should
 * exist once, not once per caller.
 *
 * The two vocabularies, because they are easy to confuse:
 *   groups        one entry per NUMBERED beat, its value = cells it spans.
 *                 7/8 is [2,2,3]. Uneven meters need this.
 *   cpq           cells per QUARTER NOTE — the subdivision density. It sets
 *                 timing: beatsPerBar = steps / cpq, and the Sequencer turns
 *                 that into a per-cell tick interval.
 * These coincide for 4/4-in-eighths ([2,2,2,2], cpq 2) and diverge the moment
 * a beat is grouped in threes: 6/8 is groups [3,3] with cpq 2.
 */

// Tempo bounds. They live here rather than in useTransport so that pure data
// modules can clamp a BPM without importing React, Tone.js and the engine.
// useTransport re-exports them, so its documented surface is unchanged.
export const MIN_BPM = 40, MAX_BPM = 200;

export const sumGroups = (groups) => groups.reduce((a, b) => a + b, 0);

/**
 * A beat's groups — stored if it has them, reconstructed if it doesn't.
 * Assumes a beat object; callers wanting a "no beat yet" default supply
 * their own (the editor seeds a fresh 4/4 bar).
 */
export function groupsFor(beat) {
  if (Array.isArray(beat.groups)) return [...beat.groups];
  const cpg = beat.cellsPerGroup ?? Math.max(1, Math.round(beat.steps / (beat.beatsPerBar ?? 4)));
  const beats = Math.max(1, Math.round(beat.steps / cpg));
  return Array(beats).fill(cpg);
}

/**
 * A beat's cells-per-quarter. Derived from the TIMING fields, never from
 * cellsPerGroup — for 6/8 the editor saves cellsPerGroup 3 while cpq is 2,
 * so reading cellsPerGroup here would make the beat play half again too fast.
 */
export const cpqFor = (beat) =>
  Math.max(1, Math.round(beat.steps / (beat.beatsPerBar ?? 4)));
