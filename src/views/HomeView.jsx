import { useState, useRef, useEffect, useMemo } from "react";
import { LANES } from "../data/lanes.js";
import { MIN_BPM, MAX_BPM } from "../hooks/useTransport.js";
import BeatStrip from "../BeatStrip.jsx";
import Wordmark from "../Wordmark.jsx";
import ScrollFadeRow from "../ui/ScrollFadeRow.jsx";
import * as sh from "../ui/styles.js";
import {
  LockIcon, PencilIcon, MixerIcon, SpeakerIcon, EqIcon, CheckIcon,
  PlayIcon, PauseIcon, RadioDot,
} from "../ui/icons.jsx";

// Scroll-wheel BPM picker: an iOS-style snapping column, tuned so the
// CENTRED value is the selection (updates live so you hear the tempo as you
// scroll). Touch flick / mouse wheel / drag all scroll it. `value` changes
// coming from OUTSIDE (typing in the field) re-centre the wheel; a self-sync
// guard keeps the two from fighting.
const WHEEL_ITEM_H = 40;   // px per row
const WHEEL_PAD = 2;       // rows of padding each side (so ends can centre)
function BpmWheel({ value, min, max, onChange }) {
  const ref = useRef(null);
  const scrollingRef = useRef(false);
  const rafRef = useRef(0);
  const settleRef = useRef(0);
  const values = useMemo(
    () => Array.from({ length: max - min + 1 }, (_, i) => min + i),
    [min, max]
  );

  // Re-centre when value changes from elsewhere (not mid-scroll).
  useEffect(() => {
    const el = ref.current;
    if (el && !scrollingRef.current) el.scrollTop = (value - min) * WHEEL_ITEM_H;
  }, [value, min]);

  const onScroll = () => {
    const el = ref.current;
    if (!el) return;
    scrollingRef.current = true;
    cancelAnimationFrame(rafRef.current);
    rafRef.current = requestAnimationFrame(() => {
      const idx = Math.round(el.scrollTop / WHEEL_ITEM_H);
      const v = Math.min(max, Math.max(min, min + idx));
      if (v !== value) onChange(v);
    });
    clearTimeout(settleRef.current);
    settleRef.current = setTimeout(() => { scrollingRef.current = false; }, 140);
  };

  return (
    <div className="kc-wheel" ref={ref} onScroll={onScroll}
      style={{ height: (WHEEL_PAD * 2 + 1) * WHEEL_ITEM_H }}>
      <div style={{ height: WHEEL_PAD * WHEEL_ITEM_H }} aria-hidden="true" />
      {values.map((v) => (
        <div key={v} className="kc-wheel-item" data-sel={v === value}
          style={{ height: WHEEL_ITEM_H }}>{v}</div>
      ))}
      <div style={{ height: WHEEL_PAD * WHEEL_ITEM_H }} aria-hidden="true" />
    </div>
  );
}

// Per-end EQ (epic #1): the band labels mirror the engine's fixed five-filter
// table. The band gains and panel-open state live in the transport (eqBands /
// eqOpen); EQ_FLAT is the render-safe fallback for reading them until the
// hook side of the contract has landed.
const EQ_BAND_LABELS = ["100 Hz", "300 Hz", "1 kHz", "3 kHz", "8 kHz"];
const EQ_FLAT = [0, 0, 0, 0, 0];

// Signed gain readout for the EQ bands: "+3 dB" / "0 dB" / "−3 dB" — the
// explicit sign makes the cut/boost direction legible at a glance.
function fmtDb(db) {
  if (db === 0) return "0 dB";
  return (db > 0 ? "+" : "−") + Math.abs(db) + " dB";
}

/**
 * HomeView — the playing screen, in two layouts.
 *
 * PORTRAIT is the phone-in-hand layout: wordmark, beat switcher, the strip
 * filling whatever height is left, then tempo and a full-width clay play bar
 * at thumb height. LANDSCAPE is the propped-up layout: phone-height
 * landscape leaves ~120-200px for everything besides the strip and nav, so
 * the ENTIRE transport collapses into one 44px rail and the strip takes the
 * rest, its cells sized from the slot via container query units.
 *
 * Both layouts share the same three sheets (quick-pick, mixer, tempo), which
 * is why they live in one file rather than two: the sheets and every
 * transport handler would otherwise be duplicated or lifted for no reason.
 *
 * STATE THIS SCREEN OWNS: only which sheet is open and the raw text in the
 * tempo field. Everything else is the transport's, passed in.
 */
