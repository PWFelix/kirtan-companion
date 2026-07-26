import { useState, useRef, useEffect } from "react";
import BeatStrip from "./BeatStrip.jsx";
import { generateGuidedLabels } from "./data/stepLabels.js";
import { BOLS, COMBO_BOLS } from "./data/bols.js";

/**
 * BeatEditor — three fixed zones, no scrolling, phone-first
 * --------------------------------------------------------
 * 1. OVERVIEW  — a mini BeatStrip of the WHOLE beat with a frame showing
 *    which page the zoom is on. Doubles as the live visualiser in preview.
 * 2. ZOOM      — one page of cells, magnified, both mridanga lanes aligned
 *    in a column. The CURSOR is a column; tapping a cell moves it.
 * 3. PADS      — one pad per enterable stroke (single hands + named two-hand
 *    combos + Rest), generated from data/bols.js. Tapping a pad WRITES the
 *    whole cursor column, SOUNDS the sample(s), and advances the cursor.
 *    A mistake is: tap the right cell, tap the right pad. No cycling ever.
 *
 * Tempo is subdivision-locked (see the old header / beats.js): the invariant
 * beatsPerBar = steps / cellsPerGroup holds, so adding a group lengthens the
 * loop without changing the tempo. Feel / length / tempo / preview / save all
 * carry over from the previous editor unchanged; only the grid UI is new.
 */

const emptyGrid = (n) => Array(n).fill(null);

const FEELS = [
  { cpg: 2, label: "Standard" },  // eighth notes  — "1 + 2 +"
  { cpg: 3, label: "Triplets" },  // dadra swing   — "1 trip let"
  { cpg: 4, label: "Double" },    // sixteenths    — "1 e + a"
];

const DEFAULT_CELLS_PER_GROUP = 2;
const DEFAULT_BEATS = 4;
const NEW_BEAT_CELLS = DEFAULT_BEATS * DEFAULT_CELLS_PER_GROUP; // 8
const deriveGroup = (b) =>
  b == null ? DEFAULT_CELLS_PER_GROUP
            : b.cellsPerGroup ?? Math.max(1, Math.round(b.steps / (b.beatsPerBar ?? 4)));

// Pad palettes keyed by edit mode. Each pad's `write` names exactly the
// lanes it touches: in "both" mode a pad writes the whole column (both
// hands); in a single-lane mode it writes ONLY that lane, leaving the other
// untouched. Order: dayan singles, bayan singles, named combos, rest.
const PAD_SETS = {
  both: [
    { key: "ta",   label: BOLS.dayan.O,      write: { dayan: "O",  bayan: null } },
    { key: "te",   label: BOLS.dayan.X,      write: { dayan: "X",  bayan: null } },
    { key: "ge",   label: BOLS.bayan.O,      write: { dayan: null, bayan: "O"  } },
    { key: "khe",  label: BOLS.bayan.X,      write: { dayan: null, bayan: "X"  } },
    { key: "da",   label: COMBO_BOLS["O+O"], write: { dayan: "O",  bayan: "O"  } },
    { key: "gi",   label: COMBO_BOLS["X+O"], write: { dayan: "X",  bayan: "O"  } },
    { key: "tk",   label: COMBO_BOLS["O+X"], write: { dayan: "O",  bayan: "X"  } },
    { key: "tek",  label: COMBO_BOLS["X+X"], write: { dayan: "X",  bayan: "X"  } },
    { key: "rest", label: "Rest",            write: { dayan: null, bayan: null }, rest: true },
  ],
  dayan: [
    { key: "ta",   label: BOLS.dayan.O, write: { dayan: "O" } },
    { key: "te",   label: BOLS.dayan.X, write: { dayan: "X" } },
    { key: "rd",   label: "Rest",       write: { dayan: null }, rest: true },
  ],
  bayan: [
    { key: "ge",   label: BOLS.bayan.O, write: { bayan: "O" } },
    { key: "khe",  label: BOLS.bayan.X, write: { bayan: "X" } },
    { key: "rb",   label: "Rest",       write: { bayan: null }, rest: true },
  ],
};

