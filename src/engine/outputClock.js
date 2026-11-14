/**
 * outputClock — when is NOW, as far as the listener's ears are concerned?
 * ----------------------------------------------------------------------
 * The playhead used to read `Tone.Transport.progress`, which is computed at
 * Tone's `now()`: the context's currentTime PLUS the 100 ms scheduling
 * lookahead. So the display ran a lookahead ahead of the drum before the
 * output latency was even counted, and a Bluetooth speaker added a third of
 * a second more.
 *
 * The right "now" is the context time being HEARD. The Web Audio API reports
 * it directly: `getOutputTimestamp()` pairs a context time with the
 * performance.now() at which it reaches the speaker, so the heard time at any
 * moment is extrapolated from that pair. Browsers without it fall back to
 * `currentTime − outputLatency` (or baseLatency), which is close.
 *
 * What the browser still gets wrong — most Bluetooth on most platforms — is
 * the user's display delay, set on the Sync screen and stored once per
 * browser (the web can't tell one output device from another without asking
 * for microphone permission, so there is no per-device key as in the apps).
 *
 * Pure: the context is passed in, so this is tested with a stub.
 */

/** A timestamp older than this (ms) is not trusted; fall back. */
const STALE_MS = 1000;

/**
 * The context time being heard at `perfMs` (a performance.now() value or an
 * event's timeStamp), in seconds.
 */
export function heardContextTime(ctx, perfMs) {
  if (typeof ctx.getOutputTimestamp === "function") {
    const ts = ctx.getOutputTimestamp();
    if (ts && ts.contextTime > 0 && ts.performanceTime > 0 && perfMs - ts.performanceTime < STALE_MS) {
      return ts.contextTime + (perfMs - ts.performanceTime) / 1000;
    }
  }
  const latency = Number.isFinite(ctx.outputLatency) && ctx.outputLatency > 0
    ? ctx.outputLatency
    : (Number.isFinite(ctx.baseLatency) ? ctx.baseLatency : 0);
  const now = typeof performance !== "undefined" ? performance.now() : perfMs;
  return ctx.currentTime - latency - (now - perfMs) / 1000;
}

/**
 * The latency the browser itself reports, in ms — already allowed for.
 * Null when it reports none.
 */
export function reportedLatencyMs(ctx) {
  const out = Number.isFinite(ctx.outputLatency) ? ctx.outputLatency : 0;
  const base = Number.isFinite(ctx.baseLatency) ? ctx.baseLatency : 0;
  const total = out + base;
  return total > 0 ? Math.round(total * 1000) : null;
}
