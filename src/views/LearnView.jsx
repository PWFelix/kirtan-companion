import { useEffect, useMemo, useRef, useState } from "react";
import * as sh from "../ui/styles.js";
import BeatPickerSheet from "../ui/BeatPickerSheet.jsx";
import { t } from "../i18n/index.js";
import { BackIcon, ChevronRightIcon, PlayIcon, PauseIcon, StairsIcon } from "../ui/icons.jsx";
import { CLIMB_STEPS, CLIMB_INTERVALS, climbNext, climbStepOf, climbIntervalOf, climbClock } from "../data/hillClimb.js";
import { tempoRange } from "../data/meter.js";
import { TRAIL_STEPS, baseColumns, columnsForInstrument, hasPart, highwayFrame, lookaheadSteps } from "../data/highway.js";

// Left-handed mirror. Only Learn reads it, so it lives here, not in a hook.
const LEFT_KEY = "kirtan-learn-left-handed";
const loadLeft = () => { try { return localStorage.getItem(LEFT_KEY) === "1"; } catch { return false; } };
// Which part is being learned: the mridanga or the karatalas. Remembered, so a
// kartal player lands back on their own part.
const INSTRUMENT_KEY = "kirtan-learn-instrument";
const loadInstrument = () => {
  try { return localStorage.getItem(INSTRUMENT_KEY) === "kartal" ? "kartal" : "mrdanga"; } catch { return "mrdanga"; }
};
import { cpqFor } from "../data/meter.js";
// Hill climb: the last ACCEPTED choice is remembered; on/off is per session, so a
// lesson never starts speeding up unasked.
const CLIMB_KEY = "kirtan-learn-hill-climb";
const loadClimb = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(CLIMB_KEY) || "{}");
    return { step: climbStepOf(raw.step), every: climbIntervalOf(raw.every) };
  } catch { return { step: climbStepOf(), every: climbIntervalOf() }; }
};
const intervalLabel = (s) => (s < 60 ? t("learn.climbSec", { n: s }) : t("learn.climbMin", { n: s / 60 }));

/**
 * LearnView — "falling strokes": the loaded beat as a highway.
 *
 * Each stroke drops down its hand's column (bayan and dayan, or the karatalas'
 * one), drawn as its stroke's shape with its bol beside it, headed by
 * its bol) and lands on the hit line the instant the drum sounds it, so a
 * learner can watch what's coming and play along on a real mridanga.
 * Watch-only: no scoring — the point is to follow the drum, not tap a phone.
 * Same design as the apps' Learn tab (LearnScreen.kt / LearnView.swift).
 *
 * It drives the SAME transport as Home (beat, tempo, play state shared), so a
 * learner can pick a beat on Home, slow it down here, and go back. The header
 * chevrons step through beats the way Home's do (App.cycleBeat).
 */
