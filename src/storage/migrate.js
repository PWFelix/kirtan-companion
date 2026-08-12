/**
 * migrate.js
 * ----------
 * The one-time move from the pre-provider layout to the versioned one.
 *
 * BEFORE (three loose keys, ids like "custom_te_ta_1699882341"):
 *   kirtan-custom-beats      JSON array
 *   kirtan-user-categories   JSON array
 *   kirtan-active-category   a BARE STRING, not JSON — the odd one out, and
 *                            the reason this can't be a generic key rename
 *
 * AFTER (namespaced, versioned, everything JSON, every id a UUID):
 *   kirtan.v2.beats
 *   kirtan.v2.categories
 *   kirtan.v2.activeCategory
 *   kirtan.schemaVersion = 2
 *
 * WHY REWRITE THE IDS AT ALL. The old ones are perfectly unique strings, so
 * this is not about correctness today — it is about the database column
 * these will land in. A Postgres `uuid` primary key rejects
 * "custom_te_ta_1699882341" outright, and discovering that after people
 * have real beats saved makes it a data-loss problem instead of a one-line
 * one. Doing it now, while the only store is this device, is free.
 *
 * THE PART THAT MUST NOT BE GOT WRONG. A category is nothing but an ORDERED
 * LIST OF BEAT IDS — that list is the kirtan progression. Rewriting beat ids
 * without rewriting every reference to them would leave every progression
 * pointing at beats that no longer exist, and categoryBeats() filters
 * missing ids out silently, so the user would simply find their
 * progressions empty with no error anywhere. Hence idMap, and hence the
 * built-in ids ("te_ta") passing through untouched — they are not ours to
 * rewrite, and they legitimately appear in progressions.
 *
 * THE OLD KEYS ARE LEFT IN PLACE. They cost a few KB and they are the only
 * way back if this got something wrong. A later release deletes them.
 */

import { newId } from "./BeatsProvider.js";

export const KEYS = {
  beats: "kirtan.v2.beats",
  categories: "kirtan.v2.categories",
  activeCategory: "kirtan.v2.activeCategory",
  version: "kirtan.schemaVersion",
};

const V1 = {
  beats: "kirtan-custom-beats",
  categories: "kirtan-user-categories",
  activeCategory: "kirtan-active-category",
};

export const SCHEMA_VERSION = 2;

/** Parse a v1 JSON key, tolerating anything at all in there. */
function readV1Array(key) {
  try {
    const parsed = JSON.parse(localStorage.getItem(key));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/**
 * Run the migration if it hasn't run. Safe to call repeatedly — the version
 * marker is the guard, and a store that never had v1 data is simply stamped
 * and left alone.
 *
 * Throws only if localStorage itself is unreachable; the caller classifies.
 */
export function migrateIfNeeded() {
  if (localStorage.getItem(KEYS.version) === String(SCHEMA_VERSION)) return;

  const oldBeats = readV1Array(V1.beats);
  const oldCats = readV1Array(V1.categories);
  const oldActive = localStorage.getItem(V1.activeCategory); // bare string

  // Every custom beat and every category gets a fresh UUID; the maps are what
  // keep the references pointing at the right things afterwards.
  const beatIdMap = new Map();
  const beats = oldBeats
    .filter((b) => b && typeof b === "object" && typeof b.id === "string")
    .map((b) => {
      const id = newId();
      beatIdMap.set(b.id, id);
      return { ...b, id };
    });

  const catIdMap = new Map();
  const categories = oldCats
    .filter((c) => c && typeof c === "object" && typeof c.id === "string")
    .map((c) => {
      const id = newId();
      catIdMap.set(c.id, id);
      return {
        ...c,
        id,
        // Built-in ids aren't in the map and pass straight through — a
        // progression mixing shipped and custom beats survives intact.
        beatIds: (Array.isArray(c.beatIds) ? c.beatIds : []).map(
          (bid) => beatIdMap.get(bid) ?? bid
        ),
      };
    });

  // "builtin" and "custom" are pseudo-categories that were never stored as
  // rows, so they aren't in the map either and pass through the same way.
  const activeCategory = oldActive ? catIdMap.get(oldActive) ?? oldActive : "builtin";

  localStorage.setItem(KEYS.beats, JSON.stringify(beats));
  localStorage.setItem(KEYS.categories, JSON.stringify(categories));
  localStorage.setItem(KEYS.activeCategory, JSON.stringify(activeCategory));
  localStorage.setItem(KEYS.version, String(SCHEMA_VERSION));
}
