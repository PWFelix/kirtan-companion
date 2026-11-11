/**
 * communityTracking.js
 * --------------------
 * How a community beat in the library relates to the original it follows.
 * Mirrors Android's CommunityTracking.kt and iOS's CommunityTracking.swift.
 *
 * A community beat (`sourceItemId` set) is read-only, so ANY difference from
 * the original means the author changed it: that is all "update available"
 * needs — no timestamps, no database change. Editing makes a copy in Your
 * beats (`forkedFrom`), and that copy can be shared only once it is musically
 * different from the original: renaming or retagging someone else's beat is
 * not a new beat.
 *
 * Pure: `originals` is { itemId: [beat, ...] } (one beat for a single item,
 * the list's beats for a playlist), or null when not loaded.
 */

// Fields that say where a library beat came from, never part of its content.
const LOCAL = ["id", "sourceItemId", "sourceIndex", "forkedFrom", "readOnly"];

export const isCommunity = (beat) => Boolean(beat?.sourceItemId);

// Keys sorted and empties dropped, so two beats built in a different order —
// or by different apps — still compare equal.
function canonical(v) {
  if (Array.isArray(v)) return v.map(canonical);
  if (v && typeof v === "object") {
    return Object.fromEntries(Object.keys(v).sort()
      .filter((k) => v[k] !== undefined && v[k] !== null && !(k === "tags" && Array.isArray(v[k]) && v[k].length === 0))
      .map((k) => [k, canonical(v[k])]));
  }
  return v;
}

function strip(beat, extra = []) {
  const out = { ...beat };
  for (const k of [...LOCAL, ...extra]) delete out[k];
  return out;
}

/**
 * What the update check compares: everything the author controls except the
 * name (the import may have suffixed it to keep names unique) and the
 * built-in-only group.
 */
export const contentPrint = (beat) => JSON.stringify(canonical(strip(beat, ["name", "group"])));

/** The music alone: what makes a copy a different beat rather than a relabel. */
export const musicalPrint = (beat) => JSON.stringify(canonical(strip(beat, ["name", "group", "note", "description", "tags"])));

export function upstreamOf(beat, originals) {
  const list = originals?.[beat.sourceItemId];
  if (!list) return null;
  if (Number.isInteger(beat.sourceIndex)) return list[beat.sourceIndex] ?? null;
  return list.length === 1 ? list[0] : null;
}

/** { kind: "unknown" | "current" | "gone" } or { kind: "update", upstream }. */
export function communityStatus(beat, originals) {
  if (!isCommunity(beat)) return { kind: "current" };
  if (!originals) return { kind: "unknown" };
  const up = upstreamOf(beat, originals);
  if (!up) return { kind: "gone" };
  return contentPrint(up) === contentPrint(beat) ? { kind: "current" } : { kind: "update", upstream: up };
}

/** `beat` brought up to `upstream`: the author's content, the library's identity and name. */
export function applyUpdate(beat, upstream) {
  const next = { ...strip(upstream, ["group"]), id: beat.id, name: beat.name, sourceItemId: beat.sourceItemId };
  if (Number.isInteger(beat.sourceIndex)) next.sourceIndex = beat.sourceIndex;
  return next;
}

/** True when an edited copy is still just the original. Unknown originals don't block. */
export function isUnchangedCopy(beat, originals) {
  const list = beat?.forkedFrom ? originals?.[beat.forkedFrom] : null;
  if (!list) return false;
  const print = musicalPrint(beat);
  return list.some((o) => musicalPrint(o) === print);
}

/**
 * The one-time migration of imports made back when they were editable:
 * { kind: "keep", sourceIndex } (untouched — stays a community beat),
 * { kind: "fork" } (edited in place — becomes the user's copy), or
 * { kind: "undecided" } (original gone; leave it).
 */
export function migrate(beat, originals) {
  if (!originals?.length) return { kind: "undecided" };
  const print = musicalPrint(beat);
  const index = originals.findIndex((o) => musicalPrint(o) === print);
  if (index < 0) return { kind: "fork" };
  return { kind: "keep", sourceIndex: originals.length === 1 ? null : index };
}
