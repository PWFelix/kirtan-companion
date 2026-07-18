import { useState, useRef, useEffect } from "react";
import { generateGuidedLabels } from "./data/stepLabels.js";

/**
 * BeatEditor
 * ----------
 * Builds a custom beat of arbitrary length.
 * Tap a cell to cycle: empty -> "O" open -> "X" closed -> empty.
 * "+ Add group" / "− Remove group" grow or shrink the beat one musical unit
 * (`cellsPerGroup` cells) at a time, preserving the cells that remain.
 * Live preview drives the engine. Save hands the beat up to App.
 *
 * TEMPO IS SUBDIVISION-LOCKED, not bar-locked: `cellsPerGroup` is fixed for
 * the beat, and we keep the invariant `beatsPerBar = steps / cellsPerGroup`.
 * Because the sequencer's per-step interval is PPQ / cellsPerGroup ticks, each
 * cell's real-time duration is CONSTANT — adding a group just makes the loop
 * one pulse longer without changing the tempo. (See beats.js for the maths.)
 *
 * Optional `initialBeat` pre-fills the grid/tempo/name for editing an
 * existing beat. When provided we keep its id (so App overwrites the
 * matching saved entry); when absent the editor mints a fresh id (new beat).
 * Arrays are cloned on seed so a built-in's data is never mutated in place.
 */

const CYCLE = [null, "O", "X"];
const nextValue = (c) => CYCLE[(CYCLE.indexOf(c) + 1) % CYCLE.length];
const emptyGrid = (n) => Array(n).fill(null);

// The "feel" of a beat = its subdivision = cellsPerGroup (cells per numbered
// pulse). These are the three traditional kirtan feels the labels support.
const FEELS = [
  { cpg: 2, label: "Standard" },  // eighth notes  — "1 + 2 +"
  { cpg: 3, label: "Triplets" },  // dadra swing   — "1 trip let"
  { cpg: 4, label: "Double" },    // sixteenths    — "1 e + a"
];

// New beats default to a 4-beat Standard bar (the classic 8-cell beat, matching
// the old editor default). Editing an existing beat inherits its group size
// (falling back to its derived subdivision for older beats that predate it).
const DEFAULT_CELLS_PER_GROUP = 2;
const DEFAULT_BEATS = 4;
const NEW_BEAT_CELLS = DEFAULT_BEATS * DEFAULT_CELLS_PER_GROUP; // 8
const deriveGroup = (b) =>
  b == null ? DEFAULT_CELLS_PER_GROUP
            : b.cellsPerGroup ?? Math.max(1, Math.round(b.steps / (b.beatsPerBar ?? 4)));

