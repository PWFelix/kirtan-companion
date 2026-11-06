/**
 * shippedBeats.js
 * ---------------
 * The built-in beat set as the SERVER holds it: how a `shipped_beats` row
 * becomes an app beat, which sets of rows may be used at all, and how a beat
 * is packed back into a row — by a promote, or by an edit in place.
 *
 * WHY THE BUILT-INS COME FROM A TABLE. They used to be compiled in twice —
 * data/beats.js here, data/Beats.kt on Android — so changing one beat meant
 * editing both files, rebuilding and redistributing the app. The table is
 * canonical now: both platforms read it at launch and the compiled list is
 * what runs when it can't be reached. data/beats.js is therefore NOT dead
 * code, and must stay byte-identical to the Android copy: it is the offline
 * fallback, and it is the seed the table was filled from.
 *
 * WHY THIS MODULE IS PURE. No React, no Supabase client, no localStorage and
 * no import.meta.env. The rules that decide what every user hears are then
 * the rules a plain Node script can assert against, and nothing in here can
 * be reached by a network failure — the fetch, the disk cache and the React
 * lifecycle are three thin shells around it (storage/shippedBeatsClient,
 * storage/shippedBeatsCache, hooks/useShippedBeats).
 *
 * THE ANDROID HALF implements these same rules against the same table, so
 * every bound below is either imported — one definition per platform, from
 * shareCodec and meter — or a column check the database already enforces. A
 * divergence here is not cosmetic: it means one platform plays a different
 * beat than the other, which is exactly what the share codec's derivation
 * rules exist to prevent.
 *
 * MAX_STEPS IS NOT AN ARBITRARY DoS BOUND here the way it is in shareCodec.
 * It is precisely the set of patterns the share format can express, and a
 * promoted beat always arrives through the share format, so "shareable" and
 * "promotable" are the same set by construction: neither platform can accept
 * a row the other would reject. The live `matan` beat is 48 cells, so the
 * bound has real headroom without being loose.
 *
 * ROW SHAPE (public.shipped_beats — world-readable, writable by maintainers):
 *   id text PK · ordinal int · heading text · name text · note text
 *   bpm int 40..200 · groups int[] 1..32 · cpq int 1..12 · lanes jsonb
 *   description text? · source_published_id uuid? · updated_at timestamptz
 */

import { LANES } from "./lanes.js";
import { STROKES } from "./strokes.js";
import { MIN_BPM, MAX_BPM, groupsFor, cpqFor, sumGroups } from "./meter.js";
import { MAX_GROUPS, MAX_GROUP_CELLS, MAX_STEPS, MAX_CPQ } from "./shareCodec.js";

// A rest in the compact lane notation, spelled the way shareCodec spells it:
// the two formats are the same idea (a lane as a string, "-" for silence) and
// a reader of one should not have to learn a second alphabet.
const REST_CHAR = "-";

// The notation's stroke alphabet, deliberately written out rather than read
// off STROKES. strokes.js gaining a new stroke ("D" for duggi) must NOT widen
// what this table may hold, because the Android half reads the same rows and
// would reject what we accepted. Adding a stroke to the shipped format is a
// two-platform change, and this set is where that gets decided.
const LANE_CHARS = new Set(["O", "X", REST_CHAR]);

// `id` is a slug a maintainer can read in the table, so it is capped; the
// column itself is unbounded text.
const MAX_ID_LEN = 48;

// Own-property only, and lanes are read by iterating LANES rather than the
// row's own keys — the two rules that stop a JSON blob carrying "__proto__"
// or an invented lane from reaching app state. shareCodec states them for a
// stranger's link; a server row is a second untrusted source (anyone with
// the service role, or a bug in a migration, can write one), and the same
// allowlist-not-denylist posture applies.
const has = (obj, key) => Object.prototype.hasOwnProperty.call(obj, key);
const isPlainObject = (v) => typeof v === "object" && v !== null && !Array.isArray(v);

// ── Lanes ────────────────────────────────────────────────────────────────

/**
 * Compact notation → the cell array a beat carries ("O"→"O", "X"→"X",
 * "-"→null). Returns null when `notation` isn't a string of exactly `steps`
 * characters from the alphabet above; never throws.
 *
 * The length check uses UTF-16 length while the split is by code point, which
 * is safe in that order: anything non-ASCII (a surrogate pair included) fails
 * the alphabet check before it can be split into fewer cells than promised.
 */
