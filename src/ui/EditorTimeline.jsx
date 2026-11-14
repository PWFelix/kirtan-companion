import { useEffect, useRef } from "react";
import { timelineMath } from "../data/editorTimeline.js";

/*
 * The editor's TIMELINE view: the beat as a vertical lane, like the Learn
 * highway, read past a fixed line in the MIDDLE. Time runs upward — what is
 * coming sits above the line, what has passed below — so playing scrolls the
 * cells down through it exactly as Learn's notes fall.
 *
 * The centre line IS the cursor. Dragging scrubs (and sounds each cell as it
 * crosses the line); letting go snaps to the nearest cell and puts the cursor
 * there, so the pads below write on the line. Tapping a cell edits it with the
 * same rule as the strip view (BeatEditor's tapCell). Touching it while the
 * preview plays pauses the preview: you have stopped to edit.
 *
 * The bar loops, so the lane never ends: rows past either end are the next or
 * previous cycle, drawn faint, which makes the join between cycles visible.
 * Cells keep one height at every resolution — a 21-cell beat gets taller, not
 * smaller — and heavier rules mark where each beat begins.
 *
 * Drawn on a canvas from one requestAnimationFrame loop; React state only
 * changes when the cursor lands. Same design as Android's EditorTimeline.kt.
 */

const CELL = 44;
const GUTTER = 34;
const HEIGHT = 242; // five and a half cells: the pads stay in reach below
const DRAG_SLOP = 6;
const SOUND_GAP_MS = 45;

export default function EditorTimeline({
  rows, patterns, groups, labels, cursor, cursorLane, selection, previewing, getPhase,
  onCellTap, onLaneTap, onCursor, onPause, onScrubSound,
}) {
  const canvasRef = useRef(null);
  const posRef = useRef(cursor);
  const dragRef = useRef(null);
  // The loop reads the latest props through a ref, so it never restarts on an edit.
  const propsRef = useRef(null);
  useEffect(() => {
    propsRef.current = { patterns, groups, labels, cursor, cursorLane, selection, previewing, getPhase };
  });

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas.getContext("2d");
    const css = getComputedStyle(canvas);
    const color = (name) => css.getPropertyValue(name).trim() || "#888";
    let raf;
    const frame = () => {
      raf = requestAnimationFrame(frame);
      const p = propsRef.current;
      if (!p) return;
      const steps = p.labels.length;
      if (p.previewing && !dragRef.current?.moved) posRef.current = p.getPhase() * steps;
      else if (!dragRef.current?.moved) {
        // At rest the line glides to the cursor, the short way round the loop.
        const target = timelineMath.nearest(p.cursor, posRef.current, steps);
        const d = target - posRef.current;
        posRef.current = Math.abs(d) < 0.01 ? target : posRef.current + d * 0.3;
      }
      draw(ctx, canvas, p, rows, posRef.current, color);
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, [rows]);

  const laneAt = (x, width) => {
    if (x < GUTTER) return null;
    return rows[Math.min(rows.length - 1, Math.floor((x - GUTTER) / ((width - GUTTER) / rows.length)))][0];
  };

  return (
    <div>
      <div style={st.heads}>
        <div style={{ width: GUTTER }} />
        {rows.map(([end, label]) => (
          <button key={end} onClick={() => onLaneTap(end)} aria-pressed={end === cursorLane}
            style={{ ...st.head, color: `var(--lane-${end})`, fontWeight: end === cursorLane ? 800 : 500 }}>
            {label.toUpperCase()}
          </button>
        ))}
      </div>
      <canvas ref={canvasRef} style={st.canvas} data-testid="editor-timeline"
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          dragRef.current = { y0: e.clientY, pos0: posRef.current, moved: false, lastCell: Math.round(posRef.current), lastSound: 0 };
        }}
        onPointerMove={(e) => {
          const d = dragRef.current;
          if (!d) return;
          const dy = e.clientY - d.y0;
          if (!d.moved && Math.abs(dy) < DRAG_SLOP) return;
          if (!d.moved) { d.moved = true; if (propsRef.current.previewing) onPause(); d.pos0 = posRef.current; d.y0 = e.clientY; return; }
          // Content follows the finger: dragging down brings later cells to the line.
          posRef.current = d.pos0 + dy / CELL;
          const cell = Math.round(posRef.current);
          const now = performance.now();
          if (cell !== d.lastCell) {
            d.lastCell = cell;
            // Sound what crosses the line, but not a machine-gun on a fast drag.
            if (now - d.lastSound > SOUND_GAP_MS) { d.lastSound = now; onScrubSound(timelineMath.wrap(cell, propsRef.current.labels.length)); }
          }
        }}
        onPointerUp={(e) => {
          const d = dragRef.current;
          dragRef.current = null;
          if (!d) return;
          const steps = propsRef.current.labels.length;
          if (d.moved) {
            const snapped = Math.round(posRef.current);
            onCursor(timelineMath.wrap(snapped, steps));
            return;
          }
          const r = e.currentTarget.getBoundingClientRect();
          const lane = laneAt(e.clientX - r.left, r.width);
          if (!lane || steps <= 0) return;
          const row = timelineMath.rowAt(posRef.current, r.height / 2 - (e.clientY - r.top), CELL);
          if (propsRef.current.previewing) onPause();
          onCellTap(lane, timelineMath.wrap(row, steps));
        }}
        onPointerCancel={() => { dragRef.current = null; }} />
    </div>
  );
}

