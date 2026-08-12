import { useState, useRef, useEffect } from "react";
import BeatStrip from "./BeatStrip.jsx";
import { labelsFromGroups } from "./data/stepLabels.js";
import { BOLS, COMBO_BOLS } from "./data/bols.js";

/**
 * BeatEditor — three zones, phone-first
 * -------------------------------------
 * 1. OVERVIEW  — a mini BeatStrip of the WHOLE beat with a draggable frame
 *    showing which page the zoom is on; doubles as the live visualiser.
 * 2. ZOOM      — one page of cells, magnified, both mridanga lanes aligned
 *    in a column. The CURSOR is a column; tapping a cell moves it. A lane
 *    label above each row doubles as an "edit only this lane" toggle.
 * 3. PADS      — one pad per enterable stroke (from data/bols.js). Tapping a
 *    pad writes the cursor column (or one lane when isolated), SOUNDS the
 *    sample(s), and advances the cursor.
 *
 * The three zones sit in one scrolling region so a viewport too short for all
 * of them scrolls instead of overflowing; the title, Save and the nav bar are
 * pinned, which is what keeps the nav pill aligned with the other screens.
 *
 * METER MODEL — a beat is a list of GROUPS (one entry per numbered beat, its
 * value = cells it spans). This supports uneven meters like 7/8 = [2,2,3]
 * that a single cellsPerGroup can't. Timing stays equal-duration cells:
 *   beatsPerBar (quarter notes) = steps / cellsPerQuarter
 * which the Sequencer turns into a per-cell tick interval. cellsPerQuarter
 * (cpq) is the subdivision density; beatsPerBar can be fractional (7/8 = 3.5)
 * and the engine handles it.
 */

const emptyGrid = (n) => Array(n).fill(null);
const sum = (a) => a.reduce((x, y) => x + y, 0);

// Meter/feel presets. `bar` = one bar's groups, `unit` = what +/- Group adds
// or removes, `cpq` = cells per quarter note (sets timing via beatsPerBar).
const METERS = [
  { id: "std",  label: "Standard",    sig: "4/4 · eighths",    bar: [2, 2, 2, 2], unit: [2],       cpq: 2 },
  { id: "trip", label: "Triplets",    sig: "swing · 12/8",     bar: [3, 3, 3, 3], unit: [3],       cpq: 3 },
  { id: "dbl",  label: "Double-time", sig: "16ths",            bar: [4, 4, 4, 4], unit: [4],       cpq: 4 },
  { id: "68",   label: "Six-eight",   sig: "6/8 · 3+3",        bar: [3, 3],       unit: [3, 3],    cpq: 2 },
  { id: "78",   label: "Seven-eight", sig: "7/8 · 2+2+3",      bar: [2, 2, 3],    unit: [2, 2, 3], cpq: 2 },
];

// Does `arr` consist of whole repetitions of `pattern`?
function repeats(pattern, arr) {
  if (arr.length === 0 || arr.length % pattern.length !== 0) return false;
  return arr.every((v, i) => v === pattern[i % pattern.length]);
}

// Recover the meter preset (or a synthesized "custom" one) from a beat's
// groups + cpq, so editing an existing beat lands on the right picker entry.
function meterFor(groups, cpq) {
  const uniform = groups.every((g) => g === groups[0]);
  if (uniform && groups[0] === cpq) {
    const m = METERS.find((x) => x.cpq === cpq && x.unit.length === 1 && x.unit[0] === cpq);
    if (m) return m;
  }
  if (cpq === 2 && repeats([3, 3], groups)) return METERS.find((m) => m.id === "68");
  if (cpq === 2 && repeats([2, 2, 3], groups)) return METERS.find((m) => m.id === "78");
  return { id: "custom", label: "Custom", sig: "", unit: uniform ? [groups[0]] : [...groups], cpq };
}

function seedGroups(b) {
  if (!b) return [...METERS[0].bar];
  if (Array.isArray(b.groups)) return [...b.groups];
  const cpg = b.cellsPerGroup ?? Math.max(1, Math.round(b.steps / (b.beatsPerBar ?? 4)));
  const beats = Math.max(1, Math.round(b.steps / cpg));
  return Array(beats).fill(cpg);
}
const seedCpq = (b) => (b ? Math.max(1, Math.round(b.steps / (b.beatsPerBar ?? 4))) : 2);

// filled = open, ring = closed, faint dot = silent.
function dotStyle(v, color, size) {
  if (v === "O") return { width: size, height: size, borderRadius: "50%", background: color };
  if (v === "X") return { width: size, height: size, borderRadius: "50%", border: `2.5px solid ${color}` };
  return { width: 4, height: 4, borderRadius: "50%", background: "var(--rule)" };
}

