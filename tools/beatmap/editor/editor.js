// The beat-map correction editor.
//
// One horizontal timeline, drawn on a canvas, top to bottom:
//   ruler     beat lines numbered within the cycle; sam beats labelled with
//             their cycle number. Drag a beat to fix the grid; Alt-click to
//             insert or delete one.
//   tempo     the local BPM curve, so a tracker hiccup shows up as a spike.
//   envelope  the separated mridanga's dayan (up) and bayan (down) attacks,
//             so you can SEE a stroke the draft missed.
//   lanes     Ta, Te, Khe, Ge. Click a cell to add, change or remove a stroke.
//   overview  the whole song; click to jump.
//
// Corrections are cheap to undo (every edit snapshots the grid and notes),
// and nothing is written until you save, which also keeps a .bak of the last
// saved map on disk.

import {
  STROKES, Player, loadMap, loadEnvelope, timedNotes, nearestCell, beatAt,
  beatEnd, cellTime, beatInCycle, cycleOf, bpmAt, lowerBound, fmtTime,
} from "./common.js";

// Lanes, top to bottom: dayan above bayan, open strokes on the outside.
const ROWS = ["Ta", "Te", "Khe", "Ge"].map((bol) => STROKES.find((k) => k.bol === bol));

const GUTTER = 104;
const RULER = { y: 0, h: 38 };
const TEMPO = { y: 38, h: 46 };
const ENV = { y: 84, h: 96 };
const LANES = { y: 180, rowH: 54 };
const OVERVIEW_H = 34;

const UNSURE = 0.5;

const $ = (id) => document.getElementById(id);
const cv = $("cv");
const ctx = cv.getContext("2d");

let m, env, player;
let byKey = new Map();   // "b:c:lane" -> note
let timed = [];          // notes with .t, time-sorted
const undo = [], redo = [];
let dirty = false;

const view = { start: 0, pps: 160 };  // left edge (s) and pixels per second
let lastUserScroll = 0;
let hover = null;        // {x, y} in CSS px
let drag = null;         // {beat, moved}

// ---------------------------------------------------------------- state

const keyOf = (n) => `${n.b}:${n.c}:${n.lane}`;

function reindex() {
  byKey = new Map(m.notes.map((n) => [keyOf(n), n]));
  timed = timedNotes(m);
  player?.setNotes(timed);
  const unsure = m.notes.filter((n) => n.conf !== undefined && n.conf < UNSURE).length;
  $("counts").textContent =
    `${m.beats.length} beats · ${m.notes.length} strokes · ${unsure} unsure`;
}

function snapshot() {
  undo.push(JSON.stringify({ beats: m.beats, notes: m.notes, grid: m.grid }));
  if (undo.length > 80) undo.shift();
  redo.length = 0;
}

function restore(from, to) {
  if (!from.length) return;
  to.push(JSON.stringify({ beats: m.beats, notes: m.notes, grid: m.grid }));
  const s = JSON.parse(from.pop());
  m.beats = s.beats; m.notes = s.notes; m.grid = s.grid;
  reindex();
  setDirty(true);
}

function setDirty(d) {
  dirty = d;
  $("save").textContent = d ? "Save" : "Saved";
  $("save").classList.toggle("dirty", d);
}

/** Re-place every note on a changed beat list, keeping its time. */
function resnap(oldBeats) {
  const cpb = m.grid.cellsPerBeat;
  const seen = new Map();
  for (const n of m.notes) {
    const t = cellTime(oldBeats, cpb, n.b, n.c);
    const cell = nearestCell(m.beats, cpb, t);
    if (!cell) continue;
    const moved = { ...n, b: cell.b, c: cell.c };
    const k = keyOf(moved);
    // Two notes on one hand landing in one cell: keep the surer one.
    const prev = seen.get(k);
    if (!prev || (prev.conf ?? 1) < (moved.conf ?? 1)) seen.set(k, moved);
  }
  m.notes = [...seen.values()];
}

// ---------------------------------------------------------------- edits

