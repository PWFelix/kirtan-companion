import { useState, useRef, useEffect } from "react";
import SwingControl from "./ui/SwingControl.jsx";
import { SWING_STRAIGHT } from "./data/swing.js";
import { runBetween, copyRun, pasteRun, clipLength } from "./data/clipboard.js";
import BeatStrip from "./BeatStrip.jsx";
import { labelsFromGroups } from "./data/stepLabels.js";
import { BOLS } from "./data/bols.js";
import { t, tOr } from "./i18n/index.js";
import { tagLine } from "./data/tags.js";
import { TagPicker } from "./ui/TagPicker.jsx";
import { MAX_NAME_LEN, clampName } from "./data/names.js";
import { closedMarkBackground } from "./data/strokes.js";
import { tempoRange, brahmalokaFor } from "./data/meter.js";
import BpmRange from "./ui/BpmRange.jsx";
import BrahmalokaBurst from "./ui/BrahmalokaBurst.jsx";
import { leverFor, reshape, resample, pagesFor } from "./data/resolution.js";
import { MAX_STEPS, MAX_GROUPS } from "./data/shareCodec.js";
import { METERS, meterFor, emptyGrid, sum, draftFrom } from "./data/editorDraft.js";

/**
 * BeatEditor — three zones, phone-first
 * -------------------------------------
 * 1. OVERVIEW  — a mini BeatStrip of the WHOLE beat with a draggable frame
 *    showing which page the zoom is on; doubles as the live visualiser.
 * 2. ZOOM      — one page of cells, magnified, the lanes aligned in a column
 *    (the two mridanga heads and the karatalas). Editing is CELL-DRIVEN: the
 *    cursor is one cell (a lane and a step). Tapping any cell selects it, and
 *    the pads become that row's strokes.
 * 3. PADS      — the selected row's palette (data/bols.js): its open and
 *    closed strokes plus Rest. Tapping a pad writes the selected cell, SOUNDS
 *    the stroke, and advances to the next cell along the same row, so a row
 *    can be typed in one go. A two-hand bol (Da, Gi...) is simply both drum
 *    rows filled in the same column.
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

// filled = open, X = closed, faint dot = silent.
function dotStyle(v, color, size) {
  if (v === "O") return { width: size, height: size, borderRadius: "50%", background: color };
  if (v === "X") return { width: size, height: size, background: closedMarkBackground(color, 1.4) };
  return { width: 4, height: 4, borderRadius: "50%", background: "var(--rule)" };
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

// The editable rows, top to bottom.
const ROWS = [["dayan", "Dayan"], ["bayan", "Bayan"], ["kartal", "Kartal"]];

/** The palette for a row: its open stroke, its closed stroke, and Rest. */
const padsFor = (lane) => [
  { key: lane + "O", label: BOLS[lane].O, value: "O" },
  { key: lane + "X", label: BOLS[lane].X, value: "X" },
  { key: lane + "-", label: "Rest", value: null, rest: true },
];

