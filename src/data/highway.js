/**
 * highway.js — the geometry of the Learn "falling strokes" highway.
 * -----------------------------------------------------------------
 * Pure data, ported from the apps' Highway.kt / Highway.swift so the three
 * draw identical notes. Which strokes are on screen, and where; the canvas
 * in LearnView only paints. Everything is in STEPS relative to the playhead:
 * rel = 0 is sounding now (on the hit line); rel = 3 sounds three cells from
 * now, three step-heights above it.
 *
 * Position comes from the engine's bar phase (getPhase() × steps), a
 * continuous playhead; animating off step events would lag by up to a cell.
 *
 * One column per HAND, not per stroke: the eye only needs to know which hand
 * and when — the hand already knows how to make the stroke. So the mridanga is
 * two columns, laid out as the drum sits before a player (bayan, left hand, on
 * the left; dayan on the right), and the karatalas one. Each note still says
 * what it is: open is a disc, closed an X, with its bol beside it.
 * Handedness does not apply to the kartals.
 */
import { BOLS, COMBO_BOLS } from "./bols.js";
import { groupsFor } from "./meter.js";

export const COLUMNS = [{ lane: "bayan" }, { lane: "dayan" }];

/**
 * The columns left to right. COLUMNS is the right-handed layout; a left-handed
 * player sits the drum the other way round (dayan under the left hand), so it
 * mirrors — dayan on the left.
 */
export const columnsFor = (leftHanded) => (leftHanded ? [...COLUMNS].reverse() : COLUMNS);

/** How much time the screen shows ahead of the hit line, in seconds. */
export const LOOKAHEAD_SECONDS = 1.8;
/** How far past the hit line a note keeps drawing (fading), in steps. */
export const TRAIL_STEPS = 1;

const floorMod = (a, n) => ((a % n) + n) % n;

export const KARTAL_COLUMNS = [{ lane: "kartal" }];

/** What Learn can teach. Each instrument's base columns, in default order. */
export const INSTRUMENTS = { mrdanga: COLUMNS, kartal: KARTAL_COLUMNS };

/** The base columns for an instrument (unknown falls back to the mridanga). */
export const baseColumns = (instrument) => INSTRUMENTS[instrument] ?? COLUMNS;

/** The columns left to right for an instrument; only the mridanga mirrors. */
export const columnsForInstrument = (instrument, leftHanded) =>
  instrument === "kartal" ? KARTAL_COLUMNS : columnsFor(leftHanded);

/** True when the beat gives this instrument anything to play. */
export const hasPart = (beat, instrument) =>
  [...new Set(baseColumns(instrument).map((c) => c.lane))]
    .some((lane) => (beat[lane] ?? []).some(Boolean));

export const columnOf = (lane, base = COLUMNS) => base.findIndex((c) => c.lane === lane);

/** Steps visible ahead at this tempo — time-based, never fewer than four. */
export const lookaheadSteps = (bpm, cellsPerQuarter) =>
  Math.max(4, (LOOKAHEAD_SECONDS * bpm * cellsPerQuarter) / 60);

/** The step index at which each group (beat) of the bar begins. */
export function groupBoundaries(beat) {
  const out = new Set();
  let at = 0;
  for (const g of groupsFor(beat)) {
    if (at < beat.steps) out.add(at);
    at += g;
  }
  if (out.size === 0) out.add(0);
  return out;
}

/**
 * Notes ({ column, stroke, bol, step, rel }) and beat lines ({ rel, sam }) visible with
 * the playhead at `position` steps. The beat loops, so the window wraps.
 * `column` indexes `base`, the instrument's columns (default: the mridanga).
 */
export function highwayFrame(beat, position, lookahead, base = COLUMNS) {
  const lanes = [...new Set(base.map((c) => c.lane))];
  const steps = beat.steps;
  if (!(steps > 0)) return { notes: [], lines: [], currentStep: 0 };
  const first = Math.floor(position - TRAIL_STEPS);
  const last = Math.floor(position + lookahead) + 1;
  const bounds = groupBoundaries(beat);
  const notes = [], lines = [];
  for (let abs = first; abs <= last; abs++) {
    const step = floorMod(abs, steps);
    const rel = abs - position;
    for (const lane of lanes) {
      const stroke = beat[lane]?.[step];
      if (!stroke) continue;
      const column = columnOf(lane, base);
      if (column >= 0) notes.push({ column, stroke, bol: BOLS[lane]?.[stroke] ?? "", step, rel });
    }
    if (bounds.has(step)) lines.push({ rel, sam: step === 0 });
  }
  return { notes, lines, currentStep: floorMod(Math.floor(position), steps) };
}

/** The bol spoken at `step`: a two-hand combination if both heads strike. */
export function bolAt(beat, step) {
  const d = beat.dayan?.[step], b = beat.bayan?.[step];
  if (d && b) return COMBO_BOLS[`${d}+${b}`];
  return (d && BOLS.dayan[d]) || (b && BOLS.bayan[b]) || null;
}
