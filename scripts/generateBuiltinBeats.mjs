/**
 * Regenerates the BUILT-IN beat set in BOTH platforms from a share code.
 *
 *   node scripts/generateBuiltinBeats.mjs 'kirtan://beat?c=…'   (or a bare code)
 *
 * WHY A GENERATOR RATHER THAN EDITING THE FILES. The patterns are long strings of
 * O/X/- — one is 48 characters — and a single mistyped cell changes the music
 * silently: nothing fails, the beat just plays wrong. Hand-transcription has
 * already produced exactly that bug once in this project. Decoding the payload and
 * emitting both files removes the human from the transcription path entirely.
 *
 * WHY BOTH FILES, ALWAYS. A shared link carries no id, so each client resolves
 * built-in ids against its OWN compiled list. If the two lists diverge, the same
 * link plays a different beat on each platform. This script writes them from one
 * source of truth so they cannot.
 *
 * The share format deliberately drops id, group and description (it is a trust
 * boundary), so those three are MINTED here rather than carried:
 *   id    — slugified from the name, stable across regenerations
 *   group — derived from the subdivision, so the Beats page sections are factual
 *   note  — the number of numbered pulses in the bar
 *   description — null; these beats have no transcribed prose
 *
 * It also derives steps / beatsPerBar / cellsPerGroup the SAME way the share
 * decoder does, so a round trip through ShareCodec is exact — including for
 * compound meters like `matan`, where cells-per-group (3) is not cells-per-
 * quarter (2) and the old `steps = beatsPerBar × cellsPerGroup` invariant does
 * not hold.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { decodeShare } from "../src/data/shareCodec.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const JS_FILE = join(ROOT, "src/data/beats.js");
const KT_FILE = join(ROOT, "android/app/src/main/java/com/kirtan/companion/data/Beats.kt");

const code = process.argv[2];
if (!code) {
  console.error("usage: node scripts/generateBuiltinBeats.mjs '<share link or code>'");
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

const payload = decodeShare(extractCode(code));
if (!payload || payload.kind === "invalid") {
  console.error("that code did not decode — nothing written");
  process.exit(1);
}
const beats = payload.kind === "category" ? payload.beats : [payload.beat];
console.log(`decoded ${beats.length} beat(s) from a ${payload.kind} payload`);

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

const used = new Set();
const rows = beats.map((b) => {
  const groups = b.groups;
  const cpq = Math.round(b.steps / b.beatsPerBar);
  const uniform = groups.every((g) => g === groups[0]);
  const lanes = {
    dayan: (b.dayan ?? []).map((s) => s ?? "-").join(""),
    bayan: (b.bayan ?? []).map((s) => s ?? "-").join(""),
    // An all-rest cymbal line is omitted, not stored: that is how "this beat has
    // no cymbals" is expressed, and it keeps an empty brass row off the strip.
    kartal: (b.kartal ?? []).some((s) => s != null)
      ? (b.kartal ?? []).map((s) => s ?? "-").join("")
      : null,
  };
  for (const [lane, pattern] of Object.entries(lanes)) {
    if (pattern && pattern.length !== b.steps) {
      throw new Error(`${b.name}/${lane}: ${pattern.length} cells, expected ${b.steps}`);
    }
  }
  return {
    id: slug(b.name, used),
    group: subdivision(cpq),
    name: b.name,
    note: `${groups.length} beats`,
    bpm: b.bpm,
    steps: b.steps,
    beatsPerBar: b.beatsPerBar,
    cellsPerGroup: uniform ? groups[0] : cpq,
    groups,
    cpq,
    ...lanes,
  };
});

// ── Emitters ───────────────────────────────────────────────────────────────

const ktStr = (s) => `"${String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;

const kotlin = rows
  .map((r) => {
    const lanes = [
      `        dayan = ${ktStr(r.dayan)},`,
      `        bayan = ${ktStr(r.bayan)},`,
      r.kartal ? `        kartal = ${ktStr(r.kartal)},` : null,
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
    description: null,
${lanes}
  },`;
  })
  .join("\n");

// ── Splice ─────────────────────────────────────────────────────────────────

function splice(file, pattern, replacement, label) {
  const before = readFileSync(file, "utf8");
  if (!pattern.test(before)) throw new Error(`${label}: anchor not found in ${file}`);
  const after = before.replace(pattern, replacement);
  if (after === before) throw new Error(`${label}: replacement was a no-op`);
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
