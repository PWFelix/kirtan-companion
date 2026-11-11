// Whether one of the user's beats is already in the community library, so Share
// can say "Already shared" (disabled) or "Share updated version" instead of
// quietly publishing a duplicate. Mirrors Android's ShareStatus.kt and iOS's
// ShareStatus.swift.
//
// A published snapshot can't be traced back to a local beat by id, so this
// device keeps a record of which beat was published as which item. A beat with
// no record still reads as shared when one of the user's items holds exactly
// its content (shares from before the record existed). Only "edited since"
// needs the record, since an edited beat matches nothing.

const LINKS_KEY = "kirtan-share-links";

// The beat as published, minus ids, with keys sorted so two objects built in a
// different order still compare equal.
export function fingerprint(beat) {
  const sorted = (v) => {
    if (Array.isArray(v)) return v.map(sorted);
    if (v && typeof v === "object") {
      return Object.fromEntries(Object.keys(v).sort().filter((k) => v[k] !== undefined).map((k) => [k, sorted(v[k])]));
    }
    return v;
  };
  // eslint-disable-next-line no-unused-vars
  const { id, sourceItemId, ...rest } = beat;
  return JSON.stringify(sorted(rest));
}

/**
 * "new" | "shared" | { changed: itemId }.
 * `mine` is [{ id, beat }] for the user's published beats; `links` maps local
 * beat id → item id.
 */
export function shareStatus(beat, mine, links) {
  const print = fingerprint(beat);
  const linked = beat.id && links[beat.id] ? mine.find((m) => m.id === links[beat.id]) : null;
  if (linked) return fingerprint(linked.beat) === print ? "shared" : { changed: linked.id };
  return mine.some((m) => fingerprint(m.beat) === print) ? "shared" : "new";
}

export function loadLinks() {
  try { return JSON.parse(localStorage.getItem(LINKS_KEY)) || {}; } catch { return {}; }
}

export function saveLink(beatId, itemId) {
  const all = { ...loadLinks(), [beatId]: itemId };
  try { localStorage.setItem(LINKS_KEY, JSON.stringify(all)); } catch { /* private mode: session only */ }
  return all;
}
