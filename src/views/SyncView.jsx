import { useEffect, useRef, useState } from "react";
import * as sh from "../ui/styles.js";
import { BackIcon } from "../ui/icons.jsx";
import {
  estimate, isComplete, clampDelay, WARM_UP, TAPS_NEEDED,
  DISPLAY_DELAY_MIN_MS, DISPLAY_DELAY_MAX_MS,
} from "../data/syncCalibration.js";

/**
 * SyncView — line the playhead up with what is actually heard, for a
 * Bluetooth speaker (or any output) that lags the screen.
 *
 * Two ways in, as in the apps:
 *   1. TAP ALONG: a plain click plays and the user taps on each one, eyes off
 *      the screen. Where the taps land against the clicks the browser thinks
 *      are sounding is the delay it failed to report (data/syncCalibration.js).
 *   2. LINE UP: a light flashes where the display thinks each click is, and a
 *      slider moves it until flash and click coincide.
 *
 * Opens IN PLACE from Settings (local state), and owns only its own draft:
 * the taps, the result and whether the click is running. The delay itself
 * lives in useTransport, which persists it and hands it to the engine.
 */
function SyncView({ transport, onBack, nav }) {
  const { engine, ready, displayDelayMs, setDisplayDelay, startSyncTest, stopSyncTest, getPhase } = transport;
  const [clicking, setClicking] = useState(false);
  const [tapping, setTapping] = useState(false);
  const [taps, setTaps] = useState([]);
  const [result, setResult] = useState(null);
  const [pressed, setPressed] = useState(false);
  const flashRef = useRef(null);

  // Leaving the screen always stops the click. stopSyncTest is re-created
  // each render, so hold the latest in a ref for the unmount cleanup.
  const stopRef = useRef(stopSyncTest);
  useEffect(() => { stopRef.current = stopSyncTest; });
  useEffect(() => () => stopRef.current(), []);

  // The flash: lit for the first fifth of each beat by the playhead WITH the
  // delay, so it shows exactly what the strip will. Imperative, like the strip.
  useEffect(() => {
    const el = flashRef.current;
    if (!el) return;
    if (!clicking) { el.style.opacity = 0.12; return; }
    let raf;
    const tick = () => {
      const position = (getPhase() || 0) * 4;
      const into = position - Math.floor(position);
      el.style.opacity = 0.12 + 0.88 * (into < 0.2 ? 1 - into / 0.2 : 0);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [clicking, getPhase]);

  async function startClick() {
    if (clicking) return;
    await startSyncTest();
    setClicking(true);
  }

  function stopClick() {
    stopSyncTest();
    setClicking(false);
    setTapping(false);
  }

  function onPadDown(e) {
    setPressed(true);
    if (!tapping) return;
    // The event's own timestamp, not "now": a busy main thread can't skew it.
    const next = [...taps, engine.offsetFromNearestStepMs(e.timeStamp)];
    setTaps(next);
    if (isComplete(next)) {
      const r = estimate(next);
      // Report what is actually set, after clamping to the slider's range.
      const final = r.delay != null ? { delay: clampDelay(r.delay) } : r;
      setResult(final);
      setTapping(false);
      if (final.delay != null) setDisplayDelay(final.delay);
    }
  }

  const counted = Math.min(Math.max(taps.length - WARM_UP, 0), TAPS_NEEDED);
  const reported = clicking ? engine.getReportedLatencyMs() : null;

  return (
    <div className="kc-screen" style={sh.screen}>
      <header style={sh.subHeader}>
        <button onClick={onBack} style={sh.backBtn} aria-label="Back">
          <BackIcon />
        </button>
        <h1 style={sh.subTitle}>Sync</h1>
        <span style={{ width: 44 }} />
      </header>

      <section style={st.body}>
        <p style={st.text}>
          Bluetooth speakers play a little after the screen. If the strip or the
          highway runs ahead of the drum, set the delay here.
        </p>

        <div style={st.card}>
          <span style={st.caps}>This browser</span>
          <span style={st.cardTitle}>
            Display delay: {displayDelayMs === 0 ? "none" : `${displayDelayMs > 0 ? "+" : ""}${displayDelayMs} ms`}
          </span>
          {reported != null && (
            <span style={st.small}>Browser reports {reported} ms, already allowed for</span>
          )}
        </div>

        <h2 style={st.caps}>1 · Tap along</h2>
        <p style={st.text}>
          {tapping
            ? "Tap on every click. Listen, don't watch."
            : "Start the click, then look away and tap the circle on every click you hear."}
        </p>
        <button
          type="button"
          aria-label="Tap pad: tap on every click"
          onPointerDown={onPadDown}
          onPointerUp={() => setPressed(false)}
          onPointerLeave={() => setPressed(false)}
          style={{
            ...st.pad,
            opacity: tapping ? 1 : 0.45,
            transform: pressed ? "scale(0.95)" : "none",
            background: pressed ? "color-mix(in oklch, var(--clay) 30%, transparent)" : st.pad.background,
          }}
        >
          {tapping ? `${counted} / ${TAPS_NEEDED}` : "Tap"}
        </button>

        {result?.delay != null && (
          <p style={{ ...st.text, color: "var(--syahi)" }}>
            Done. The display now runs {describe(result.delay)}. Check it below and fine-tune if you need to.
          </p>
        )}
        {result?.tooUneven && (
          <p style={{ ...st.text, color: "var(--clay)" }}>
            Those taps were too uneven to measure. Try again, keeping a steady tap with the click.
          </p>
        )}

        <button
          type="button"
          disabled={!ready}
          style={st.primary}
          onClick={async () => {
            if (tapping) { setTapping(false); return; }
            setTaps([]);
            setResult(null);
            await startClick();
            setTapping(true);
          }}
        >
          {tapping ? "Cancel" : result ? "Tap again" : "Start tapping"}
        </button>

        <h2 style={st.caps}>2 · Line up</h2>
        <p style={st.text}>
          The light flashes where the display thinks each click is. Slide until
          flash and click happen together.
        </p>
        <div style={st.lineUp}>
          <span ref={flashRef} aria-label="Flash on each click" style={st.flash} />
          <label style={st.sliderBox}>
            <span style={st.cardTitle}>{capitalise(describe(displayDelayMs))}</span>
            <input
              type="range"
              min={DISPLAY_DELAY_MIN_MS}
              max={DISPLAY_DELAY_MAX_MS}
              step={5}
              value={displayDelayMs}
              onChange={(e) => setDisplayDelay(Number(e.target.value))}
              style={{ width: "100%", accentColor: "var(--clay)" }}
            />
          </label>
        </div>
        <div style={st.row}>
          <button type="button" disabled={!ready} style={st.secondary}
            onClick={() => (clicking ? stopClick() : startClick())}>
            {clicking ? "Stop click" : "Play click"}
          </button>
          <button type="button" style={st.secondary} onClick={() => setDisplayDelay(0)}>
            Reset to 0
          </button>
        </div>
      </section>
      {nav}
    </div>
  );
}

/** "on time", "120 ms later", "30 ms earlier". */
function describe(ms) {
  if (ms === 0) return "on time, with no extra delay";
  return ms > 0 ? `${ms} ms later` : `${-ms} ms earlier`;
}

const capitalise = (s) => s.charAt(0).toUpperCase() + s.slice(1);

const st = {
  body: { display: "flex", flexDirection: "column", gap: "var(--space-4)", paddingBottom: "var(--space-6)" },
  text: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", lineHeight: 1.45 },
  caps: { margin: 0, fontFamily: "var(--font-body)", fontSize: 12.5, fontWeight: 600, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  card: { display: "flex", flexDirection: "column", gap: 2, padding: 16, borderRadius: 14, background: "var(--head-worn)", border: "var(--rule-hairline)" },
  cardTitle: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, color: "var(--syahi)" },
  small: { fontFamily: "var(--font-body)", fontSize: 13, color: "var(--syahi-soft)" },
  pad: {
    alignSelf: "center", width: 190, height: 190, borderRadius: "50%",
    border: "3px solid var(--clay)", background: "color-mix(in oklch, var(--clay) 14%, transparent)",
    color: "var(--clay-deep, var(--clay))", fontFamily: "var(--font-body)", fontSize: 30, fontWeight: 700,
    cursor: "pointer", touchAction: "manipulation", transition: "transform 80ms",
  },
  primary: {
    minHeight: 54, borderRadius: 18, border: "none", background: "var(--clay)", color: "var(--on-clay)",
    fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer",
  },
  lineUp: { display: "flex", alignItems: "center", gap: "var(--space-4)" },
  flash: { flexShrink: 0, width: 56, height: 56, borderRadius: "50%", background: "var(--clay)", opacity: 0.12 },
  sliderBox: { flex: 1, display: "flex", flexDirection: "column", gap: 6 },
  row: { display: "flex", gap: "var(--space-2)" },
  secondary: {
    flex: 1, minHeight: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent",
    color: "var(--syahi)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer",
  },
};

export default SyncView;
