/**
 * eqPrefs.js
 * ----------
 * Persistence for the per-end mixer sound-shaping settings — EQ band gains,
 * the tuning offset, plus the EQ and tuning panels' open/closed disclosure
 * state — the third file allowed to call the localStorage API directly,
 * alongside localStorageProvider.js and migrate.js.
 *
 * WHY A SEPARATE FILE INSTEAD OF A PROVIDER METHOD. localStorageProvider is
 * shaped like the database the beat library becomes: rows, read-modify-write
 * lists, and errors that surface. EQ settings are none of those things — a
 * single small blob of cosmetic preference with no rows, no ids and no
 * migration — so it gets its own key and its own posture.
 *
 * THE POSTURE IS "NEVER THROW". localStorage reads throw when storage is
 * blocked outright (Safari private browsing, cookies disabled), and
 * JSON.parse throws whenever something unexpected lands under our key — a
 * future schema change, devtools, a sync bug. A corrupt EQ setting must
 * degrade to flat, not brick the mixer on mount, so every failure path below
 * arrives at the defaults: all bands 0 dB, tuning centred, panels closed.
 * By the same
 * reasoning a failed SAVE is only a warning: the sliders keep working for
 * this session, the browser just won't remember. This is deliberately not
 * the beat provider's "errors are the point" stance — a lost EQ is re-set in
 * seconds, a lost beat is not.
 *
 * THE SHAPE carries dayan and bayan only. Kartal is the mixer's third lane
 * but has no equaliser and no tuning, so it gets no entry here — do not add
 * one. Bands are clamped to [-12, 12] and the tuning offset to ±600 cents on
 * load so a poisoned value cannot reach the engine via mount hydration.
 */

import { EQ_BAND_COUNT, EQ_MIN_DB, EQ_MAX_DB } from "../data/eq.js";
import { TUNE_MIN_CENTS, TUNE_MAX_CENTS } from "../data/tuning.js";

const KEY = "kirtan-eq-prefs";

/**
 * The flat state every end starts in and falls back to. Built fresh per call
 * so callers never share (and mutate) one module-level object; the band
 * count comes from the shared EQ spec, so the engine, the mixer and this
 * store can't drift apart.
 */
function defaultPrefs() {
  return {
    dayan: { bands: Array.from({ length: EQ_BAND_COUNT }, () => 0), tune: 0, open: false, tuneOpen: false },
    bayan: { bands: Array.from({ length: EQ_BAND_COUNT }, () => 0), tune: 0, open: false, tuneOpen: false },
  };
}

/**
 * One band value coerced into a dB number in the shared EQ range. Numbers
 * pass through and numeric strings (e.g. "3.5") are coerced; anything
 * non-finite flattens to 0 before clamping, matching the load contract.
 */
function bandOf(value) {
  const num = Number(value);
  const db = Number.isFinite(num) ? num : 0;
  return Math.min(EQ_MAX_DB, Math.max(EQ_MIN_DB, db));
}

/**
 * The tuning offset coerced into cents in the shared TUNE range — same
 * coerce-then-clamp contract as bandOf, so a poisoned value can't reach
 * the engine via mount hydration.
 */
function tuneOf(value) {
  const num = Number(value);
  const cents = Number.isFinite(num) ? num : 0;
  return Math.min(TUNE_MAX_CENTS, Math.max(TUNE_MIN_CENTS, cents));
}

/**
 * Rebuild one end's settings from whatever the store held. Every wrong shape
 * — missing, null, wrong type, short or long bands array — falls back per
 * field, so even a half-corrupt blob yields a complete, valid end.
 */
function endOf(value) {
  const end = value && typeof value === "object" ? value : {};
  const rawBands = Array.isArray(end.bands) ? end.bands : [];
  return {
    bands: Array.from({ length: EQ_BAND_COUNT }, (_, i) => bandOf(rawBands[i])),
    tune: tuneOf(end.tune),
    open: end.open === true,
    tuneOpen: end.tuneOpen === true,
  };
}

/**
 * Load the EQ settings, or the flat defaults when anything at all is wrong.
 * Never throws: see the header for why.
 */
export function loadEqPrefs() {
  let raw;
  try {
    raw = localStorage.getItem(KEY);
  } catch {
    // Storage is blocked entirely; there is nothing to load.
    return defaultPrefs();
  }
  if (raw == null) return defaultPrefs();

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    console.warn(`[storage] ${KEY} was unreadable and has been ignored`, err);
    return defaultPrefs();
  }

  const prefs =
    parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  return { dayan: endOf(prefs.dayan), bayan: endOf(prefs.bayan) };
}

/**
 * Write the whole settings object under the key. Failures warn rather than
 * throw — a slider change must never take the mixer down with it.
 */
export function saveEqPrefs(prefs) {
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch (err) {
    console.warn(`[storage] ${KEY} could not be saved`, err);
  }
}
