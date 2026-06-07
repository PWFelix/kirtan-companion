import { useState, useRef, useEffect } from "react";

/**
 * BeatEditor
 * ----------
 * A separate view for building a custom 8-step beat.
 *
 * - Two rows of 8 cells: dayan (top end) and bayan (bottom end).
 * - Tap a cell to cycle it: empty -> "O" (open) -> "X" (closed) -> empty.
 * - Live preview: while previewing, the editor drives the engine so you
 *   hear your changes immediately.
 * - Name it and save: the saved beat is handed back to App via onSave,
 *   which stores it (persisted in the browser) and shows it as a card.
 *
 * Receives the engine so it can preview, plus callbacks to talk to App.
 * Knows nothing about how beats are stored — it just builds one and
 * hands it up. (Same "events up" idea: the child reports, the parent decides.)
 */

const STEPS = 8;
const EMPTY_GRID = () => Array(STEPS).fill(null);

// Tapping a cell cycles through these in order.
const CYCLE = [null, "O", "X"];
function nextValue(current) {
  const i = CYCLE.indexOf(current);
  return CYCLE[(i + 1) % CYCLE.length];
}

function BeatEditor({ engine, onSave, onClose }) {
  // The beat being built — two arrays of 8 cells.
  const [dayan, setDayan] = useState(EMPTY_GRID());
  const [bayan, setBayan] = useState(EMPTY_GRID());
  const [bpm, setBpm]     = useState(90);
  const [name, setName]   = useState("");
  const [previewing, setPreviewing] = useState(false);
  const [step, setStep]   = useState(-1);

  // Build a beat object from the current grid state.
  // useRef so the live-preview effect always reads the latest version.
  const beatRef = useRef(null);
  beatRef.current = {
    id: "preview",
    name: name || "Preview",
    note: "Custom",
    bpm,
    steps: STEPS,
    dayan,
    bayan,
  };

  // Subscribe to the engine's step event so the editor grid shows
  // the playhead while previewing.
  useEffect(() => {
    const onStep = (s) => setStep(s);
    engine.on("step", onStep);
    // Cleanup: stop listening if the editor closes, so we don't leak.
    return () => engine.off("step", onStep);
  }, [engine]);

  // While previewing, keep the engine playing the latest version of the beat.
  // If the grid changes mid-preview, we push the updated beat to the engine.
  useEffect(() => {
    if (!previewing) return;
    engine.setBeat(beatRef.current);
    engine.setBpm(bpm);
  }, [previewing, dayan, bayan, bpm, engine]);

  // ── Cell tapping ──
  function tapDayan(i) {
    setDayan(prev => {
      const copy = [...prev];
      copy[i] = nextValue(copy[i]);
      return copy;
    });
  }
  function tapBayan(i) {
    setBayan(prev => {
      const copy = [...prev];
      copy[i] = nextValue(copy[i]);
      return copy;
    });
  }

  // ── Preview ──
  async function togglePreview() {
    await engine.unlock();
    if (previewing) {
      engine.stop();
      setPreviewing(false);
    } else {
      engine.setBeat(beatRef.current);
      engine.setBpm(bpm);
      engine.start();
      setPreviewing(true);
    }
  }

  // ── Save ──
  function handleSave() {
    if (previewing) { engine.stop(); setPreviewing(false); }

    // Don't save an empty beat.
    const hasAnyHit = dayan.some(c => c !== null) || bayan.some(c => c !== null);
    if (!hasAnyHit) { alert("Add at least one stroke before saving."); return; }

    const finalName = name.trim() || "Custom Beat";
    // A unique id from the name + timestamp, so two beats can share a name.
    const id = "custom_" + finalName.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now();

    onSave({
      id,
      name: finalName,
      note: "Custom",
      bpm,
      steps: STEPS,
      dayan,
      bayan,
    });
    onClose();
  }

  function clearGrid() {
    setDayan(EMPTY_GRID());
    setBayan(EMPTY_GRID());
  }

  // Render one row of cells.
  function renderRow(values, tapFn, label) {
    return (
      <div style={st.row}>
        <span style={st.rowLabel}>{label}</span>
        <div style={st.cells}>
          {values.map((v, i) => {
            const active = previewing && i === step;
            const bg = v === "O" ? "var(--saffron)"
                     : v === "X" ? "var(--gold)"
                     : "var(--surface)";
            return (
              <button
                key={i}
                onClick={() => tapFn(i)}
                aria-label={`${label} step ${i + 1}: ${v === "O" ? "open" : v === "X" ? "closed" : "empty"}`}
                style={{
                  ...st.cell,
                  background: bg,
                  borderColor: active ? "var(--saffron-d)" : "var(--line)",
                  boxShadow: active ? "0 0 12px oklch(0.815 0.135 80 / 0.7)" : "none",
                  color: v ? "#fff" : "var(--faint)",
                }}
              >
                {v || ""}
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  return (
    <div style={st.screen}>
      <header style={st.header}>
        <button onClick={() => { if (previewing) engine.stop(); onClose(); }} style={st.backBtn}>
          ‹ Back
        </button>
        <h1 style={st.title}>Beat Editor</h1>
        <div style={{ width: 56 }} />{/* spacer to balance the back button */}
      </header>

      <p style={st.hint}>
        Tap a cell to cycle it: empty → <b style={{ color: "var(--saffron-d)" }}>open</b> → <b style={{ color: "var(--gold)" }}>closed</b>
      </p>

      {/* The grid */}
      <div style={st.grid}>
        <div style={st.stepNums}>
          <span style={st.rowLabel} />
          <div style={st.cells}>
            {Array.from({ length: STEPS }, (_, i) => (
              <span key={i} style={{ ...st.stepNum, fontWeight: i % 2 === 0 ? 700 : 400, opacity: i % 2 === 0 ? 0.8 : 0.5 }}>
                {i % 2 === 0 ? (i / 2) + 1 : "+"}
              </span>
            ))}
          </div>
        </div>
        {renderRow(dayan, tapDayan, "Top")}
        {renderRow(bayan, tapBayan, "Bottom")}
      </div>

      {/* Tempo */}
      <div style={st.tempoBlock}>
        <div style={st.tempoHead}>
          <span style={st.controlLabel}>Tempo</span>
          <span style={st.bpmReadout}>
            <span style={st.bpmNum}>{bpm}</span>
            <span style={st.bpmUnit}>BPM</span>
          </span>
        </div>
        <input
          className="kc-range"
          type="range"
          min={40}
          max={200}
          value={bpm}
          onChange={(e) => setBpm(Number(e.target.value))}
          style={{ "--fill": ((bpm - 40) / 160) * 100 + "%" }}
        />
      </div>

      {/* Actions */}
      <div style={st.actions}>
        <button onClick={togglePreview} style={{ ...st.actionBtn, ...st.previewBtn }}>
          {previewing ? "■ Stop" : "▶ Preview"}
        </button>
        <button onClick={clearGrid} style={{ ...st.actionBtn, ...st.clearBtn }}>
          Clear
        </button>
      </div>

      {/* Save */}
      <div style={st.saveRow}>
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Name your beat"
          style={st.nameInput}
        />
        <button onClick={handleSave} style={st.saveBtn}>Save</button>
      </div>
    </div>
  );
}

const st = {
  screen: {
    width: "100%", maxWidth: 430, minHeight: "100dvh", margin: "0 auto",
    display: "flex", flexDirection: "column",
    padding: "26px 22px calc(26px + env(safe-area-inset-bottom))", gap: 18,
  },
  header: { display: "flex", alignItems: "center", justifyContent: "space-between" },
  backBtn: {
    border: "none", background: "transparent", color: "var(--saffron-d)",
    fontSize: 16, fontWeight: 700, cursor: "pointer", fontFamily: "inherit", padding: 4, width: 56, textAlign: "left",
  },
  title: { fontFamily: '"Marcellus", serif', fontWeight: 400, fontSize: 24, margin: 0, color: "var(--ink)" },
  hint: { textAlign: "center", fontSize: 13, color: "var(--muted)", margin: 0 },
  grid: { display: "flex", flexDirection: "column", gap: 10, background: "var(--surface)", borderRadius: 18, padding: 16, border: "1.5px solid var(--line)" },
  stepNums: { display: "flex", alignItems: "center", gap: 10 },
  stepNum: { flex: 1, textAlign: "center", fontSize: 12, color: "var(--muted)" },
  row: { display: "flex", alignItems: "center", gap: 10 },
  rowLabel: { width: 52, flexShrink: 0, fontSize: 12, fontWeight: 700, color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.05em" },
  cells: { display: "flex", gap: 6, flex: 1 },
  cell: {
    flex: 1, aspectRatio: "1", borderRadius: 10, border: "1.5px solid var(--line)",
    cursor: "pointer", fontFamily: '"Marcellus", serif', fontSize: 15, fontWeight: 700,
    transition: "background 100ms ease, box-shadow 120ms ease, border-color 120ms ease",
    display: "grid", placeItems: "center", padding: 0,
  },
  tempoBlock: {},
  tempoHead: { display: "flex", alignItems: "baseline", justifyContent: "space-between" },
  controlLabel: { fontSize: 12.5, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--muted)", fontWeight: 700, marginBottom: 8 },
  bpmReadout: { display: "flex", alignItems: "baseline", gap: 5 },
  bpmNum: { fontFamily: '"Marcellus", serif', fontSize: 26, color: "var(--saffron-d)", lineHeight: 1 },
  bpmUnit: { fontSize: 12, fontWeight: 700, letterSpacing: "0.08em", color: "var(--faint)" },
  actions: { display: "flex", gap: 10 },
  actionBtn: {
    flex: 1, padding: "13px", borderRadius: 14, fontSize: 15, fontWeight: 700,
    cursor: "pointer", fontFamily: "inherit", border: "1.5px solid var(--line)",
  },
  previewBtn: { background: "var(--saffron)", color: "#fff", borderColor: "var(--saffron)" },
  clearBtn: { background: "var(--surface)", color: "var(--muted)" },
  saveRow: { display: "flex", gap: 10, marginTop: 4 },
  nameInput: {
    flex: 1, padding: "12px 14px", borderRadius: 14, border: "1.5px solid var(--line)",
    fontSize: 15, fontFamily: "inherit", color: "var(--ink)", background: "var(--surface)", outline: "none",
  },
  saveBtn: {
    padding: "12px 24px", borderRadius: 14, border: "none", background: "var(--saffron-d)",
    color: "#fff", fontSize: 15, fontWeight: 700, cursor: "pointer", fontFamily: "inherit",
  },
};

export default BeatEditor;
