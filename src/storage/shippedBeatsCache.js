/**
 * shippedBeatsCache.js
 * --------------------
 * The on-disk half of the built-in set's fallback ladder:
 *
 *     valid remote  →  valid cache  →  the compiled BEATS
 *
 * Without the middle rung, every launch shows the compiled beats for as long
 * as the fetch takes — and offline shows them forever, even on a device that
 * successfully loaded a newer set an hour ago. The cache is what makes a
 * maintainer's change survive a flight.
 *
 * THE POSTURE IS eqPrefs' "NEVER THROW", not the provider's "errors are the
 * point". This is a cache of data that also exists in the bundle, so every
 * failure — blocked storage, a quota, a blob written by an older build that
 * no longer validates — degrades to "no cache" and the app carries on. An
 * error surfaced here would tell the user something is broken when the worst
 * case is that they see the nine beats that shipped in the binary.
 *
 * WHAT IS STORED IS THE RAW ROWS, not the beats derived from them. The
 * derivation (data/shippedBeats.js) is the half most likely to change — a new
 * lane, a corrected cellsPerGroup rule — and storing rows means such a change
 * needs no cache-format bump: the next launch re-derives what is already on
 * disk. It also means the cached copy is validated by the SAME function the
 * network response is, so there is one definition of "a usable set" and no
 * way for the disk copy to be trusted more than the server's.
 *
 * The key follows migrate.js's `kirtan.v2.` namespace: one schema version for
 * every key this app writes, so a future migration has one prefix to look
 * under. It lives here rather than in KEYS because migrate.js's map is the
 * migration's output, and this key is not something the migration touches.
 */

import { validShippedSet, revisionOf } from "../data/shippedBeats.js";

const KEY = "kirtan.v2.shippedBeats";

/**
 * The cached set — { rows, revision } — or null when there isn't a usable
 * one. Validated on read for the reason in the header: this blob was written
 * by whatever build ran last, which may have derived beats differently or may
 * have been interrupted mid-write.
 */
export function readShippedCache() {
  let raw;
  try {
    raw = localStorage.getItem(KEY);
  } catch {
    return null;   // storage blocked outright — there is nothing to read
  }
  if (raw == null) return null;

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    console.warn(`[storage] ${KEY} was unreadable and has been ignored`, err);
    return null;
  }
  if (!parsed || typeof parsed !== "object" || typeof parsed.revision !== "string") return null;

  const rows = validShippedSet(parsed.rows);
  return rows ? { rows, revision: parsed.revision } : null;
}

/**
 * Store a set that has already passed validation. Best-effort, like
 * saveEqPrefs: losing the cache costs the next launch its fast path and
 * nothing else, so a quota or a blocked store is a warning, not a failure the
 * caller has to handle.
 */
export function writeShippedCache(rows) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ revision: revisionOf(rows), rows }));
  } catch (err) {
    console.warn(`[storage] ${KEY} could not be saved`, err);
  }
}