// Top/bottom mark PAIR — used on the both-hands pads (top = dayan, bottom = bayan).
function StrokeMark({ dayan, bayan, size = 12 }) {
  return (
    <span aria-hidden="true" style={{ display: "inline-flex", flexDirection: "column", alignItems: "center", gap: 3 }}>
      <span style={dotStyle(dayan, "var(--lane-dayan)", size)} />
      <span style={dotStyle(bayan, "var(--lane-bayan)", size)} />
    </span>
  );
}

function PencilIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
    </svg>
  );
}

const soundName = (end, v) => (v === "O" ? `${end}_open` : v === "X" ? `${end}_closed` : null);

// Pad palettes keyed by edit mode (both hands / one isolated lane). Each
// pad's `write` names exactly the lanes it touches.
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
    { key: "ta", label: BOLS.dayan.O, write: { dayan: "O" } },
    { key: "te", label: BOLS.dayan.X, write: { dayan: "X" } },
    { key: "rd", label: "Rest",       write: { dayan: null }, rest: true },
  ],
  bayan: [
    { key: "ge",  label: BOLS.bayan.O, write: { bayan: "O" } },
    { key: "khe", label: BOLS.bayan.X, write: { bayan: "X" } },
    { key: "rb",  label: "Rest",       write: { bayan: null }, rest: true },
  ],
};

