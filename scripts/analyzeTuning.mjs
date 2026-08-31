/**
 * analyzeTuning.mjs
 * -----------------
 * Measures the base pitch of each mridanga end from its OPEN samples and
 * regenerates src/data/tuning.js — the file the engine, the mixer's tune
 * readout and persistence import their tuning facts from.
 *
 * WHY A DEV-TIME SCRIPT: the samples are static assets, so their pitch is a
 * fixed fact about the repo. Measuring here means no pitch-detection DSP is
 * shipped to phones, and the numbers can be eyeballed (and committed) like
 * any other data change. Run it whenever sample files change:
 *
 *   npm run analyze-tuning
 *
 * HOW IT MEASURES: normalized autocorrelation over a handful of windows,
 * skipping the first 20 ms (the attack thump sits around 100–130 Hz and is
 * NOT the drum's tone). Per window we take the best lag in 55–1200 Hz with
 * parabolic interpolation for sub-sample accuracy; implausible intermediate
 * values (transient windows, a blown interpolation) are discarded. Per end
 * we take the median over windows, then the median over the round-robin
 * variants, so one odd window or one odd recording can't move the number.
 *
 * WHAT IT CAN'T SEE: the bayan open stroke bends downward (the "ge"), so its
 * number is the STRIKE resonance — a nominal reference, not a sustained
 * pitch. The user trims by ear with the cents slider; that's what it's for.
 * Closed strokes aren't measured at all — they ride the same head, so the
 * engine retunes them by the same ratio.
 *
 * Zero dependencies: the WAVs are plain 44.1 kHz Int16 RIFF, parsed by hand.
 * The output file is written WHOLESALE from the template at the bottom —
 * never hand-edit src/data/tuning.js; edit this script and re-run.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const ENDS = ["dayan", "bayan"];

// Measurement envelope. The lower bound clears the bayan's ~80 Hz strike;
// the upper clears the dayan's ~650 Hz ring with room for sharper drums.
const F_MIN_HZ = 55;
const F_MAX_HZ = 1200;
const SKIP_MS = 20;   // attack transient
const WINDOW = 4096;  // samples per analysis window (~93 ms at 44.1 kHz)
const STEP_MS = 50;   // hop between windows
const MAX_WINDOWS = 6;

/** Minimal RIFF/WAVE reader: walks chunks, returns mono float samples
 *  (multi-channel input is downmixed by averaging all channels per frame). */
