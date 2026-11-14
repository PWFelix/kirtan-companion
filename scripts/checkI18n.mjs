/**
 * check-i18n — does every language have every line?
 *
 * Compares each dictionary in src/i18n/ against en.js and lists keys a
 * language is missing (those lines show in English) or has left over (dead
 * lines from a key that was renamed or removed). Also lists t("…") keys used
 * in src/ that en.js doesn't define, which would show the raw key on screen.
 * Exits non-zero if anything is off, so it can gate CI.
 *
 * Keys built at runtime (t(`meter.${id}`), noteLabel, the what.* storage
 * phrases) can't be seen by the source scan; en.js is their reference.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { DICTS } from "../src/i18n/index.js";

const en = DICTS.en;
let problems = 0;

for (const [lang, dict] of Object.entries(DICTS)) {
  if (lang === "en") continue;
  const missing = Object.keys(en).filter((k) => !(k in dict));
  const extra = Object.keys(dict).filter((k) => !(k in en));
  if (missing.length) console.log(`${lang}: missing ${missing.length}\n  ${missing.join("\n  ")}`);
  if (extra.length) console.log(`${lang}: left over ${extra.length}\n  ${extra.join("\n  ")}`);
  if (!missing.length && !extra.length) console.log(`${lang}: complete (${Object.keys(dict).length} keys)`);
  problems += missing.length + extra.length;
}

function* sources(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) yield* sources(path);
    else if (/\.(jsx?|mjs)$/.test(name) && !path.includes(join("src", "i18n"))) yield path;
  }
}

const used = new Set();
for (const file of sources("src")) {
  // Skip comments so doc examples don't count as uses.
  const code = readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, "");
  for (const m of code.matchAll(/\bt\(\s*"([\w.]+)"/g)) used.add(m[1]);
}
const undefinedKeys = [...used].filter((k) => !(k in en));
if (undefinedKeys.length) {
  console.log(`used but not in en.js: ${undefinedKeys.length}\n  ${undefinedKeys.join("\n  ")}`);
  problems += undefinedKeys.length;
}

process.exit(problems ? 1 : 0);
