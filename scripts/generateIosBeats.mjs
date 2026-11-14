/**
 * One-shot / re-sync emitter for the iOS compiled beat set, from src/data/beats.js.
 *
 * The CANONICAL set lives in public.shipped_beats and the sanctioned generator is
 * scripts/generateBuiltinBeats.mjs (--from-server), which writes beats.js AND
 * Beats.kt AND Beats.swift. This script exists so the iOS file can be produced from
 * the web fallback without Supabase credentials: beats.js is itself generated from the
 * same source, so reading it is faithful. Re-run it (or the canonical generator) after
 * any change to the set so the three compiled files never drift.
 */
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { BEATS } from "../src/data/beats.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "ios/KirtanCompanion/Sources/data/Beats.swift");

const str = (s) => `"${String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
const notation = (arr) => (arr ?? []).map((s) => s ?? "-").join("");

const rows = BEATS.map((b) => {
  const cpq = Math.round(b.steps / b.beatsPerBar);
  const lanes = [
    `            dayan: ${str(notation(b.dayan))},`,
    `            bayan: ${str(notation(b.bayan))},`,
    b.kartal && b.kartal.some((s) => s != null)
      ? `            kartal: ${str(notation(b.kartal))},`
      : null,
    b.description ? `            description: ${str(b.description)},` : null,
  ].filter(Boolean).join("\n");
  return `        builtIn(\n` +
    `            id: ${str(b.id)}, group: ${str(b.group)}, name: ${str(b.name)}, note: ${str(b.note)},\n` +
    `            bpm: ${b.bpm}, groups: [${b.groups.join(", ")}], cpq: ${cpq},\n` +
    `${lanes}\n` +
    `        ),`;
});

// Built from a line array, not a backticked template literal, because the Swift doc
// comments below use markdown backticks, which would otherwise terminate it.
const header = [
  "import Foundation",
  "",
  "// Pure beat data. No logic, no dependencies.",
  "//",
  "// Transcribed from src/data/beats.js, which is itself transcribed from Sita-pati das,",
  '// "The Art and Science of Harinam Sankirtan Yajna".',
  "//",
  "// - THIS IS THE FALLBACK SET, NOT THE CANONICAL ONE -",
  "// The built-ins the app shows come from the server shipped_beats table when it can be",
  "// read, and from this list when it cannot - see ShippedBeats. All three compiled files",
  "// (beats.js, Beats.kt, Beats.swift) are generated from the same source and must agree",
  "// at release time: an offline user plays THIS list, and a shared #b= link carries no",
  "// id, so each client resolves built-in ids against the set it happens to be holding.",
  "// Divergent ids or patterns mean the same link plays a different beat on each platform.",
  "//",
  '// Pattern notation is the wire format\'s own: "O" open, "X" closed, "-" rest.',
  "//",
  "// - GENERATED: DO NOT EDIT THE ARRAY BY HAND -",
  "// Run scripts/generateBuiltinBeats.mjs (or generateIosBeats.mjs) to regenerate.",
  "",
  "private func pattern(_ notation: String) -> [Stroke?] {",
  "    notation.unicodeScalars.map { Stroke.fromCode(String($0)) }",
  "}",
  "",
  "/// One built-in beat, with the invariant every beat must hold checked up front:",
  "/// steps must equal each lane's pattern length. A stale length once made the web strip",
  "/// wrap into extra rows and the sequencer play only half the pattern; failing loudly at",
  "/// load beats that bug silently. The old steps == beatsPerBar * cellsPerGroup check is",
  "/// gone - it is not a general truth; matan is 16 groups of 3 at eighth subdivision,",
  "/// 48 cells over 24 quarters. The invariant that holds is steps == sum(groups).",
  "private func builtIn(",
  "    id: String, group: String, name: String, note: String,",
  "    bpm: Int, groups: [Int], cpq: Int,",
  "    dayan: String, bayan: String, kartal: String? = nil, description: String? = nil,",
  ") -> Beat {",
  "    // Derived exactly as ShareCodec.decodeBeat derives them, so a built-in round-trips",
  "    // through the share format to an identical beat, which a test asserts.",
  "    let steps = sumGroups(groups)",
  '    precondition(steps > 0, "beat \\(id): groups sum to zero cells")',
  '    precondition(cpq >= 1, "beat \\(id): cells-per-quarter must be at least 1")',
  "",
  "    let uniform = groups.allSatisfy { $0 == groups[0] }",
  "    let cellsPerGroup = uniform ? groups[0] : cpq",
  "    let beatsPerBar = Double(steps) / Double(cpq)",
  "",
  "    var lanes: [LaneId: [Stroke?]] = [",
  "        .dayan: pattern(dayan),",
  "        .bayan: pattern(bayan),",
  "    ]",
  "    // A beat without a kartal line simply has no cymbal row - the key is absent, which",
  "    // is what activeLanes() and the strip's lane filter read.",
  "    if let kartal { lanes[.kartal] = pattern(kartal) }",
  "",
  "    for (lane, cells) in lanes {",
  "        precondition(cells.count == steps,",
  '            "beat \\(id): lane \\(lane.wireId) has \\(cells.count) cells, expected \\(steps)")',
  "    }",
  "",
  "    return Beat(",
  "        id: id, name: name, note: note, bpm: bpm, steps: steps,",
  "        beatsPerBar: beatsPerBar, cellsPerGroup: cellsPerGroup, groups: groups,",
  "        description: description, lanePatterns: lanes, group: group,",
  "    )",
  "}",
  "",
  "enum Beats {",
  "",
  "    // (generated array below)",
  "    // (BEGIN GENERATED - do not edit inside this block.)",
  "    static let beats: [Beat] = [",
  rows.join("\n"),
  "    ]",
  "    // (END GENERATED)",
  "}",
  "",
].join("\n");

writeFileSync(OUT, header);
console.log(`wrote ${OUT} (${rows.length} beats)`);
for (const b of BEATS) {
  console.log(`  ${(b.id ?? "?").padEnd(34)} ${(b.group ?? "?").padEnd(11)} ${String(b.bpm).padStart(3)}bpm ${String(b.steps).padStart(2)} cells${b.kartal?.some((s) => s) ? "  +kartal" : ""}`);
}