function LearnView({ transport, beat, onCycle, library, beatId, onSelect, nav }) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const { playing, ready, bpm, nudgeBpm, changeBpm, togglePlay, getPhase, brahmaloka } = transport;

  // ── Hill climb ──
  const [climb, setClimb] = useState(loadClimb);
  const [climbOn, setClimbOn] = useState(false);
  const [climbLeft, setClimbLeft] = useState(climb.every);
  const [climbSheet, setClimbSheet] = useState(false);
  const ceiling = tempoRange(brahmaloka).max;
  const climbTop = bpm >= ceiling;
  const live = useRef({});
  const leftRef = useRef(climb.every);
  useEffect(() => { live.current = { bpm, ceiling, changeBpm }; });
  // The countdown runs on PLAYING time: pausing holds it, and Play carries on.
  useEffect(() => {
    if (!climbOn || !playing) return;
    const id = setInterval(() => {
      const { bpm: now, ceiling: top } = live.current;
      if (now >= top) return;
      // Counted in a ref, not a state updater: updaters must stay pure (strict
      // mode runs them twice), and this one changes the tempo.
      const left = leftRef.current - 1;
      if (left > 0) leftRef.current = left;
      else {
        live.current.changeBpm(climbNext(now, climb.step, top));
        leftRef.current = climb.every;
      }
      setClimbLeft(leftRef.current);
    }, 1000);
    return () => clearInterval(id);
  }, [climbOn, playing, climb]);
  const acceptClimb = (next) => {
    setClimb(next);
    try { localStorage.setItem(CLIMB_KEY, JSON.stringify(next)); } catch { /* private mode: session only */ }
    leftRef.current = next.every;
    setClimbLeft(next.every);
    setClimbOn(true);
    setClimbSheet(false);
  };
  const [leftHanded, setLeftHanded] = useState(loadLeft);
  const [instrument, setInstrument] = useState(loadInstrument);
  const columns = useMemo(() => columnsForInstrument(instrument, leftHanded), [instrument, leftHanded]);
  const pickInstrument = (next) => {
    setInstrument(next);
    try { localStorage.setItem(INSTRUMENT_KEY, next); } catch { /* private mode: session only */ }
  };
  const flipHands = () => {
    const next = !leftHanded;
    setLeftHanded(next);
    try { localStorage.setItem(LEFT_KEY, next ? "1" : "0"); } catch { /* private mode: session only */ }
  };

  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        <button onClick={() => onCycle(-1)} style={sh.backBtn} aria-label={t("common.prevBeat")}>
          <BackIcon />
        </button>
        <div style={st.titleWrap}>
          {/* Tap the name for the quick-pick: built-ins, your beats, your lists. */}
          <button onClick={() => setPickerOpen(true)} style={st.titleBtn}
            aria-haspopup="dialog" aria-label={t("home.chooseBeat")}>
            <h1 style={st.title}>{beat.name} <span style={st.caret}>▾</span></h1>
          </button>
          <span style={st.subtitle}>{t("learn.subtitle")}</span>
        </div>
        <button onClick={() => onCycle(1)} style={sh.backBtn} aria-label={t("common.nextBeat")}>
          <ChevronRightIcon />
        </button>
      </header>

      {pickerOpen && (
        <BeatPickerSheet library={library} beatId={beatId} onSelect={onSelect}
          onClose={() => setPickerOpen(false)} />
      )}

      <div style={st.segments} role="radiogroup" aria-label={t("learn.instrument")}>
        {["mrdanga", "kartal"].map((id) => (
          <button key={id} role="radio" aria-checked={instrument === id} onClick={() => pickInstrument(id)}
            style={{ ...st.segment, ...(instrument === id ? st.segmentOn : null) }}>
            {t(`learn.${id}`)}
          </button>
        ))}
      </div>

      {/* Handedness is about which side the drum's heads sit; karatalas have none. */}
      {instrument === "mrdanga" && (
        <button onClick={flipHands} style={st.hands} aria-pressed={leftHanded}>
          {leftHanded ? t("learn.leftHanded") : t("learn.rightHanded")}
        </button>
      )}

      <HighwayCanvas beat={beat} bpm={bpm} playing={playing} getPhase={getPhase}
        columns={columns} base={baseColumns(instrument)}
        emptyMsg={hasPart(beat, instrument) ? null : t("learn.noKartal")} />

      <div style={st.controls}>
        {/* Learning wants SLOWER more than anything: 5 BPM steps. */}
        <button onClick={() => nudgeBpm(-5)} style={st.stepBtn} aria-label={t("common.slower")}>−</button>
        <span style={st.bpm}>{bpm} BPM</span>
        <button onClick={() => nudgeBpm(5)} style={st.stepBtn} aria-label={t("common.faster")}>+</button>
        <span style={{ flex: 1 }} />
        {/* Off: opens the choice (last one pre-selected). On: one tap turns it off. */}
        <button onClick={() => (climbOn ? setClimbOn(false) : setClimbSheet(true))}
          style={{ ...st.climbBtn, ...(climbOn ? st.climbOn : null) }}
          aria-pressed={climbOn} aria-label={climbOn ? t("learn.climbOff") : t("learn.climb")}
          title={climbOn ? t("learn.climbOff") : t("learn.climb")}>
          <StairsIcon />
        </button>
        <button onClick={() => togglePlay(beat)} disabled={!ready} style={st.play}>
          {playing ? <PauseIcon size={18} /> : <PlayIcon size={18} />}
          {playing ? t("common.pause") : t("common.play")}
        </button>
      </div>
      {climbOn && (
        <p style={st.climbStatus} aria-live="polite">
          {climbTop ? t("learn.climbTop", { bpm })
            : playing ? t("learn.climbStatus", { n: climb.step, every: intervalLabel(climb.every), left: climbClock(climbLeft) })
            : t("learn.climbPaused", { n: climb.step, every: intervalLabel(climb.every) })}
        </p>
      )}
      {climbSheet && <HillClimbSheet initial={climb} onCancel={() => setClimbSheet(false)} onAccept={acceptClimb} />}
      {nav}
    </div>
  );
}