function HomeView({
  transport, beat, beatId, library, isLandscape,
  onSelect, onCycle, onEdit, nav,
}) {
  // The EQ fields default to flat/closed: the transport hook implements them
  // in a parallel slice (epic #1), and a destructure default only applies
  // while the field is still undefined — so this stays correct once it lands.
  const {
    ready, playing, step, getPhase,
    bpm, changeBpm, nudgeBpm, commitBpm, tapTempo,
    tempoLocked, setTempoLocked,
    volume, changeVolume, endVolumes, changeEndVolume, mutedEnds, toggleMute,
    eqBands = { dayan: EQ_FLAT, bayan: EQ_FLAT },
    eqOpen = { dayan: false, bayan: false },
    changeEqBand, toggleEqPanel,
    togglePlay,
  } = transport;
  const { categories, activeCat, categoryBeats, catName, isCustomBeat } = library;

  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerTab, setPickerTab] = useState("builtin");
  const [mixerOpen, setMixerOpen] = useState(false);
  const [bpmEntryOpen, setBpmEntryOpen] = useState(false);
  const [bpmDraft, setBpmDraft] = useState("");     // raw text in the entry field
  const bpmFocusRef = useRef(false);                // is the field being typed in

  // Precise-entry field: edits live in a DRAFT string (never clamped mid-type,
  // so backspace works and "80" isn't briefly applied as "8"). In-range drafts
  // commit after a short pause; out-of-range/partial drafts commit-clamp only
  // on blur or close.
  useEffect(() => {
    if (bpmEntryOpen && !bpmFocusRef.current) setBpmDraft(String(bpm));
  }, [bpm, bpmEntryOpen]);
  useEffect(() => {
    if (!bpmEntryOpen) return;
    const v = parseInt(bpmDraft, 10);
    if (!Number.isFinite(v) || v < MIN_BPM || v > MAX_BPM) return;
    const t = setTimeout(() => { if (v !== bpm) changeBpm(v); }, 450);
    return () => clearTimeout(t);
  }, [bpmDraft, bpmEntryOpen, bpm]);

  function commitBpmDraft() {
    const v = parseInt(bpmDraft, 10);
    if (Number.isFinite(v)) commitBpm(v);
    else setBpmDraft(String(bpm));
  }
  // The ✓ (and backdrop) confirm: apply whatever's typed, then close.
  function closeBpmEntry() {
    bpmFocusRef.current = false;
    commitBpmDraft();
    setBpmEntryOpen(false);
  }

  // Quick-pick opens on the ACTIVE category but carries its own tab row, so
  // the user can hop to another progression without visiting the Beats page.
  function openPicker() {
    setPickerTab(activeCat);
    setPickerOpen(true);
  }

  const fillPct = ((bpm - MIN_BPM) / (MAX_BPM - MIN_BPM)) * 100;
  const playIcon = playing ? <PauseIcon /> : <PlayIcon />;
  const editLabel = isCustomBeat(beat.id) ? "Edit this beat" : "Customize this beat";

  // ── Sheets, shared by both orientations ──

  // Picking a beat makes its tab the active cycling context and dismisses.
  const pickerBeats = categoryBeats(pickerTab);
  const beatPicker = pickerOpen && (
    <div style={sh.sheetBackdrop} onClick={() => setPickerOpen(false)}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Choose a beat"
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>Choose a beat</h2>
          <button onClick={() => setPickerOpen(false)} aria-label="Close" style={sh.sheetClose}>×</button>
        </div>
        <ScrollFadeRow rowStyle={sh.catTabs}>
          {["builtin", "custom", ...categories.map(c => c.id)].map(id => (
            <button key={id} onClick={() => setPickerTab(id)}
              style={{ ...sh.catTab, ...(pickerTab === id ? sh.catTabActive : null) }}
              aria-pressed={pickerTab === id}>
              {catName(id)}
            </button>
          ))}
        </ScrollFadeRow>
        <div style={sh.pickList}>
          {pickerBeats.length === 0 && (
            <p style={sh.emptyHint}>This category is empty — add beats to it on the Beats page.</p>
          )}
          {pickerBeats.map(b => {
            const sel = b.id === beatId;
            return (
              <button key={b.id} onClick={() => { onSelect(b, pickerTab); setPickerOpen(false); }}
                style={{ ...sh.pickRow, borderColor: sel ? "var(--clay)" : "var(--rule)" }}>
                <div style={sh.beatRowTop}>
                  <span style={sh.beatRowName}>{b.name}</span>
                  <span style={{ ...sh.beatRowMeta, flex: 1 }}>{b.note}</span>
                  <RadioDot selected={sel} />
                </div>
                <BeatStrip beat={b} mini />
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );

  // Precise BPM entry: type the number (desktop) or scroll the wheel (touch);
  // both drive changeBpm live.
  const bpmEntry = bpmEntryOpen && (
    <div style={sh.sheetBackdrop} onClick={closeBpmEntry}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Set tempo"
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>Tempo</h2>
          <button onClick={closeBpmEntry} aria-label="Confirm tempo" style={st.sheetConfirm}>
            <CheckIcon />
          </button>
        </div>
        <div style={st.bpmEntryTop}>
          {/* select-all on focus so the first keystroke replaces the value
              (open at 140, type 8 → "8", type 0 → "80"); capped at 3 digits. */}
          <input type="text" inputMode="numeric" maxLength={3} value={bpmDraft}
            onFocus={(e) => { bpmFocusRef.current = true; e.target.select(); }}
            onBlur={() => { bpmFocusRef.current = false; commitBpmDraft(); }}
            onChange={(e) => setBpmDraft(e.target.value.replace(/[^0-9]/g, "").slice(0, 3))}
            onKeyDown={(e) => { if (e.key === "Enter") e.currentTarget.blur(); }}
            aria-label="Tempo in BPM" style={st.bpmInput} />
        </div>
        <span style={{ ...st.bpmUnit, display: "block", textAlign: "center" }}>BPM</span>
        <div style={st.wheelWrap}>
          <div style={st.wheelBand} aria-hidden="true" />
          <BpmWheel value={bpm} min={MIN_BPM} max={MAX_BPM} onChange={changeBpm} />
        </div>
      </div>
    </div>
  );

  // Mixer sheet — master fader plus one fader + mute per lane. This is where
  // muting properly lives; the strip's small lane labels remain a shortcut.
  const mixerSheet = mixerOpen && (
    <div style={sh.sheetBackdrop} onClick={() => setMixerOpen(false)}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Mixer"
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>Mixer</h2>
          <button onClick={() => setMixerOpen(false)} aria-label="Close" style={sh.sheetClose}>×</button>
        </div>
        <div style={st.mixRows}>
          <div style={st.mixRow}>
            <span style={st.mixLabel}>Master</span>
            <input className="kc-range" type="range" min={0} max={100} value={Math.round(volume * 100)}
              onChange={(e) => changeVolume(Number(e.target.value) / 100)}
              style={{ "--fill": volume * 100 + "%", flex: 1 }} aria-label="Master volume" />
            {/* Two spacers: the mute AND EQ columns now close every lane row,
                so the master fader keeps its right edge aligned with theirs. */}
            <span style={{ width: 44 }} aria-hidden="true" />
            <span style={{ width: 44 }} aria-hidden="true" />
          </div>
          {LANES.map(l => {
            const v = endVolumes[l.id] ?? 1;
            const muted = !!mutedEnds[l.id];
            // EQ belongs to the two mridanga ends only — the frozen contract
            // has no kartal key (eqOpen[kartal] would be undefined), so the
            // Kartal row keeps just its fader + mute, plus a spacer that holds
            // the fader column aligned with the EQ'd rows.
            const isEqEnd = l.id === "dayan" || l.id === "bayan";
            const open = !!eqOpen[l.id];
            const bands = eqBands[l.id] ?? EQ_FLAT;
            return (
              <div key={l.id} style={st.mixLane}>
                <div style={st.mixRow}>
                  <span style={{ ...st.mixLabel, color: l.color }}>{l.label}</span>
                  <input className="kc-range" type="range" min={0} max={100} value={Math.round(v * 100)}
                    onChange={(e) => changeEndVolume(l.id, Number(e.target.value) / 100)}
                    style={{ "--fill": v * 100 + "%", flex: 1, opacity: muted ? 0.35 : 1 }}
                    aria-label={`${l.label} volume`} />
                  <button onClick={() => toggleMute(l.id)} aria-pressed={muted}
                    aria-label={muted ? `Unmute ${l.label}` : `Mute ${l.label}`}
                    style={{ ...st.muteBtn, background: muted ? "var(--head-sunken)" : "transparent",
                      color: muted ? "var(--clay)" : "var(--syahi-soft)" }}>
                    <SpeakerIcon muted={muted} />
                  </button>
                  {isEqEnd ? (
                    // Gated on the handler: the transport's EQ slice (epic #1)
                    // hasn't landed yet, so the toggle stays visibly disabled
                    // instead of looking interactive while doing nothing.
                    <button onClick={() => toggleEqPanel?.(l.id)} disabled={!toggleEqPanel}
                      aria-pressed={open} aria-expanded={open} aria-label={`${l.label} EQ`}
                      style={{ ...st.muteBtn, background: open ? "var(--head-sunken)" : "transparent",
                        color: open ? l.color : "var(--syahi-soft)",
                        opacity: toggleEqPanel ? 1 : 0.35, cursor: toggleEqPanel ? "pointer" : "not-allowed" }}>
                      <EqIcon />
                    </button>
                  ) : (
                    <span style={{ width: 44 }} aria-hidden="true" />
                  )}
                </div>
                {isEqEnd && open && (
                  <div style={st.eqPanel} role="group" aria-label={`${l.label} equalizer`}>
                    {EQ_BAND_LABELS.map((bandLabel, i) => {
                      const db = bands[i] ?? 0;
                      return (
                        <div key={bandLabel} style={st.mixRow}>
                          <span style={{ ...st.mixLabel, textTransform: "none" }}>{bandLabel}</span>
                          <input className="kc-range" type="range" min={-12} max={12} step={1} value={db}
                            onChange={(e) => changeEqBand?.(l.id, i, Number(e.target.value))}
                            disabled={!changeEqBand}
                            style={{ "--fill": ((db + 12) / 24) * 100 + "%", "--accent-action": l.color, flex: 1,
                              opacity: changeEqBand ? 1 : 0.5, cursor: changeEqBand ? "pointer" : "not-allowed" }}
                            aria-label={`${l.label} ${bandLabel} gain`} />
                          <span style={st.eqDb}>{fmtDb(db)}</span>
                          <span style={{ width: 44 }} aria-hidden="true" />
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <p style={{ ...sh.emptyHint, marginTop: "var(--space-4)" }}>
          100% is balanced for phone speakers — the bass end is already boosted.
        </p>
      </div>
    </div>
  );

  const lockButton = (
    <button onClick={() => setTempoLocked(v => !v)}
      style={{ ...st.lockBtn,
        background: tempoLocked ? "var(--clay)" : "transparent",
        color: tempoLocked ? "var(--on-clay)" : "var(--syahi-soft)" }}
      aria-label={tempoLocked ? "Unlock tempo" : "Lock tempo"}
      aria-pressed={tempoLocked}>
      <LockIcon locked={tempoLocked} />
    </button>
  );

  // ── Landscape: the propped-up layout ──
  if (isLandscape) {
    return (
      <div className="kc-screen" style={st.landScreen}>
        <main style={st.landStripWrap}>
          <BeatStrip
            beat={beat}
            step={step}
            playing={playing}
            getPhase={getPhase}
            mutedEnds={mutedEnds}
            onToggleMute={toggleMute}
          />
        </main>

        <div style={st.landRail}>
          <button onClick={() => onEdit(beat)} style={st.landEditBtn} aria-label={editLabel}>
            <PencilIcon />
          </button>
          <button onClick={() => onCycle(-1)} aria-label="Previous beat" style={st.chevBtnSm}>‹</button>
          <button onClick={openPicker} style={st.landNameBtn}
            aria-haspopup="dialog" aria-expanded={pickerOpen}>
            {beat.name} <span style={st.nameCaret}>▾</span>
          </button>
          <button onClick={() => onCycle(1)} aria-label="Next beat" style={st.chevBtnSm}>›</button>
          <button onClick={() => setBpmEntryOpen(true)} style={{ ...st.bpmBtn, ...st.landBpmBtn }}
            aria-haspopup="dialog" aria-label={`Tempo ${bpm} BPM — tap to set`}>
            <span style={st.landBpmNum}>{bpm}</span>
            <span style={st.bpmUnit}>BPM</span>
            <span style={st.bpmCaret} aria-hidden="true">▾</span>
          </button>
          <input className="kc-range" type="range" min={MIN_BPM} max={MAX_BPM} value={bpm}
            onChange={(e) => changeBpm(Number(e.target.value))} disabled={tempoLocked}
            style={{ "--fill": fillPct + "%", flex: 1, minWidth: 90, opacity: tempoLocked ? 0.5 : 1, cursor: tempoLocked ? "not-allowed" : "pointer" }}
            aria-label="Tempo" />
          {lockButton}
          <button onClick={tapTempo} style={st.tapBtn} aria-label="Tap tempo">Tap</button>
          <button onClick={() => setMixerOpen(true)} style={st.landEditBtn} aria-label="Mixer"
            aria-haspopup="dialog" aria-expanded={mixerOpen}>
            <MixerIcon />
          </button>
          <button onClick={() => togglePlay(beat)} disabled={!ready}
            style={{ ...st.landPlayBtn, opacity: ready ? 1 : 0.5 }}
            aria-label={playing ? "Pause" : "Play"}>
            {playIcon}
          </button>
        </div>

        <div style={st.landNavWrap}>{nav}</div>
        {beatPicker}
        {mixerSheet}
        {bpmEntry}
      </div>
    );
  }

  // ── Portrait ──
  return (
    <div className="kc-screen" style={sh.screenFixed}>
      {/* Small live wordmark — same component as the splash hero. */}
      <header style={st.header}>
        <Wordmark style={{ "--wm-size": "clamp(18px, 3.6vh, 24px)" }} />
      </header>

      {/* Beat switcher: ‹ › step through beats; tapping the name opens
          the quick-pick sheet. Pencil edits the loaded beat. */}
      <div style={st.beatHead}>
        <button onClick={() => onCycle(-1)} aria-label="Previous beat" style={st.chevBtn}>‹</button>
        <button onClick={openPicker} style={st.nameBtn}
          aria-haspopup="dialog" aria-expanded={pickerOpen}>
          <h1 style={st.beatName}>{beat.name} <span style={st.nameCaret}>▾</span></h1>
          <span style={st.beatMeta}>{beat.note} · {beat.steps} cells</span>
        </button>
        <button onClick={() => onCycle(1)} aria-label="Next beat" style={st.chevBtn}>›</button>
        <button onClick={() => onEdit(beat)} style={st.landEditBtn} aria-label={editLabel}>
          <PencilIcon />
        </button>
      </div>

      {/* The strip — vertically centred in whatever space is left. */}
      <main style={st.stripWrap}>
        <BeatStrip
          beat={beat}
          step={step}
          playing={playing}
          getPhase={getPhase}
          mutedEnds={mutedEnds}
          onToggleMute={toggleMute}
        />
      </main>

      <section style={sh.controls}>
        {/* BPM as signage: the number IS the label, and tapping it opens
            precise entry. */}
        <div>
          <div style={st.bpmRow}>
            <button onClick={() => nudgeBpm(-1)} disabled={bpm <= MIN_BPM}
              aria-label="Slower" style={{ ...st.bpmStep, opacity: bpm <= MIN_BPM ? 0.3 : 1 }}>−</button>
            <button onClick={() => setBpmEntryOpen(true)} style={st.bpmBtn}
              aria-haspopup="dialog" aria-label={`Tempo ${bpm} BPM — tap to set precisely`}>
              <span style={st.bpmNum}>{bpm}</span>
              <span style={st.bpmUnit}>BPM</span>
              <span style={st.bpmCaret} aria-hidden="true">▾</span>
            </button>
            <button onClick={() => nudgeBpm(1)} disabled={bpm >= MAX_BPM}
              aria-label="Faster" style={{ ...st.bpmStep, opacity: bpm >= MAX_BPM ? 0.3 : 1 }}>+</button>
          </div>
          <div style={st.tempoRow}>
            <input className="kc-range" type="range" min={MIN_BPM} max={MAX_BPM} value={bpm}
              onChange={(e) => changeBpm(Number(e.target.value))} disabled={tempoLocked}
              style={{ "--fill": fillPct + "%", flex: 1, opacity: tempoLocked ? 0.5 : 1, cursor: tempoLocked ? "not-allowed" : "pointer" }}
              aria-label="Tempo" />
            {lockButton}
            <button onClick={tapTempo} style={st.tapBtn} aria-label="Tap tempo">Tap</button>
          </div>
        </div>

        {/* Loudness lives in the mixer: master + per-drum faders. */}
        <button onClick={() => setMixerOpen(true)} style={st.mixerBtn}
          aria-haspopup="dialog" aria-expanded={mixerOpen}>
          <MixerIcon /> Mixer
        </button>
      </section>

      {/* The clay play bar — full width at thumb height: impossible to
          miss, impossible to fumble. */}
      <button onClick={() => togglePlay(beat)} disabled={!ready}
        style={{ ...st.playBtn, opacity: ready ? 1 : 0.5 }}
        aria-label={playing ? "Pause" : "Play"}>
        {playIcon}
        {playing ? "Pause" : "Play"}
      </button>

      {nav}
      {beatPicker}
      {mixerSheet}
      {bpmEntry}
    </div>
  );
}

const st = {
  header: { flexShrink: 0, display: "flex", justifyContent: "center" },

  // ── Beat switcher ──
  beatHead: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)" },
  beatName: { margin: 0, maxWidth: "100%", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)", lineHeight: 1.15, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  beatMeta: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", fontWeight: 500 },
  nameBtn: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", alignItems: "center", gap: 2, padding: 0, border: "none", background: "transparent", cursor: "pointer" },
  nameCaret: { fontSize: "0.55em", color: "var(--syahi-soft)", verticalAlign: "middle" },
  chevBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", fontSize: 26, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", paddingBottom: 4 },
  chevBtnSm: { flexShrink: 0, width: 32, height: 44, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 24, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", paddingBottom: 4 },
  stripWrap: { flex: 1, minHeight: 0, display: "flex", alignItems: "center" },

  // ── Tempo ──
  bpmRow: { display: "flex", alignItems: "center", justifyContent: "center", gap: "var(--space-4)", marginBottom: "var(--space-2)" },
  tempoRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  tapBtn: { flexShrink: 0, minHeight: 44, padding: "0 16px", borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontWeight: 700, fontSize: "var(--text-body-sm)", cursor: "pointer", letterSpacing: "0.04em" },
  lockBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", cursor: "pointer", display: "grid", placeItems: "center" },
  // Fixed width (3 tabular digits) so 2- vs 3-digit values don't change the
  // button's size and jitter the flanking steppers.
  bpmNum: { display: "inline-block", minWidth: "3ch", textAlign: "center", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-numeric-xl)", fontWeight: 600, color: "var(--syahi)", lineHeight: 1 },
  bpmUnit: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", color: "var(--syahi-soft)" },
  bpmBtn: { display: "flex", alignItems: "baseline", gap: 6, border: "none", background: "transparent", cursor: "pointer", padding: "4px 6px" },
  bpmCaret: { fontSize: "0.7rem", color: "var(--syahi-soft)", alignSelf: "center" },
  bpmStep: { flexShrink: 0, width: 48, height: 48, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--clay)", fontSize: 26, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center" },

  // ── Tempo-entry sheet ──
  sheetConfirm: { flexShrink: 0, width: 44, height: 44, margin: "-6px -6px 0 0", borderRadius: 12, border: "none", background: "var(--clay)", color: "var(--on-clay)", cursor: "pointer", display: "grid", placeItems: "center" },
  bpmEntryTop: { display: "flex", alignItems: "center", justifyContent: "center", gap: "var(--space-3)", marginTop: "var(--space-4)" },
  bpmInput: { width: 120, textAlign: "center", border: "none", background: "transparent", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "2.75rem", fontWeight: 600, color: "var(--syahi)", outline: "none", padding: 0, MozAppearance: "textfield" },
  wheelWrap: { position: "relative", marginTop: "var(--space-5)" },
  wheelBand: { position: "absolute", left: 0, right: 0, top: "50%", height: 40, transform: "translateY(-50%)", borderRadius: 12, background: "var(--head-sunken)", pointerEvents: "none" },

  // ── Mixer sheet ──
  mixerBtn: { width: "100%", minHeight: 46, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 9 },
  mixRows: { display: "flex", flexDirection: "column", gap: "var(--space-4)", marginTop: "var(--space-4)" },
  mixRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  mixLabel: { flexShrink: 0, width: 64, fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  muteBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", cursor: "pointer", display: "grid", placeItems: "center" },
  // A lane row plus its (optional) EQ panel — tighter gap than between lanes.
  mixLane: { display: "flex", flexDirection: "column", gap: "var(--space-2)" },
  eqPanel: { display: "flex", flexDirection: "column", gap: 2 },
  // Fixed width + tabular digits so −12…+12 readouts never jitter the slider.
  eqDb: { flexShrink: 0, width: 44, textAlign: "right", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-body-xs)", fontWeight: 600, color: "var(--syahi-soft)" },

  playBtn: { flexShrink: 0, width: "100%", minHeight: 58, borderRadius: 16, border: "none", background: "var(--clay)", color: "var(--on-clay)", display: "flex", alignItems: "center", justifyContent: "center", gap: 10, cursor: "pointer", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase" },

  // ── Landscape (the propped-up layout) ──
  landScreen: { width: "100%", maxWidth: 1100, minHeight: 0, margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-3) + env(safe-area-inset-top)) calc(var(--space-4) + env(safe-area-inset-right)) calc(var(--space-2) + env(safe-area-inset-bottom)) calc(var(--space-4) + env(safe-area-inset-left))", gap: "var(--space-3)" },
  // Cells sized from the WRAPPER's height (container query units), not
  // the viewport's, so the strip can never outgrow its slot and spill
  // over the rail. 100cqh = wrapper height; ~96px covers the strip's
  // fixed parts (numbers row, two lane labels, internal gaps); the
  // remainder splits between the two lanes.
  landStripWrap: { flex: 1, minHeight: 0, display: "flex", alignItems: "center", containerType: "size", "--ks-cellh": "clamp(18px, calc((100cqh - 96px) / 2), 72px)" },
  landRail: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)", minHeight: 44 },
  landNameBtn: { flexShrink: 1, minWidth: 56, padding: 0, border: "none", background: "transparent", cursor: "pointer", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "1.125rem", color: "var(--syahi)", lineHeight: 1.1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" },
  // Also the portrait pencil button — a plain 44px icon square.
  landEditBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", display: "grid", placeItems: "center", cursor: "pointer" },
  landBpmBtn: { alignItems: "center" },
  landBpmNum: { flexShrink: 0, display: "inline-block", minWidth: "3ch", textAlign: "center", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "1.5rem", fontWeight: 600, color: "var(--syahi)", lineHeight: 1, marginLeft: "var(--space-2)" },
  landPlayBtn: { flexShrink: 0, minWidth: 92, height: 44, borderRadius: 12, border: "none", background: "var(--clay)", color: "var(--on-clay)", display: "grid", placeItems: "center", cursor: "pointer", marginLeft: "var(--space-2)" },
  landNavWrap: { flexShrink: 0, width: "100%", maxWidth: 430, alignSelf: "center" },
};

export default HomeView;
