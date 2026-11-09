// The homepage hero: the app's Learn highway, live. Strokes of the app's
// "Double Time 2" beat fall down four lanes (Ge Khe | Te Ta: bayan on the
// left, open strokes on the outside) and land on the strike line. "Hear it"
// plays the real mridanga samples, scheduled on the audio clock from the same
// timeline the canvas draws, so sight and sound stay locked together.
//
// Below the hero, the tempo curve of a real kirtan draws itself when it
// scrolls into view.

(() => {
  const BPM = 96;
  const CELLS_PER_BEAT = 4;
  const CELL = 60 / BPM / CELLS_PER_BEAT;
  // Double Time 2, from src/data/beats.js (16 cells, 4 beats).
  const DAYAN = ["X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O", "X", null, "O", "O"];
  const BAYAN = ["O", null, null, "X", null, "O", "O", null, "O", null, null, "X", null, "O", "O", null];
  const COLS = [
    { lane: "bayan", s: "O", bol: "Ge", color: "#6b4126", sound: "ge" },
    { lane: "bayan", s: "X", bol: "Khe", color: "#6b4126", sound: "khe" },
    { lane: "dayan", s: "X", bol: "Te", color: "#b0532a", sound: "te" },
    { lane: "dayan", s: "O", bol: "Ta", color: "#b0532a", sound: "ta" },
  ];
  const AHEAD = 1.4; // seconds of music visible above the strike line
  const LOOP = DAYAN.length * CELL;

  const colOf = (lane, s) => COLS.findIndex((c) => c.lane === lane && c.s === s);
  // Every stroke in one loop: {t, col}.
  const LOOP_NOTES = [];
  for (let i = 0; i < DAYAN.length; i++) {
    if (BAYAN[i]) LOOP_NOTES.push({ t: i * CELL, col: colOf("bayan", BAYAN[i]) });
    if (DAYAN[i]) LOOP_NOTES.push({ t: i * CELL, col: colOf("dayan", DAYAN[i]) });
  }

  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const cv = document.getElementById("highway");
  const ctx = cv.getContext("2d");
  const t0 = performance.now();
  const songTime = () => (performance.now() - t0) / 1000 + 0.6;

  // Notes in [a, b) on the endless timeline.
  function notesBetween(a, b) {
    const out = [];
    for (let k = Math.floor(a / LOOP) - 1; k * LOOP < b; k++) {
      for (const n of LOOP_NOTES) {
        const t = k * LOOP + n.t;
        if (t >= a && t < b) out.push({ t, col: n.col });
      }
    }
    return out;
  }

  function draw() {
    const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) {
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);

    const now = reduced ? 1.3 : songTime();
    const padX = W * 0.08, colW = (W - padX * 2) / COLS.length;
    const strikeY = H - 96, top = 56;
    const pxPerS = (strikeY - top) / AHEAD;
    const yOf = (t) => strikeY - (t - now) * pxPerS;

    // Lanes.
    COLS.forEach((c, i) => {
      ctx.fillStyle = i % 2 ? "rgba(255,255,255,0.22)" : "rgba(255,255,255,0.08)";
      ctx.fillRect(padX + i * colW, 0, colW, H);
    });

    // Beat lines (heavier on the first beat of the loop).
    const beat = 60 / BPM;
    for (let k = Math.ceil((now - 0.3) / beat); k * beat < now + AHEAD; k++) {
      const y = Math.round(yOf(k * beat)) + 0.5;
      const first = k % (DAYAN.length / CELLS_PER_BEAT) === 0;
      ctx.strokeStyle = first ? "rgba(176,83,42,0.55)" : "rgba(42,35,28,0.12)";
      ctx.lineWidth = first ? 2.5 : 1;
      ctx.beginPath(); ctx.moveTo(padX, y); ctx.lineTo(W - padX, y); ctx.stroke();
    }

    // Glow of strokes that just landed.
    // Sized so neighbouring 16th notes never touch.
    const r = Math.min(colW * 0.28, ((strikeY - top) / AHEAD) * CELL * 0.4, 24);
    for (const n of notesBetween(now - 0.18, now)) {
      ctx.globalAlpha = 0.3 * (1 - (now - n.t) / 0.18);
      ctx.fillStyle = COLS[n.col].color;
      ctx.fillRect(padX + n.col * colW, top - 40, colW, strikeY - top + 40);
    }
    ctx.globalAlpha = 1;

    // Strike line.
    ctx.fillStyle = "#2a231c";
    ctx.fillRect(padX, strikeY - 2, W - padX * 2, 4);

    // Notes: open strokes filled, closed strokes as rings.
    for (const n of notesBetween(now - 0.2, now + AHEAD)) {
      const c = COLS[n.col];
      const x = padX + n.col * colW + colW / 2, y = Math.min(strikeY, yOf(n.t));
      const past = n.t < now;
      // A landed stroke stays on the line and fades, so it never covers the bols.
      ctx.globalAlpha = past ? Math.max(0, 1 - (now - n.t) / 0.12) : Math.min(1, (y - top + 40) / 80);
      const rr = r;
      ctx.beginPath(); ctx.arc(x, y, rr, 0, Math.PI * 2);
      if (c.s === "O") { ctx.fillStyle = c.color; ctx.fill(); }
      else { ctx.strokeStyle = c.color; ctx.lineWidth = Math.max(3, r * 0.3); ctx.stroke(); }
    }
    ctx.globalAlpha = 1;

    // Bols.
    ctx.textAlign = "center";
    COLS.forEach((c, i) => {
      const x = padX + i * colW + colW / 2;
      ctx.fillStyle = c.color;
      ctx.font = `700 ${Math.round(Math.min(26, colW * 0.3))}px Fraunces, Georgia, serif`;
      ctx.fillText(c.bol, x, strikeY + 40);
      ctx.fillStyle = "#6a5d4e";
      ctx.font = "600 12px 'Hind Madurai', sans-serif";
      ctx.fillText(c.lane, x, strikeY + 62);
    });

    if (!reduced) requestAnimationFrame(draw);
  }

  // ---- sound ------------------------------------------------------------
  let audio = null;      // {ctx, buffers, timer, until}
  const hear = document.getElementById("hear");

  async function startSound() {
    const ac = new AudioContext();
    const load = async (name) => ac.decodeAudioData(await (await fetch(`assets/sounds/${name}.mp3`)).arrayBuffer());
    const buffers = {};
    await Promise.all(COLS.map(async (c) => { buffers[c.sound] = await load(c.sound); }));
    const out = ac.createGain();
    out.gain.value = 0.9;
    out.connect(ac.destination);
    audio = { ac, buffers, out, until: songTime() + 0.05 };
    // Every 25 ms, queue the strokes in the next 150 ms on the audio clock.
    audio.timer = setInterval(() => {
      const now = songTime(), to = now + 0.15;
      for (const n of notesBetween(audio.until, to)) {
        const src = ac.createBufferSource();
        src.buffer = buffers[COLS[n.col].sound];
        src.connect(out);
        src.start(ac.currentTime + (n.t - now) + 0.02);
      }
      audio.until = to;
    }, 25);
  }

  function stopSound() {
    clearInterval(audio.timer);
    audio.ac.close();
    audio = null;
  }

  hear.addEventListener("click", async () => {
    const on = !audio;
    hear.setAttribute("aria-pressed", String(on));
    hear.lastElementChild.textContent = on ? "Listening…" : "Hear it";
    if (on) await startSound(); else stopSound();
  });

  requestAnimationFrame(draw);

  // ---- the tempo curve draws itself ------------------------------------
  const line = document.getElementById("tempo-line");
  const fill = document.getElementById("tempo-fill");
  const dot = document.getElementById("tempo-dot");
  if (line) {
    const pts = line.getAttribute("points").trim().split(/\s+/).map((p) => p.split(",").map(Number));
    fill.setAttribute("d", `M${pts[0][0]},260 L${pts.map((p) => p.join(",")).join(" L")} L${pts[pts.length - 1][0]},260 Z`);
    const len = line.getTotalLength();
    const show = (f) => {
      line.style.strokeDasharray = `${len}`;
      line.style.strokeDashoffset = `${len * (1 - f)}`;
      fill.style.opacity = String(f);
      const p = line.getPointAtLength(len * f);
      dot.setAttribute("cx", p.x); dot.setAttribute("cy", p.y);
    };
    if (reduced) { show(1); return; }
    show(0);
    const io = new IntersectionObserver((entries) => {
      if (!entries.some((e) => e.isIntersecting)) return;
      io.disconnect();
      const start = performance.now(), DUR = 3200;
      const step = (now) => {
        const f = Math.min(1, (now - start) / DUR);
        show(1 - Math.pow(1 - f, 3));
        if (f < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    }, { threshold: 0.4 });
    io.observe(line.closest("svg"));
  }
})();
