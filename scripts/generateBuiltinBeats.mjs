/**
 * Regenerates the COMPILED built-in beat set in BOTH platforms.
 *
 *   node scripts/generateBuiltinBeats.mjs --from-server          (the normal case)
 *   node scripts/generateBuiltinBeats.mjs 'kirtan://beat?c=…'    (a share link)
 *
 * WHY A GENERATOR RATHER THAN EDITING THE FILES. The patterns are long strings of
 * O/X/- — one is 48 characters — and a single mistyped cell changes the music
 * silently: nothing fails, the beat just plays wrong. Hand-transcription has
 * already produced exactly that bug once in this project. Emitting both files from
 * a source that is already structured removes the human from that path entirely.
 *
 * WHY BOTH FILES, ALWAYS. A shared link carries no id, so each client resolves
 * built-in ids against its OWN compiled list. If the two lists diverge, the same
 * link plays a different beat on each platform. This script writes them from one
 * source of truth so they cannot.
 *
 * ── TWO SOURCES, ONE OUTPUT ──
 * `--from-server` reads `public.shipped_beats`, which is CANONICAL: it is what
 * every installed app plays, and it is what a maintainer edits (in the database,
 * or in the app once the editor exists). The compiled files are the offline
 * fallback and the seed a fresh project starts from, so after any change to the
 * table they are regenerated from it — never hand-edited, and never left to drift.
 * In this mode id, heading, note and description come FROM THE ROW, because the
 * table stores what the share format has to drop.
 *
 * A share link is the other way in, and is how a set gets into the table in the
 * first place: the format deliberately drops id, group and description (it is a
 * trust boundary), so those are MINTED here instead —
 *   id    — slugified from the name, stable across regenerations
 *   group — derived from the subdivision, so the Beats page sections are factual
 *   note  — the number of numbered pulses in the bar
 *
 * Both modes derive steps / beatsPerBar / cellsPerGroup the SAME way the share
 * decoder and both clients do, so a round trip through ShareCodec is exact —
 * including for compound meters like `matan`, where cells-per-group (3) is not
 * cells-per-quarter (2) and the old `steps = beatsPerBar × cellsPerGroup`
 * invariant does not hold.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { decodeShare } from "../src/data/shareCodec.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const JS_FILE = join(ROOT, "src/data/beats.js");
const KT_FILE = join(ROOT, "android/app/src/main/java/com/kirtan/companion/data/Beats.kt");

const arg = process.argv[2];
const fromServer = arg === "--from-server";
if (!arg) {
  console.error(
    "usage: node scripts/generateBuiltinBeats.mjs --from-server\n" +
    "       node scripts/generateBuiltinBeats.mjs '<share link or code>'",
  );
  process.exit(2);
}

/**
 * Pull the code out of whatever was pasted.
 *
 * Handles three shapes: a bare code, the web app's `#b=<code>` fragment, and the
 * Android deep link's `?c=<code>` query — the last is an Android-side convention
 * that `shareCodec.js`'s own codeFromInput does not know about, so it is handled
 * here rather than assumed.
 */