function laneCells(notation, steps) {
  if (typeof notation !== "string" || notation.length !== steps) return null;
  for (const c of notation) {
    if (!LANE_CHARS.has(c)) return null;
  }
  return Array.from(notation, (c) => (c === REST_CHAR ? null : c));
}

/**
 * The lane arrays of a validated row, or null.
 *
 * A lane key that is ABSENT stays absent. For kartal that is the whole
 * distinction the format turns on: a beat with no cymbals has no `kartal`
 * key, which BeatStrip reads as "draw no cymbal row", while an all-rest lane
 * would draw an empty one. Materialising the difference away is how a
 * two-lane beat silently becomes a three-lane beat.
 *
 * Primary lanes (dayan, bayan) are required: they are a beat's rhythmic
 * identity and the Sequencer indexes them unguarded. Non-primary ones are
 * optional, so lanes.js uncommenting `melody` needs no change here.
 */
function lanesOf(lanes, steps) {
  if (!isPlainObject(lanes)) return null;
  const out = {};
  for (const lane of LANES) {
    if (!has(lanes, lane.id)) {
      if (lane.primary) return null;
      continue;
    }
    const cells = laneCells(lanes[lane.id], steps);
    if (cells === null) return null;
    out[lane.id] = cells;
  }
  return out;
}

// ── Validation ───────────────────────────────────────────────────────────

/**
 * One row, validated. Returns it untouched (it is already plain JSON data)
 * or null. Every rule mirrors a column constraint or a bound this app already
 * enforces elsewhere; nothing here invents a limit.
 *
 * Never throws, because the only callers hand it a network response and a
 * localStorage blob — the two places in this feature where a malformed value
 * has to end up as "fall back", not as an exception inside a React render.
 */
export function validShippedRow(row) {
  if (!isPlainObject(row)) return null;

  // `id` and `name` must be non-BLANK, not merely non-empty: a row of spaces
  // is a beat with nothing to select and nothing to read in the list, and the
  // Android half refuses one too. Refusing it here as well is what keeps a
  // hand-edited row from being a beat on one platform and a whole-set fallback
  // on the other.
  if (typeof row.id !== "string" || row.id.trim() === "") return null;
  if (typeof row.name !== "string" || row.name.trim() === "") return null;
  if (typeof row.heading !== "string") return null;
  if (typeof row.note !== "string") return null;   // empty is a legal note

  if (!Number.isInteger(row.ordinal)) return null;
  if (!Number.isInteger(row.bpm) || row.bpm < MIN_BPM || row.bpm > MAX_BPM) return null;
  if (!Number.isInteger(row.cpq) || row.cpq < 1 || row.cpq > MAX_CPQ) return null;

  const groups = row.groups;
  if (!Array.isArray(groups) || groups.length < 1 || groups.length > MAX_GROUPS) return null;
  if (!groups.every((n) => Number.isInteger(n) && n >= 1 && n <= MAX_GROUP_CELLS)) return null;
  const steps = sumGroups(groups);
  if (steps < 1 || steps > MAX_STEPS) return null;

  if (lanesOf(row.lanes, steps) === null) return null;

  if (row.description != null && typeof row.description !== "string") return null;
  return row;
}

/**
 * The whole set, in ordinal order, or null.
 *
 * ALL OR NOTHING, and it is worth being explicit about why: a partially
 * applied built-in set is worse than falling back to the compiled one. The
 * section headings on the Beats screen and — far more importantly — the ids a
 * stored playlist holds both depend on the set being the one the maintainer
 * published. Nine beats with the tenth missing is not a smaller library, it
 * is a library whose "builtin" category quietly dropped a beat somebody's
 * progression points at, with no error anywhere. So one bad row rejects all
 * of them, exactly as one bad beat rejects a shared category in
 * shareCodec.decodeShare and a damaged list falls back in the library codec.
 *
 * Two rows sharing an id are rejected for the same reason: the table's
 * primary key makes that impossible, so a response containing one is not the
 * table's contents and must not be treated as if it were.
 *
 * Sorting by `ordinal` rather than trusting the response order is what makes
 * the derived list's order a property of the DATA. Both the section order and
 * "the first built-in" (which App launches on) read it, and this set can
 * arrive from the network or from a cache an older build wrote — two paths
 * that should not have to agree about ordering for the app to be right.
 */
