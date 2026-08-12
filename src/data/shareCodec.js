/**
 * shareCodec.js
 * -------------
 * Packing a beat (or a whole category) into a share code, and — the half
 * that matters — unpacking one safely.
 *
 * A share code is the app's FIRST UNTRUSTED INPUT. Everything else comes
 * from the user's own hands; this comes from a stranger and ends up written
 * to localStorage, where it persists across sessions. So this module is a
 * TRUST BOUNDARY: it is the only place in the app that touches a payload it
 * did not create, and everything downstream receives either a clean,
 * freshly-constructed object or null.
 *
 * The rules that keep it a boundary, all of which have teeth:
 *
 *  1. NEVER SPREAD PARSED DATA. `{...incoming}` would carry a "__proto__"
 *     key straight into app state. We read named fields and build new
 *     object literals. Patterns are read by iterating LANES, not by
 *     iterating the payload's own keys — an allowlist, not a denylist.
 *  2. NEVER TRUST AN ID. There is no id in the format at all. saveBeat is
 *     an upsert by id, so an incoming id could silently replace one of the
 *     user's beats (or shadow a built-in). Ids are minted locally on import.
 *  3. NEVER THROW. A payload arriving by URL that threw during render would
 *     white-screen the app on every refresh. decodeShare returns null on
 *     anything unexpected, and readShareFromLocation clears the hash BEFORE
 *     decoding, so a hostile link gets exactly one chance and is then gone.
 *  4. HARD BOUNDS ON EVERY NUMBER. `steps: 1e9` renders a DOM node per cell
 *     and locks the tab. Nothing is unbounded.
 *  5. NO PROSE. `description` and `group` are dropped entirely rather than
 *     validated — a description renders as a paragraph in the info sheet,
 *     which is a fine place to phish from ("your library is corrupt,
 *     visit ..."). Editor-made beats never have one anyway, so it costs
 *     nothing.
 *
 *  ── THE RULE FOR WHOEVER EXTENDS THIS FORMAT ──
 *  The schema must stay a PURE DATA ALLOWLIST and must NEVER gain a
 *  URL-valued or code-valued field. PROJECT_PLAN §7 has "record or upload
 *  custom sample sounds" on the roadmap; the day a beat can reference a
 *  sound, a share link carrying a sound URL becomes an arbitrary remote
 *  fetch performed by every recipient. If shared beats ever need custom
 *  sounds, the audio must be a bundled/known id, never a URL from the
 *  payload. Same for anything else that would be dereferenced or evaluated.
 *
 * WIRE FORMAT (deliberately compact — a link has to survive a messenger):
 *   one beat        { v:1, t:"b", n, m, g, q, p }
 *   a category      { v:1, t:"c", n, b:[ {n,m,g,q,p}, ... ] }
 * where n = name, m = bpm, g = groups, q = cells per quarter, and p = the
 * patterns keyed by LANE ID, each a string with "-" for a rest ("X-OO-XOO").
 * Strings rather than JSON arrays is most of the saving: ~250 bytes down to
 * ~110. Keying p by lane id means the commented-out kartal/melody lanes in
 * lanes.js need no format change to start sharing.
 *
 * steps, beatsPerBar, cellsPerGroup and note are all DERIVED on import, not
 * carried — four fewer fields anyone can lie about. The derivation mirrors
 * BeatEditor's save exactly, so a beat round-trips byte-identical.
 *
 * The payload rides in the URL FRAGMENT (#) rather than a query param, so it
 * is never sent to a server: no server logs, no CDN caches, no analytics.
 */

import { LANES } from "./lanes.js";
import { STROKES } from "./strokes.js";
import { MIN_BPM, MAX_BPM, groupsFor, cpqFor, sumGroups } from "./meter.js";

const VERSION = 1;
const REST_CHAR = "-";