function draw(ctx, canvas, p, rows, pos, color) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.globalAlpha = 1;
  ctx.fillStyle = color("--head-worn");
  ctx.fillRect(0, 0, w, h);
  const steps = p.labels.length;
  if (!steps) return;
  const colW = (w - GUTTER) / rows.length;
  const cy = h / 2;
  const half = h / 2 / CELL;
  const lineCycle = timelineMath.cycleOf(Math.round(pos), steps);
  const starts = new Set();
  p.groups.reduce((a, g) => { starts.add(a); return a + g; }, 0);
  const clay = color("--clay"), rule = color("--rule"), soft = color("--syahi-soft");

  // The band the line sits in: the cell the pads will write to.
  ctx.globalAlpha = 0.10; ctx.fillStyle = clay; ctx.fillRect(0, cy - CELL / 2, w, CELL);

  for (let row = Math.floor(pos - half - 1); row <= Math.ceil(pos + half + 1); row++) {
    const idx = timelineMath.wrap(row, steps);
    const y = cy - (row - pos) * CELL; // time runs UP
    const faint = timelineMath.cycleOf(row, steps) !== lineCycle;
    const a = faint ? 0.35 : 1;
    if (p.selection && !faint && idx >= p.selection[0] && idx <= p.selection[1]) {
      ctx.globalAlpha = 0.18; ctx.fillStyle = clay; ctx.fillRect(GUTTER, y - CELL / 2, w - GUTTER, CELL);
    }
    // A beat begins at a cell's lower edge: a heavier rule there.
    const beat = starts.has(idx);
    ctx.globalAlpha = (beat ? 0.55 : 0.6) * a;
    ctx.strokeStyle = beat ? soft : rule; ctx.lineWidth = beat ? 2 : 1;
    ctx.beginPath(); ctx.moveTo(beat ? 0 : GUTTER, y + CELL / 2); ctx.lineTo(w, y + CELL / 2); ctx.stroke();
    const label = p.labels[idx];
    if (label) {
      ctx.globalAlpha = (label === "·" ? 0.5 : 0.95) * a;
      ctx.fillStyle = soft;
      ctx.font = `${beat ? 700 : 400} ${beat ? 13 : 11}px system-ui, sans-serif`;
      ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillText(label, GUTTER / 2, y);
    }
    rows.forEach(([end], i) => {
      const cx = GUTTER + colW * i + colW / 2;
      const v = p.patterns[end]?.[idx] ?? null;
      ctx.globalAlpha = a;
      const lane = color(`--lane-${end}`);
      if (v === "O") {
        ctx.fillStyle = lane; ctx.beginPath(); ctx.arc(cx, y, CELL * 0.26, 0, Math.PI * 2); ctx.fill();
      } else if (v) {
        const r = CELL * 0.2;
        ctx.strokeStyle = lane; ctx.lineWidth = 2.6; ctx.lineCap = "round";
        ctx.beginPath(); ctx.moveTo(cx - r, y - r); ctx.lineTo(cx + r, y + r); ctx.moveTo(cx - r, y + r); ctx.lineTo(cx + r, y - r); ctx.stroke();
      } else {
        ctx.fillStyle = rule; ctx.beginPath(); ctx.arc(cx, y, 2, 0, Math.PI * 2); ctx.fill();
      }
      if (!faint && idx === p.cursor && end === p.cursorLane && Math.abs(row - pos) < 0.5) {
        ctx.globalAlpha = 1; ctx.strokeStyle = clay; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.roundRect(GUTTER + colW * i + 4, y - CELL / 2 + 3, colW - 8, CELL - 6, 8); ctx.stroke();
      }
    });
  }
  // The line itself, across everything.
  ctx.globalAlpha = 1; ctx.strokeStyle = clay; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(0, cy + CELL / 2); ctx.lineTo(w, cy + CELL / 2); ctx.stroke();
}

const st = {
  heads: { display: "flex", alignItems: "center", marginBottom: 4 },
  head: { flex: 1, background: "none", border: "none", font: "inherit", fontSize: 11, letterSpacing: "0.07em", padding: "6px 0", cursor: "pointer", borderRadius: 8 },
  canvas: { display: "block", width: "100%", height: HEIGHT, borderRadius: 12, touchAction: "none", cursor: "ns-resize" },
};
