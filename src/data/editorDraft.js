/**
 * editorDraft.js — the editor's draft as plain data, outside the editor.
 * ---------------------------------------------------------------------
 * The editor used to own its draft, and unmounting was how a draft was
 * discarded — so walking to another screen and back threw the work away. Now
 * App holds ONE editor session ({ seed, draft, returnTo }) and the editor starts
 * from it and reports every change back (see App.jsx). This module is what both
 * sides share: how a draft is seeded from a beat, what counts as "changed"
 * (content, not "was touched" — undoing back clears it), and the localStorage
 * copy that lets a dirty draft survive a reload.
 *
 * The meter helpers moved here from BeatEditor.jsx because seeding needs them.
 */
import { resolutionFactor } from "./resolution.js";
import { clampSwing, SWING_STRAIGHT } from "./swing.js";

export const emptyGrid = (n) => Array(n).fill(null);
export const sum = (a) => a.reduce((x, y) => x + y, 0);

// Meter/feel presets. `bar` = one bar's groups, `unit` = what +/- Group adds
// or removes, `cpq` = cells per quarter note (sets timing via beatsPerBar).
export const METERS = [
  { id: "std",  label: "Standard",    sig: "4/4 · eighths",    bar: [2, 2, 2, 2], unit: [2],       cpq: 2 },
  { id: "trip", label: "Triplets",    sig: "swing · 12/8",     bar: [3, 3, 3, 3], unit: [3],       cpq: 3 },
  { id: "dbl",  label: "Double-time", sig: "16ths",            bar: [4, 4, 4, 4], unit: [4],       cpq: 4 },
  { id: "68",   label: "Six-eight",   sig: "6/8 · 3+3",        bar: [3, 3],       unit: [3, 3],    cpq: 2 },
  { id: "78",   label: "Seven-eight", sig: "7/8 · 2+2+3",      bar: [2, 2, 3],    unit: [2, 2, 3], cpq: 2 },
];

// Does `arr` consist of whole repetitions of `pattern`?
function repeats(pattern, arr) {
  if (arr.length === 0 || arr.length % pattern.length !== 0) return false;
  return arr.every((v, i) => v === pattern[i % pattern.length]);
}

// Recover the meter preset (or a synthesized "custom" one) from a beat's
// groups + cpq, so editing an existing beat lands on the right picker entry.
export function meterFor(groups, cpq) {
  const uniform = groups.every((g) => g === groups[0]);
  if (uniform && groups[0] === cpq) {
    const m = METERS.find((x) => x.cpq === cpq && x.unit.length === 1 && x.unit[0] === cpq);
    if (m) return m;
  }
  // Odd meters at a finer resolution (6/8 ×2 is [6,6] at cpq 4) are still that
  // meter: divide the common factor out, match, and scale the preset back up so
  // ± keeps adding a whole 3+3 or 2+2+3 cycle.
  const k = resolutionFactor(groups, cpq);
  const base = groups.map((g) => g / k);
  if (cpq / k === 2) {
    for (const id of ["68", "78"]) {
      const m = METERS.find((x) => x.id === id);
      if (repeats(m.unit, base)) return k === 1 ? m : { ...m, bar: m.bar.map((x) => x * k), unit: m.unit.map((x) => x * k), cpq: cpq };
    }
  }
  return { id: "custom", label: "Custom", sig: "", unit: uniform ? [groups[0]] : [...groups], cpq };
}

export function seedGroups(b) {
  if (!b) return [...METERS[0].bar];
  if (Array.isArray(b.groups)) return [...b.groups];
  const cpg = b.cellsPerGroup ?? Math.max(1, Math.round(b.steps / (b.beatsPerBar ?? 4)));
  const beats = Math.max(1, Math.round(b.steps / cpg));
  return Array(beats).fill(cpg);
}
export const seedCpq = (b) => (b ? Math.max(1, Math.round(b.steps / (b.beatsPerBar ?? 4))) : 2);


/** A draft seeded from `seed` (a beat), or blank for a new one. */
export function draftFrom(seed) {
  const groups = seedGroups(seed);
  const n = sum(groups);
  return {
    groups,
    meter: meterFor(groups, seedCpq(seed)),
    dayan: seed ? [...seed.dayan] : emptyGrid(n),
    bayan: seed ? [...seed.bayan] : emptyGrid(n),
    // Kartal is optional on a beat: an empty row of the same length.
    kartal: seed?.kartal ? [...seed.kartal] : emptyGrid(n),
    bpm: seed?.bpm ?? 90,
    name: seed?.name ?? "",
    cursor: 0,
    cursorLane: "dayan",
    tags: seed?.tags ?? [],
    swing: clampSwing(seed?.swing ?? SWING_STRAIGHT),
  };
}

const sameCells = (a = [], b = [], n) => {
  for (let i = 0; i < n; i++) if ((a[i] ?? null) !== (b[i] ?? null)) return false;
  return true;
};

/** What a save would write, compared; cursor and undo don't count. */
export function sameContent(a, b) {
  const n = sum(a.groups);
  return n === sum(b.groups) && a.groups.join() === b.groups.join() &&
    a.meter.cpq === b.meter.cpq && a.bpm === b.bpm &&
    (a.swing ?? SWING_STRAIGHT) === (b.swing ?? SWING_STRAIGHT) && a.name.trim() === b.name.trim() &&
    sameCells(a.dayan, b.dayan, n) && sameCells(a.bayan, b.bayan, n) && sameCells(a.kartal, b.kartal, n) &&
    (a.tags ?? []).join() === (b.tags ?? []).join();
}

/** True when the session's draft differs from where it started. */
export const isDirty = (session) => !!session?.draft && !sameContent(session.draft, draftFrom(session.seed));

const KEY = "kirtan-editor-draft";

/** The session on disk while dirty; removed when clean, so a clean one costs nothing. */
export function saveSession(session) {
  try {
    if (session && isDirty(session)) {
      const { seed, draft, forEveryone } = session;
      localStorage.setItem(KEY, JSON.stringify({ seed, draft, forEveryone }));
    } else localStorage.removeItem(KEY);
  } catch { /* full or blocked: the draft lasts the session */ }
}

export function loadSession() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY));
    if (!raw?.draft?.groups || !raw.draft.meter) return null;
    return { seed: raw.seed ?? null, draft: raw.draft, returnTo: null };
  } catch { return null; }
}
