/**
 * Sequences — Home's running order: beats in order, each held for a number
 * of bars ("daspahir for 8, then tehai for 4, then …").
 *
 * Pure data, no audio. Entries refer to beats BY ID, so editing a beat
 * changes it everywhere it is sequenced, and a deleted beat simply drops out
 * of playback (its entry stays, shown as missing, until removed).
 *
 * Two shapes:
 *   saved:   { id, name, entries: [{ beatId, bars }] }
 *   working: { entries, savedId, name, loop } — what Home is showing and
 *            playing, kept across reloads; savedId says which saved one it
 *            was opened from, so "Save" can overwrite it.
 *
 * The SequenceCursor at the bottom is the engine's counter, kept here so the
 * counting is plain JS with no clock.
 */

export const DEFAULT_BARS = 4;
export const MAX_BARS = 64;

export const clampBars = (n) => Math.max(1, Math.min(MAX_BARS, Math.round(Number(n) || 1)));

export const EMPTY_WORKING = Object.freeze({ entries: [], savedId: null, name: "", loop: false });

// ── Entry edits (each returns a new array) ──

export function addEntry(entries, beatId, bars = DEFAULT_BARS) {
  return [...entries, { beatId, bars: clampBars(bars) }];
}

export function setEntryBars(entries, i, bars) {
  return entries.map((e, j) => (j === i ? { ...e, bars: clampBars(bars) } : e));
}

export function setEntryBeat(entries, i, beatId) {
  return entries.map((e, j) => (j === i ? { ...e, beatId } : e));
}

export function removeEntry(entries, i) {
  return entries.filter((_, j) => j !== i);
}

export function moveEntry(entries, from, to) {
  if (from < 0 || from >= entries.length) return entries;
  const dest = Math.max(0, Math.min(entries.length - 1, to));
  const out = [...entries];
  const [e] = out.splice(from, 1);
  out.splice(dest, 0, e);
  return out;
}

export const totalBars = (entries) => entries.reduce((n, e) => n + clampBars(e.bars), 0);

/**
 * What actually plays: entries whose beat still exists, as
 * [{ entryIndex, beat, bars }]. entryIndex maps the engine's position back
 * to the chip to light.
 */
export function resolveSequence(entries, lookup) {
  const out = [];
  entries.forEach((e, entryIndex) => {
    const beat = lookup(e.beatId);
    if (beat) out.push({ entryIndex, beat, bars: clampBars(e.bars) });
  });
  return out;
}

/** Unsaved changes worth a "Save"? An empty, never-saved sequence has none. */
export function isDirty(working, saved) {
  const from = saved.find((s) => s.id === working.savedId);
  if (!from) return working.entries.length > 0;
  return from.name !== working.name || !sameEntries(from.entries, working.entries);
}

function sameEntries(a, b) {
  return a.length === b.length && a.every((e, i) => e.beatId === b[i].beatId && e.bars === b[i].bars);
}

// ── Storage codec: tolerant of anything, never throws ──

function cleanEntries(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((e) => e && typeof e.beatId === "string")
    .map((e) => ({ beatId: e.beatId, bars: clampBars(e.bars) }));
}

export function decodeSaved(json) {
  try {
    const raw = JSON.parse(json);
    if (!Array.isArray(raw)) return [];
    return raw
      .filter((s) => s && typeof s.id === "string")
      .map((s) => ({ id: s.id, name: typeof s.name === "string" ? s.name : "", entries: cleanEntries(s.entries) }));
  } catch {
    return [];
  }
}

export function decodeWorking(json) {
  try {
    const raw = JSON.parse(json);
    if (!raw || typeof raw !== "object") return { ...EMPTY_WORKING };
    return {
      entries: cleanEntries(raw.entries),
      savedId: typeof raw.savedId === "string" ? raw.savedId : null,
      name: typeof raw.name === "string" ? raw.name : "",
      loop: raw.loop === true,
    };
  } catch {
    return { ...EMPTY_WORKING };
  }
}

/**
 * Save the working sequence: overwrite the one it came from, or (asNew, or
 * never saved) add a fresh one. Returns { saved, working } — the working copy
 * now points at what it was saved as.
 */
export function saveWorking(working, saved, name, asNew, newId) {
  const existing = !asNew && saved.find((s) => s.id === working.savedId);
  const id = existing ? existing.id : newId;
  const entry = { id, name: name.trim(), entries: working.entries };
  const next = existing ? saved.map((s) => (s.id === id ? entry : s)) : [...saved, entry];
  return { saved: next, working: { ...working, savedId: id, name: entry.name } };
}

/**
 * Where a playing sequence is, counted in DOWNBEATS. The Sequencer calls
 * onDownbeat() at every bar line; the answer says whether that 1 still
 * belongs to the current entry, starts the next, or is past the end.
 * The first downbeat after reset() is bar 1 of entry 0 and not an advance.
 *
 * Outcomes: { type: "stay" } | { type: "switch", index } | { type: "end" }
 */
export class SequenceCursor {
  constructor(bars, loop) {
    this.bars = bars.map((b) => Math.max(1, b));
    this.loop = loop;
    this.reset();
  }

  reset() {
    this.index = 0;
    this.bar = 0;          // 0-based within the entry
    this.started = false;
  }

  onDownbeat() {
    if (this.bars.length === 0) return { type: "end" };
    if (!this.started) {
      this.started = true;
      return { type: "stay" };
    }
    this.bar++;
    if (this.bar < this.bars[this.index]) return { type: "stay" };
    this.bar = 0;
    const next = this.index + 1;
    if (next < this.bars.length) { this.index = next; return { type: "switch", index: next }; }
    if (this.loop) { this.index = 0; return { type: "switch", index: 0 }; }
    return { type: "end" };
  }

  /** Carry the position into an edited sequence: same entry and bar where they still exist. */
  adopt(from) {
    this.started = from.started;
    if (from.index < this.bars.length) {
      this.index = from.index;
      this.bar = Math.min(from.bar, this.bars[this.index] - 1);
    }
  }
}