export function validShippedSet(raw) {
  if (!Array.isArray(raw) || raw.length === 0) return null;

  const rows = [];
  const seen = new Set();
  for (const entry of raw) {
    const row = validShippedRow(entry);
    if (!row) return null;
    if (seen.has(row.id)) return null;
    seen.add(row.id);
    rows.push(row);
  }
  // Stable, so two rows with one ordinal keep their relative order.
  return rows.sort((a, b) => a.ordinal - b.ordinal);
}

// ── Derivation ───────────────────────────────────────────────────────────

/**
 * One VALIDATED row as an app beat. The field order is data/beats.js's, so a
 * derived beat and its compiled twin are the same object down to key order —
 * which is what lets a check compare them literally.
 *
 * steps / beatsPerBar / cellsPerGroup are derived from `groups` + `cpq` by
 * the same rule shareCodec's decodeBeat uses, so a beat that travels
 * server → app and a beat that travels link → app agree about their own
 * meter. In particular cellsPerGroup is `groups[0]` for a uniform pattern and
 * `cpq` for an uneven one — 6/8 is groups [3,3] with cpq 2, and reading cpq
 * there would call the grouping a subdivision.
 */
export function deriveBeat(row) {
  const groups = [...row.groups];
  const steps = sumGroups(groups);
  const uniform = groups.every((n) => n === groups[0]);
  return {
    id: row.id,
    group: row.heading,
    name: row.name,
    note: row.note,
    bpm: row.bpm,
    steps,
    beatsPerBar: steps / row.cpq,
    cellsPerGroup: uniform ? groups[0] : row.cpq,
    groups,
    description: row.description ?? null,
    ...lanesOf(row.lanes, steps),
  };
}

/** The set as beats, in ordinal order. Rows must have come from validShippedSet. */
export function rowsToBeats(rows) {
  return rows.map(deriveBeat);
}

/**
 * The set's revision: the newest `updated_at` across its rows, or "" when
 * none carries one.
 *
 * Compared as a STRING, not parsed as a date, because Postgres renders a
 * `timestamptz` through PostgREST in one fixed shape — always UTC, always
 * the same field order — so lexicographic order IS chronological order, with
 * no NaN path to handle. The value is only ever used to ask "is this the set
 * I already have?", so a revision that changed without the rows changing
 * costs one redundant write, and one that didn't change when the rows did is
 * the only real failure — which the fixed rendering rules out.
 */
export function revisionOf(rows) {
  let newest = "";
  for (const row of rows) {
    if (typeof row.updated_at === "string" && row.updated_at > newest) newest = row.updated_at;
  }
  return newest;
}

// ── The write side (promote, and edit in place) ──────────────────────────

/**
 * The id a promoted beat gets: the slug of its name, suffixed until it is
 * free.
 *
 * Ids in this table are FOREVER. A playlist may hold a built-in id, and
 * nothing anywhere can tell a stale reference from a live one, so an id is
 * never reused and never rewritten — which is why a collision gets a suffix
 * rather than replacing what is there.
 *
 * The steps, and their order, are the Android half's: trim, cap at 48, then
 * drop a separator the cap landed on. Same name in, same id out, on both
 * platforms — so one beat promoted from a phone and from a browser is one
 * row, not two.
 *
 * `taken` must be the ids the SERVER holds right now, not the ones on screen:
 * see shippedBeatsClient.promoteShippedBeat.
 */
export function shippedIdFor(name, taken) {
  const slug =
    (typeof name === "string" ? name.toLowerCase() : "")
      .replace(/[^a-z0-9]+/g, "_")   // every run of anything else → one "_"
      .replace(/^_+|_+$/g, "")       // trim, but don't collapse what's inside
      .slice(0, MAX_ID_LEN)
      .replace(/_+$/, "")            // a cut can land on a separator
      || "beat";
  if (!taken.has(slug)) return slug;
  // Same shape as useBeatLibrary's uniqueName: unbounded in principle, and in
  // practice bounded by `taken`, which is at most the size of the table.
  for (let n = 2; ; n++) {
    const candidate = `${slug}_${n}`;
    if (!taken.has(candidate)) return candidate;
  }
}

