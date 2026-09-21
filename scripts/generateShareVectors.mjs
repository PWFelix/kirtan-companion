// Generates wire-compatibility test vectors using the REAL web encoder, so the
// Kotlin port can be checked against the implementation it must interoperate
// with rather than against a hand-written expectation.
import { BEATS } from "/Users/joshua.wulf/workspace/kirtan-companion/src/data/beats.js";
import { encodeBeat, encodeCategory, decodeShare } from "/Users/joshua.wulf/workspace/kirtan-companion/src/data/shareCodec.js";

const out = {
  generatedFrom: "src/data/shareCodec.js (the web encoder)",
  beats: BEATS.map((b) => ({
    id: b.id,
    name: b.name,
    code: encodeBeat(b),
    // What the web decoder makes of that code — the Kotlin decoder must agree.
    decoded: decodeShare(encodeBeat(b)),
  })),
};

// A category vector too: the multi-beat path has its own bounds and its own
// "one bad beat rejects the list" rule.
const catCode = encodeCategory("Morning Programme", [BEATS[0], BEATS[1], BEATS[5]]);
out.category = { name: "Morning Programme", code: catCode, decoded: decodeShare(catCode) };

// Hostile vectors the Kotlin decoder must reject. Built as raw base64url JSON so
// they exercise the trust boundary rather than the encoder.
const b64url = (o) =>
  Buffer.from(JSON.stringify(o), "utf8").toString("base64url");

out.hostile = [
  { why: "wrong version", code: b64url({ v: 2, t: "b", n: "x", m: 90, g: [2, 2], q: 2, p: {} }) },
  { why: "steps far beyond MAX_STEPS", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: Array(32).fill(12), q: 12, p: {} }) },
  { why: "group cell count out of range", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: [99], q: 2, p: {} }) },
  { why: "pattern length disagrees with steps", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: [2, 2], q: 2, p: { dayan: "XO" } }) },
  { why: "q out of range", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: [2, 2], q: 99, p: {} }) },
  { why: "empty groups", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: [], q: 2, p: {} }) },
  { why: "not JSON at all", code: "bm90LWpzb24" },
  { why: "bpm absurd, must clamp not reject", code: b64url({ v: 1, t: "b", n: "x", m: 1e9, g: [2, 2], q: 2, p: {} }), expectClampedBpm: 200 },
  { why: "name with control + format chars", code: b64url({ v: 1, t: "b", n: "Evil\u202e\u0000Name", m: 90, g: [2, 2], q: 2, p: {} }), expectName: "EvilName" },
  // NOTE: the key must be computed. `{ __proto__: ... }` in an object literal
  // sets the PROTOTYPE and is dropped by JSON.stringify, so the vector would
  // silently not test what it claims to.
  { why: "unknown lane key must be ignored", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: [2, 2], q: 2, p: { dayan: "XOXO", ["__proto__"]: { admin: true }, melody: "XXXX" } }) },
  { why: "smuggled description and group must be dropped", code: b64url({ v: 1, t: "b", n: "x", m: 90, g: [2, 2], q: 2, p: {}, description: "visit evil.example", group: "Hacked" }) },
  { why: "smuggled id must not be honoured", code: b64url({ v: 1, t: "b", id: "te_ta", n: "x", m: 90, g: [2, 2], q: 2, p: {} }) },
];

const dest = "/Users/joshua.wulf/workspace/kirtan-companion/android/app/src/test/resources/share-vectors.json";
const fs = await import("node:fs");
const path = await import("node:path");
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 2));

console.log(`wrote ${dest}`);
console.log(`  ${out.beats.length} beat vectors, 1 category, ${out.hostile.length} hostile`);
console.log(`  first beat: ${out.beats[0].id} -> ${out.beats[0].code.slice(0, 40)}…`);