function readWav(filePath) {
  const buf = fs.readFileSync(filePath);
  if (buf.toString("ascii", 0, 4) !== "RIFF") throw new Error(`${filePath}: not a RIFF file`);
  let off = 12, fmt = null, data = null;
  while (off + 8 <= buf.length) {
    const id = buf.toString("ascii", off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    if (id === "fmt ") fmt = { channels: buf.readUInt16LE(off + 10), rate: buf.readUInt32LE(off + 12), bits: buf.readUInt16LE(off + 22) };
    if (id === "data") data = buf.subarray(off + 8, off + 8 + size);
    off += 8 + size + (size & 1);
  }
  if (!fmt || !data) throw new Error(`${filePath}: missing fmt or data chunk`);
  if (fmt.bits !== 16) throw new Error(`${filePath}: expected 16-bit PCM, got ${fmt.bits}`);
  const bytesPerSample = fmt.bits / 8;
  const frames = Math.floor(data.length / bytesPerSample / fmt.channels);
  const mono = new Float64Array(frames);
  for (let i = 0; i < frames; i++) {
    let sum = 0;
    for (let ch = 0; ch < fmt.channels; ch++) {
      sum += data.readInt16LE((i * fmt.channels + ch) * bytesPerSample) / 32768;
    }
    mono[i] = sum / fmt.channels;
  }
  return { mono, rate: fmt.rate };
}

/**
 * Autocorrelation power at one lag, averaged over the overlap length (divided
 * by sample count, not window energy). This is not energy-normalized, which is
 * fine here: it is only ever compared relatively to pick the strongest lag.
 */
function acf(w, lag, len) {
  let sum = 0;
  for (let i = 0; i < len - lag; i++) sum += w[i] * w[i + lag];
  return sum / (len - lag);
}

/**
 * Fundamental estimate for one window, or null when nothing in the search
 * range looks periodic (a transient window fails here, which is the point).
 * Parabolic interpolation refines the lag; a |shift| > 0.5 means the peak
 * wasn't a real peak and the window is rejected too.
 */
function estimateWindow(x, rate, start, len) {
  const minLag = Math.floor(rate / F_MAX_HZ);
  const maxLag = Math.min(Math.ceil(rate / F_MIN_HZ), len - 2);
  const w = x.subarray(start, start + len);
  let best = 0, bestLag = -1;
  for (let lag = minLag; lag <= maxLag; lag++) {
    const power = acf(w, lag, len);
    if (power > best) { best = power; bestLag = lag; }
  }
  if (bestLag <= minLag || bestLag >= maxLag) return null;
  const y0 = acf(w, bestLag - 1, len), y1 = best, y2 = acf(w, bestLag + 1, len);
  const denom = 2 * (y0 - 2 * y1 + y2);
  const shift = denom === 0 ? 0 : (y0 - y2) / denom;
  if (!Number.isFinite(shift) || Math.abs(shift) > 0.5) return null;
  const freq = rate / (bestLag + shift);
  return freq >= F_MIN_HZ && freq <= F_MAX_HZ ? freq : null;
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

/** Median fundamental across the sample's analysis windows. */
function measureFile(filePath) {
  const { mono, rate } = readWav(filePath);
  const freqs = [];
  for (let start = Math.floor(rate * SKIP_MS / 1000);
       start + WINDOW < mono.length && freqs.length < MAX_WINDOWS;
       start += Math.floor(rate * STEP_MS / 1000)) {
    const freq = estimateWindow(mono, rate, start, WINDOW);
    if (freq != null) freqs.push(freq);
  }
  if (!freqs.length) throw new Error(`${filePath}: no periodic window found`);
  return median(freqs);
}

// ── Measure ──
const measured = {};
for (const end of ENDS) {
  const dir = path.join(ROOT, "public", "sounds", end);
  const variants = fs.readdirSync(dir).filter((f) => f.startsWith("open") && f.endsWith(".wav")).sort();
  if (!variants.length) throw new Error(`${dir}: no open_*.wav samples found`);
  const perVariant = variants.map((f) => {
    const freq = measureFile(path.join(dir, f));
    console.log(`  ${end}/${f}: ${freq.toFixed(1)} Hz`);
    return freq;
  });
  measured[end] = median(perVariant);
  console.log(`${end}: ${measured[end].toFixed(1)} Hz (median of ${variants.length} variants)`);
}

// ── Regenerate src/data/tuning.js wholesale ──
const output = `/**
 * tuning.js — GENERATED by scripts/analyzeTuning.mjs. Do not hand-edit;
 * re-run \`npm run analyze-tuning\` when the samples change.
 *
 * The pure tuning facts — no React, no audio, no storage behind them. This
 * is the same contract pattern as data/eq.js: the engine's clamp, the
 * mixer's slider/readout and persistence's load-time sanitise all import
 * here, so the three can't drift apart.
 *
 * END_BASE_FREQ is each end's measured open-stroke pitch (see the script
 * for method and caveats — the bayan number is its strike resonance, a
 * nominal reference). The UI shows note = base freq shifted by the user's
 * cents offset, so the readout always names the pitch actually sounding.
 */

// Per-end tuning bounds in cents. ±600 reaches EVERY key: no starting pitch
// is more than a tritone from its nearest target note.
export const TUNE_MIN_CENTS = -600;
export const TUNE_MAX_CENTS = 600;

// Measured open-stroke fundamentals in Hz (median over analysis windows,
// then over the round-robin variants).
export const END_BASE_FREQ = {
  dayan: ${measured.dayan.toFixed(1)},
  bayan: ${measured.bayan.toFixed(1)},
};

/** Cents offset -> playback rate ratio (what Tone.Player.playbackRate eats). */
export function centsToRate(cents) {
  return 2 ** (cents / 1200);
}

const NOTE_NAMES = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"];

/**
 * Frequency -> nearest note name plus residual cents, e.g. "E5 −12¢". The
 * residual keeps the readout honest when the drum sits between semitones
 * (a hand-tuned drum usually does).
 */
export function freqToNoteName(freq) {
  const n = 69 + 12 * Math.log2(freq / 440);
  const nearest = Math.round(n);
  const cents = Math.round((n - nearest) * 100);
  const name = NOTE_NAMES[((nearest % 12) + 12) % 12] + (Math.floor(nearest / 12) - 1);
  return cents === 0 ? name : \`\${name} \${cents > 0 ? "+" : "−"}\${Math.abs(cents)}¢\`;
}
`;

const outPath = path.join(ROOT, "src", "data", "tuning.js");
fs.writeFileSync(outPath, output);
console.log(`\nwrote ${path.relative(ROOT, outPath)}`);
