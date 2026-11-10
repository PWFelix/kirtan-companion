import { useEffect, useMemo, useRef, useState } from "react";
import * as sh from "../ui/styles.js";
import { t } from "../i18n/index.js";
import { BackIcon, ChevronRightIcon, PlayIcon, PauseIcon } from "../ui/icons.jsx";
import { COLUMNS, TRAIL_STEPS, columnsFor, highwayFrame, lookaheadSteps } from "../data/highway.js";

// Left-handed mirror. Only Learn reads it, so it lives here, not in a hook.
const LEFT_KEY = "kirtan-learn-left-handed";
const loadLeft = () => { try { return localStorage.getItem(LEFT_KEY) === "1"; } catch { return false; } };
import { cpqFor } from "../data/meter.js";

/**
 * LearnView — "falling strokes": the loaded beat as a highway.
 *
 * Each stroke drops down its own column (one per hand and stroke, headed by
 * its bol) and lands on the hit line the instant the drum sounds it, so a
 * learner can watch what's coming and play along on a real mridanga.
 * Watch-only: no scoring — the point is to follow the drum, not tap a phone.
 * Same design as the apps' Learn tab (LearnScreen.kt / LearnView.swift).
 *
 * It drives the SAME transport as Home (beat, tempo, play state shared), so a
 * learner can pick a beat on Home, slow it down here, and go back. The header
 * chevrons step through beats the way Home's do (App.cycleBeat).
 */
function LearnView({ transport, beat, onCycle, nav }) {
  const { playing, ready, bpm, nudgeBpm, togglePlay, getPhase } = transport;
  const [leftHanded, setLeftHanded] = useState(loadLeft);
  const columns = useMemo(() => columnsFor(leftHanded), [leftHanded]);
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
          <h1 style={st.title}>{beat.name}</h1>
          <span style={st.subtitle}>{t("learn.subtitle")}</span>
        </div>
        <button onClick={() => onCycle(1)} style={sh.backBtn} aria-label={t("common.nextBeat")}>
          <ChevronRightIcon />
        </button>
      </header>

      <button onClick={flipHands} style={st.hands} aria-pressed={leftHanded}>
        {leftHanded ? t("learn.leftHanded") : t("learn.rightHanded")}
      </button>

      <HighwayCanvas beat={beat} bpm={bpm} playing={playing} getPhase={getPhase}
        columns={columns} />

      <div style={st.controls}>
        {/* Learning wants SLOWER more than anything: 5 BPM steps. */}
        <button onClick={() => nudgeBpm(-5)} style={st.stepBtn} aria-label={t("common.slower")}>−</button>
        <span style={st.bpm}>{bpm} BPM</span>
        <button onClick={() => nudgeBpm(5)} style={st.stepBtn} aria-label={t("common.faster")}>+</button>
        <span style={{ flex: 1 }} />
        <button onClick={() => togglePlay(beat)} disabled={!ready} style={st.play}>
          {playing ? <PauseIcon size={18} /> : <PlayIcon size={18} />}
          {playing ? t("common.pause") : t("common.play")}
        </button>
      </div>
      {nav}
    </div>
  );
}

/**
 * The highway. Redraws every animation frame while playing, reading the
 * engine's phase each frame (as BeatStrip's playhead does), and holds the
 * first bar at rest while stopped so the learner can read what's coming.
 * Colours are read from the design tokens, so it re-tints with the theme.
 */
function HighwayCanvas({ beat, bpm, playing, getPhase, columns }) {
  const ref = useRef(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const css = getComputedStyle(document.documentElement);
    const tok = (name) => css.getPropertyValue(name).trim();
    const c = {
      head: tok("--head"), worn: tok("--head-worn"), rule: tok("--rule"),
      syahi: tok("--syahi"), soft: tok("--syahi-soft"),
      dayan: tok("--lane-dayan"), bayan: tok("--lane-bayan"),
      font: tok("--font-body") || "sans-serif",
    };
    let raf = 0;

    const paint = (ctx, w, h) => {
      const position = playing ? (getPhase() || 0) * beat.steps : 0;
      const lookahead = lookaheadSteps(bpm, cpqFor(beat));
      const frame = highwayFrame(beat, position, lookahead);
      const gap = 6, labelH = 34;
      const colW = (w - gap * (columns.length - 1)) / columns.length;
      const colOf = (n) => columns.indexOf(COLUMNS[n.column]);
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

      // Notes: open = filled disc, closed = ring (the strip's language). One
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
        if (COLUMNS[n.column].stroke === "O") {
          ctx.fillStyle = laneColor(ci); circle(cx, y, r); ctx.fill();
        } else {
          ctx.strokeStyle = laneColor(ci); ctx.lineWidth = 4; circle(cx, y, r - 2); ctx.stroke();
        }
        ctx.globalAlpha = 1;
      }

      // Targets on the hit line, with each column's bol underneath.
      ctx.textAlign = "center";
      columns.forEach((col, i) => {
        const cx = colX(i) + colW / 2;
        const hot = playing && frame.notes.some((n) => colOf(n) === i && n.rel <= 0 && n.rel > -0.5);
        ctx.globalAlpha = hot ? 1 : 0.35;
        ctx.strokeStyle = laneColor(i); ctx.lineWidth = hot ? 4 : 2;
        circle(cx, hitY, r + 4); ctx.stroke();
        ctx.globalAlpha = 1;
        ctx.fillStyle = c.syahi; ctx.font = `600 15px ${c.font}`;
        ctx.fillText(col.bol, cx, h - labelH + 18);
        ctx.fillStyle = c.soft; ctx.font = `9px ${c.font}`;
        ctx.fillText(`${col.lane === "dayan" ? "Dayan" : "Bayan"} · ${col.stroke === "O" ? t("stroke.open") : t("stroke.closed")}`, cx, h - 3);
      });

      if (!playing) {
        const msg = t("learn.pressPlay");
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
  }, [beat, bpm, playing, getPhase, columns]);

  return <canvas ref={ref} style={st.canvas} role="img" aria-label="Falling strokes" />;
}

const st = {
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
  play: {
    width: 140, minHeight: 48, borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)",
    display: "flex", alignItems: "center", justifyContent: "center", gap: 8,
    fontFamily: "var(--font-body)", fontWeight: 700, fontSize: 16, cursor: "pointer",
  },
};

export default LearnView;