function toggleStroke(t, row) {
  const cell = nearestCell(m.beats, m.grid.cellsPerBeat, t);
  if (!cell) return;
  const { lane, s } = ROWS[row];
  snapshot();
  const k = `${cell.b}:${cell.c}:${lane}`;
  const cur = byKey.get(k);
  if (cur && cur.s === s && cur.conf === undefined) {
    m.notes = m.notes.filter((n) => n !== cur);          // remove
  } else if (cur) {
    cur.s = s; delete cur.conf;                          // change or confirm
  } else {
    m.notes.push({ b: cell.b, c: cell.c, lane, s });     // add
  }
  reindex();
  setDirty(true);
}

function insertBeat(t) {
  const b = beatAt(m.beats, t);
  if (b >= 0 && (t - m.beats[b] < 0.08 || beatEnd(m.beats, b) - t < 0.08)) return;
  snapshot();
  const old = m.beats.slice();
  m.beats.splice(b + 1, 0, +t.toFixed(4));
  if (b + 1 <= m.grid.samBeat) m.grid.samBeat += 1;
  resnap(old);
  reindex();
  setDirty(true);
}

function deleteBeat(b) {
  if (m.beats.length < 3) return;
  snapshot();
  const old = m.beats.slice();
  m.beats.splice(b, 1);
  if (b < m.grid.samBeat) m.grid.samBeat -= 1;
  resnap(old);
  reindex();
  setDirty(true);
}

function setSam(t) {
  const b = beatAt(m.beats, t);
  if (b < 0) return;
  const nb = b + 1 < m.beats.length && m.beats[b + 1] - t < t - m.beats[b] ? b + 1 : b;
  snapshot();
  m.grid.samBeat = nb % m.grid.beatsPerCycle;
  setDirty(true);
}

async function save() {
  const out = { ...m, notes: [...m.notes].sort((x, y) => x.b - y.b || x.c - y.c || (x.lane < y.lane ? -1 : 1)) };
  const res = await fetch("/api/save", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(out),
  });
  if (res.ok) setDirty(false);
  else alert("Save failed: " + (await res.json()).error);
}

// ---------------------------------------------------------------- geometry

const W = () => cv.clientWidth;
const H = () => cv.clientHeight;
const xOf = (t) => GUTTER + (t - view.start) * view.pps;
const tOf = (x) => view.start + (x - GUTTER) / view.pps;
const visibleSecs = () => (W() - GUTTER) / view.pps;
const lanesBottom = () => LANES.y + LANES.rowH * ROWS.length;
const overviewY = () => H() - OVERVIEW_H;

function rowAt(y) {
  const r = Math.floor((y - LANES.y) / LANES.rowH);
  return r >= 0 && r < ROWS.length ? r : -1;
}

function beatNearX(x, px = 6) {
  const t = tOf(x);
  const b = beatAt(m.beats, t);
  for (const cand of [b, b + 1]) {
    if (cand >= 0 && cand < m.beats.length && Math.abs(xOf(m.beats[cand]) - x) <= px) return cand;
  }
  return -1;
}

function clampView() {
  const dur = player.duration;
  view.start = Math.max(-1, Math.min(view.start, dur - visibleSecs() * 0.5));
}

function zoom(f, aroundX = GUTTER + (W() - GUTTER) / 2) {
  const t = tOf(aroundX);
  view.pps = Math.max(30, Math.min(900, view.pps * f));
  view.start = t - (aroundX - GUTTER) / view.pps;
  clampView();
}

// ---------------------------------------------------------------- drawing

const C = {
  bg: "#1d1915", row: "#231e19", rowAlt: "#201b16", line: "#3a332b",
  cell: "#2c261f", beat: "#5a5045", sam: "#e08a4f", ink: "#efe6d8",
  muted: "#a39684", dayan: "#e07a43", bayan: "#b7835a", play: "#f5ede0",
};