/** The hill-climb choice: N BPM every X. Start turns it on; Cancel leaves it off. */
function HillClimbSheet({ initial, onCancel, onAccept }) {
  const [step, setStep] = useState(initial.step);
  const [every, setEvery] = useState(initial.every);
  const chip = (on) => ({ ...st.chip, ...(on ? st.chipOn : null) });
  return (
    <div style={sh.sheetBackdrop} onClick={onCancel}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("learn.climb")} onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("learn.climb")}</h2>
          <button onClick={onCancel} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <p style={st.climbDesc}>{t("learn.climbDesc")}</p>
        <div style={st.chipLabel}>{t("learn.climbStep")}</div>
        <div style={st.chips} role="radiogroup" aria-label={t("learn.climbStep")}>
          {CLIMB_STEPS.map((n) => (
            <button key={n} role="radio" aria-checked={n === step} onClick={() => setStep(n)} style={chip(n === step)}>
              {t("learn.climbBpm", { n })}
            </button>
          ))}
        </div>
        <div style={st.chipLabel}>{t("learn.climbEvery")}</div>
        <div style={st.chips} role="radiogroup" aria-label={t("learn.climbEvery")}>
          {CLIMB_INTERVALS.map((s) => (
            <button key={s} role="radio" aria-checked={s === every} onClick={() => setEvery(s)} style={chip(s === every)}>
              {intervalLabel(s)}
            </button>
          ))}
        </div>
        <div style={st.sheetActions}>
          <button onClick={onCancel} style={st.cancel}>{t("common.cancel")}</button>
          <button onClick={() => onAccept({ step, every })} style={{ ...st.play, flex: 1, width: "auto" }}>
            <StairsIcon size={18} /> {t("learn.climbStart")}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * The highway. Redraws every animation frame while playing, reading the
 * engine's phase each frame (as BeatStrip's playhead does), and holds the
 * first bar at rest while stopped so the learner can read what's coming.
 * Colours are read from the design tokens, so it re-tints with the theme.
 */
