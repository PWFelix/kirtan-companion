import { useState, useEffect, useRef } from "react";
import { KirtanEngine } from "./engine/KirtanEngine.js";
import { BEATS } from "./data/beats.js";
import BeatEditor from "./BeatEditor.jsx";

const MIN_BPM = 40, MAX_BPM = 200;
const SAVED_KEY = "kirtan-custom-beats"; // localStorage key for saved beats

// Load any beats the user saved in a previous session.
function loadSavedBeats() {
  try {
    return JSON.parse(localStorage.getItem(SAVED_KEY)) || [];
  } catch (e) {
    return [];
  }
}

function App() {
  // ── Engine: created once ──
  const engineRef = useRef(null);
  if (engineRef.current === null) {
    engineRef.current = new KirtanEngine();
  }
  const engine = engineRef.current;

  // ── Which view are we showing? ──
  const [view, setView] = useState("main"); // "main" | "editor"

  // ── Beats: built-in plus any the user has saved ──
  const [customBeats, setCustomBeats] = useState(loadSavedBeats);
  const allBeats = [...BEATS, ...customBeats];

  // ── State ──
  const [ready, setReady]     = useState(false);
  const [beatId, setBeatId]   = useState(BEATS[0].id);
  const [bpm, setBpm]         = useState(BEATS[0].bpm);
  const [playing, setPlaying] = useState(false);
  const [step, setStep]       = useState(-1);
  const [volume, setVolume]   = useState(0.9);

  const tapTimesRef = useRef([]);

  const beat = allBeats.find(b => b.id === beatId) || allBeats[0];

  // ── One-time setup ──
  useEffect(() => {
    engine.loadSounds({
      dayan_open:   "/sounds/dayan_open.wav",
      dayan_closed: "/sounds/dayan_closed.wav",
      bayan_open:   "/sounds/bayan_open.wav",
      bayan_closed: "/sounds/bayan_closed.wav",
    });
    engine.on("ready",   () => setReady(true));
    engine.on("started", () => setPlaying(true));
    engine.on("stopped", () => setPlaying(false));
    engine.on("step",    (s) => setStep(s));
    engine.setVolume(volume);
  }, []);

  // ── Handlers ──
  async function togglePlay() {
    await engine.unlock();
    if (playing) {
      engine.stop();
    } else {
      engine.setBpm(bpm);
      engine.setBeat(beat);
      engine.start();
    }
  }

  function selectBeat(b) {
    setBeatId(b.id);
    setBpm(b.bpm);
    engine.setBeat(b);
    engine.setBpm(b.bpm);
  }

  function changeBpm(value) {
    setBpm(value);
    engine.setBpm(value);
  }

  function changeVolume(value) {
    setVolume(value);
    engine.setVolume(value);
  }

  function handleTap() {
    const now = Date.now();
    const taps = tapTimesRef.current;
    taps.push(now);
    if (taps.length > 4) taps.shift();
    if (taps.length >= 2) {
      const gaps = [];
      for (let i = 1; i < taps.length; i++) gaps.push(taps[i] - taps[i - 1]);
      if (gaps[gaps.length - 1] > 2000) { tapTimesRef.current = [now]; return; }
      const avgGap = gaps.reduce((a, b) => a + b, 0) / gaps.length;
      const clamped = Math.max(MIN_BPM, Math.min(MAX_BPM, Math.round(60000 / avgGap)));
      changeBpm(clamped);
    }
  }

  // ── Saving a custom beat from the editor ──
  function handleSaveBeat(newBeat) {
    const updated = [...customBeats, newBeat];
    setCustomBeats(updated);
    // Persist to the browser so it survives a refresh.
    try { localStorage.setItem(SAVED_KEY, JSON.stringify(updated)); } catch (e) {}
  }

  function deleteCustomBeat(id, e) {
    e.stopPropagation(); // don't also select the card
    const updated = customBeats.filter(b => b.id !== id);
    setCustomBeats(updated);
    try { localStorage.setItem(SAVED_KEY, JSON.stringify(updated)); } catch (e) {}
    // If we deleted the selected beat, fall back to the first built-in.
    if (beatId === id) selectBeat(BEATS[0]);
  }

  // ── Editor view ──
  if (view === "editor") {
    return (
      <BeatEditor
        engine={engine}
        onSave={handleSaveBeat}
        onClose={() => {
          // Make sure preview isn't left running, and reflect state.
          engine.stop();
          setPlaying(false);
          setView("main");
        }}
      />
    );
  }

  // ── Main view ──
  const fillPct = ((bpm - MIN_BPM) / (MAX_BPM - MIN_BPM)) * 100;
  const volPct  = volume * 100;
  const beatMs  = (60 * 1000) / bpm;

  return (
    <div style={st.screen}>
      <header style={st.header}>
        <div style={st.emblem} aria-hidden="true" />
        <h1 style={st.title}>Kirtan Companion</h1>
        <p style={st.subtitle}>Let the mridanga keep time while you chant</p>
      </header>

      <main style={st.stage}>
        <div style={st.beatRow} role="img" aria-label={`Beat ${step + 1} of ${beat.steps}`}>
          {beat.dayan.map((hit, i) => {
            const active   = playing && i === step;
            const isAccent = i % 2 === 0;
            const isRest   = hit === null && beat.bayan[i] === null;
            return (
              <div
                key={i}
                style={{
                  ...st.cell,
                  height: isAccent ? 34 : 24,
                  background: active ? "var(--gold)" : isRest ? "transparent" : "var(--cell)",
                  border: isRest ? "2px solid var(--cell)" : "2px solid transparent",
                  opacity: active ? 1 : isRest ? 0.6 : 0.85,
                  transform: active ? "scaleY(1.18)" : "scaleY(1)",
                  boxShadow: active ? "0 0 16px oklch(0.815 0.135 80 / 0.7)" : "none",
                }}
              />
            );
          })}
        </div>

        <div style={st.playWrap}>
          {playing && (
            <span className="kc-glow" style={{ ...st.glow, animation: `kc-breathe ${beatMs * 2}ms ease-in-out infinite` }} aria-hidden="true" />
          )}
          <button onClick={togglePlay} disabled={!ready} aria-label={playing ? "Stop" : "Play"}
            style={{ ...st.play, transform: playing ? "scale(0.97)" : "scale(1)", opacity: ready ? 1 : 0.5 }}>
            {playing ? (
              <svg width="46" height="46" viewBox="0 0 46 46" aria-hidden="true"><rect x="11" y="11" width="24" height="24" rx="7" fill="#fff" /></svg>
            ) : (
              <svg width="50" height="50" viewBox="0 0 50 50" aria-hidden="true"><path d="M18 12 L38 25 L18 38 Z" fill="#fff" /></svg>
            )}
          </button>
          <span style={st.playLabel}>{!ready ? "Loading…" : playing ? "Tap to stop" : "Tap to begin"}</span>
        </div>
      </main>

      <section style={st.controls}>
        {/* Rhythm cards */}
        <div>
          <div style={st.controlLabel}>Rhythm</div>
          <div style={st.cardRow}>
            {allBeats.map(b => {
              const sel = b.id === beatId;
              const isCustom = b.note === "Custom";
              return (
                <button key={b.id} onClick={() => selectBeat(b)}
                  style={{
                    ...st.card,
                    background: sel ? "var(--saffron)" : "var(--surface)",
                    borderColor: sel ? "var(--saffron)" : "var(--line)",
                    boxShadow: sel ? "0 8px 20px oklch(0.62 0.16 46 / 0.25)" : "0 1px 2px oklch(0.5 0.05 60 / 0.06)",
                    position: "relative",
                  }}>
                  <span style={{ ...st.cardName, color: sel ? "#fff" : "var(--ink)" }}>{b.name}</span>
                  <span style={{ ...st.cardNote, color: sel ? "oklch(0.97 0.02 80)" : "var(--faint)" }}>{b.note}</span>
                  {isCustom && (
                    <span onClick={(e) => deleteCustomBeat(b.id, e)} role="button" aria-label={`Delete ${b.name}`}
                      style={{ ...st.deleteDot, color: sel ? "#fff" : "var(--faint)" }}>×</span>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Tempo + tap */}
        <div>
          <div style={st.tempoHead}>
            <span style={st.controlLabel}>Tempo</span>
            <span style={st.bpmReadout}><span style={st.bpmNum}>{bpm}</span><span style={st.bpmUnit}>BPM</span></span>
          </div>
          <div style={st.tempoRow}>
            <input className="kc-range" type="range" min={MIN_BPM} max={MAX_BPM} value={bpm}
              onChange={(e) => changeBpm(Number(e.target.value))} style={{ "--fill": fillPct + "%", flex: 1 }} aria-label="Tempo" />
            <button onClick={handleTap} style={st.tapBtn} aria-label="Tap tempo">Tap</button>
          </div>
          <div style={st.scaleRow}><span>Slow</span><span>Fast</span></div>
        </div>

        {/* Volume */}
        <div>
          <div style={st.tempoHead}>
            <span style={st.controlLabel}>Volume</span>
            <span style={st.bpmReadout}><span style={st.volNum}>{Math.round(volPct)}</span><span style={st.bpmUnit}>%</span></span>
          </div>
          <input className="kc-range" type="range" min={0} max={100} value={volPct}
            onChange={(e) => changeVolume(Number(e.target.value) / 100)} style={{ "--fill": volPct + "%" }} aria-label="Volume" />
        </div>

        {/* Create a beat — opens the editor */}
        <button onClick={() => { engine.stop(); setPlaying(false); setView("editor"); }} style={st.createBtn}>
          + Create a beat
        </button>
      </section>
    </div>
  );
}

const st = {
  screen: { width: "100%", maxWidth: 430, minHeight: "100dvh", margin: "0 auto", display: "flex", flexDirection: "column", padding: "34px 26px calc(34px + env(safe-area-inset-bottom))", gap: 18 },
  header: { textAlign: "center", display: "flex", flexDirection: "column", alignItems: "center", gap: 8 },
  emblem: { width: 30, height: 30, borderRadius: "50%", background: "radial-gradient(circle at 50% 38%, var(--gold), var(--saffron) 70%)", boxShadow: "0 4px 14px oklch(0.7 0.15 60 / 0.35)", marginBottom: 4 },
  title: { fontFamily: '"Marcellus", serif', fontWeight: 400, fontSize: 30, margin: 0, letterSpacing: "0.01em", color: "var(--ink)" },
  subtitle: { margin: 0, fontSize: 14.5, color: "var(--muted)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
  stage: { flex: 1, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 40, minHeight: 0, padding: "10px 0" },
  beatRow: { display: "flex", alignItems: "center", justifyContent: "center", gap: 11, height: 44 },
  cell: { width: 16, borderRadius: 99, transition: "transform 90ms ease, background 90ms ease, box-shadow 120ms ease, opacity 120ms ease" },
  playWrap: { position: "relative", display: "flex", flexDirection: "column", alignItems: "center", gap: 18 },
  glow: { position: "absolute", top: -18, left: "50%", marginLeft: -109, width: 218, height: 218, borderRadius: "50%", background: "radial-gradient(circle, oklch(0.78 0.14 62 / 0.55) 0%, transparent 68%)", pointerEvents: "none" },
  play: { position: "relative", width: 182, height: 182, borderRadius: "50%", border: "none", cursor: "pointer", display: "grid", placeItems: "center", background: "radial-gradient(circle at 50% 32%, var(--saffron) 0%, var(--saffron-d) 100%)", boxShadow: "0 18px 44px oklch(0.6 0.16 48 / 0.4), inset 0 2px 6px oklch(0.85 0.12 80 / 0.6), inset 0 -10px 22px oklch(0.5 0.14 44 / 0.45)", transition: "transform 140ms ease" },
  playLabel: { fontSize: 13.5, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--faint)", fontWeight: 600 },
  controls: { display: "flex", flexDirection: "column", gap: 22 },
  controlLabel: { fontSize: 12.5, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--muted)", fontWeight: 700, marginBottom: 11 },
  cardRow: { display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 9 },
  card: { display: "flex", flexDirection: "column", alignItems: "center", gap: 2, padding: "13px 4px 11px", borderRadius: 18, border: "1.5px solid var(--line)", cursor: "pointer", transition: "all 150ms ease", fontFamily: "inherit" },
  cardName: { fontSize: 14, fontWeight: 700, letterSpacing: "0.01em" },
  cardNote: { fontSize: 10.5, fontWeight: 500 },
  deleteDot: { position: "absolute", top: 4, right: 7, fontSize: 16, lineHeight: 1, fontWeight: 700, cursor: "pointer" },
  tempoHead: { display: "flex", alignItems: "baseline", justifyContent: "space-between" },
  tempoRow: { display: "flex", alignItems: "center", gap: 10 },
  tapBtn: { flexShrink: 0, padding: "8px 16px", borderRadius: 12, border: "1.5px solid var(--saffron)", background: "var(--surface)", color: "var(--saffron-d)", fontWeight: 700, fontSize: 13, cursor: "pointer", fontFamily: "inherit", letterSpacing: "0.04em" },
  bpmReadout: { display: "flex", alignItems: "baseline", gap: 5 },
  bpmNum: { fontFamily: '"Marcellus", serif', fontSize: 28, color: "var(--saffron-d)", lineHeight: 1 },
  volNum: { fontFamily: '"Marcellus", serif', fontSize: 22, color: "var(--saffron-d)", lineHeight: 1 },
  bpmUnit: { fontSize: 12, fontWeight: 700, letterSpacing: "0.08em", color: "var(--faint)" },
  scaleRow: { display: "flex", justifyContent: "space-between", fontSize: 11.5, color: "var(--faint)", fontWeight: 600, marginTop: 2, letterSpacing: "0.04em" },
  createBtn: { marginTop: 2, padding: "15px", borderRadius: 16, border: "1.5px dashed var(--saffron)", background: "transparent", color: "var(--saffron-d)", fontSize: 15, fontWeight: 700, cursor: "pointer", fontFamily: "inherit", letterSpacing: "0.03em" },
};

export default App;