function draw() {
  const dpr = window.devicePixelRatio || 1;
  if (cv.width !== W() * dpr || cv.height !== H() * dpr) {
    cv.width = W() * dpr; cv.height = H() * dpr;
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  // Lanes take whatever height the window gives them: bigger targets, fewer misclicks.
  LANES.rowH = Math.max(44, Math.min(120, Math.floor((H() - OVERVIEW_H - 14 - LANES.y) / ROWS.length)));
  ctx.fillStyle = C.bg;
  ctx.fillRect(0, 0, W(), H());

  const t0 = view.start, t1 = view.start + visibleSecs();
  const b0 = Math.max(0, beatAt(m.beats, t0)), b1 = Math.min(m.beats.length - 1, beatAt(m.beats, t1) + 1);
  const cpb = m.grid.cellsPerBeat;

  // Lane backgrounds and cell grid.
  ROWS.forEach((r, i) => {
    ctx.fillStyle = i % 2 ? C.rowAlt : C.row;
    ctx.fillRect(GUTTER, LANES.y + i * LANES.rowH, W() - GUTTER, LANES.rowH);
  });
  const cellPx = view.pps * (m.beats[b1] - m.beats[b0]) / Math.max(1, b1 - b0) / cpb;
  if (cellPx > 7) {
    ctx.strokeStyle = C.cell; ctx.lineWidth = 1; ctx.beginPath();
    for (let b = b0; b <= b1; b++) for (let c = 1; c < cpb; c++) {
      const x = Math.round(xOf(cellTime(m.beats, cpb, b, c))) + 0.5;
      ctx.moveTo(x, LANES.y); ctx.lineTo(x, lanesBottom());
    }
    ctx.stroke();
  }

  // Hovered cell.
  if (hover && rowAt(hover.y) >= 0 && hover.x > GUTTER) {
    const cell = nearestCell(m.beats, cpb, tOf(hover.x));
    if (cell) {
      const a = cellTime(m.beats, cpb, cell.b, cell.c);
      const w = (beatEnd(m.beats, cell.b) - m.beats[cell.b]) / cpb * view.pps;
      ctx.fillStyle = "rgba(224,138,79,0.16)";
      ctx.fillRect(xOf(a) - w / 2, LANES.y + rowAt(hover.y) * LANES.rowH, w, LANES.rowH);
    }
  }

  drawEnvelope(t0, t1);
  drawTempo(b0, b1);

  // Beat lines through ruler, envelope and lanes; sams heavier.
  for (let b = b0; b <= b1; b++) {
    const x = Math.round(xOf(m.beats[b])) + 0.5;
    if (x < GUTTER) continue;
    const pos = beatInCycle(m, b);
    const isSam = pos === 0;
    const dragged = drag && drag.beat === b;
    ctx.strokeStyle = dragged ? C.play : isSam ? C.sam : C.beat;
    ctx.lineWidth = isSam || dragged ? 2 : 1;
    ctx.beginPath(); ctx.moveTo(x, RULER.y + 16); ctx.lineTo(x, lanesBottom()); ctx.stroke();
    ctx.fillStyle = isSam ? C.sam : C.muted;
    ctx.font = isSam ? "bold 12px sans-serif" : "11px sans-serif";
    if (isSam) ctx.fillText(`${cycleOf(m, b)}`, x + 4, RULER.y + 13);
    if (view.pps * (beatEnd(m.beats, b) - m.beats[b]) > 18) ctx.fillText(String(pos + 1), x + 4, RULER.y + 31);
  }

  // Notes.
  const i0 = lowerBound(timed, t0 - 0.2);
  const r = Math.max(4, Math.min(LANES.rowH * 0.3, cellPx * 0.42));
  for (let i = i0; i < timed.length && timed[i].t < t1 + 0.2; i++) {
    const n = timed[i];
    const row = ROWS.findIndex((k) => k.lane === n.lane && k.s === n.s);
    const x = xOf(n.t), y = LANES.y + row * LANES.rowH + LANES.rowH / 2;
    if (x < GUTTER - r) continue;
    const unsure = n.conf !== undefined;
    ctx.globalAlpha = unsure ? 0.3 + 0.6 * n.conf : 1;
    const col = n.lane === "dayan" ? C.dayan : C.bayan;
    ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2);
    if (n.s === "O") { ctx.fillStyle = col; ctx.fill(); }
    else { ctx.strokeStyle = col; ctx.lineWidth = Math.max(2, r * 0.32); ctx.stroke(); }
    if (unsure && n.conf < UNSURE) {
      ctx.globalAlpha = 1; ctx.setLineDash([3, 3]); ctx.strokeStyle = C.muted; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.arc(x, y, r + 4, 0, Math.PI * 2); ctx.stroke(); ctx.setLineDash([]);
    }
    ctx.globalAlpha = 1;
  }

  drawGutter();
  drawOverview();

  // Playhead.
  const px = xOf(player.time);
  if (px >= GUTTER) {
    ctx.strokeStyle = C.play; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, lanesBottom() + 6); ctx.stroke();
  }
  updateStatus();
}