function HighwayCanvas({ beat, bpm, playing, getPhase, columns, base, emptyMsg }) {
  const ref = useRef(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const css = getComputedStyle(document.documentElement);
    const tok = (name) => css.getPropertyValue(name).trim();
    const c = {
      head: tok("--head"), worn: tok("--head-worn"), rule: tok("--rule"),
      syahi: tok("--syahi"), soft: tok("--syahi-soft"),
      dayan: tok("--lane-dayan"), bayan: tok("--lane-bayan"), kartal: tok("--lane-kartal"),
      font: tok("--font-body") || "sans-serif",
    };
    let raf = 0;

    const paint = (ctx, w, h) => {
      const position = playing ? (getPhase() || 0) * beat.steps : 0;
      const lookahead = lookaheadSteps(bpm, cpqFor(beat));
      const frame = highwayFrame(beat, position, lookahead, base);
      const gap = 6, labelH = 34;
      const colW = (w - gap * (columns.length - 1)) / columns.length;
      const colOf = (n) => columns.indexOf(base[n.column]);
      const hitY = h - labelH - 28;
      const pxPerStep = hitY / lookahead;
      const r = Math.min(20, Math.max(7, Math.min(colW, pxPerStep) * 0.36));
      const colX = (i) => i * (colW + gap);
      const yFor = (rel) => hitY - rel * pxPerStep;
      const laneColor = (i) => c[columns[i].lane];
      const circle = (x, y, rad) => { ctx.beginPath(); ctx.arc(x, y, rad, 0, Math.PI * 2); };
      const hline = (y, color, width) => {
        ctx.strokeStyle = color; ctx.lineWidth = width;
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
      };

      // Column tracks.
      ctx.fillStyle = c.worn;
      columns.forEach((_, i) => { ctx.beginPath(); ctx.roundRect(colX(i), 0, colW, h - labelH, 10); ctx.fill(); });

      // Beat lines; sam, the bar's first, bolder.
      for (const l of frame.lines) {
        const y = yFor(l.rel);
        if (y < 0 || y > hitY) continue;
        ctx.globalAlpha = l.sam ? 0.55 : 1;
        hline(y, l.sam ? c.soft : c.rule, l.sam ? 2.5 : 1);
        ctx.globalAlpha = 1;
      }
      hline(hitY, c.syahi, 3);

      // Notes: open = filled disc, closed = X (the strip's language). One
      // that has just landed glows its column's target.
      for (const n of frame.notes) {
        const ci = colOf(n);
        const cx = colX(ci) + colW / 2, y = yFor(n.rel);
        if (y < -r || y > hitY + r * 3) continue;
        const landed = n.rel <= 0;
        const alpha = landed ? Math.min(1, Math.max(0, 1 + n.rel / TRAIL_STEPS)) : 1;
        if (playing && landed && n.rel > -0.6) {
          ctx.globalAlpha = 0.25 * alpha; ctx.fillStyle = laneColor(ci);
          circle(cx, hitY, r * 2); ctx.fill();
        }
        ctx.globalAlpha = alpha;
        if (n.stroke === "O") {
          ctx.fillStyle = laneColor(ci); circle(cx, y, r); ctx.fill();
        } else {
          // An X inscribed in the disc's bounds, round-capped like a pen stroke.
          const d = (r - 2) * 0.75;
          ctx.strokeStyle = laneColor(ci); ctx.lineWidth = 4; ctx.lineCap = "round";
          ctx.beginPath();
          ctx.moveTo(cx - d, y - d); ctx.lineTo(cx + d, y + d);
          ctx.moveTo(cx + d, y - d); ctx.lineTo(cx - d, y + d);
          ctx.stroke();
        }
        // Its bol beside it: one column per hand, so the stroke is named here.
        ctx.fillStyle = c.syahi; ctx.font = `600 13px ${c.font}`;
        ctx.textAlign = "left"; ctx.textBaseline = "middle";
        // (Not once it has passed the line: it would run into the hand's label.)
        if (y <= hitY) ctx.fillText(n.bol, cx + r + 6, y);
        ctx.textBaseline = "alphabetic";
        ctx.globalAlpha = 1;
      }

      // Targets on the hit line, with the hand (lane) underneath.
      ctx.textAlign = "center";
      columns.forEach((col, i) => {
        const cx = colX(i) + colW / 2;
        const hot = playing && frame.notes.some((n) => colOf(n) === i && n.rel <= 0 && n.rel > -0.5);
        ctx.globalAlpha = hot ? 1 : 0.35;
        ctx.strokeStyle = laneColor(i); ctx.lineWidth = hot ? 4 : 2;
        circle(cx, hitY, r + 4); ctx.stroke();
        ctx.globalAlpha = 1;
        ctx.fillStyle = c.syahi; ctx.font = `600 15px ${c.font}`;
        ctx.fillText(LANE_NAMES[col.lane], cx, h - labelH + 18);
      });

      // A beat with nothing for this instrument says so, rather than leaving
      // an empty highway that reads as broken.
      if (!playing || emptyMsg) {
        const msg = emptyMsg ?? t("learn.pressPlay");
        ctx.font = `12px ${c.font}`;
        const tw = ctx.measureText(msg).width, y = hitY - pxPerStep / 2;
        ctx.globalAlpha = 0.92; ctx.fillStyle = c.head;
        ctx.beginPath(); ctx.roundRect(w / 2 - tw / 2 - 12, y - 14, tw + 24, 28, 12); ctx.fill();
        ctx.globalAlpha = 1; ctx.fillStyle = c.soft; ctx.textBaseline = "middle";
        ctx.fillText(msg, w / 2, y); ctx.textBaseline = "alphabetic";
      }
    };

    const draw = () => {
      const dpr = window.devicePixelRatio || 1;
      const w = canvas.clientWidth, h = canvas.clientHeight;
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
      }
      const ctx = canvas.getContext("2d");
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);
      paint(ctx, w, h);
      if (playing) raf = requestAnimationFrame(draw);
    };

    draw();
    const onResize = () => { if (!playing) draw(); };
    window.addEventListener("resize", onResize);
    return () => { cancelAnimationFrame(raf); window.removeEventListener("resize", onResize); };
  }, [beat, bpm, playing, getPhase, columns, base, emptyMsg]);

  return <canvas ref={ref} style={st.canvas} role="img" aria-label="Falling strokes" />;
}

