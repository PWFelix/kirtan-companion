import { useState, useRef, useEffect } from "react";
import { getStepLabels } from "./data/stepLabels.js";

/**
 * BeatEditor
 * ----------
 * Builds a custom beat of 8, 12, or 16 steps.
 * Tap a cell to cycle: empty -> "O" open -> "X" closed -> empty.
 * A toggle cycles the step count (8 -> 12 -> 16 -> 8), clearing the grid.
 * Live preview drives the engine. Save hands the beat up to App.
 */

const STEP_OPTIONS = [8, 12, 16];
const CYCLE = [null, "O", "X"];
const nextValue = (c) => CYCLE[(CYCLE.indexOf(c) + 1) % CYCLE.length];
const emptyGrid = (n) => Array(n).fill(null);

function BeatEditor({ engine, onSave, onClose }) {
  const [steps, setSteps] = useState(8);
  const [dayan, setDayan] = useState(emptyGrid(8));
  const [bayan, setBayan] = useState(emptyGrid(8));
  const [bpm, setBpm]     = useState(90);
  const [name, setName]   = useState("");
  const [previewing, setPreviewing] = useState(false);
  const [step, setStep]   = useState(-1);
  // All custom beats are 4/4 in this pass — the editor doesn't expose
  // a meter control yet. 12-step custom beats therefore play as triplets,
  // matching how the built-in dadra is now defined.
  const beatsPerBar = 4;

  const labels = getStepLabels(steps, beatsPerBar);

  const beatRef = useRef(null);
  beatRef.current = { id: "preview", name: name || "Preview", note: "Custom", bpm, steps, beatsPerBar, dayan, bayan };

  useEffect(() => {
    const onStep = (s) => setStep(s);
    engine.on("step", onStep);
    return () => engine.off("step", onStep);
  }, [engine]);

  useEffect(() => {
    if (!previewing) return;
    engine.setBeat(beatRef.current);
    engine.setBpm(bpm);
  }, [previewing, dayan, bayan, bpm, steps, engine]);

  // Cycle the step count: 8 -> 12 -> 16 -> 8. Clears the grid.
  function cycleSteps() {
    if (previewing) { engine.stop(); setPreviewing(false); }
    const i = STEP_OPTIONS.indexOf(steps);
    const next = STEP_OPTIONS[(i + 1) % STEP_OPTIONS.length];
    setSteps(next);
    setDayan(emptyGrid(next));
    setBayan(emptyGrid(next));
  }

  function tapDayan(i) { setDayan(p => { const c = [...p]; c[i] = nextValue(c[i]); return c; }); }
  function tapBayan(i) { setBayan(p => { const c = [...p]; c[i] = nextValue(c[i]); return c; }); }

  async function togglePreview() {
    await engine.unlock();
    if (previewing) { engine.stop(); setPreviewing(false); }
    else { engine.setBeat(beatRef.current); engine.setBpm(bpm); engine.start(); setPreviewing(true); }
  }

  function handleSave() {
    if (previewing) { engine.stop(); setPreviewing(false); }
    const hasAnyHit = dayan.some(c => c !== null) || bayan.some(c => c !== null);
    if (!hasAnyHit) { alert("Add at least one stroke before saving."); return; }
    const finalName = name.trim() || "Custom Beat";
    const id = "custom_" + finalName.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now();
    onSave({ id, name: finalName, note: "Custom", bpm, steps, beatsPerBar, dayan, bayan });
    onClose();
  }

  function clearGrid() { setDayan(emptyGrid(steps)); setBayan(emptyGrid(steps)); }

  // Cell size shrinks as step count grows so 16 cells still fit on a phone.
  const cellGap = steps === 16 ? 3 : steps === 12 ? 5 : 6;

  function renderRow(values, tapFn, label) {
    return (
      <div style={st.row}>
        <span style={st.rowLabel}>{label}</span>
        <div style={{ ...st.cells, gap: cellGap }}>
          {values.map((v, i) => {
            const active = previewing && i === step;
            const bg = v === "O" ? "var(--saffron)" : v === "X" ? "var(--gold)" : "var(--surface)";
            return (
              <button key={i} onClick={() => tapFn(i)}
                aria-label={`${label} step ${i + 1}: ${v === "O" ? "open" : v === "X" ? "closed" : "empty"}`}
                style={{
                  ...st.cell,
                  fontSize: steps === 16 ? 11 : 14,
                  background: bg,
                  borderColor: active ? "var(--saffron-d)" : "var(--line)",
                  boxShadow: active ? "0 0 10px oklch(0.815 0.135 80 / 0.7)" : "none",
                  color: v ? "#fff" : "var(--faint)",
                }}>
                {v || ""}
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  const typeName = steps === 16 ? "Double-time" : steps === 12 ? "Triplets" : "Standard";

  return (
    <div style={st.screen}>
      <header style={st.header}>
        <button onClick={() => { if (previewing) engine.stop(); onClose(); }} style={st.backBtn}>‹ Back</button>
        <h1 style={st.title}>Beat Editor</h1>
        <div style={{ width: 56 }} />
      </header>

      {/* Step count toggle */}
      <button onClick={cycleSteps} style={st.stepToggle}>
        <span style={st.stepToggleNum}>{steps}</span>
        <span style={st.stepToggleLabel}>cells · {typeName}</span>
        <span style={st.stepToggleHint}>tap to change</span>
      </button>

      <p style={st.hint}>
        Tap a cell: empty → <b style={{ color: "var(--saffron-d)" }}>open</b> → <b style={{ color: "var(--gold)" }}>closed</b>
      </p>

      {/* Grid */}
      <div style={st.grid}>
        <div style={st.stepNums}>
          <span style={st.rowLabel} />
          <div style={{ ...st.cells, gap: cellGap }}>
            {labels.map((l, i) => (
              <span key={i} style={{ ...st.stepNum, fontSize: steps === 16 ? 9 : 11, fontWeight: l.strong ? 700 : 400, opacity: l.strong ? 0.85 : 0.45 }}>
                {l.text}
              </span>
            ))}
          </div>
        </div>
        {renderRow(dayan, tapDayan, "Top")}
        {renderRow(bayan, tapBayan, "Bottom")}
      </div>

      {/* Tempo */}
      <div>
        <div style={st.tempoHead}>
          <span style={st.controlLabel}>Tempo</span>
          <span style={st.bpmReadout}><span style={st.bpmNum}>{bpm}</span><span style={st.bpmUnit}>BPM</span></span>
        </div>
        <input className="kc-range" type="range" min={40} max={200} value={bpm}
          onChange={(e) => setBpm(Number(e.target.value))} style={{ "--fill": ((bpm - 40) / 160) * 100 + "%" }} />
      </div>

      {/* Actions */}
      <div style={st.actions}>
        <button onClick={togglePreview} style={{ ...st.actionBtn, ...st.previewBtn }}>{previewing ? "■ Stop" : "▶ Preview"}</button>
        <button onClick={clearGrid} style={{ ...st.actionBtn, ...st.clearBtn }}>Clear</button>
      </div>

      {/* Save */}
      <div style={st.saveRow}>
        <input type="text" value={name} onChange={(e) => setName(e.target.value)} placeholder="Name your beat" style={st.nameInput} />
        <button onClick={handleSave} style={st.saveBtn}>Save</button>
      </div>
    </div>
  );
}

const st = {
  screen: { width: "100%", maxWidth: 430, minHeight: "100dvh", margin: "0 auto", display: "flex", flexDirection: "column", padding: "26px 22px calc(26px + env(safe-area-inset-bottom))", gap: 16 },
  header: { display: "flex", alignItems: "center", justifyContent: "space-between" },
  backBtn: { border: "none", background: "transparent", color: "var(--saffron-d)", fontSize: 16, fontWeight: 700, cursor: "pointer", fontFamily: "inherit", padding: 4, width: 56, textAlign: "left" },
  title: { fontFamily: '"Marcellus", serif', fontWeight: 400, fontSize: 24, margin: 0, color: "var(--ink)" },
  stepToggle: { display: "flex", flexDirection: "column", alignItems: "center", gap: 2, padding: "12px", borderRadius: 16, border: "1.5px solid var(--saffron)", background: "var(--surface)", cursor: "pointer", fontFamily: "inherit" },
  stepToggleNum: { fontFamily: '"Marcellus", serif', fontSize: 28, color: "var(--saffron-d)", lineHeight: 1 },
  stepToggleLabel: { fontSize: 13, fontWeight: 700, color: "var(--ink)" },
  stepToggleHint: { fontSize: 11, color: "var(--faint)", letterSpacing: "0.05em", textTransform: "uppercase" },
  hint: { textAlign: "center", fontSize: 13, color: "var(--muted)", margin: 0 },
  grid: { display: "flex", flexDirection: "column", gap: 10, background: "var(--surface)", borderRadius: 18, padding: 14, border: "1.5px solid var(--line)" },
  stepNums: { display: "flex", alignItems: "center", gap: 10 },
  stepNum: { flex: 1, textAlign: "center", color: "var(--muted)", minWidth: 0 },
  row: { display: "flex", alignItems: "center", gap: 10 },
  rowLabel: { width: 48, flexShrink: 0, fontSize: 11, fontWeight: 700, color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.05em" },
  cells: { display: "flex", flex: 1 },
  cell: { flex: 1, aspectRatio: "1", borderRadius: 8, border: "1.5px solid var(--line)", cursor: "pointer", fontFamily: '"Marcellus", serif', fontWeight: 700, transition: "background 100ms ease, box-shadow 120ms ease, border-color 120ms ease", display: "grid", placeItems: "center", padding: 0, minWidth: 0 },
  tempoHead: { display: "flex", alignItems: "baseline", justifyContent: "space-between" },
  controlLabel: { fontSize: 12.5, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--muted)", fontWeight: 700, marginBottom: 8 },
  bpmReadout: { display: "flex", alignItems: "baseline", gap: 5 },
  bpmNum: { fontFamily: '"Marcellus", serif', fontSize: 26, color: "var(--saffron-d)", lineHeight: 1 },
  bpmUnit: { fontSize: 12, fontWeight: 700, letterSpacing: "0.08em", color: "var(--faint)" },
  actions: { display: "flex", gap: 10 },
  actionBtn: { flex: 1, padding: "13px", borderRadius: 14, fontSize: 15, fontWeight: 700, cursor: "pointer", fontFamily: "inherit", border: "1.5px solid var(--line)" },
  previewBtn: { background: "var(--saffron)", color: "#fff", borderColor: "var(--saffron)" },
  clearBtn: { background: "var(--surface)", color: "var(--muted)" },
  saveRow: { display: "flex", gap: 10, marginTop: 2 },
  nameInput: { flex: 1, padding: "12px 14px", borderRadius: 14, border: "1.5px solid var(--line)", fontSize: 15, fontFamily: "inherit", color: "var(--ink)", background: "var(--surface)", outline: "none" },
  saveBtn: { padding: "12px 24px", borderRadius: 14, border: "none", background: "var(--saffron-d)", color: "#fff", fontSize: 15, fontWeight: 700, cursor: "pointer", fontFamily: "inherit" },
};

export default BeatEditor;