function drawGutter() {
  ctx.fillStyle = "#27221c";
  ctx.fillRect(0, 0, GUTTER, lanesBottom());
  ctx.strokeStyle = C.line; ctx.beginPath(); ctx.moveTo(GUTTER + 0.5, 0); ctx.lineTo(GUTTER + 0.5, lanesBottom()); ctx.stroke();
  ctx.fillStyle = C.muted; ctx.font = "11px sans-serif";
  ctx.fillText("cycle / beat", 10, 24);
  ctx.fillText("tempo", 10, TEMPO.y + 26);
  ctx.fillText("dayan attacks", 10, ENV.y + 30);
  ctx.fillText("bayan attacks", 10, ENV.y + 72);
  ROWS.forEach((k, i) => {
    const y = LANES.y + i * LANES.rowH;
    ctx.fillStyle = k.lane === "dayan" ? C.dayan : C.bayan;
    const mid = y + LANES.rowH / 2;
    ctx.font = "bold 17px sans-serif"; ctx.fillText(k.bol, 12, mid - 2);
    ctx.fillStyle = C.muted; ctx.font = "11px sans-serif"; ctx.fillText(k.label, 12, mid + 14);
    ctx.strokeStyle = C.line; ctx.beginPath(); ctx.moveTo(0, y + 0.5); ctx.lineTo(W(), y + 0.5); ctx.stroke();
  });
}

function drawEnvelope(t0, t1) {
  if (!env) return;
  const mid = ENV.y + ENV.h / 2, half = ENV.h / 2 - 4;
  ctx.strokeStyle = C.line; ctx.beginPath(); ctx.moveTo(GUTTER, mid + 0.5); ctx.lineTo(W(), mid + 0.5); ctx.stroke();
  const fps = env.fps, step = 1 / view.pps;
  for (const [key, dir, col] of [["dayan", -1, C.dayan], ["bayan", 1, C.bayan]]) {
    const arr = env[key];
    ctx.fillStyle = col;
    for (let x = GUTTER; x < W(); x++) {
      const ta = t0 + (x - GUTTER) * step;
      if (ta > t1) break;
      const ia = Math.floor(ta * fps), ib = Math.max(ia + 1, Math.floor((ta + step) * fps));
      let v = 0;
      for (let i = Math.max(0, ia); i < Math.min(arr.length, ib); i++) v = Math.max(v, arr[i]);
      if (v > 2) { const h = (v / 99) * half; ctx.fillRect(x, dir < 0 ? mid - h : mid, 1, h); }
    }
  }
}

function drawTempo(b0, b1) {
  const lo = 60, hi = 180;
  const y = (bpm) => TEMPO.y + TEMPO.h - 4 - ((Math.min(hi, Math.max(lo, bpm)) - lo) / (hi - lo)) * (TEMPO.h - 8);
  ctx.strokeStyle = C.line;
  for (const g of [90, 120, 150]) {
    ctx.beginPath(); ctx.moveTo(GUTTER, y(g) + 0.5); ctx.lineTo(W(), y(g) + 0.5); ctx.stroke();
  }
  ctx.strokeStyle = "#d9c3a0"; ctx.lineWidth = 1.5; ctx.beginPath();
  for (let b = Math.max(1, b0); b <= b1; b++) {
    const raw = 60 / (m.beats[b] - m.beats[b - 1]);
    const x = xOf((m.beats[b] + m.beats[b - 1]) / 2);
    b === Math.max(1, b0) ? ctx.moveTo(x, y(raw)) : ctx.lineTo(x, y(raw));
  }
  ctx.stroke();
}

