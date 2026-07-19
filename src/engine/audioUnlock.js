/**
 * audioUnlock
 * -----------
 * Makes the engine audible on iPhones/iPads with the silent switch on.
 *
 * WHY: iOS sorts a page's sound into two buckets. HTML <audio> elements
 * count as MEDIA PLAYBACK (like a music app — plays even in silent mode).
 * The Web Audio API — which Tone.js uses for sample-accurate timing —
 * counts as UI SOUND EFFECTS, and the ringer/silent switch mutes it.
 *
 * THE TRICK (used by web games and music apps everywhere): after the
 * first user tap, start a tiny looping SILENT <audio> element. That
 * flips the whole page's audio session into "media playback" mode, and
 * from then on Web Audio plays through the media channel too — silent
 * switch or not. On every other platform this is a harmless no-op.
 *
 * The silence is a 44-byte-header WAV (8 kHz mono, 0.05 s) inlined as a
 * data URI, so there is no network request and nothing to preload.
 *
 * Must be called from inside a user-gesture handler (a tap), the same
 * rule as Tone.start(). Idempotent — safe to call on every tap.
 */

// prettier-ignore
const SILENT_WAV =
  "data:audio/wav;base64,UklGRrQBAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YZABAACAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICA";

let element = null;

/** Start (or restart) the silent keep-alive loop. Call from a tap handler. */
export function enableMediaPlayback() {
  if (!element) {
    element = new Audio(SILENT_WAV);
    element.loop = true;
    // Belt-and-braces for iOS: never fullscreen, never treated as video.
    element.setAttribute("playsinline", "");
  }
  // play() returns a promise that rejects if the browser refuses (e.g.
  // called outside a gesture). That's fine — we'll try again next tap.
  const p = element.play();
  if (p && p.catch) p.catch(() => {});
}
