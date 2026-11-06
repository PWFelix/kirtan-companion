// Play-through preview: the map as a learner would meet it.
//
// The same highway as the app's Learn tab (bayan on the left, open strokes on
// the outside, Ge Khe | Te Ta; mirrored for left-handed players), but driven
// by the RECORDING's clock instead of a fixed tempo. Notes fall towards the
// strike line at the bottom and land on it exactly when the artist plays
// them. Horizontal lines are the beats; heavy ones are sam, numbered by cycle.
//
// Because the kirtan accelerates, the highway's speed is in seconds, not
// beats: a note is always SECONDS_AHEAD seconds of song away when it appears,
// so as the tempo rises the notes simply crowd closer together, which is
// exactly what it feels like to play.

import {
  STROKES, Player, loadMap, timedNotes, beatAt, beatInCycle, cycleOf, bpmAt,
  lowerBound, fmtTime,
} from "./common.js";

const SECONDS_AHEAD = 2.2;
const TRAIL = 0.25;         // seconds a note stays visible past the strike line
const GLOW = 0.12;          // seconds a lane glows after a stroke lands

const $ = (id) => document.getElementById(id);
const cv = $("cv");
const ctx = cv.getContext("2d");

let m, player, timed;
let leftHanded = localStorage.getItem("beatmap-left-handed") === "1";

const columns = () => (leftHanded ? [...STROKES].reverse() : STROKES);

function draw() {
  const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
  if (cv.width !== W * dpr || cv.height !== H * dpr) { cv.width = W * dpr; cv.height = H * dpr; }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = "#f1ebdd"; ctx.fillRect(0, 0, W, H);

  const cols = columns();
  const hwW = Math.min(W - 40, H * 0.75, 640);
  const left = (W - hwW) / 2, colW = hwW / cols.length;
  const strikeY = H - 90;
  const pxPerS = (strikeY - 20) / SECONDS_AHEAD;
  const now = player.time;
  const yOf = (t) => strikeY - (t - now) * pxPerS;

  // Columns.
  cols.forEach((k, i) => {
    ctx.fillStyle = i % 2 ? "#e9e0cf" : "#e4dac7";
    ctx.fillRect(left + i * colW, 0, colW, H);
  });

  // Beat lines, sam heavier with the cycle number beside it.
  const beats = m.beats;
  const b0 = Math.max(0, beatAt(beats, now - TRAIL)), b1 = beatAt(beats, now + SECONDS_AHEAD);
  for (let b = b0; b <= b1 && b < beats.length; b++) {
    const y = Math.round(yOf(beats[b])) + 0.5, sam = beatInCycle(m, b) === 0;
    ctx.strokeStyle = sam ? "#b2582c" : "#cdbfa8";
    ctx.lineWidth = sam ? 3 : 1;
    ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(left + hwW, y); ctx.stroke();
    if (sam) {
      ctx.fillStyle = "#b2582c"; ctx.font = "bold 13px sans-serif"; ctx.textAlign = "right";
      ctx.fillText(String(cycleOf(m, b)), left - 10, y + 4);
    }
  }

  // Strike line.
  ctx.fillStyle = "#2b241d"; ctx.fillRect(left, strikeY - 2, hwW, 4);

  // Notes, and the glow of strokes that just landed.
  const r = Math.min(colW * 0.32, 30);
  const i0 = lowerBound(timed, now - TRAIL);
  for (let i = i0; i < timed.length && timed[i].t < now + SECONDS_AHEAD; i++) {
    const n = timed[i];
    const ci = cols.findIndex((k) => k.lane === n.lane && k.s === n.s);
    const cx = left + ci * colW + colW / 2, y = yOf(n.t);
    const col = n.lane === "dayan" ? "#b2582c" : "#6b4126";
    const past = n.t < now;
    if (past && now - n.t < GLOW) {
      ctx.globalAlpha = 0.35 * (1 - (now - n.t) / GLOW);
      ctx.fillStyle = col; ctx.fillRect(left + ci * colW, 0, colW, strikeY);
    }
    ctx.globalAlpha = past ? Math.max(0, 1 - (now - n.t) / TRAIL) : 1;
    ctx.beginPath(); ctx.arc(cx, y, r, 0, Math.PI * 2);
    if (n.s === "O") { ctx.fillStyle = col; ctx.fill(); }
    else { ctx.strokeStyle = col; ctx.lineWidth = Math.max(3, r * 0.3); ctx.stroke(); }
    ctx.globalAlpha = 1;
  }

  // Bols under the strike line.
  ctx.textAlign = "center";
  cols.forEach((k, i) => {
    const cx = left + i * colW + colW / 2;
    ctx.fillStyle = k.lane === "dayan" ? "#b2582c" : "#6b4126";
    ctx.font = "bold 22px sans-serif"; ctx.fillText(k.bol, cx, strikeY + 34);
    ctx.fillStyle = "#6e6253"; ctx.font = "12px sans-serif"; ctx.fillText(k.label, cx, strikeY + 54);
  });
  ctx.textAlign = "left";

  const b = beatAt(beats, now);
  $("stats").textContent = b >= 0
    ? `cycle ${cycleOf(m, b)} · beat ${beatInCycle(m, b) + 1} · ${Math.round(bpmAt(beats, b))} BPM`
    : "";
  $("time").textContent = `${fmtTime(now)} / ${fmtTime(player.duration)}`;
  if (!seeking) $("seek").value = String(Math.round((now / player.duration) * 1000));
}

let seeking = false;

function frame() {
  draw();
  requestAnimationFrame(frame);
}

async function main() {
  m = await loadMap();
  document.title = `${m.title} · play-through`;
  $("song").textContent = m.title;
  $("artist").textContent = m.artist || "";
  timed = timedNotes(m);
  player = new Player(m);
  player.setNotes(timed);
  await player.loadSamples();
  player.onEnded = () => { $("play").textContent = "▶ Play"; };
  // Begin a bar before the drum comes in.
  if (timed.length) player.seek(Math.max(0, timed[0].t - 3));

  $("play").onclick = async () => {
    await player.toggle();
    $("play").textContent = player.playing ? "❚❚ Pause" : "▶ Play";
  };
  $("source").onchange = (e) => player.setSource(e.target.value);
  $("rate").onchange = (e) => player.setRate(+e.target.value);
  $("chart").onchange = (e) => { player.chartOn = e.target.checked; player.setNotes(timed); };
  const handsLabel = () => { $("hands").textContent = leftHanded ? "Left-handed" : "Right-handed"; };
  handsLabel();
  $("hands").onclick = () => {
    leftHanded = !leftHanded;
    localStorage.setItem("beatmap-left-handed", leftHanded ? "1" : "0");
    handsLabel();
  };
  $("seek").addEventListener("input", (e) => {
    seeking = true;
    player.seek((+e.target.value / 1000) * player.duration);
  });
  $("seek").addEventListener("change", () => { seeking = false; });
  window.addEventListener("keydown", (e) => {
    if (e.key === " ") { e.preventDefault(); $("play").click(); }
  });
  requestAnimationFrame(frame);
}

main();

window.beatmap = { get map() { return m; }, get player() { return player; } };
