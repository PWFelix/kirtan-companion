/**
 * sessionPrefs.js
 *
 * Where you left off: the last loaded beat and its tempo, so reopening the app
 * lands on the beat you were playing. One small JSON object under one key.
 *
 * Same posture as eqPrefs.js — NEVER THROW. Storage can be blocked (private
 * mode) or hold junk; either way the app opens on its defaults.
 *
 * The tempo is restored CAPPED AT 200: every launch starts in the normal range,
 * so Brahmaloka time has to be earned again (see data/meter.js).
 */
import { MIN_BPM, NORMAL_MAX_BPM } from "../data/meter.js";

const KEY = "kirtan-session";

/** { beatId: string|null, bpm: number|null } — nulls mean "use the default". */
export function loadSession() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || "{}");
    const bpm = Number(raw?.bpm);
    return {
      beatId: typeof raw?.beatId === "string" ? raw.beatId : null,
      bpm: Number.isFinite(bpm) ? Math.min(NORMAL_MAX_BPM, Math.max(MIN_BPM, Math.round(bpm))) : null,
    };
  } catch {
    return { beatId: null, bpm: null };
  }
}

/** Merge `patch` into the stored session. Failures are ignored: it's a nicety. */
export function saveSession(patch) {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || "{}");
    localStorage.setItem(KEY, JSON.stringify({ ...(raw && typeof raw === "object" ? raw : {}), ...patch }));
  } catch { /* blocked or full: this session only */ }
}