function BeatEditor({ engine, onSave, onClose, initialBeat, nav }) {
  // Group size (feel) is changeable via the Feel selector below.
  const [cellsPerGroup, setCellsPerGroup] = useState(() => deriveGroup(initialBeat));
  const [steps, setSteps] = useState(initialBeat?.steps ?? NEW_BEAT_CELLS);
  const [dayan, setDayan] = useState(initialBeat ? [...initialBeat.dayan] : emptyGrid(NEW_BEAT_CELLS));
  const [bayan, setBayan] = useState(initialBeat ? [...initialBeat.bayan] : emptyGrid(NEW_BEAT_CELLS));
  const [bpm, setBpm]     = useState(initialBeat?.bpm ?? 90);
  const [name, setName]   = useState(initialBeat?.name ?? "");
  const [previewing, setPreviewing] = useState(false);
  const [step, setStep]   = useState(-1);

  // The invariant that keeps the tempo subdivision-locked (see header).
  const beatsPerBar = steps / cellsPerGroup;

  const labels = generateGuidedLabels(steps, cellsPerGroup);

  const beatRef = useRef(null);
  beatRef.current = { id: "preview", name: name || "Preview", note: "Custom", bpm, steps, beatsPerBar, cellsPerGroup, dayan, bayan };

  useEffect(() => {
    const onStep = (s) => setStep(s);
    engine.on("step", onStep);
    // Stop preview audio on unmount too — leaving via the bottom nav (not just
    // Back/Cancel) must not leave the engine playing the preview loop.
    return () => { engine.off("step", onStep); engine.stop(); };
  }, [engine]);

  useEffect(() => {
    if (!previewing) return;
    engine.setBeat(beatRef.current);
    engine.setBpm(bpm);
  }, [previewing, dayan, bayan, bpm, steps, engine]);

  // Grow by one group: append `cellsPerGroup` empty cells to both ends. The
  // existing cells keep their state (no re-flow). Preview can stay running —
  // the live effect below hands the engine the longer beat, and because
  // beatsPerBar grows in step the tempo is unchanged (only the loop is longer).
  function addGroup() {
    const pad = Array(cellsPerGroup).fill(null);
    setDayan(p => [...p, ...pad]);
    setBayan(p => [...p, ...pad]);
    setSteps(s => s + cellsPerGroup);
  }
  // Shrink by one group: drop the last `cellsPerGroup` cells. Never below one
  // group (the button is disabled at that point).
  function removeGroup() {
    if (steps <= cellsPerGroup) return;
    setDayan(p => p.slice(0, -cellsPerGroup));
    setBayan(p => p.slice(0, -cellsPerGroup));
    setSteps(s => s - cellsPerGroup);
  }

  // Change the feel (subdivision) while keeping the SAME number of numbered
  // beats — so a 4-beat bar stays 4 beats, just re-divided (8↔12↔16 cells,
  // exactly the old toggle's counts). The subdivision remap is ambiguous, so
  // the grid resets to empty, like the previous step-count toggle did.
  function changeFeel(nextCpg) {
    if (nextCpg === cellsPerGroup) return;
    if (previewing) { engine.stop(); setPreviewing(false); }
    const beats = steps / cellsPerGroup;      // preserve beat count
    const nextSteps = beats * nextCpg;
    setCellsPerGroup(nextCpg);
    setSteps(nextSteps);
    setDayan(emptyGrid(nextSteps));
    setBayan(emptyGrid(nextSteps));
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
    // Editing keeps the original id so App overwrites the saved entry;
    // a new beat (or a fork of a built-in) carries its own fresh id.
    const id = initialBeat?.id ?? ("custom_" + finalName.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now());
    onSave({ id, name: finalName, note: "Custom", bpm, steps, beatsPerBar, cellsPerGroup, dayan, bayan });
    onClose();
  }

  function clearGrid() { setDayan(emptyGrid(steps)); setBayan(emptyGrid(steps)); }

  // Long beats wrap into stacked blocks so cells stay tappable on a phone.
  // Each block holds a whole number of GROUPS (never splitting a pulse), up to
  // ~16 cells wide — the density the single-row layout already handled. Every
  // block renders its cells into a fixed `perRow`-column grid, so a short final
  // block left-aligns at the SAME cell width as the full ones (no ragged sizes).
  const perRow = Math.max(cellsPerGroup, Math.floor(16 / cellsPerGroup) * cellsPerGroup);
  const blocks = [];
  for (let start = 0; start < steps; start += perRow) {
    blocks.push([start, Math.min(start + perRow, steps)]);
  }

  // Sizing is keyed to the widest a row can get (perRow), not total length.
  const cellGap  = perRow >= 16 ? 3 : perRow >= 12 ? 5 : 6;
  const cellFont = perRow >= 16 ? 11 : 14;
  const numFont  = perRow >= 16 ? 9 : 11;
  const gridCols = { gridTemplateColumns: `repeat(${perRow}, 1fr)`, gap: cellGap };

  function renderRow(values, tapFn, label, start, end) {
    return (
      <div style={st.row}>
        <span className="kc-rowLabel" style={st.rowLabel}>{label}</span>
        <div className="kc-cellrow" style={{ ...st.cells, ...gridCols }}>
          {values.slice(start, end).map((v, j) => {
            const i = start + j;
            const active = previewing && i === step;
            const bg = v === "O" ? "var(--saffron)" : v === "X" ? "var(--gold)" : "var(--surface-paper)";
            return (
              <button key={i} onClick={() => tapFn(i)} className="kc-cell"
                aria-label={`${label} step ${i + 1}: ${v === "O" ? "open" : v === "X" ? "closed" : "empty"}`}
                style={{
                  ...st.cell,
                  fontSize: cellFont,
                  background: bg,
                  borderColor: active ? "var(--accent-saffron)" : "var(--rule)",
                  borderWidth: active ? 2 : 1,
                  color: v ? "var(--surface-paper)" : "var(--ink-secondary)",
                  // First cell of each group is a scroll-snap point on the
                  // narrow-screen block scroller (inert without a snap
                  // container, so desktop is unaffected).
                  scrollSnapAlign: i % cellsPerGroup === 0 ? "start" : undefined,
                }}>
                {v || ""}
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  return (
    <div className="kc-screen" style={st.screen}>
      <header style={st.header}>
        <button onClick={() => { if (previewing) engine.stop(); onClose(); }} style={st.backBtn}>‹ Back</button>
        <h1 style={st.title}>{initialBeat ? "Edit beat" : "New beat"}</h1>
        <div style={{ width: 56 }} />
      </header>

      {/* Everything between the pinned header and the pinned nav scrolls. */}
      <div style={st.scrollArea}>
      {/* Feel: the subdivision. Changing it re-divides the same number of
          beats and clears the grid. */}
      <div>
        <div style={st.feelLabel}>Feel</div>
        <div style={st.feelRow}>
          {FEELS.map(f => {
            const sel = f.cpg === cellsPerGroup;
            return (
              <button key={f.cpg} onClick={() => changeFeel(f.cpg)} aria-pressed={sel}
                style={{ ...st.feelBtn,
                  background: sel ? "var(--accent-saffron)" : "transparent",
                  borderColor: sel ? "var(--accent-saffron)" : "var(--rule)",
                  color: sel ? "var(--ink-primary)" : "var(--ink-secondary)" }}>
                {f.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Length control: add/remove one group (beat) at a time */}
      <div style={st.lengthRow}>
        <button onClick={removeGroup} disabled={steps <= cellsPerGroup}
          style={{ ...st.groupBtn, opacity: steps <= cellsPerGroup ? 0.4 : 1, cursor: steps <= cellsPerGroup ? "not-allowed" : "pointer" }}
          aria-label="Remove group">− Group</button>
        <div style={st.lengthReadout}>
          <span style={st.lengthNum}>{steps}</span>
          <span style={st.lengthLabel}>cells · {steps / cellsPerGroup} beats</span>
        </div>
        <button onClick={addGroup} style={st.groupBtn} aria-label="Add group">+ Group</button>
      </div>

      <p style={st.hint}>
        Tap a cell: empty → <b style={{ color: "var(--saffron-d)" }}>open</b> → <b style={{ color: "var(--gold)" }}>closed</b>
      </p>

      {/* Grid — one block per ~16-cell row of whole groups */}
      <div style={st.grid}>
        {blocks.map(([start, end]) => (
          <div key={start} className="kc-block" style={st.block}>
            <div style={st.stepNums}>
              <span className="kc-rowLabel" style={st.rowLabel} />
              <div className="kc-cellrow" style={{ ...st.cells, ...gridCols, alignItems: "center" }}>
                {labels.slice(start, end).map((l, j) => {
                  const strong = l !== "·"; // numbered downbeats read heavier than subdivisions
                  // Bump the middle-dot up a few px so the subdivision reads more clearly.
                  return (
                    <span key={start + j} style={{ ...st.stepNum, lineHeight: 1, fontSize: strong ? numFont : numFont + 6, fontWeight: strong ? 700 : 400, opacity: strong ? 0.85 : 0.45 }}>
                      {l}
                    </span>
                  );
                })}
              </div>
            </div>
            {renderRow(dayan, tapDayan, "Top", start, end)}
            {renderRow(bayan, tapBayan, "Bottom", start, end)}
          </div>
        ))}
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
        <button onClick={togglePreview} style={{ ...st.actionBtn, ...st.previewBtn }}>{previewing ? "Stop" : "Preview"}</button>
        <button onClick={clearGrid} style={{ ...st.actionBtn, ...st.clearBtn }}>Clear</button>
      </div>

      {/* Save */}
      <div style={st.saveRow}>
        <input type="text" value={name} onChange={(e) => setName(e.target.value)} placeholder="Name your beat" style={st.nameInput} />
        <button onClick={handleSave} style={st.saveBtn}>Save</button>
      </div>

      {/* Cancel — closes without saving; never touches localStorage */}
      <button onClick={() => { if (previewing) engine.stop(); onClose(); }} style={st.cancelBtn}>Cancel</button>
      </div>

      {nav}
    </div>
  );
}

const st = {
  // NOTE: padding kept identical to App's st.screen so the shared bottom nav
  // sits at the exact same spot here as on the other pages (no shift on switch).
  screen: { width: "100%", maxWidth: 430, minHeight: 0, margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-6) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-4) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))", gap: "var(--space-4)" },
  // The scrolling body between the pinned header and the pinned bottom nav.
  scrollArea: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", gap: "var(--space-4)" },
  header: { flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "space-between" },
  backBtn: { border: "none", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: 16, fontWeight: 700, cursor: "pointer", padding: 4, width: 56, textAlign: "left" },
  title: { fontFamily: "var(--font-display)", fontWeight: 400, fontSize: "var(--text-display-lg)", margin: 0, color: "var(--ink-primary)" },
  feelLabel: { fontFamily: "var(--font-display)", fontSize: "var(--text-display-md)", letterSpacing: "0.18em", textTransform: "uppercase", color: "var(--ink-secondary)", fontWeight: 400, marginBottom: "var(--space-2)" },
  feelRow: { display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 8 },
  feelBtn: { padding: "11px 6px", borderRadius: 12, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer", transition: "all 150ms ease" },
  lengthRow: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, padding: "10px 12px", borderRadius: 16, border: "var(--rule-hairline)", background: "var(--surface-raised)" },
  groupBtn: { flexShrink: 0, minWidth: 84, padding: "10px 14px", borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  lengthReadout: { display: "flex", flexDirection: "column", alignItems: "center", gap: 1, lineHeight: 1 },
  lengthNum: { fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-numeric-xl)", fontWeight: 700, color: "var(--accent-saffron)", lineHeight: 1 },
  lengthLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, color: "var(--ink-secondary)", textTransform: "uppercase", letterSpacing: "0.05em" },
  hint: { textAlign: "center", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", margin: 0 },
  grid: { display: "flex", flexDirection: "column", gap: 18, background: "var(--surface-raised)", borderRadius: 18, padding: 14, border: "var(--rule-hairline)" },
  block: { display: "flex", flexDirection: "column", gap: 10 },
  stepNums: { display: "flex", alignItems: "center", gap: 10 },
  stepNum: { textAlign: "center", fontFamily: "var(--font-body)", color: "var(--ink-secondary)", minWidth: 0 },
  row: { display: "flex", alignItems: "center", gap: 10 },
  rowLabel: { width: 48, flexShrink: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, color: "var(--ink-secondary)", textTransform: "uppercase", letterSpacing: "0.05em" },
  cells: { display: "grid", flex: 1, minWidth: 0 },
  cell: { aspectRatio: "1", borderRadius: 8, border: "1px solid var(--rule)", cursor: "pointer", fontFamily: "var(--font-display)", fontWeight: 700, transition: "background 100ms ease, border-color 120ms ease, border-width 120ms ease", display: "grid", placeItems: "center", padding: 0, minWidth: 0 },
  tempoHead: { display: "flex", alignItems: "baseline", justifyContent: "space-between" },
  controlLabel: { fontFamily: "var(--font-display)", fontSize: "var(--text-display-md)", letterSpacing: "0.18em", textTransform: "uppercase", color: "var(--ink-secondary)", fontWeight: 400, marginBottom: "var(--space-2)" },
  bpmReadout: { display: "flex", alignItems: "baseline", gap: 5 },
  bpmNum: { fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-numeric-xl)", fontWeight: 700, color: "var(--accent-saffron)", lineHeight: 1 },
  bpmUnit: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", color: "var(--ink-secondary)" },
  actions: { display: "flex", gap: 10 },
  actionBtn: { flex: 1, padding: "13px", borderRadius: 14, fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer", border: "var(--rule-hairline)" },
  previewBtn: { background: "var(--accent-saffron)", color: "var(--ink-primary)", borderColor: "var(--accent-saffron)" },
  clearBtn: { background: "transparent", color: "var(--ink-primary)" },
  saveRow: { display: "flex", gap: 10, marginTop: 2 },
  nameInput: { flex: 1, padding: "12px 14px", borderRadius: 14, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", background: "var(--surface-paper)", outline: "none" },
  saveBtn: { padding: "12px 24px", borderRadius: 14, border: "1px solid var(--accent-saffron)", background: "var(--accent-saffron)", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  cancelBtn: { marginTop: -6, padding: "11px", borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-secondary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer", letterSpacing: "0.03em" },
};

export default BeatEditor;
