/**
 * BeatsProvider.js
 * ----------------
 * THE CONTRACT between the app and wherever the user's saved work lives.
 *
 * This file holds no storage code at all — it is the shape every provider
 * must implement, the error type they all throw, and the id minter they all
 * share. `localStorageProvider.js` is the only implementation today;
 * `index.js` is where the active one is chosen.
 *
 * THREE RULES, and the reason each exists:
 *
 *  1. EVERY METHOD IS ASYNC, even though localStorage is synchronous.
 *     This is the whole point of the layer. A remote provider can be
 *     dropped in without touching a single call site, because every caller
 *     already awaits. If any method here were sync, the swap would ripple
 *     into every component that reads a beat.
 *
 *  2. A PROVIDER OWNS ONLY WHAT THE USER MADE. The beats shipped in
 *     data/beats.js are compiled in, identical for every user, and needed
 *     synchronously — so they are merged in one level up, in
 *     useBeatLibrary. Putting them here would mean writing that merge again
 *     inside every future provider, and would leave methods like deleteBeat
 *     having to reject for rows the provider never stored.
 *
 *  3. FAILURES REJECT, THEY DO NOT RETURN FALSE OR SWALLOW.
 *     The code this replaced wrapped every write in `catch {}`, so a full
 *     or blocked storage looked exactly like a successful save: the beat
 *     appeared in the list and was gone on the next reload. Every method
 *     here rejects with a StorageError instead, and the hook above turns
 *     that into something the user can actually see.
 *
 * ── THE INTERFACE ──
 *
 *   loadAll()                  → { beats, categories, activeCategoryId }
 *   createBeat(draft)          → Beat        (mints and returns the id)
 *   createBeats(drafts)        → Beat[]      (one write, see below)
 *   updateBeat(id, patch)      → Beat
 *   deleteBeat(id)             → void
 *   createCategory(draft)      → Category    (mints and returns the id)
 *   updateCategory(id, patch)  → Category
 *   deleteCategory(id)         → void
 *   setActiveCategory(id)      → void
 *
 * createBeats is plural on purpose. Importing a shared category writes
 * several beats at once, and the loop-of-single-creates version of that has
 * already been a bug here once: each call read stale state and dropped
 * beats. One call, one write — and a batch insert when this becomes a
 * network call.
 *
 * @typedef {Object} Beat
 * @property {string} id
 * @property {string} name
 * @property {string} note
 * @property {number} bpm
 * @property {number} steps
 * @property {number} beatsPerBar
 * @property {number} cellsPerGroup
 * @property {number[]} [groups]   per-pulse cell counts (non-uniform meters)
 * @property {Array<string|null>} dayan
 * @property {Array<string|null>} bayan
 *
 * @typedef {Object} Category
 * @property {string} id
 * @property {string} name
 * @property {string[]} beatIds    ordered — this IS the kirtan progression
 */

/**
 * The one error type crossing this boundary.
 *
 * `code` is what callers branch on; `message` is written to be shown to a
 * person as-is. "network" and "conflict" are unused by the localStorage
 * provider and exist so a remote one adds no new codes — the day Supabase
 * lands, the UI already handles everything it can throw.
 *
 * @typedef {"unavailable"|"quota"|"notFound"|"conflict"|"network"|"unknown"} StorageErrorCode
 */
export class StorageError extends Error {
  /**
   * @param {StorageErrorCode} code
   * @param {string} message  safe to show to the user verbatim
   * @param {unknown} [cause] the original throw, for the console
   */
  constructor(code, message, cause) {
    super(message);
    this.name = "StorageError";
    this.code = code;
    this.cause = cause;
  }
}

/** Human-readable text for a failure, whatever it turns out to be. */
export function storageErrorMessage(err) {
  if (err instanceof StorageError) return err.message;
  return "Something went wrong saving that. Your beat may not have been kept.";
}

/**
 * Mint an id.
 *
 * crypto.randomUUID is the intended path, but it only exists in a SECURE
 * CONTEXT — https, or localhost. Open the dev server from a phone on the
 * same wifi (http://192.168.x.x:5173) and it is undefined, which is exactly
 * how this app gets tested. Without the fallback, saving a beat would throw
 * on the one device the app is designed for.
 *
 * The fallback is a v4 UUID built from getRandomValues, so the format is
 * identical either way and a future `uuid` database column accepts both.
 */
export function newId() {
  if (typeof crypto !== "undefined") {
    if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
    if (typeof crypto.getRandomValues === "function") {
      const b = crypto.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40; // version 4
      b[8] = (b[8] & 0x3f) | 0x80; // variant 10x
      const hex = [...b].map((n) => n.toString(16).padStart(2, "0")).join("");
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
  }
  // No crypto at all (very old browser). Still unique enough for one device.
  const rnd = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, "0");
  return `${rnd()}${rnd()}-${rnd()}-4${rnd().slice(1)}-a${rnd().slice(1)}-${rnd()}${rnd()}${rnd()}`;
}