function extractCode(input) {
  const trimmed = input.trim();
  const fragment = trimmed.lastIndexOf("#b=");
  if (fragment !== -1) return trimmed.slice(fragment + 3);
  const query = trimmed.match(/[?&]c=([^&#]+)/);
  if (query) return query[1];
  return trimmed;
}

// ── Derivation ─────────────────────────────────────────────────────────────

const slug = (name, used) => {
  let base =
    name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "") || "beat";
  let id = base;
  let n = 2;
  while (used.has(id)) id = `${base}_${n++}`;
  used.add(id);
  return id;
};

/** Subdivision name, from cells-per-quarter. */
const subdivision = (cpq) => (cpq >= 4 ? "Sixteenths" : cpq === 3 ? "Triplets" : "Straight");

/**
 * The meter fields both modes derive, from groups + cpq.
 *
 * One function because there must be one rule: the clients derive the same three
 * fields from the same two, and a generator that computed them differently would
 * write a compiled beat that disagrees with the row it came from.
 */
function meterOf(groups, cpq) {
  const steps = groups.reduce((a, b) => a + b, 0);
  const uniform = groups.every((g) => g === groups[0]);
  return {
    steps,
    beatsPerBar: steps / cpq,
    cellsPerGroup: uniform ? groups[0] : cpq,
  };
}

/** Refuse to write a pattern that is not the length its own meter claims. */
function checkedLanes(name, lanes, steps) {
  for (const [lane, pattern] of Object.entries(lanes)) {
    if (pattern && pattern.length !== steps) {
      throw new Error(`${name}/${lane}: ${pattern.length} cells, expected ${steps}`);
    }
  }
  return lanes;
}

/**
 * Rows from a share payload. id / group / note are MINTED, because the format
 * drops them: it is a trust boundary, and an id from a stranger's link must not be
 * able to overwrite a built-in.
 */
function rowsFromBeats(beats) {
  const used = new Set();
  return beats.map((b) => {
    const groups = b.groups;
    const cpq = Math.round(b.steps / b.beatsPerBar);
    const lanes = {
      dayan: (b.dayan ?? []).map((s) => s ?? "-").join(""),
      bayan: (b.bayan ?? []).map((s) => s ?? "-").join(""),
      // An all-rest cymbal line is omitted, not stored: that is how "this beat has
      // no cymbals" is expressed, and it keeps an empty brass row off the strip.
      kartal: (b.kartal ?? []).some((s) => s != null)
        ? (b.kartal ?? []).map((s) => s ?? "-").join("")
        : null,
    };
    checkedLanes(b.name, lanes, b.steps);
    return {
      id: slug(b.name, used),
      group: subdivision(cpq),
      name: b.name,
      note: `${groups.length} beats`,
      description: null,
      bpm: b.bpm,
      groups,
      cpq,
      ...meterOf(groups, cpq),
      ...lanes,
    };
  });
}

/**
 * Rows from `public.shipped_beats`, the canonical set.
 *
 * Unlike a share payload, a row CARRIES its id, heading, note and description, so
 * nothing is minted and nothing is guessed — a maintainer's chosen section heading
 * survives into the compiled fallback instead of being re-derived from the
 * subdivision. The same lane-length check runs, because a row that cannot be
 * written into the compiled files is a row no client should be serving either.
 */
function rowsFromServerRows(serverRows) {
  const ids = new Set();
  return serverRows.map((r) => {
    for (const field of ["id", "heading", "name", "note"]) {
      if (typeof r[field] !== "string" || (field !== "note" && r[field].trim() === "")) {
        throw new Error(`row ${r.id ?? "(no id)"}: ${field} is not usable`);
      }
    }
    if (ids.has(r.id)) throw new Error(`duplicate id in the table: ${r.id}`);
    ids.add(r.id);
    if (!Array.isArray(r.groups) || !r.groups.length) throw new Error(`${r.id}: no groups`);
    const lanes = {};
    for (const lane of ["dayan", "bayan", "kartal"]) {
      if (typeof r.lanes?.[lane] === "string") lanes[lane] = r.lanes[lane];
      else if (lane !== "kartal") throw new Error(`${r.id}: no ${lane} pattern`);
    }
    const meter = meterOf(r.groups, r.cpq);
    checkedLanes(r.name, lanes, meter.steps);
    return {
      id: r.id,
      group: r.heading,
      name: r.name,
      note: r.note,
      description: typeof r.description === "string" ? r.description : null,
      bpm: r.bpm,
      groups: r.groups,
      cpq: r.cpq,
      ...meter,
      kartal: null,
      ...lanes,
    };
  });
}

/**
 * The canonical set over PostgREST, with the anon key.
 *
 * The table is world-readable by design — a signed-out app has to be able to load
 * its built-in beats — so this needs no session and no service key. Credentials
 * come from the web project's `.env.local` when there is one and from the Android
 * build's `local.properties` otherwise; both are git-ignored, and neither is
 * printed.
 */
async function fetchServerRows() {
  const { url, key } = readCredentials();
  const res = await fetch(`${url}/rest/v1/shipped_beats?select=*&order=ordinal.asc`, {
    headers: { apikey: key, Authorization: `Bearer ${key}` },
  });
  if (!res.ok) {
    throw new Error(`the server said ${res.status}: ${(await res.text()).slice(0, 200)}`);
  }
  const rows = await res.json();
  if (!Array.isArray(rows) || rows.length === 0) {
    // Writing nothing would empty both compiled lists, and an empty built-in set
    // is a state the apps refuse to adopt for good reason.
    throw new Error("the table returned no rows — refusing to empty the compiled set");
  }
  return rows;
}

function readCredentials() {
  const parse = (text) =>
    Object.fromEntries(
      text
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(line) && !line.startsWith("#"))
        .map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1).trim()]),
    );

  const candidates = [
    { file: join(ROOT, ".env.local"), url: "VITE_SUPABASE_URL", key: "VITE_SUPABASE_ANON_KEY" },
    { file: join(ROOT, "android/local.properties"), url: "SUPABASE_URL", key: "SUPABASE_ANON_KEY" },
  ];
  for (const c of candidates) {
    let text;
    try {
      text = readFileSync(c.file, "utf8");
    } catch {
      continue;
    }
    const vars = parse(text);
    if (vars[c.url] && vars[c.key]) {
      console.log(`credentials: ${c.file}`);
      return { url: vars[c.url], key: vars[c.key] };
    }
  }
  throw new Error(
    "no Supabase credentials found in .env.local or android/local.properties",
  );
}