function drawOverview() {
  const y0 = overviewY(), dur = player.duration;
  ctx.fillStyle = "#27221c"; ctx.fillRect(0, y0, W(), OVERVIEW_H);
  const sx = (t) => (t / dur) * W();
  if (env) {
    ctx.fillStyle = "#5a4a3a";
    const n = env.rms.length;
    for (let x = 0; x < W(); x++) {
      const i = Math.floor((x / W()) * n), j = Math.floor(((x + 1) / W()) * n);
      let v = 0;
      for (let k = i; k < j; k++) v = Math.max(v, env.rms[k]);
      const h = (v / 99) * (OVERVIEW_H - 6);
      ctx.fillRect(x, y0 + OVERVIEW_H - 3 - h, 1, h);
    }
  }
  ctx.strokeStyle = C.sam; ctx.lineWidth = 1;
  ctx.strokeRect(sx(view.start) + 0.5, y0 + 1.5, Math.max(3, sx(visibleSecs())), OVERVIEW_H - 3);
  ctx.fillStyle = C.play; ctx.fillRect(sx(player.time), y0, 2, OVERVIEW_H);
}

function updateStatus() {
  const t = player.time;
  $("pos").textContent = `${fmtTime(t)} / ${fmtTime(player.duration)}`;
  const b = beatAt(m.beats, t);
  $("where").textContent = b >= 0 ? `cycle ${cycleOf(m, b)} · beat ${beatInCycle(m, b) + 1}` : "before the first beat";
  $("bpm").textContent = b >= 0 ? `${Math.round(bpmAt(m.beats, b))} BPM` : "";
}

// ---------------------------------------------------------------- loop