/** The ordinal that appends to the end of the set (0 for an empty one). */
export function nextOrdinal(rows) {
  let max = -1;
  for (const row of rows) {
    if (Number.isInteger(row?.ordinal) && row.ordinal > max) max = row.ordinal;
  }
  return max + 1;
}

/**
 * A library beat as a `shipped_beats` row, or null if it can't be one.
 *
 * `groups` and `cpq` come from meter.js's groupsFor/cpqFor — the same
 * reconstruction shareCodec uses when it packs a beat that predates the
 * groups model — and `steps` is summed from the groups rather than read off
 * the beat, so the lane length and the meter can't disagree.
 *
 * The row is put through validShippedRow before it is returned, which is the
 * guarantee this function exists to give: the set is all-or-nothing, so ONE
 * unrepresentable beat written to the table would take every user's built-in
 * list back to the compiled fallback. Refusing the write here turns that into
 * a sentence on the maintainer's screen instead. It also means an out-of-range
 * field can never reach the database as an opaque constraint violation.
 *
 * `description` travels as-is UNLESS the caller supplies one, which only the
 * in-place update does (see shippedRowForUpdate): the editor that produces an
 * edited beat has no field for prose, so an edit has to hand the row's own
 * paragraph back or lose it. shareCodec drops prose from an unattended import
 * because it renders as a paragraph in the info sheet and that is a place to
 * phish from; a promote is attended — the maintainer is looking at the card
 * and typing the heading — so their judgement is the gate here.
 *
 * ── TWO RULES ABOUT WHAT IS *IN* THE ROW ──
 *
 * THE NULLABLE COLUMNS ARE ALWAYS PRESENT, as explicit nulls. A promote is an
 * upsert on `id`, and PostgREST's merge-duplicates resolution only touches
 * the columns the body carries — so omitting `description` for a beat that has
 * none would leave the previous occupant's prose attached to this id, and the
 * promoted beat would ship a paragraph that describes somebody else's pattern.
 * This is deliberately the opposite of how the library codec handles absent
 * fields: there an omission means "leave it alone", here it must mean "clear
 * it", because a promote replaces a row wholesale.
 *
 * THE DERIVED FIELDS ARE NEVER SENT. No `steps`, `beatsPerBar` or
 * `cellsPerGroup`: every client derives all three from `groups` + `cpq` (see
 * deriveBeat), so storing them would let one row declare a meter that
 * contradicts its own pattern — and the two platforms would then have to agree
 * about which of the two to believe.
 */
export function shippedRowForBeat(beat, { id, ordinal, heading, description, sourcePublishedId }) {
  // A blank name is REFUSED rather than written around, and the reason is the
  // other platform: Android rejects a blank-named row, and since the set is
  // all-or-nothing, one such row would take every Android user back to the
  // compiled beats. So the choice isn't "placeholder name or nothing", it is
  // "nothing or a broken sibling" — and a placeholder would be a name no
  // maintainer chose, fixable only in SQL. Only a stranger's published payload
  // can get here blank; the editor and the share codec both name their beats.
  const name = typeof beat?.name === "string" ? beat.name : "";
  if (name.trim() === "") return null;

  let groups;
  try {
    groups = groupsFor(beat);
  } catch {
    // groupsFor reconstructs a missing `groups` by allocating
    // Array(steps / cellsPerGroup), and a beat with no usable meter — one out
    // of a published payload, which is a stranger's JSON stored verbatim at
    // publish time — has no length to allocate, so it throws instead of
    // returning junk. Every beat this app's own editor produces has a meter,
    // so "can't be a built-in" is the entire response this needs.
    return null;
  }
  const steps = sumGroups(groups);

  const lanes = {};
  for (const lane of LANES) {
    const cells = beat[lane.id];
    // An absent lane stays absent — see lanesOf. Anything present is packed
    // to exactly `steps` chars, extra cells ignored and missing ones rested,
    // which is how BeatStrip and the Sequencer already read a lane array.
    if (!Array.isArray(cells)) continue;
    lanes[lane.id] = Array.from({ length: steps }, (_, i) => {
      const v = cells[i];
      // A stroke outside the notation's alphabet (a future "D") lands here and
      // is rejected below rather than being silently rested away: losing a
      // stroke the maintainer can see on the card is worse than refusing.
      return v && has(STROKES, v) ? v : REST_CHAR;
    }).join("");
  }

  // A `description` the caller passed wins, INCLUDING when it is null: `??`
  // would read "this row has no prose" as "no opinion" and fall through to the
  // beat's. Only shippedRowForUpdate passes one; a promote leaves it absent and
  // the published beat's own prose travels.
  const prose = description === undefined ? beat.description : description;

  const row = {
    id,
    ordinal,
    heading,
    name,
    // DERIVED from the pattern, not carried over from `beat.note` — which is
    // what this used to do, and it was wrong for every beat that reached the
    // table through the editor. `note` is the first half of a list row
    // ("4 beats · 16 cells"), so a stale one is a lie the user can read: the
    // editor drafts every beat with the note "Custom", which says nothing in a
    // built-in list, and a preserved "4 beats" sitting over a bar its
    // maintainer just changed to five groups contradicts the strip next to it.
    // `${groups.length} beats` is also exactly what
    // scripts/generateBuiltinBeats.mjs mints, so the table and the compiled
    // fallback generated from it can't disagree about a beat's own count.
    note: `${groups.length} beats`,
    // Clamped with meter.js's bounds, exactly as shareCodec clamps an
    // incoming bpm — the table checks 40..200 too, so anything else would
    // come back as a database error rather than a tempo.
    bpm: Number.isFinite(beat.bpm)
      ? Math.min(MAX_BPM, Math.max(MIN_BPM, Math.round(beat.bpm)))
      : 90,
    groups,
    cpq: cpqFor(beat),
    lanes,
    // Both nullable columns are written explicitly, never omitted — see the
    // header. To an upsert an absent key means "leave the existing value",
    // not "clear it".
    description: typeof prose === "string" ? prose : null,
    source_published_id: sourcePublishedId ?? null,
  };
  return validShippedRow(row) ? row : null;
}