function BeatEditor({ engine, onSave, onClose, onBack, initialBeat, nav }) {
  const [groups, setGroups] = useState(() => seedGroups(initialBeat));
  const [meter, setMeter]   = useState(() => meterFor(seedGroups(initialBeat), seedCpq(initialBeat)));
  const [dayan, setDayan] = useState(initialBeat ? [...initialBeat.dayan] : emptyGrid(sum(seedGroups(initialBeat))));
  const [bayan, setBayan] = useState(initialBeat ? [...initialBeat.bayan] : emptyGrid(sum(seedGroups(initialBeat))));
  const [bpm, setBpm]     = useState(initialBeat?.bpm ?? 90);
  const [name, setName]   = useState(initialBeat?.name ?? "");
  const [previewing, setPreviewing] = useState(false);
  const [step, setStep]   = useState(-1);
  const [cursor, setCursor] = useState(0);
  const [editLane, setEditLane] = useState("both");
  const [feelOpen, setFeelOpen] = useState(false);
  const overviewRef = useRef(null);
  const scrubbingRef = useRef(false);
  const nameInputRef = useRef(null);

  const cpq = meter.cpq;
  const steps = sum(groups);
  const beatsPerBar = steps / cpq;
  const labels = labelsFromGroups(groups);

  // The zoom shows up to 8 cells at once (matching the main strip). Pages
  // tile the beat in fixed 8-cell windows; the visible one holds the cursor.
  const zoomCells = Math.min(steps, 8);
  const pageCount = Math.ceil(steps / zoomCells);
  const page = Math.floor(cursor / zoomCells);
  const windowStart = page * zoomCells;
  const windowEnd = Math.min(windowStart + zoomCells, steps);

  const previewBeat = { id: "preview", name: name || "Preview", note: "Custom", bpm, steps, beatsPerBar, cellsPerGroup: cpq, groups, dayan, bayan };
  const beatRef = useRef(previewBeat);
  useEffect(() => { beatRef.current = previewBeat; });

  // Undo history of {dayan, bayan, cursor} snapshots.
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
  function resetUndo() { undoRef.current = []; setCanUndo(false); }

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
    const u = meter.unit, pad = Array(sum(u)).fill(null);
    setGroups((g) => [...g, ...u]);
    setDayan((p) => [...p, ...pad]);
    setBayan((p) => [...p, ...pad]);
    resetUndo();
  }
  function removeGroup() {
    if (groups.length <= meter.unit.length) return;
    const drop = sum(meter.unit);
    setGroups((g) => g.slice(0, -meter.unit.length));
    setDayan((p) => p.slice(0, -drop));
    setBayan((p) => p.slice(0, -drop));
    setCursor((c) => Math.min(c, steps - drop - 1));
    resetUndo();
  }

  function selectMeter(m) {
    if (previewing) { engine.stop(); setPreviewing(false); }
    setMeter(m);
    setGroups([...m.bar]);
    setDayan(emptyGrid(sum(m.bar)));
    setBayan(emptyGrid(sum(m.bar)));
    setCursor(0);
    resetUndo();
    setFeelOpen(false);
  }

  function tapPad(pad) {
    pushUndo();
    const w = pad.write;
    if ("dayan" in w) setDayan((p) => { const c = [...p]; c[cursor] = w.dayan; return c; });
    if ("bayan" in w) setBayan((p) => { const c = [...p]; c[cursor] = w.bayan; return c; });
    if (!previewing) {
      if ("dayan" in w) { const n = soundName("dayan", w.dayan); if (n) engine.playStroke(n); }
      if ("bayan" in w) { const n = soundName("bayan", w.bayan); if (n) engine.playStroke(n); }
    }
    setCursor((c) => (c + 1) % steps);
  }

  async function togglePreview() {
    await engine.unlock();
    if (previewing) { engine.stop(); setPreviewing(false); }
    else { engine.setBeat(beatRef.current); engine.setBpm(bpm); engine.start(); setPreviewing(true); }
  }

  function handleSave() {
    if (previewing) { engine.stop(); setPreviewing(false); }
    const hasAnyHit = dayan.some((c) => c !== null) || bayan.some((c) => c !== null);
    if (!hasAnyHit) { alert("Add at least one stroke before saving."); return; }
    const finalName = name.trim() || "Custom Beat";
    // No id means "new" — the library mints one on save, and it is now the
    // only place in the app ids come from. Editing an existing custom beat
    // keeps its id and so overwrites in place.
    const id = initialBeat?.id ?? null;
    const uniform = groups.every((g) => g === groups[0]);
    onSave({ id, name: finalName, note: "Custom", bpm, steps, beatsPerBar,
      cellsPerGroup: uniform ? groups[0] : cpq, groups, dayan, bayan });
    onClose();
  }

  function clearGrid() {
    pushUndo();
    setDayan(emptyGrid(steps)); setBayan(emptyGrid(steps)); setCursor(0);
  }

  function scrubTo(clientX) {
    const el = overviewRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
    setCursor(Math.min(steps - 1, Math.floor(frac * steps)));
  }

  const fillPct = ((bpm - 40) / 160) * 100;

  return (
    <div className="kc-screen" style={st.screen}>
      <header style={st.header}>
        {onBack && <button onClick={onBack} style={st.backBtn} aria-label="Back">‹</button>}
        <div style={st.titleWrap}>
          <input ref={nameInputRef} type="text" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="Name your beat" aria-label="Beat name" style={st.titleInput} />
          <button onClick={() => nameInputRef.current?.focus()} aria-label="Rename beat"
            style={st.titlePencil}><PencilIcon /></button>
        </div>
      </header>

      {/* Everything between the title and Save scrolls as one region. The
          editor's sections are all flexShrink:0 (a squashed pad grid is
          unusable), so on a short viewport they used to overflow the fixed
          frame and push the nav bar under the clip edge. Pinning the header,
          Save and nav keeps the nav pill at the same y as every other page. */}
      <div style={st.body}>

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
                  <button onClick={() => setEditLane((m) => (m === end ? "both" : end))}
                    aria-pressed={active}
                    style={{ ...st.laneLabel, color: `var(--lane-${end})`, fontWeight: active ? 800 : 700 }}>
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

        {/* ── PADS ── */}
        <div style={st.pads}>
          {PAD_SETS[editLane].map((pad) => {
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

        {/* ── Controls ── */}
        <div style={st.controls}>
          <div style={st.controlRow}>
            <button onClick={undo} disabled={!canUndo} style={{ ...st.utilBtn, opacity: canUndo ? 1 : 0.4 }}>↶ Undo</button>
            <button onClick={clearGrid} style={st.utilBtn}>Clear</button>
            <button onClick={togglePreview} style={{ ...st.utilBtn, ...(previewing ? st.previewOn : null) }}>
              {previewing ? "■ Stop" : "▶ Preview"}
            </button>
          </div>

          <div style={st.metaRow}>
            {/* Feel/meter — one picker button. */}
            <button onClick={() => setFeelOpen(true)} style={st.feelBtn}
              aria-haspopup="dialog" aria-expanded={feelOpen}>
              <span style={st.feelName}>{meter.label}</span>
              <span style={st.feelCaret}>▾</span>
            </button>
            {/* Length: add/remove one unit of the current meter. */}
            <div style={st.lengthRow}>
              <button onClick={removeGroup} disabled={groups.length <= meter.unit.length}
                style={{ ...st.groupBtn, opacity: groups.length <= meter.unit.length ? 0.4 : 1 }}
                aria-label="Remove a group">−</button>
              <span style={st.lengthLabel}>{steps} cells</span>
              <button onClick={addGroup} style={st.groupBtn} aria-label="Add a group">+</button>
            </div>
          </div>

          <div style={st.tempoRow}>
            <span style={st.bpmNum}>{bpm}</span><span style={st.bpmUnit}>BPM</span>
            <input className="kc-range" type="range" min={40} max={200} value={bpm}
              onChange={(e) => setBpm(Number(e.target.value))}
              style={{ "--fill": fillPct + "%", flex: 1 }} aria-label="Tempo" />
          </div>
        </div>

      </div>

      <div style={st.footer}>
        <button onClick={handleSave} style={st.saveBtn}>Save beat</button>
      </div>

      {/* Feel/meter picker sheet */}
      {feelOpen && (
        <div style={st.sheetBackdrop} onClick={() => setFeelOpen(false)}>
          <div style={st.sheet} role="dialog" aria-modal="true" aria-label="Feel"
            onClick={(e) => e.stopPropagation()}>
            <div style={st.sheetHead}>
              <h2 style={st.sheetTitle}>Feel &amp; meter</h2>
              <button onClick={() => setFeelOpen(false)} aria-label="Close" style={st.sheetClose}>×</button>
            </div>
            <p style={st.sheetHint}>Changing the feel clears the grid.</p>
            <div style={st.feelList}>
              {METERS.map((m) => {
                const sel = m.id === meter.id;
                return (
                  <button key={m.id} onClick={() => selectMeter(m)}
                    style={{ ...st.feelOption, borderColor: sel ? "var(--clay)" : "var(--rule)" }}>
                    <span style={st.feelOptName}>{m.label}</span>
                    <span style={st.feelOptSig}>{m.sig}</span>
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {nav}
    </div>
  );
}

const st = {
  screen: { width: "100%", maxWidth: 430, minHeight: 0, margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-5) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-4) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))", gap: "var(--space-4)" },
  header: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)", paddingBottom: "var(--space-3)", borderBottom: "var(--rule-hairline)" },
  // The one scrolling region — see the note at its JSX. `padding: 2px` keeps
  // the overview's and the pads' outer edges off the clipping boundary.
  body: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", gap: "var(--space-4)", padding: "2px" },
  backBtn: { flexShrink: 0, width: 40, height: 44, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 28, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", paddingBottom: 4 },
  titleWrap: { flex: 1, minWidth: 0, display: "flex", alignItems: "center", gap: "var(--space-2)" },
  titleInput: { flex: 1, minWidth: 0, border: "none", background: "transparent", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)", padding: "2px 0", outline: "none" },
  titlePencil: { flexShrink: 0, width: 40, height: 40, border: "none", background: "transparent", color: "var(--syahi-soft)", cursor: "pointer", display: "grid", placeItems: "center" },

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
  metaRow: { display: "flex", gap: "var(--space-2)" },
  feelBtn: { flex: 1, minHeight: 46, padding: "0 14px", borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8 },
  feelName: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, color: "var(--ink-primary)" },
  feelCaret: { fontSize: "0.7rem", color: "var(--syahi-soft)" },
  lengthRow: { flexShrink: 0, display: "flex", alignItems: "center", gap: 8 },
  groupBtn: { flexShrink: 0, width: 44, height: 46, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--clay)", fontSize: 24, fontWeight: 700, cursor: "pointer", display: "grid", placeItems: "center" },
  lengthLabel: { minWidth: 54, textAlign: "center", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, color: "var(--syahi-soft)" },
  tempoRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  bpmNum: { fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "1.5rem", fontWeight: 600, color: "var(--syahi)", lineHeight: 1 },
  bpmUnit: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", color: "var(--syahi-soft)" },

  footer: { flexShrink: 0, display: "flex", justifyContent: "flex-end" },
  saveBtn: { minHeight: 48, padding: "0 28px", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.02em", cursor: "pointer" },

  sheetBackdrop: { position: "fixed", inset: 0, background: "oklch(0.24 0.02 60 / 0.35)", display: "flex", alignItems: "flex-end", justifyContent: "center", zIndex: 10 },
  sheet: { width: "100%", maxWidth: 430, maxHeight: "85dvh", overflowY: "auto", background: "var(--head)", borderRadius: "20px 20px 0 0", padding: "var(--space-5) var(--space-5) calc(var(--space-6) + env(safe-area-inset-bottom))", display: "flex", flexDirection: "column" },
  sheetHead: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" },
  sheetTitle: { margin: 0, fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)" },
  sheetClose: { flexShrink: 0, width: 44, height: 44, margin: "-8px -8px 0 0", border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 26, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center" },
  sheetHint: { margin: "0 0 var(--space-3)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)" },
  feelList: { display: "flex", flexDirection: "column", gap: "var(--space-2)" },
  feelOption: { display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: "var(--space-3)", minHeight: 54, padding: "0 16px", borderRadius: 14, border: "var(--rule-hairline)", background: "var(--head-worn)", cursor: "pointer", textAlign: "left" },
  feelOptName: { fontFamily: "var(--font-display)", fontSize: 18, fontWeight: 600, color: "var(--syahi)" },
  feelOptSig: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, color: "var(--syahi-soft)" },
};

export default BeatEditor;
