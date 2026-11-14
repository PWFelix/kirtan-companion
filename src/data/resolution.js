// The editor's second lever: how finely each beat is divided.
//
// Two levers shape the grid, and they mean different things:
//   • Beats — how many numbered beats the bar has (± adds one meter `unit`).
//   • Resolution — how finely each beat is divided. In a SIMPLE meter (every
//     beat one quarter, groups == cpq) it is cells per beat, 1–8. In an ODD meter
//     (6/8, 7/8, anything whose groups differ from cpq) it is a multiplier on the
//     meter's own grouping, so 7/8's 2+2+3 becomes 4+4+6 and keeps its shape.
//
// Changing resolution KEEPS THE RHYTHM (see `resample`). Pure, no React, and the
// same maths as Android's EditorDraft and iOS's EditorShape.

import { MAX_STEPS, MAX_GROUP_CELLS } from "./shareCodec.js";

export const SIMPLE_RESOLUTIONS = [1, 2, 3, 4, 6, 8];
export const MULTIPLIERS = [1, 2, 3];
/** The tala-style feels' beat sizes (editorDraft METERS t5/t7/t9). */
export const TALA_BASES = [5, 7, 9];

/** The 5/7/9 a cells-per-beat count is a whole multiple (1–3×) of, or null. */
export function talaBase(cpq) {
  return [...TALA_BASES].reverse().find((b) => cpq % b === 0 && cpq / b >= 1 && cpq / b <= 3) ?? null;
}

const gcd = (a, b) => (b === 0 ? a : gcd(b, a % b));

/** The largest whole factor shared by every group and cpq (at least 1). */
export function resolutionFactor(groups, cpq) {
  return Math.max(1, groups.reduce(gcd, cpq));
}

/** The lever for a grid: { multiplier, current, options }. */
export function leverFor(groups, cpq) {
  if (groups.length && groups.every((g) => g === cpq)) {
    const b = talaBase(cpq);
    if (b) return { multiplier: false, current: cpq, options: [b, 2 * b, 3 * b] };
    return { multiplier: false, current: cpq, options: [...new Set([...SIMPLE_RESOLUTIONS, cpq])].sort((a, b) => a - b) };
  }
  const k = resolutionFactor(groups, cpq);
  return { multiplier: true, current: k, options: [...new Set([...MULTIPLIERS, k])].sort((a, b) => a - b) };
}

/**
 * The grid at `option` on the lever: { groups, cpq, unit }, or null when it would
 * pass the format's caps (MAX_STEPS cells in all, MAX_GROUP_CELLS in one beat) — older apps reject those.
 */
export function reshape(groups, cpq, unit, option) {
  const lever = leverFor(groups, cpq);
  const scale = (x) => (lever.multiplier ? (x / lever.current) * option : (x / cpq) * option);
  const next = {
    groups: lever.multiplier ? groups.map(scale) : groups.map(() => option),
    cpq: scale(cpq),
    unit: unit.map(scale),
  };
  const total = next.groups.reduce((a, b) => a + b, 0);
  if (total > MAX_STEPS || Math.max(...next.groups) > MAX_GROUP_CELLS) return null;
  return next;
}

/**
 * Move one lane's hits from `fromCpq` to `toCpq` cells per quarter, each to the
 * nearest new cell. A hit that rounds past the end wraps to the downbeat (the
 * bar loops). When two hits meet in a cell the nearer one wins; the other is
 * counted as dropped. `report` ({ moved, dropped }) is updated in place.
 */
export function resample(cells, fromCpq, toCpq, steps, report) {
  const out = Array(steps).fill(null);
  const error = Array(steps).fill(Infinity);
  cells.forEach((v, j) => {
    if (v == null) return;
    const exact = (j * toCpq) / fromCpq;
    const target = Math.floor(exact + 0.5) % steps;
    const err = Math.abs(exact - Math.round(exact));
    if ((j * toCpq) % fromCpq !== 0) report.moved++;
    if (out[target] != null) {
      report.dropped++;
      if (err >= error[target]) return;
    }
    out[target] = v;
    error[target] = err;
  });
  return out;
}

/**
 * The zoom's pages: whole beats packed up to `max` cells, so a page never starts
 * mid-beat. A beat wider than `max` gets a page to itself. Returns [start, end) pairs.
 */
export const MAX_PAGE_CELLS = 12;
export function pagesFor(groups, max = 8) {
  const pages = [];
  let start = 0, end = 0;
  for (const g of groups) {
    // A tala beat finer than 12 cells (14, 21, 27…) would be too narrow to tap
    // on one page: it gets several, in near-equal parts (21 = 11+10).
    if (g > MAX_PAGE_CELLS) {
      if (end > start) pages.push([start, end]);
      const parts = Math.ceil(g / MAX_PAGE_CELLS);
      let at = end;
      for (let i = 0; i < parts; i++) {
        const len = Math.floor(g / parts) + (i < g % parts ? 1 : 0);
        pages.push([at, at + len]);
        at += len;
      }
      end += g; start = end;
      continue;
    }
    if (end > start && end - start + g > max) { pages.push([start, end]); start = end; }
    end += g;
  }
  if (end > start) pages.push([start, end]);
  return pages;
}