/**
 * A CORRECTED built-in as the row that replaces it, or null if it can't be one.
 *
 * `current` is the row as the table holds it right now — a fresh read, not the
 * beat in memory and not the list on screen (see
 * shippedBeatsClient.updateShippedBeat for why the read has to be fresh) — and
 * `beat` is the edited version of it.
 *
 * FIVE FIELDS COME FROM THE ROW, and every one is a thing the editor that
 * produced `beat` cannot express, so taking it from the beat would invent it or
 * erase it:
 *
 *   id — THE ONE THAT MUST NOT BE GOT WRONG. Never re-slugified, not even when
 *     the maintainer renamed the beat, because playlists store built-in ids and
 *     nothing anywhere can tell a stale reference from a live one. A new id
 *     orphans every progression that pointed at this beat, and the freed one is
 *     then handed to the next beat that slugs to it — two breakages for the
 *     price of one rename. The name is a label; the id is the identity.
 *   ordinal — the editor has no notion of order, and a correction is not a move.
 *   heading — the editor has no notion of sections, so it has nothing to say
 *     about which one this beat is in.
 *   description — the editor has no prose field, so the beat carries none and
 *     writing the beat's would erase the row's paragraph.
 *   source_published_id — a correction is still the beat that community snapshot
 *     made. Provenance is the only answer to "where did this ship from?", and
 *     it is exactly what an edit would otherwise quietly lose.
 *
 * Everything else is the edited beat's — name, bpm, groups, cpq, lanes — plus
 * `note`, derived from the new groups rather than preserved, for the reason
 * shippedRowForBeat gives.
 *
 * A THIN COMPOSITION over shippedRowForBeat, not a second builder: one place
 * knows how a beat becomes a row, and one gate (validShippedRow, inside it)
 * decides whether it may, for both writes. That is what keeps a promote and an
 * edit from drifting into disagreeing about what the table may hold — and the
 * row an edit writes is a row every client reads back through validShippedSet,
 * where one unparseable entry costs everyone the whole built-in list.
 */
export function shippedRowForUpdate(current, beat) {
  return shippedRowForBeat(beat, {
    id: current.id,
    ordinal: current.ordinal,
    heading: current.heading,
    // Passed explicitly, and an ABSENT key would be a different rule: the
    // builder would fall back to the beat's prose, and a row that has none must
    // stay a row that has none (see `prose` in shippedRowForBeat).
    description: current.description ?? null,
    sourcePublishedId: current.source_published_id ?? null,
  });
}