const rows = fromServer
  ? rowsFromServerRows(await fetchServerRows())
  : (() => {
      const payload = decodeShare(extractCode(arg));
      if (!payload || payload.kind === "invalid") {
        console.error("that code did not decode — nothing written");
        process.exit(1);
      }
      const beats = payload.kind === "category" ? payload.beats : [payload.beat];
      console.log(`decoded ${beats.length} beat(s) from a ${payload.kind} payload`);
      return rowsFromBeats(beats);
    })();
if (fromServer) console.log(`read ${rows.length} row(s) from public.shipped_beats`);

// ── Emitters ───────────────────────────────────────────────────────────────

const ktStr = (s) => `"${String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;

const kotlin = rows
  .map((r) => {
    const lanes = [
      `        dayan = ${ktStr(r.dayan)},`,
      `        bayan = ${ktStr(r.bayan)},`,
      r.kartal ? `        kartal = ${ktStr(r.kartal)},` : null,
      // Only when the row has prose: every shipped beat today has none, and an
      // explicit `description = null` on all nine would be noise. Omitting it is
      // safe because builtIn's parameter defaults to null.
      r.description ? `        description = ${ktStr(r.description)},` : null,
    ]
      .filter(Boolean)
      .join("\n");
    return `    builtIn(
        id = ${ktStr(r.id)}, group = ${ktStr(r.group)}, name = ${ktStr(r.name)}, note = ${ktStr(r.note)},
        bpm = ${r.bpm}, groups = listOf(${r.groups.join(", ")}), cpq = ${r.cpq},
${lanes}
    ),`;
  })
  .join("\n");

const jsArr = (pattern) =>
  `[${[...pattern].map((c) => (c === "-" ? "null" : `"${c}"`)).join(", ")}]`;

const js = rows
  .map((r) => {
    const lanes = [
      `    dayan: ${jsArr(r.dayan)},`,
      `    bayan: ${jsArr(r.bayan)},`,
      r.kartal ? `    kartal: ${jsArr(r.kartal)},` : null,
    ]
      .filter(Boolean)
      .join("\n");
    return `  {
    id: ${JSON.stringify(r.id)}, group: ${JSON.stringify(r.group)}, name: ${JSON.stringify(r.name)}, note: ${JSON.stringify(r.note)},
    bpm: ${r.bpm}, steps: ${r.steps}, beatsPerBar: ${r.beatsPerBar}, cellsPerGroup: ${r.cellsPerGroup}, groups: [${r.groups.join(", ")}],
    description: ${JSON.stringify(r.description)},
${lanes}
  },`;
  })
  .join("\n");

// ── Splice ─────────────────────────────────────────────────────────────────

/**
 * Replace one anchored region of a file.
 *
 * A MISSING anchor is fatal: it means the file was restructured and this script
 * would otherwise write nothing and report success. An unchanged result is NOT —
 * it means the file already held exactly this, which is the normal outcome of
 * `--from-server` and the point of running it: regenerate, then `git diff`. Empty
 * diff means the compiled fallback agrees with the canonical table; a diff shows
 * precisely what drifted.
 */
function splice(file, pattern, replacement, label) {
  const before = readFileSync(file, "utf8");
  if (!pattern.test(before)) throw new Error(`${label}: anchor not found in ${file}`);
  const after = before.replace(pattern, replacement);
  if (after === before) {
    console.log(`${label}: already in sync, nothing written`);
    return;
  }
  writeFileSync(file, after);
  console.log(`wrote ${label}: ${file}`);
}

splice(
  KT_FILE,
  /val BEATS: List<Beat> = listOf\([\s\S]*?\n\)\n/,
  `val BEATS: List<Beat> = listOf(\n${kotlin}\n)\n`,
  "Beats.kt",
);
splice(
  JS_FILE,
  /export const BEATS = \[[\s\S]*?\n\];\n/,
  `export const BEATS = [\n${js}\n];\n`,
  "beats.js",
);

console.log(`\n${rows.length} beats:`);
for (const r of rows) {
  console.log(`  ${r.id.padEnd(34)} ${r.group.padEnd(11)} ${String(r.bpm).padStart(3)}bpm ${String(r.steps).padStart(2)} cells${r.kartal ? "  +kartal" : ""}`);
}
console.log("\nNext: node scripts/generateShareVectors.mjs   (wire-compat fixtures)");