const soundName = (end, v) => (v === "O" ? `${end}_open` : v === "X" ? `${end}_closed` : null);

function PencilIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
    </svg>
  );
}

// filled = open, ring = closed, faint dot = silent.
function dotStyle(v, color, size) {
  if (v === "O") return { width: size, height: size, borderRadius: "50%", background: color };
  if (v === "X") return { width: size, height: size, borderRadius: "50%", border: `2.5px solid ${color}` };
  return { width: 4, height: 4, borderRadius: "50%", background: "var(--rule)" };
}

// A top/bottom mark PAIR — used on the pads, which write BOTH hands at once
// (top = dayan, bottom = bayan).
function StrokeMark({ dayan, bayan, size = 12 }) {
  return (
    <span aria-hidden="true" style={{ display: "inline-flex", flexDirection: "column", alignItems: "center", gap: 3 }}>
      <span style={dotStyle(dayan, "var(--lane-dayan)", size)} />
      <span style={dotStyle(bayan, "var(--lane-bayan)", size)} />
    </span>
  );
}

function BeatEditor({ engine, onSave, onClose, onBack, initialBeat, nav }) {
  const [cellsPerGroup, setCellsPerGroup] = useState(() => deriveGroup(initialBeat));
  const [steps, setSteps] = useState(initialBeat?.steps ?? NEW_BEAT_CELLS);
  const [dayan, setDayan] = useState(initialBeat ? [...initialBeat.dayan] : emptyGrid(NEW_BEAT_CELLS));
  const [bayan, setBayan] = useState(initialBeat ? [...initialBeat.bayan] : emptyGrid(NEW_BEAT_CELLS));
  const [bpm, setBpm]     = useState(initialBeat?.bpm ?? 90);
  const [name, setName]   = useState(initialBeat?.name ?? "");
  const [previewing, setPreviewing] = useState(false);
  const [step, setStep]   = useState(-1);
  const [cursor, setCursor] = useState(0); // the column the pads write to
  const [editLane, setEditLane] = useState("both"); // "both" | "dayan" | "bayan"
  const overviewRef = useRef(null);
  const scrubbingRef = useRef(false);
  const nameInputRef = useRef(null);

  const beatsPerBar = steps / cellsPerGroup;
  const labels = generateGuidedLabels(steps, cellsPerGroup);

  // How many cells the zoom shows at once: a whole number of groups, ~8 cells
  // to match the main strip's fit (so a standard 8-cell beat shows in one
  // page). The visible page is the one containing the cursor.
  const zoomCells = Math.min(steps, Math.max(cellsPerGroup, Math.round(8 / cellsPerGroup) * cellsPerGroup));
  const pageCount = Math.ceil(steps / zoomCells);
  const page = Math.floor(cursor / zoomCells);
  const windowStart = page * zoomCells;
  const windowEnd = Math.min(windowStart + zoomCells, steps);

  // Fresh each render (BeatStrip reads this const directly); a ref kept in
  // sync via an effect lets handlers/effects reach the latest without a
  // render-time ref write.
  const previewBeat = { id: "preview", name: name || "Preview", note: "Custom", bpm, steps, beatsPerBar, cellsPerGroup, dayan, bayan };
  const beatRef = useRef(previewBeat);
  useEffect(() => { beatRef.current = previewBeat; });

  // Undo history of {dayan, bayan, cursor} snapshots (pad writes and clears).
  const undoRef = useRef([]);
  const [canUndo, setCanUndo] = useState(false);
  function pushUndo() {
    undoRef.current.push({ dayan: [...dayan], bayan: [...bayan], cursor });
    if (undoRef.current.length > 60) undoRef.current.shift();
    setCanUndo(true);
  }
  function undo() {
    const prev = undoRef.current.pop();
    if (!prev) return;
    setDayan(prev.dayan); setBayan(prev.bayan); setCursor(prev.cursor);
    setCanUndo(undoRef.current.length > 0);
  }

  useEffect(() => {
    const onStep = (s) => setStep(s);
    engine.on("step", onStep);
    return () => { engine.off("step", onStep); engine.stop(); };
  }, [engine]);

  useEffect(() => {
    if (!previewing) return;
    engine.setBeat(beatRef.current);
    engine.setBpm(bpm);
  }, [previewing, dayan, bayan, bpm, steps, engine]);

  const getPhase = () => engine.getPhase();

  function addGroup() {
    const pad = Array(cellsPerGroup).fill(null);
    setDayan(p => [...p, ...pad]);
    setBayan(p => [...p, ...pad]);
    setSteps(s => s + cellsPerGroup);
    undoRef.current = []; setCanUndo(false); // length change is a clean slate for undo
  }
  function removeGroup() {
    if (steps <= cellsPerGroup) return;
    setDayan(p => p.slice(0, -cellsPerGroup));
    setBayan(p => p.slice(0, -cellsPerGroup));
    setSteps(s => s - cellsPerGroup);
    setCursor(c => Math.min(c, steps - cellsPerGroup - 1));
    undoRef.current = []; setCanUndo(false);
  }

  function changeFeel(nextCpg) {
    if (nextCpg === cellsPerGroup) return;
    if (previewing) { engine.stop(); setPreviewing(false); }
    const beats = steps / cellsPerGroup;
    const nextSteps = beats * nextCpg;
    setCellsPerGroup(nextCpg);
    setSteps(nextSteps);
    setDayan(emptyGrid(nextSteps));
    setBayan(emptyGrid(nextSteps));
    setCursor(0);
    undoRef.current = []; setCanUndo(false);
  }

  // Tap a pad: record undo, write only the lanes the pad names (both in
  // "both" mode, one in a single-lane mode), sound them, advance the cursor.
  function tapPad(pad) {
    pushUndo();
    const w = pad.write;
    if ("dayan" in w) setDayan(p => { const c = [...p]; c[cursor] = w.dayan; return c; });
    if ("bayan" in w) setBayan(p => { const c = [...p]; c[cursor] = w.bayan; return c; });
    if (!previewing) {
      if ("dayan" in w) { const n = soundName("dayan", w.dayan); if (n) engine.playStroke(n); }
      if ("bayan" in w) { const n = soundName("bayan", w.bayan); if (n) engine.playStroke(n); }
    }
    setCursor(c => (c + 1) % steps); // wrap at the end of the loop
  }

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
    const id = initialBeat?.id ?? ("custom_" + finalName.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now());
    onSave({ id, name: finalName, note: "Custom", bpm, steps, beatsPerBar, cellsPerGroup, dayan, bayan });
    onClose();
  }

  function clearGrid() {
    pushUndo();
    setDayan(emptyGrid(steps)); setBayan(emptyGrid(steps)); setCursor(0);
  }

  const fillPct = ((bpm - 40) / 160) * 100;

  // Drag anywhere on the overview to scrub the cursor (and thus the zoom
  // page) to the cell under the finger.
  function scrubTo(clientX) {
    const el = overviewRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
    setCursor(Math.min(steps - 1, Math.floor(frac * steps)));
  }

  return (
    <div className="kc-screen" style={st.screen}>
      <header style={st.header}>
        {onBack && (
          <button onClick={onBack} style={st.backBtn} aria-label="Back">‹</button>
        )}
        {/* The name IS the title: click (or the pencil) to rename. */}
        <div style={st.titleWrap}>
          <input ref={nameInputRef} type="text" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="Name your beat" aria-label="Beat name" style={st.titleInput} />
          <button onClick={() => nameInputRef.current?.focus()} aria-label="Rename beat"
            style={st.titlePencil}><PencilIcon /></button>
        </div>
      </header>

      {/* ── OVERVIEW: whole beat + a draggable frame over the current page ── */}
      <div ref={overviewRef} style={st.overviewWrap}
        onPointerDown={(e) => { scrubbingRef.current = true; e.currentTarget.setPointerCapture(e.pointerId); scrubTo(e.clientX); }}
        onPointerMove={(e) => { if (scrubbingRef.current) scrubTo(e.clientX); }}
        onPointerUp={() => { scrubbingRef.current = false; }}
        onPointerCancel={() => { scrubbingRef.current = false; }}>
        <BeatStrip beat={previewBeat} step={step} playing={previewing} getPhase={getPhase} mini />
        {pageCount > 1 && (
          <div aria-hidden="true" style={{ ...st.overviewFrame,
            left: (windowStart / steps) * 100 + "%",
            // span only the cells actually shown, so a short last page's
            // frame doesn't overshoot the edge of the strip
            width: ((windowEnd - windowStart) / steps) * 100 + "%" }} />
        )}
      </div>

      {/* ── ZOOM: one magnified page; the cursor is a column ── */}
      <div style={st.zoom}>
        <div style={st.zoomHead}>
          <button onClick={() => setCursor(Math.max(0, windowStart - zoomCells))}
            disabled={page === 0} aria-label="Previous page"
            style={{ ...st.pageBtn, opacity: page === 0 ? 0.3 : 1 }}>‹</button>
          <span style={st.zoomLabel}>{windowStart + 1}–{windowEnd} of {steps}</span>
          <button onClick={() => setCursor(Math.min(steps - 1, windowStart + zoomCells))}
            disabled={page >= pageCount - 1} aria-label="Next page"
            style={{ ...st.pageBtn, opacity: page >= pageCount - 1 ? 0.3 : 1 }}>›</button>
        </div>
        {/* Each row (numbers, dayan, bayan) is its OWN grid of a full page of
            columns (zoomCells). Separate grids keep the three rows stacked
            even on a short last page — a single shared grid would auto-flow
            a 2-cell page's items onto one row. Cells keep constant width;
            unused tracks stay empty. */}
        <div style={st.zoomRows}>
          <div style={{ ...st.zoomLine, gridTemplateColumns: `repeat(${zoomCells}, 1fr)` }}>
            {labels.slice(windowStart, windowEnd).map((l, j) => (
              <div key={"n" + j} style={{ ...st.zoomNum, opacity: l !== "·" ? 0.9 : 0.4, fontWeight: l !== "·" ? 700 : 400 }}>{l}</div>
            ))}
          </div>
          {[["dayan", "Dayan", dayan], ["bayan", "Bayan", bayan]].map(([end, label, arr]) => {
            const isolatedOut = editLane !== "both" && editLane !== end;
            const active = editLane === end;
            return (
              <div key={end} style={{ opacity: isolatedOut ? 0.4 : 1 }}>
                {/* Row label doubles as the isolate toggle: tap to edit only
                    this lane (pads switch to its strokes); tap again = both. */}
                <button onClick={() => setEditLane(m => (m === end ? "both" : end))}
                  aria-pressed={active}
                  style={{ ...st.laneLabel, color: `var(--lane-${end})`,
                    fontWeight: active ? 800 : 700 }}>
                  {label}{active ? " · editing only" : ""}
                </button>
                <div style={{ ...st.zoomLine, gridTemplateColumns: `repeat(${zoomCells}, 1fr)` }}>
                  {arr.slice(windowStart, windowEnd).map((v, j) => {
                    const i = windowStart + j;
                    const isCursor = i === cursor;
                    const lit = previewing && i === step;
                    return (
                      <button key={end + i} onClick={() => setCursor(i)}
                        aria-label={`${end} cell ${i + 1}${v ? `, ${BOLS[end]?.[v] ?? v}` : ", empty"}`}
                        style={{ ...st.zoomCell,
                          borderColor: isCursor ? "var(--clay)" : "var(--rule)",
                          borderWidth: isCursor ? 2 : 1,
                          background: lit ? "var(--head-sunken)" : "var(--head)" }}>
                        {/* One dot — this cell belongs to a single lane. */}
                        <span aria-hidden="true"
                          style={dotStyle(v, end === "dayan" ? "var(--lane-dayan)" : "var(--lane-bayan)", 18)} />
                      </button>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* ── PADS: tap to write the cursor column, sound it, advance. The set
          depends on the edit mode (both hands, or one isolated lane). ── */}
      <div style={st.pads}>
        {PAD_SETS[editLane].map(pad => {
          const w = pad.write;
          const both = "dayan" in w && "bayan" in w;
          return (
            <button key={pad.key} onClick={() => tapPad(pad)}
              aria-label={pad.rest ? "Rest" : pad.label}
              style={{ ...st.pad, ...(pad.rest ? st.padRest : null) }}>
              <span style={st.padLabel}>{pad.label}</span>
              {!pad.rest && (both
                ? <StrokeMark dayan={w.dayan} bayan={w.bayan} size={9} />
                : <span aria-hidden="true" style={dotStyle(
                    "dayan" in w ? w.dayan : w.bayan,
                    "dayan" in w ? "var(--lane-dayan)" : "var(--lane-bayan)", 11)} />
              )}
            </button>
          );
        })}
      </div>

      {/* ── Controls: undo, feel, length, tempo, preview ── */}
      <div style={st.controls}>
        <div style={st.controlRow}>
          <button onClick={undo} disabled={!canUndo}
            style={{ ...st.utilBtn, opacity: canUndo ? 1 : 0.4 }}>↶ Undo</button>
          <button onClick={clearGrid} style={st.utilBtn}>Clear</button>
          <button onClick={togglePreview}
            style={{ ...st.utilBtn, ...(previewing ? st.previewOn : null) }}>
            {previewing ? "■ Stop" : "▶ Preview"}
          </button>
        </div>

        <div style={st.feelRow}>
          {FEELS.map(f => {
            const sel = f.cpg === cellsPerGroup;
            return (
              <button key={f.cpg} onClick={() => changeFeel(f.cpg)} aria-pressed={sel}
                style={{ ...st.feelBtn,
                  background: sel ? "var(--clay)" : "transparent",
                  borderColor: sel ? "var(--clay)" : "var(--rule)",
                  color: sel ? "var(--on-clay)" : "var(--syahi-soft)" }}>
                {f.label}
              </button>
            );
          })}
        </div>

        <div style={st.lengthRow}>
          <button onClick={removeGroup} disabled={steps <= cellsPerGroup}
            style={{ ...st.groupBtn, opacity: steps <= cellsPerGroup ? 0.4 : 1 }}
            aria-label="Remove a group">−</button>
          <span style={st.lengthLabel}>{steps} cells · {steps / cellsPerGroup} beats</span>
          <button onClick={addGroup} style={st.groupBtn} aria-label="Add a group">+</button>
        </div>

        <div style={st.tempoRow}>
          <span style={st.bpmNum}>{bpm}</span><span style={st.bpmUnit}>BPM</span>
          <input className="kc-range" type="range" min={40} max={200} value={bpm}
            onChange={(e) => setBpm(Number(e.target.value))}
            style={{ "--fill": fillPct + "%", flex: 1 }} aria-label="Tempo" />
        </div>
      </div>

      {/* Save — bottom-right. */}
      <div style={st.footer}>
        <button onClick={handleSave} style={st.saveBtn}>Save beat</button>
      </div>

      {nav}
    </div>
  );
}

const st = {
  screen: { width: "100%", maxWidth: 430, minHeight: 0, margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-5) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-4) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))", gap: "var(--space-4)" },
  header: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)", paddingBottom: "var(--space-3)", borderBottom: "var(--rule-hairline)" },
  backBtn: { flexShrink: 0, width: 40, height: 44, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 28, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", paddingBottom: 4 },
  titleWrap: { flex: 1, minWidth: 0, display: "flex", alignItems: "center", gap: "var(--space-2)" },
  titleInput: { flex: 1, minWidth: 0, border: "none", background: "transparent", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)", padding: "2px 0", outline: "none" },
  titlePencil: { flexShrink: 0, width: 40, height: 40, border: "none", background: "transparent", color: "var(--syahi-soft)", cursor: "pointer", display: "grid", placeItems: "center" },
  footer: { flexShrink: 0, display: "flex", justifyContent: "flex-end" },
  saveBtn: { minHeight: 48, padding: "0 28px", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.02em", cursor: "pointer" },

  overviewWrap: { position: "relative", flexShrink: 0, padding: "6px 2px", cursor: "grab", touchAction: "none" },
  overviewFrame: { position: "absolute", top: 0, bottom: 0, borderRadius: 8, border: "2px solid var(--clay)", background: "oklch(0.54 0.12 40 / 0.08)", pointerEvents: "none" },

  zoom: { flexShrink: 0, display: "flex", flexDirection: "column", gap: "var(--space-2)" },
  zoomHead: { display: "flex", alignItems: "center", justifyContent: "space-between" },
  pageBtn: { width: 44, height: 40, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 24, cursor: "pointer", display: "grid", placeItems: "center", borderRadius: 10 },
  zoomLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, color: "var(--syahi-soft)", letterSpacing: "0.03em" },
  zoomRows: { display: "flex", flexDirection: "column", gap: 6 },
  zoomLine: { display: "grid", gap: 6, alignItems: "center" },
  laneLabel: { display: "block", border: "none", background: "transparent", cursor: "pointer", padding: "2px 0 3px", fontFamily: "var(--font-body)", fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase", textAlign: "left" },
  zoomNum: { textAlign: "center", fontFamily: "var(--font-body)", fontSize: 12, color: "var(--syahi-soft)", lineHeight: 1 },
  zoomCell: { height: 56, borderRadius: 10, border: "1px solid var(--rule)", cursor: "pointer", display: "grid", placeItems: "center", padding: 0, transition: "border-color 120ms ease, background 120ms ease" },

  pads: { flexShrink: 0, display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "var(--space-2)" },
  pad: { minHeight: 58, borderRadius: 14, border: "var(--rule-hairline)", background: "var(--head-worn)", cursor: "pointer", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 4 },
  padRest: { background: "transparent" },
  padLabel: { fontFamily: "var(--font-display)", fontSize: 18, fontWeight: 600, color: "var(--syahi)", lineHeight: 1 },

  controls: { flexShrink: 0, display: "flex", flexDirection: "column", gap: "var(--space-3)" },
  controlRow: { display: "flex", gap: "var(--space-2)" },
  utilBtn: { flex: 1, minHeight: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer" },
  previewOn: { background: "var(--clay)", color: "var(--on-clay)", borderColor: "var(--clay)" },
  feelRow: { display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "var(--space-2)" },
  feelBtn: { minHeight: 42, borderRadius: 12, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer" },
  lengthRow: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, padding: "6px 12px", borderRadius: 14, border: "var(--rule-hairline)" },
  groupBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--clay)", fontSize: 24, fontWeight: 700, cursor: "pointer", display: "grid", placeItems: "center" },
  lengthLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, color: "var(--syahi-soft)" },
  tempoRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  bpmNum: { fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "1.5rem", fontWeight: 600, color: "var(--syahi)", lineHeight: 1 },
  bpmUnit: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", color: "var(--syahi-soft)" },
};

export default BeatEditor;