// Bounds. Generous enough that no real beat hits them, tight enough that a
// hostile payload can't allocate anything interesting.
const MAX_CODE_LEN = 8000;   // ~a category of ten beats, with headroom
const MAX_NAME_LEN = 40;
const MAX_GROUPS = 32;       // numbered beats in one pattern
const MAX_GROUP_CELLS = 12;  // cells in one numbered beat
const MAX_STEPS = 64;        // total cells — this is the DoS bound that matters
const MAX_CPQ = 12;
const MAX_CAT_BEATS = 50;

const has = (obj, key) => Object.prototype.hasOwnProperty.call(obj, key);
const isPlainObject = (v) => typeof v === "object" && v !== null && !Array.isArray(v);

// ── base64url ────────────────────────────────────────────────────────────
// Plain base64 is not URL-safe: "+" becomes a space and "=" terminates
// awkwardly in a fragment. btoa also only speaks latin-1, so the string goes
// through TextEncoder first and a name in Devanagari survives the trip.

function toBase64Url(str) {
  const bytes = new TextEncoder().encode(str);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(code) {
  const base64 = code.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(base64);          // throws on malformed input — caller catches
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

// ── Encoding (trusted side — these beats are already in the library) ──────

function packPattern(beat, laneId, steps) {
  const cells = beat[laneId];
  if (!Array.isArray(cells)) return null;
  // Exactly `steps` chars whatever the array length, matching how BeatStrip
  // renders and the Sequencer plays: extra data ignored, missing data rests.
  return Array.from({ length: steps }, (_, i) => {
    const v = cells[i];
    return v && has(STROKES, v) ? v : REST_CHAR;
  }).join("");
}

function packBeat(beat) {
  const groups = groupsFor(beat);
  const steps = sumGroups(groups);
  const patterns = {};
  for (const lane of LANES) {
    const packed = packPattern(beat, lane.id, steps);
    if (packed !== null) patterns[lane.id] = packed;
  }
  return { n: beat.name, m: beat.bpm, g: groups, q: cpqFor(beat), p: patterns };
}

export function encodeBeat(beat) {
  return toBase64Url(JSON.stringify({ v: VERSION, t: "b", ...packBeat(beat) }));
}

export function encodeCategory(name, beats) {
  return toBase64Url(JSON.stringify({
    v: VERSION, t: "c", n: name, b: beats.map(packBeat),
  }));
}

/**
 * The shareable link. Uses location.pathname rather than a bare "/" so a
 * project-path deploy (GitHub Pages et al) produces links that resolve.
 */
export function shareUrl(code) {
  return `${location.origin}${location.pathname}#b=${code}`;
}

// ── Decoding (untrusted side — assume every field is hostile) ─────────────

/** Strip control/format characters, trim, cap. Not ASCII-only: real names
 *  may be Devanagari, and React escapes text anyway — the cap is about
 *  layout and storage, the strip is about invisible direction-override and
 *  zero-width tricks in a name that sits beside "Built in". */
function cleanName(value, fallback) {
  if (typeof value !== "string") return fallback;
  const cleaned = value.replace(/[\p{Cc}\p{Cf}]/gu, "").trim().slice(0, MAX_NAME_LEN);
  return cleaned || fallback;
}

function validGroups(g) {
  if (!Array.isArray(g) || g.length < 1 || g.length > MAX_GROUPS) return null;
  if (!g.every((n) => Number.isInteger(n) && n >= 1 && n <= MAX_GROUP_CELLS)) return null;
  const steps = sumGroups(g);
  if (steps < 1 || steps > MAX_STEPS) return null;
  return [...g];
}

function unpackPattern(patterns, laneId, steps) {
  // Read by LANE, never by the payload's keys: unknown keys are never
  // touched, and every lane is guaranteed an array even when the payload
  // omits it (the Sequencer indexes beat.dayan/beat.bayan unguarded).
  if (!has(patterns, laneId)) return Array(steps).fill(null);
  const str = patterns[laneId];
  if (typeof str !== "string" || str.length !== steps) return undefined;  // reject
  return Array.from(str, (c) => (c !== REST_CHAR && has(STROKES, c) ? c : null));
}

/** One beat, as a DRAFT: everything a beat needs except an id, which is
 *  minted locally on import. Returns null if anything is off. */
function decodeBeat(raw) {
  if (!isPlainObject(raw)) return null;

  const groups = validGroups(raw.g);
  if (!groups) return null;

  const q = raw.q;
  if (!Number.isInteger(q) || q < 1 || q > MAX_CPQ) return null;

  if (!isPlainObject(raw.p)) return null;

  const steps = sumGroups(groups);
  const lanes = {};
  for (const lane of LANES) {
    const cells = unpackPattern(raw.p, lane.id, steps);
    if (cells === undefined) return null;
    lanes[lane.id] = cells;
  }

  const bpm = Number.isFinite(raw.m)
    ? Math.min(MAX_BPM, Math.max(MIN_BPM, Math.round(raw.m)))
    : 90;

  // Derived exactly as BeatEditor's save derives them, so a shared beat and
  // its original are the same object apart from the id.
  const uniform = groups.every((n) => n === groups[0]);
  return {
    name: cleanName(raw.n, "Shared Beat"),
    note: "Custom",
    bpm,
    steps,
    beatsPerBar: steps / q,
    cellsPerGroup: uniform ? groups[0] : q,
    groups,
    ...lanes,
  };
}

/**
 * The one entry point for untrusted data.
 * @returns {{kind:"beat", beat}|{kind:"category", name, beats}|null}
 */
export function decodeShare(code) {
  try {
    // Cheap checks first, so a 20 MB paste never reaches atob or JSON.parse.
    if (typeof code !== "string") return null;
    if (code.length < 1 || code.length > MAX_CODE_LEN) return null;
    if (!/^[A-Za-z0-9_-]+$/.test(code)) return null;

    const raw = JSON.parse(fromBase64Url(code));
    if (!isPlainObject(raw)) return null;
    // Exact version match: a future v2 fails cleanly rather than half-parsing.
    if (raw.v !== VERSION) return null;

    if (raw.t === "b") {
      const beat = decodeBeat(raw);
      return beat ? { kind: "beat", beat } : null;
    }

    if (raw.t === "c") {
      const list = raw.b;
      if (!Array.isArray(list) || list.length < 1 || list.length > MAX_CAT_BEATS) return null;
      const beats = [];
      for (const entry of list) {
        const beat = decodeBeat(entry);
        if (!beat) return null;   // one bad beat rejects the whole list
        beats.push(beat);
      }
      return { kind: "category", name: cleanName(raw.n, "Shared List"), beats };
    }

    return null;
  } catch {
    // Malformed base64, malformed JSON, anything at all — one friendly null.
    return null;
  }
}

/** A pasted value may be a whole URL or a bare code; take the code either way. */
export function codeFromInput(text) {
  if (typeof text !== "string") return "";
  const trimmed = text.trim();
  const at = trimmed.lastIndexOf("#b=");
  return at === -1 ? trimmed : trimmed.slice(at + 3);
}

/**
 * Read an inbound share link, ONCE, at module load.
 *
 * The hash is cleared BEFORE the payload is decoded, which is the whole
 * point: a hostile link that somehow got past decodeShare and wedged the app
 * would otherwise re-wedge it on every refresh, because the payload lives in
 * the URL. Clearing first means one failure, then a clean address bar.
 *
 * Call this at module scope, not in a useState initialiser — StrictMode
 * double-invokes those, and this one has a side effect.
 *
 * @returns the payload, {kind:"invalid"} if a #b= was present but unusable,
 *          or null if there was no share link at all.
 */
export function readShareFromLocation() {
  try {
    if (!location.hash.startsWith("#b=")) return null;
    const code = location.hash.slice(3);
    history.replaceState(null, "", location.pathname + location.search);
    return decodeShare(code) ?? { kind: "invalid" };
  } catch {
    return null;
  }
}