function frame() {
  if (player.playing && Date.now() - lastUserScroll > 2500) {
    const t = player.time, vs = visibleSecs();
    if (t > view.start + vs * 0.75 || t < view.start) view.start = t - vs * 0.25;
  }
  draw();
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- input

function bindInput() {
  cv.addEventListener("mousemove", (e) => {
    hover = { x: e.offsetX, y: e.offsetY };
    if (drag) {
      const b = drag.beat;
      const lo = b > 0 ? m.beats[b - 1] + 0.05 : 0;
      const hi = b + 1 < m.beats.length ? m.beats[b + 1] - 0.05 : Infinity;
      m.beats[b] = +Math.max(lo, Math.min(hi, tOf(e.offsetX))).toFixed(4);
      drag.moved = true;
    }
    const overRuler = e.offsetY < RULER.h && beatNearX(e.offsetX) >= 0;
    cv.style.cursor = drag || overRuler ? "ew-resize" : "crosshair";
  });
  cv.addEventListener("mouseleave", () => { hover = null; });

  cv.addEventListener("mousedown", (e) => {
    const { offsetX: x, offsetY: y } = e;
    if (y >= overviewY()) {
      const t = (x / W()) * player.duration;
      player.seek(t); view.start = t - visibleSecs() / 3; clampView();
      return;
    }
    if (x < GUTTER) return;
    if (y < RULER.h) {
      const b = beatNearX(x);
      if (e.altKey) { b >= 0 ? deleteBeat(b) : insertBeat(tOf(x)); return; }
      if (b >= 0) { snapshot(); drag = { beat: b, moved: false }; return; }
      player.seek(tOf(x));
      return;
    }
    const row = rowAt(y);
    if (row >= 0) { toggleStroke(tOf(x), row); return; }
    player.seek(tOf(x));
  });

  window.addEventListener("mouseup", () => {
    if (!drag) return;
    if (drag.moved) { reindex(); setDirty(true); } else undo.pop();
    drag = null;
  });

  cv.addEventListener("wheel", (e) => {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) { zoom(Math.exp(-e.deltaY * 0.01), e.offsetX); return; }
    const d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    view.start += d / view.pps;
    clampView();
    lastUserScroll = Date.now();
  }, { passive: false });

  window.addEventListener("keydown", (e) => {
    if (e.target.tagName === "SELECT") return;
    const mod = e.metaKey || e.ctrlKey;
    const at = () => (hover && hover.x > GUTTER ? tOf(hover.x) : player.time);
    if (mod && e.key.toLowerCase() === "s") { e.preventDefault(); save(); return; }
    if (mod && e.key.toLowerCase() === "z") { e.preventDefault(); e.shiftKey ? restore(redo, undo) : restore(undo, redo); return; }
    if (mod) return;
    switch (e.key) {
      case " ": e.preventDefault(); togglePlay(); break;
      case "ArrowLeft": player.seek(player.time - (e.shiftKey ? 10 : 2)); break;
      case "ArrowRight": player.seek(player.time + (e.shiftKey ? 10 : 2)); break;
      case "-": zoom(1 / 1.4); break;
      case "=": case "+": zoom(1.4); break;
      case "s": case "S": setSam(at()); break;
      case "n": case "N": nextUnsure(); break;
      case "1": case "2": case "3": setSource(["mix", "drums", "backing"][+e.key - 1]); break;
      case "c": case "C": $("chart").click(); break;
      case "m": case "M": $("click").click(); break;
      case "p": case "P": $("split").click(); break;
      case "[": stepRate(-1); break;
      case "]": stepRate(1); break;
      default: return;
    }
  });

  $("play").onclick = (e) => { e.currentTarget.blur(); togglePlay(); };
  $("save").onclick = (e) => { e.currentTarget.blur(); save(); };
  $("rate").onchange = (e) => { player.setRate(+e.target.value); e.target.blur(); };
  $("source").onchange = (e) => { player.setSource(e.target.value); e.target.blur(); };
  $("chart").onchange = (e) => { player.chartOn = e.target.checked; player.setNotes(timed); };
  $("click").onchange = (e) => { player.clickOn = e.target.checked; player.setNotes(timed); };
  $("split").onchange = (e) => player.setSplit(e.target.checked);
  window.addEventListener("beforeunload", (e) => { if (dirty) e.preventDefault(); });
  window.addEventListener("resize", clampView);
}

async function togglePlay() {
  await player.toggle();
  $("play").textContent = player.playing ? "❚❚ Pause" : "▶ Play";
}

function setSource(key) {
  player.setSource(key);
  $("source").value = key;
}

function stepRate(dir) {
  const sel = $("rate"), i = Math.max(0, Math.min(sel.options.length - 1, sel.selectedIndex + dir));
  sel.selectedIndex = i;
  player.setRate(+sel.value);
}

function nextUnsure() {
  const from = lowerBound(timed, player.time + 0.05);
  for (let i = from; i < timed.length; i++) {
    if (timed[i].conf !== undefined && timed[i].conf < UNSURE) {
      player.seek(Math.max(0, timed[i].t - 1));
      view.start = timed[i].t - visibleSecs() * 0.4;
      clampView();
      return;
    }
  }
}

// ---------------------------------------------------------------- boot

async function main() {
  try {
    m = await loadMap();
  } catch (err) {
    $("error").hidden = false;
    $("error").textContent = err.message;
    return;
  }
  document.title = `${m.title} · beat-map editor`;
  $("song").textContent = m.title;
  $("artist").textContent = m.artist || "";
  env = await loadEnvelope(m);
  player = new Player(m);
  player.onEnded = () => { $("play").textContent = "▶ Play"; };
  await player.loadSamples();
  reindex();
  setDirty(false);
  // Start where the drum starts, not on a silent intro.
  if (m.notes.length) view.start = Math.max(0, timedNotes(m)[0].t - 2);
  player.seek(Math.max(0, view.start));
  bindInput();
  requestAnimationFrame(frame);
}

main();

// Exposed for headless checks and the console.
window.beatmap = { get map() { return m; }, get player() { return player; }, view, save };