const LANE_NAMES = { dayan: "Dayan", bayan: "Bayan", kartal: "Kartal" };

const st = {
  segments: {
    flexShrink: 0, display: "flex", gap: 4, padding: 4, borderRadius: 14,
    background: "var(--head-worn)",
  },
  segment: {
    flex: 1, minHeight: 38, borderRadius: 10, border: "none", background: "transparent",
    color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontWeight: 700, fontSize: 14, cursor: "pointer",
  },
  segmentOn: { background: "var(--head)", color: "var(--syahi)", boxShadow: "0 0 0 1px var(--rule)" },
  titleBtn: { background: "none", border: "none", padding: 0, cursor: "pointer", color: "inherit", maxWidth: "100%" },
  caret: { fontSize: "0.6em", color: "var(--ink-secondary)" },
  titleWrap: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", alignItems: "center" },
  title: { ...sh.subTitle, fontSize: 22, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: "100%" },
  subtitle: { fontFamily: "var(--font-body)", fontSize: 12, color: "var(--syahi-soft)" },
  hands: {
    flexShrink: 0, minHeight: 38, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent",
    color: "var(--syahi)", fontFamily: "var(--font-body)", fontWeight: 700, fontSize: 14, cursor: "pointer",
  },
  canvas: { flex: 1, minHeight: 0, width: "100%", display: "block" },
  controls: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)" },
  stepBtn: { ...sh.backBtn, fontSize: 22, fontFamily: "var(--font-body)" },
  bpm: { fontFamily: "var(--font-body)", fontWeight: 600, fontSize: 17, color: "var(--syahi)", minWidth: 80, textAlign: "center" },
  climbBtn: {
    ...sh.backBtn, color: "var(--syahi-soft)", display: "grid", placeItems: "center",
  },
  climbOn: { background: "var(--clay)", borderColor: "var(--clay)", color: "var(--on-clay)" },
  climbStatus: {
    margin: 0, textAlign: "center", fontFamily: "var(--font-body)", fontSize: 12,
    fontWeight: 600, color: "var(--clay)",
  },
  climbDesc: { margin: "var(--space-2) 0 0", fontFamily: "var(--font-body)", fontSize: 13, color: "var(--syahi-soft)" },
  chipLabel: {
    marginTop: "var(--space-4)", marginBottom: "var(--space-2)", fontFamily: "var(--font-body)", fontSize: 11,
    fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)",
  },
  chips: { display: "flex", flexWrap: "wrap", gap: 8 },
  chip: {
    minHeight: 36, padding: "0 14px", borderRadius: 999, border: "1px solid var(--rule)", background: "transparent",
    color: "var(--syahi)", fontFamily: "var(--font-body)", fontWeight: 700, fontSize: 12, cursor: "pointer",
  },
  chipOn: { background: "var(--clay)", borderColor: "var(--clay)", color: "var(--on-clay)" },
  sheetActions: { display: "flex", gap: 8, marginTop: "var(--space-5)" },
  cancel: {
    flex: 1, minHeight: 48, borderRadius: 14, border: "1px solid var(--rule)", background: "transparent",
    color: "var(--syahi)", fontFamily: "var(--font-body)", fontWeight: 600, fontSize: 15, cursor: "pointer",
  },
  play: {
    width: 124, minHeight: 48, borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)",
    display: "flex", alignItems: "center", justifyContent: "center", gap: 8,
    fontFamily: "var(--font-body)", fontWeight: 700, fontSize: 16, cursor: "pointer",
  },
};

export default LearnView;