function BeatEditor({ engine, onSave, onClose, onBack, initialBeat, initialDraft, onDraftChange, onDiscard, dirty, nav }) {
  // The draft lives in App (one session, kept across screens and reloads):
  // start from it, and report every change back below.
  const [start] = useState(() => initialDraft ?? draftFrom(initialBeat));
  const [groups, setGroups] = useState(start.groups);
  const [meter, setMeter]   = useState(start.meter);
  const [dayan, setDayan] = useState(start.dayan);
  const [bayan, setBayan] = useState(start.bayan);
  const [kartal, setKartal] = useState(start.kartal);
  const [bpm, setBpm]     = useState(start.bpm);
  const [swing, setSwing] = useState(start.swing ?? SWING_STRAIGHT);
  // Brahmaloka time, editor-local (the editor's tempo is the draft's, not the
  // transport's). A beat saved above 200 opens already in the high range.
  const [brahmaloka, setBrahmaloka] = useState(() => start.bpm > 200);
  const [burstKey, setBurstKey] = useState(0);
  const [name, setName]   = useState(start.name);
  const [previewing, setPreviewing] = useState(false);
  const [step, setStep]   = useState(-1);
  const [cursor, setCursor] = useState(start.cursor ?? 0);
  // The row the cursor is on — the cursor is a CELL (cursorLane, cursor). It
  // picks the pads' palette. Set by tapping any cell.
  const [cursorLane, setCursorLane] = useState(start.cursorLane ?? "dayan");
  const [feelOpen, setFeelOpen] = useState(false);
  // The author's tags, published with the beat (data/tags.js).
  const [tags, setTags] = useState(start.tags ?? []);
  const [discardOpen, setDiscardOpen] = useState(false);
  // Copy and paste (data/clipboard.js). While choosing a run, selectAnchor is
  // the cell it started at and the cursor is its other end, so tapping cells —
  // on any page — moves that end. The clip outlives the run: paste it again
  // and again.
  const [selectAnchor, setSelectAnchor] = useState(null);
  const [clip, setClip] = useState(null);
  useEffect(() => {
    onDraftChange?.({ groups, meter, dayan, bayan, kartal, bpm, swing, name, cursor, cursorLane, tags });
  }, [groups, meter, dayan, bayan, kartal, bpm, swing, name, cursor, cursorLane, tags, onDraftChange]);
  const [tagsOpen, setTagsOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const overviewRef = useRef(null);
  const scrubbingRef = useRef(false);
  const nameInputRef = useRef(null);

  const cpq = meter.cpq;
  const steps = sum(groups);
  const beatsPerBar = steps / cpq;
  const labels = labelsFromGroups(groups);

  // The zoom shows up to 8 cells at once (matching the main strip). Pages hold
  // whole beats, so one never starts mid-beat; the visible one holds the cursor.
  const pages = pagesFor(groups);
  const pageCount = pages.length;
  const page = Math.max(0, pages.findIndex(([a, b]) => cursor >= a && cursor < b));
  const [windowStart, windowEnd] = pages[page] ?? [0, steps];
  const zoomCells = windowEnd - windowStart;

  // The two levers: beats (±, capped so the beat stays within MAX_STEPS cells, 384) and resolution.
  const canAddBeat = steps + sum(meter.unit) <= MAX_STEPS && groups.length + meter.unit.length <= MAX_GROUPS;
  const canRemoveBeat = groups.length > meter.unit.length;
  const lever = leverFor(groups, cpq);
  // What the last resolution change moved or dropped, shown until the next edit.
  const [resampleReport, setResampleReport] = useState(null);

  const previewBeat = { id: "preview", name: name || t("editor.preview"), note: "Custom", bpm, steps, beatsPerBar, cellsPerGroup: cpq, groups, dayan, bayan, kartal, swing };
  const beatRef = useRef(previewBeat);
  useEffect(() => { beatRef.current = previewBeat; });

  // Undo history of {dayan, bayan, kartal, cursor} snapshots.
  const undoRef = useRef([]);
  const [canUndo, setCanUndo] = useState(false);
  function pushUndo() {
    undoRef.current.push({ dayan: [...dayan], bayan: [...bayan], kartal: [...kartal], cursor, cursorLane });
    setResampleReport(null);
    if (undoRef.current.length > 60) undoRef.current.shift();
    setCanUndo(true);
  }
  function undo() {
    const prev = undoRef.current.pop();
    if (!prev) return;
    setDayan(prev.dayan); setBayan(prev.bayan); setKartal(prev.kartal); setCursor(prev.cursor);
    if (prev.cursorLane) setCursorLane(prev.cursorLane);
    // A resolution change's entry also carries the grid shape it replaced.
    if (prev.groups) { setGroups(prev.groups); setMeter(prev.meter); }
    setResampleReport(null);
    setCanUndo(undoRef.current.length > 0);
  }
  function resetUndo() { undoRef.current = []; setCanUndo(false); setResampleReport(null); setSelectAnchor(null); }

  /**
   * Change the resolution, KEEPING THE RHYTHM: every hit stays at its moment in
   * the bar on the nearest new cell (see data/resolution.js). Undoable — the
   * undo entry carries the old grid shape along with the old hits.
   */
  function changeResolution(option) {
    if (option === lever.current) return;
    const next = reshape(groups, cpq, meter.unit, option);
    if (!next) return;
    if (previewing) { engine.stop(); setPreviewing(false); }
    const n = sum(next.groups);
    const report = { moved: 0, dropped: 0 };
    undoRef.current.push({ dayan: [...dayan], bayan: [...bayan], kartal: [...kartal], cursor, groups, meter });
    setSelectAnchor(null);
    if (undoRef.current.length > 60) undoRef.current.shift();
    setCanUndo(true);
    setDayan(resample(dayan, cpq, next.cpq, n, report));
    setBayan(resample(bayan, cpq, next.cpq, n, report));
    setKartal(resample(kartal, cpq, next.cpq, n, report));
    const found = meterFor(next.groups, next.cpq);
    setMeter(found.id === "custom" ? { ...found, unit: next.unit } : found);
    setGroups(next.groups);
    setCursor(Math.min(n - 1, Math.floor((cursor * next.cpq) / cpq)));
    setResampleReport(report.moved || report.dropped ? report : null);
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
  }, [previewing, dayan, bayan, kartal, bpm, swing, steps, engine]);

  const getPhase = () => engine.getPhase();

  function addGroup() {
    if (!canAddBeat) return;
    const u = meter.unit, pad = Array(sum(u)).fill(null);
    setGroups((g) => [...g, ...u]);
    setDayan((p) => [...p, ...pad]);
    setBayan((p) => [...p, ...pad]);
    setKartal((p) => [...p, ...pad]);
    resetUndo();
  }
  function removeGroup() {
    if (groups.length <= meter.unit.length) return;
    const drop = sum(meter.unit);
    setGroups((g) => g.slice(0, -meter.unit.length));
    setDayan((p) => p.slice(0, -drop));
    setBayan((p) => p.slice(0, -drop));
    setKartal((p) => p.slice(0, -drop));
    setCursor((c) => Math.min(c, steps - drop - 1));
    resetUndo();
  }

  function selectMeter(m) {
    if (previewing) { engine.stop(); setPreviewing(false); }
    setMeter(m);
    setGroups([...m.bar]);
    setDayan(emptyGrid(sum(m.bar)));
    setBayan(emptyGrid(sum(m.bar)));
    setKartal(emptyGrid(sum(m.bar)));
    setCursor(0);
    resetUndo();
    setFeelOpen(false);
  }

  // One setter per authorable lane, so tapPad writes the cursor's row without a branch each.
  const laneSetters = { dayan: setDayan, bayan: setBayan, kartal: setKartal };
  /** Write the pad into the selected cell, sound it, and step along the same row. */
  function tapPad(pad) {
    pushUndo();
    laneSetters[cursorLane]((p) => { const c = [...p]; c[cursor] = pad.value; return c; });
    if (!previewing) { const n = soundName(cursorLane, pad.value); if (n) engine.playStroke(n); }
    setCursor((c) => (c + 1) % steps);
  }

  /**
   * Steps a cell on through its row's palette — rest → open → closed → rest —
   * so a cell is edited without moving. The cursor stays put; the stroke sounds; undo takes it back.
   */
  function cycleCell(lane, i) {
    const order = [null, ...padsFor(lane).filter((p) => !p.rest).map((p) => p.value)];
    const now = { dayan, bayan, kartal }[lane][i] ?? null;
    const next = order[(order.indexOf(now) + 1) % order.length];
    pushUndo();
    laneSetters[lane]((p) => { const c = [...p]; c[i] = next; return c; });
    if (!previewing) { const n = soundName(lane, next); if (n) engine.playStroke(n); }
  }

  async function togglePreview() {
    await engine.unlock();
    if (previewing) { engine.stop(); setPreviewing(false); }
    else { engine.setBeat(beatRef.current); engine.setBpm(bpm); engine.start(); setPreviewing(true); }
  }

  /**
   * Save, then leave — but only if it actually saved.
   *
   * Two things guard this, both because saving reaches durable storage now
   * and so takes real time (imperceptible for localStorage, not for anything
   * remote):
   *
   *  - `saving` blocks a second press. The window is tiny today — the editor
   *    unmounts so fast the button is gone before a second tap lands — but
   *    "too fast to hit" is a property of the current store, not a guarantee,
   *    and a duplicated beat is exactly what it would cost.
   *  - The editor stays OPEN if the save failed. Unmounting is how a draft is
   *    discarded here, so closing on a failed write would throw the user's
   *    work away and leave them only an error message about it.
   */
  async function handleSave(asNew = false) {
    if (saving) return;
    if (previewing) { engine.stop(); setPreviewing(false); }
    const hasAnyHit = dayan.some((c) => c !== null) || bayan.some((c) => c !== null) || kartal.some((c) => c !== null);
    if (!hasAnyHit) { alert(t("editor.needStroke")); return; }
    let finalName = clampName(name.trim()) || t("editor.defaultName");
    // A variation left with the original's name would be two identical rows.
    if (asNew && finalName === initialBeat?.name) finalName = clampName(t("editor.copyName", { name: finalName }));
    // No id means "new" — the library mints one on save, and it is now the
    // only place in the app ids come from. Editing an existing custom beat
    // keeps its id and so overwrites in place.
    // "Save as new" drops the id, so the original stays as it was.
    const id = asNew ? null : initialBeat?.id ?? null;
    const uniform = groups.every((g) => g === groups[0]);
    // Only persist a kartal line when the user actually drew one, so a beat
    // with no cymbals stays a two-lane beat (matches beats.js's convention and
    // keeps the strip from carrying an all-rest brass row).
    const hasKartal = kartal.some((c) => c !== null);
    setSaving(true);
    const saved = await onSave({ id, name: finalName, note: "Custom", bpm, steps, beatsPerBar,
      cellsPerGroup: uniform ? groups[0] : cpq, groups, dayan, bayan,
      ...(hasKartal ? { kartal } : {}),
      ...(tags.length ? { tags } : {}),
      ...(swing > SWING_STRAIGHT ? { swing } : {}),
      // A copy of a community beat remembers its original (see communityTracking.js).
      ...(initialBeat?.forkedFrom ? { forkedFrom: initialBeat.forkedFrom } : {}) });
    if (saved) { onClose(); return; }   // unmounts — no state to reset
    setSaving(false);
  }

  const selection = selectAnchor == null ? null
    : runBetween(Math.min(selectAnchor, steps - 1), Math.min(cursor, steps - 1));
  function copySelection() {
    if (!selection) return;
    setClip(copyRun({ dayan, bayan, kartal }, selection[0], selection[1]));
    setCursor(selection[0]);
    setSelectAnchor(null);
  }
  function paste(closeBayan) {
    if (!clip) return;
    const { lanes, written } = pasteRun({ dayan, bayan, kartal }, clip, cursor, closeBayan);
    if (!written) return;
    pushUndo();
    setDayan(lanes.dayan); setBayan(lanes.bayan); setKartal(lanes.kartal);
    setSelectAnchor(null);
    setResampleReport(null);
    setCursor((cursor + written) % steps);
  }

  function clearGrid() {
    pushUndo();
    setDayan(emptyGrid(steps)); setBayan(emptyGrid(steps)); setKartal(emptyGrid(steps)); setCursor(0);
  }

  function scrubTo(clientX) {
    const el = overviewRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
    setCursor(Math.min(steps - 1, Math.floor(frac * steps)));
  }

  const range = tempoRange(brahmaloka);
  const unlockBrahmaloka = () => { setBrahmaloka(true); setBurstKey((k) => k + 1); engine.rumble(); navigator.vibrate?.([90, 60, 180]); };
  function changeBpm(v) {
    setBpm(v);
    setBrahmaloka((was) => brahmalokaFor(v, was));
  }

  return (
    <div className="kc-screen" style={st.screen}>
      <BrahmalokaBurst fireKey={burstKey} />
      <header style={st.header}>
        {onBack && <button onClick={onBack} style={st.backBtn} aria-label={t("common.back")}>‹</button>}
        <div style={st.titleWrap}>
          <input ref={nameInputRef} type="text" value={name} onChange={(e) => setName(e.target.value)}
            maxLength={MAX_NAME_LEN}
            placeholder={t("editor.namePlaceholder")} aria-label={t("editor.name")} style={st.titleInput} />
          <button onClick={() => nameInputRef.current?.focus()} aria-label={t("editor.rename")}
            style={st.titlePencil}><PencilIcon /></button>
        </div>
        {/* Only when there is something to lose; the Editor tab's dot says the
            same from anywhere else in the app. */}
        {dirty && (
          <button onClick={() => setDiscardOpen(true)} style={st.revertBtn}>
            {t(initialBeat ? "editor.revert" : "editor.startOver")}
          </button>
        )}
      </header>
      <button onClick={() => setTagsOpen(true)} style={st.tagsLine} aria-label={t("tags.editAuthor")}>
        <span style={st.tagsLabel}>{t("tags.title")}</span>
        <span style={st.tagsValue}>{tagLine(tags) || t("tags.none")}</span>
      </button>

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
          {selection && (
            <div aria-hidden="true" style={{ ...st.overviewSel,
              left: (selection[0] / steps) * 100 + "%",
              width: ((selection[1] + 1 - selection[0]) / steps) * 100 + "%" }} />
          )}
          {pageCount > 1 && (
            <div aria-hidden="true" style={{ ...st.overviewFrame,
              left: (windowStart / steps) * 100 + "%",
              width: ((windowEnd - windowStart) / steps) * 100 + "%" }} />
          )}
        </div>

        {/* ── ZOOM: one magnified page; the cursor is a cell ── */}
        <div style={st.zoom}>
          <div style={st.zoomHead}>
            <button onClick={() => setCursor(pages[Math.max(0, page - 1)][0])}
              disabled={page === 0} aria-label={t("editor.prevPage")}
              style={{ ...st.pageBtn, opacity: page === 0 ? 0.3 : 1 }}>‹</button>
            <span style={st.zoomLabel}>{t("editor.pageRange", { from: windowStart + 1, to: windowEnd, total: steps })}</span>
            <button onClick={() => setCursor(pages[Math.min(pageCount - 1, page + 1)][0])}
              disabled={page >= pageCount - 1} aria-label={t("editor.nextPage")}
              style={{ ...st.pageBtn, opacity: page >= pageCount - 1 ? 0.3 : 1 }}>›</button>
          </div>
          <div style={st.zoomRows}>
            <div style={{ ...st.zoomLine, gridTemplateColumns: `repeat(${zoomCells}, 1fr)` }}>
              {labels.slice(windowStart, windowEnd).map((l, j) => (
                <div key={"n" + j} style={{ ...st.zoomNum, opacity: l !== "·" ? 0.9 : 0.4, fontWeight: l !== "·" ? 700 : 400 }}>{l}</div>
              ))}
            </div>
            {ROWS.map(([end, label]) => {
              const arr = { dayan, bayan, kartal }[end];
              const onRow = end === cursorLane;
              const pick = (i) => { setCursor(i); setCursorLane(end); };
              return (
                <div key={end}>
                  <button onClick={() => pick(cursor)}
                    aria-pressed={onRow}
                    style={{ ...st.laneLabel, color: `var(--lane-${end})`, fontWeight: onRow ? 800 : 400 }}>
                    {label}
                  </button>
                  <div style={{ ...st.zoomLine, gridTemplateColumns: `repeat(${zoomCells}, 1fr)` }}>
                    {arr.slice(windowStart, windowEnd).map((v, j) => {
                      const i = windowStart + j;
                      const isCursor = onRow && i === cursor;
                      const lit = previewing && i === step;
                      const chosen = selection && i >= selection[0] && i <= selection[1];
                      return (
                        <button key={end + i}
                          // Choosing a run to copy only ever moves its end.
                          // Another cell: select it, and an EMPTY one gets the row's first
                          // stroke at once (a filled one is only selected, so moving never
                          // overwrites). The selected cell: step it on through the palette.
                          onClick={() => {
                            if (selectAnchor != null) pick(i);
                            else if (isCursor) cycleCell(end, i);
                            else { pick(i); if (v == null) cycleCell(end, i); }
                          }}
                          aria-current={isCursor || undefined}
                          aria-label={v ? t("editor.cell", { lane: end, n: i + 1, bol: BOLS[end]?.[v] ?? v }) : t("editor.cellEmpty", { lane: end, n: i + 1 })}
                          style={{ ...st.zoomCell,
                            borderColor: isCursor ? "var(--clay)" : "var(--rule)",
                            borderWidth: isCursor ? 2 : 1,
                            // The cursor's column, faintly, on the other rows: where the
                            // other hands fall at the same moment.
                            background: lit ? "var(--head-sunken)" : chosen ? "color-mix(in srgb, var(--clay) 16%, var(--head))"
                              : (!onRow && i === cursor) ? "var(--head-worn)" : "var(--head)" }}>
                          <span aria-hidden="true"
                            style={dotStyle(v, `var(--lane-${end})`, 18)} />
                        </button>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── COPY / PASTE ── */}
        <div style={st.clip}>
          {selection ? (
            <>
              <div style={st.clipPrompt}>
                {t("editor.selection", { from: selection[0] + 1, to: selection[1] + 1 })} · {t("editor.selectHint")}
              </div>
              <div style={st.controlRow}>
                <button onClick={copySelection} style={{ ...st.utilBtn, ...st.previewOn }}>{t("editor.copy")}</button>
                <button onClick={() => setSelectAnchor(null)} style={st.utilBtn}>{t("common.cancel")}</button>
              </div>
            </>
          ) : (
            <>
              <div style={st.controlRow}>
                <button onClick={() => setSelectAnchor(cursor)} style={st.utilBtn}>{t("editor.select")}</button>
                <button onClick={() => paste(false)} disabled={!clip} style={{ ...st.utilBtn, opacity: clip ? 1 : 0.4 }}>{t("editor.paste")}</button>
              </div>
              {clip && (
                <>
                  <button onClick={() => paste(true)} style={st.utilBtn}>{t("editor.pasteClosed")}</button>
                  <div style={st.clipHint}>{t("editor.clip", { n: clipLength(clip) })} · {t("editor.pasteClosedHint")}</div>
                </>
              )}
            </>
          )}
        </div>

        {/* ── PADS ── */}
        <div style={st.padsHead}>
          <span style={{ color: `var(--lane-${cursorLane})` }}>{ROWS.find(([l]) => l === cursorLane)[1]}</span>
          {" · "}{t("editor.cellN", { n: cursor + 1 })}{" "}
          <span style={st.padsHint}>· {t("editor.tapAnyCell")}</span>
        </div>
        <div style={st.pads}>
          {padsFor(cursorLane).map((pad) => (
            <button key={pad.key} onClick={() => tapPad(pad)}
              aria-label={pad.rest ? t("editor.rest") : pad.label}
              style={{ ...st.pad, ...(pad.rest ? st.padRest : null) }}>
              <span style={st.padLabel}>{pad.rest ? t("editor.rest") : pad.label}</span>
              {!pad.rest && <span aria-hidden="true" style={dotStyle(pad.value, `var(--lane-${cursorLane})`, 11)} />}
            </button>
          ))}
        </div>

        {/* ── Controls ── */}
        <div style={st.controls}>
          <div style={st.controlRow}>
            <button onClick={undo} disabled={!canUndo} style={{ ...st.utilBtn, opacity: canUndo ? 1 : 0.4 }}>{t("editor.undo")}</button>
            <button onClick={clearGrid} style={st.utilBtn}>{t("editor.clear")}</button>
            <button onClick={togglePreview} style={{ ...st.utilBtn, ...(previewing ? st.previewOn : null) }}>
              {previewing ? t("editor.stopPreview") : t("editor.playPreview")}
            </button>
          </div>

          <div style={st.metaRow}>
            {/* Feel/meter — one picker button. */}
            <button onClick={() => setFeelOpen(true)} style={st.feelBtn}
              aria-haspopup="dialog" aria-expanded={feelOpen}>
              <span style={st.feelName}>{tOr(`meter.${meter.id}`, meter.label)}</span>
              <span style={st.feelCaret}>▾</span>
            </button>
            {/* Length: add/remove one unit of the current meter. */}
            <div style={st.lengthRow}>
              <button onClick={removeGroup} disabled={!canRemoveBeat}
                style={{ ...st.groupBtn, opacity: canRemoveBeat ? 1 : 0.4 }}
                aria-label={t("editor.removeGroup")}>−</button>
              <span style={st.lengthLabel}>{t("editor.beatCount", { n: groups.length })}</span>
              <button onClick={addGroup} disabled={!canAddBeat}
                style={{ ...st.groupBtn, opacity: canAddBeat ? 1 : 0.4 }}
                aria-label={t("editor.addGroup")}>+</button>
            </div>
          </div>

          {/* Resolution: cells per beat (or ×N for odd meters). Keeps the rhythm. */}
          <div style={st.resBlock}>
            <span style={st.resLabel}>{lever.multiplier ? t("editor.resolution") : t("editor.cellsPerBeat")}</span>
            <div style={st.resRow} role="radiogroup" aria-label={lever.multiplier ? t("editor.resolution") : t("editor.cellsPerBeat")}>
              {lever.options.map((o) => {
                const selected = o === lever.current;
                const ok = selected || reshape(groups, cpq, meter.unit, o) != null;
                return (
                  <button key={o} role="radio" aria-checked={selected} disabled={!ok}
                    onClick={() => changeResolution(o)}
                    style={{ ...st.resChip, ...(selected ? st.resChipOn : null), opacity: ok ? 1 : 0.35 }}>
                    {lever.multiplier ? `×${o}` : o}
                  </button>
                );
              })}
            </div>
            {resampleReport && (
              <p style={st.resNote} role="status">
                {[
                  resampleReport.moved ? t("editor.resampleMoved", { n: resampleReport.moved }) : null,
                  resampleReport.dropped ? t("editor.resampleDropped", { n: resampleReport.dropped }) : null,
                  t("editor.resampleUndo"),
                ].filter(Boolean).join(" ")}
              </p>
            )}
          </div>

          <div style={st.tempoRow}>
            {/* Three digits' room, so the slider doesn't shift at 100 or 200. */}
            <span style={{ ...st.bpmNum, minWidth: "3ch", textAlign: "right" }}>{bpm}</span>{/* One fixed slot: "BPM", or in Brahmaloka the ✦ way back out. */}
            <span style={{ display: "inline-flex", width: 36, flexShrink: 0, justifyContent: "flex-start" }}>
              {brahmaloka ? (
                <button onClick={() => { setBrahmaloka(false); setBpm((b) => Math.min(b, 200)); }}
                  aria-label={t("brahma.leave")} title={t("brahma.leave")} style={st.brahmaBadge}>✦</button>
              ) : <span style={st.bpmUnit}>{t("tempo.bpm")}</span>}
            </span>
            <BpmRange bpm={bpm} brahmaloka={brahmaloka} min={range.min} max={range.max}
              onChange={changeBpm} onUnlock={unlockBrahmaloka}
              title={brahmaloka ? t("brahma.badge") : t("brahma.hint")} ariaLabel={t("tempo.title")} />
          </div>
          <SwingControl value={swing} onChange={setSwing} hint={t("swing.editorHint")} />
        </div>

      </div>

      <div style={st.footer}>
        {/* Only your own beat has an id here (others arrive as forks), and only
            it can be saved over — so only it needs the way to make a variation. */}
        {initialBeat?.id && (
          <button onClick={() => handleSave(true)} disabled={saving} style={st.saveAsBtn}>
            {t("editor.saveAsNew")}
          </button>
        )}
        <button onClick={() => handleSave()} disabled={saving}
          style={{ ...st.saveBtn, ...(saving ? st.saveBtnBusy : null) }}>
          {saving ? t("editor.saving") : t("editor.save")}
        </button>
      </div>

      {tagsOpen && (
        <TagPicker title={t("tags.title")} hint={t("tags.authorHint")}
          selected={tags} onChange={setTags} onClose={() => setTagsOpen(false)} />
      )}

      {discardOpen && (
        <div style={st.sheetBackdrop} onClick={() => setDiscardOpen(false)}>
          <div style={st.sheet} role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <div style={st.sheetHead}>
              <h2 style={st.sheetTitle}>{t(initialBeat ? "editor.revert" : "editor.startOver")}</h2>
              <button onClick={() => setDiscardOpen(false)} aria-label={t("common.close")} style={st.sheetClose}>×</button>
            </div>
            <p style={st.sheetHint}>
              {initialBeat ? t("editor.revertBody", { name: initialBeat.name }) : t("editor.startOverBody")}
            </p>
            <button style={{ ...st.saveBtn, marginTop: "var(--space-3)" }}
              onClick={() => { if (previewing) engine.stop(); setDiscardOpen(false); onDiscard?.(); }}>
              {t(initialBeat ? "editor.revert" : "editor.startOver")}
            </button>
          </div>
        </div>
      )}

      {/* Feel/meter picker sheet */}
      {feelOpen && (
        <div style={st.sheetBackdrop} onClick={() => setFeelOpen(false)}>
          <div style={st.sheet} role="dialog" aria-modal="true" aria-label={t("editor.feel")}
            onClick={(e) => e.stopPropagation()}>
            <div style={st.sheetHead}>
              <h2 style={st.sheetTitle}>{t("editor.feelMeter")}</h2>
              <button onClick={() => setFeelOpen(false)} aria-label={t("common.close")} style={st.sheetClose}>×</button>
            </div>
            <p style={st.sheetHint}>{t("editor.feelClears")}</p>
            <div style={st.feelList}>
              {METERS.map((m) => {
                const sel = m.id === meter.id;
                return (
                  <button key={m.id} onClick={() => selectMeter(m)}
                    style={{ ...st.feelOption, borderColor: sel ? "var(--clay)" : "var(--rule)" }}>
                    <span style={st.feelOptName}>{tOr(`meter.${m.id}`, m.label)}</span>
                    <span style={st.feelOptSig}>{tOr(`meterSig.${m.id}`, m.sig)}</span>
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
  tagsLine: { flexShrink: 0, display: "flex", alignItems: "baseline", gap: "var(--space-2)", minHeight: 40, padding: "var(--space-2) 0", border: "none", background: "transparent", textAlign: "left", cursor: "pointer", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)" },
  tagsLabel: { fontWeight: 700, color: "var(--syahi-soft)" },
  tagsValue: { color: "var(--syahi)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  titleWrap: { flex: 1, minWidth: 0, display: "flex", alignItems: "center", gap: "var(--space-2)" },
  titleInput: { flex: 1, minWidth: 0, border: "none", background: "transparent", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)", padding: "2px 0", outline: "none" },
  titlePencil: { flexShrink: 0, width: 40, height: 40, border: "none", background: "transparent", color: "var(--syahi-soft)", cursor: "pointer", display: "grid", placeItems: "center" },

  overviewSel: { position: "absolute", top: 4, bottom: 4, borderRadius: 4, background: "var(--clay)", opacity: 0.22, pointerEvents: "none" },
  clip: { flexShrink: 0, display: "flex", flexDirection: "column", gap: "var(--space-2)" },
  clipPrompt: { color: "var(--clay)", fontFamily: "var(--font-body)", fontSize: 13, fontWeight: 600 },
  clipHint: { color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: 12 },
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

  padsHead: { flexShrink: 0, marginBottom: -6, fontFamily: "var(--font-body)", fontSize: 11, fontWeight: 800, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink-primary)" },
  padsHint: { fontWeight: 500, letterSpacing: 0, textTransform: "none", color: "var(--ink-secondary)", fontSize: 12 },
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
  resBlock: { display: "flex", flexDirection: "column", gap: 8 },
  resLabel: { fontFamily: "var(--font-body)", fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  resRow: { display: "flex", gap: 8, flexWrap: "wrap" },
  resChip: { minWidth: 44, height: 36, padding: "0 12px", borderRadius: 999, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi)", fontFamily: "var(--font-body)", fontSize: 13, fontWeight: 700, cursor: "pointer" },
  // Full `border`, not borderColor: React drops a removed longhand without restoring
  // the shorthand, which left previously selected chips with a dark border.
  resChipOn: { background: "var(--clay)", border: "1px solid var(--clay)", color: "var(--on-clay)" },
  resNote: { margin: 0, fontFamily: "var(--font-body)", fontSize: 12, color: "var(--syahi-soft)" },
  lengthRow: { flexShrink: 0, display: "flex", alignItems: "center", gap: 8 },
  groupBtn: { flexShrink: 0, width: 44, height: 46, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--clay)", fontSize: 24, fontWeight: 700, cursor: "pointer", display: "grid", placeItems: "center" },
  lengthLabel: { minWidth: 54, textAlign: "center", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, color: "var(--syahi-soft)" },
  brahmaBadge: {
    width: 32, height: 32, padding: 0, borderRadius: 999, cursor: "pointer",
    border: "1px solid oklch(0.70 0.17 60)", background: "oklch(0.93 0.07 80)",
    color: "oklch(0.45 0.14 45)", fontSize: 15, fontWeight: 700, lineHeight: 1,
  },
  tempoRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  bpmNum: { fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "1.5rem", fontWeight: 600, color: "var(--syahi)", lineHeight: 1 },
  bpmUnit: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", color: "var(--syahi-soft)" },

  footer: { flexShrink: 0, display: "flex", justifyContent: "flex-end", gap: "var(--space-3)" },
  saveAsBtn: { minHeight: 48, padding: "0 20px", borderRadius: 14, border: "1px solid var(--rule)", background: "transparent", color: "var(--syahi)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 600, cursor: "pointer" },
  saveBtn: { minHeight: 48, padding: "0 28px", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.02em", cursor: "pointer" },
  // Dimmed rather than greyed: the button keeps its colour so the press
  // clearly registered, it just isn't pressable again.
  saveBtnBusy: { opacity: 0.65, cursor: "default" },

  sheetBackdrop: { position: "fixed", inset: 0, background: "oklch(0.24 0.02 60 / 0.35)", display: "flex", alignItems: "flex-end", justifyContent: "center", zIndex: 10 },
  sheet: { width: "100%", maxWidth: 430, maxHeight: "85dvh", overflowY: "auto", background: "var(--head)", borderRadius: "20px 20px 0 0", padding: "var(--space-5) var(--space-5) calc(var(--space-6) + env(safe-area-inset-bottom))", display: "flex", flexDirection: "column" },
  revertBtn: { flexShrink: 0, minHeight: 40, padding: "0 var(--space-2)", border: "none", background: "transparent", color: "var(--clay)", fontFamily: "var(--font-body)", fontSize: 14, fontWeight: 600, cursor: "pointer" },
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
