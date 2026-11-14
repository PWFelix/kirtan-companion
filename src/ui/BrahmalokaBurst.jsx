/**
 * BrahmalokaBurst — the moment Brahmaloka time unlocks.
 *
 * About two seconds of weather over the whole app: a flash, forked lightning,
 * fire raining from the top, the screen shuddering (.kc-shake on #root), and
 * the title with its one-line caption. Then it is gone and leaves nothing
 * behind. Purely decorative: a fixed canvas with pointer-events off, so it can
 * never steal a tap or touch the audio clock.
 *
 * Fires each time `fireKey` changes to a new truthy value. With
 * prefers-reduced-motion there is no shake, flash or particles — only the
 * caption fading in and out.
 */
import { useEffect, useRef, useState } from "react";
import { t } from "../i18n/index.js";

const DURATION_MS = 2200;

const reducedMotion = () =>
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

// A jagged bolt from (x0, y0) to (x1, y1) by midpoint displacement, plus a
// branch or two forking off it.
function makeBolt(x0, y0, x1, y1, spread) {
  let pts = [[x0, y0], [x1, y1]];
  for (let gen = 0; gen < 6; gen++) {
    const next = [pts[0]];
    for (let i = 1; i < pts.length; i++) {
      const [ax, ay] = pts[i - 1], [bx, by] = pts[i];
      next.push([(ax + bx) / 2 + (Math.random() - 0.5) * spread, (ay + by) / 2], [bx, by]);
    }
    pts = next;
    spread *= 0.55;
  }
  return pts;
}

function makeStorm(w, h) {
  const bolts = [];
  const strike = (at) => {
    const x = w * (0.15 + Math.random() * 0.7);
    const main = makeBolt(x, -10, x + (Math.random() - 0.5) * w * 0.4, h * (0.55 + Math.random() * 0.3), w * 0.25);
    bolts.push({ at, pts: main });
    const fork = main[Math.floor(main.length * (0.3 + Math.random() * 0.3))];
    bolts.push({ at, pts: makeBolt(fork[0], fork[1], fork[0] + (Math.random() - 0.5) * w * 0.5, fork[1] + h * 0.25, w * 0.12), thin: true });
  };
  strike(0); strike(0.05); strike(0.42);
  const embers = Array.from({ length: 160 }, () => ({
    x: Math.random() * w,
    born: Math.random() * 0.75,                 // fraction of the run when it appears
    vy: 320 + Math.random() * 520,              // px/s
    drift: (Math.random() - 0.5) * 60,
    size: 1.5 + Math.random() * 3.5,
    hue: 18 + Math.random() * 30,
    phase: Math.random() * Math.PI * 2,
  }));
  return { bolts, embers };
}

function draw(ctx, w, h, storm, f) {
  ctx.clearRect(0, 0, w, h);
  const s = f * (DURATION_MS / 1000);

  // Sky: a storm-dark wash (so the bolts and embers burn against it) under a
  // saffron glow, both lifting as the run ends.
  ctx.fillStyle = `rgba(38, 14, 8, ${0.62 * Math.pow(1 - f, 0.7)})`;
  ctx.fillRect(0, 0, w, h);
  ctx.fillStyle = `rgba(255, 120, 30, ${0.22 * (1 - f)})`;
  ctx.fillRect(0, 0, w, h);

  // Two flashes, at the first strike and the second.
  const flash = Math.max(0, 1 - f / 0.08) * 0.85 + Math.max(0, 1 - Math.abs(f - 0.43) / 0.05) * 0.5;
  if (flash > 0) { ctx.fillStyle = `rgba(255, 248, 225, ${flash})`; ctx.fillRect(0, 0, w, h); }

  // Lightning: each bolt glows for ~0.35 of the run, flickering.
  for (const b of storm.bolts) {
    const age = f - b.at;
    if (age < 0 || age > 0.35) continue;
    const a = (1 - age / 0.35) * (0.6 + 0.4 * Math.random());
    ctx.save();
    ctx.strokeStyle = `rgba(255, 236, 170, ${a})`;
    ctx.shadowColor = "rgba(255, 190, 80, 1)";
    ctx.shadowBlur = 24;
    ctx.lineWidth = b.thin ? 1.5 : 3.5;
    ctx.lineJoin = "round";
    ctx.beginPath();
    b.pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.stroke();
    ctx.lineWidth = b.thin ? 0.6 : 1.4;
    ctx.strokeStyle = `rgba(255, 255, 255, ${a})`;
    ctx.stroke();
    ctx.restore();
  }

  // Fire rain: embers falling with a short streak, fading over the last third.
  for (const e of storm.embers) {
    if (f < e.born) continue;
    const t = s - e.born * (DURATION_MS / 1000);
    const y = -20 + e.vy * t;
    if (y > h + 20) continue;
    const x = e.x + e.drift * t + Math.sin(e.phase + t * 6) * 6;
    const a = Math.min(1, (1 - f) * 3);
    const grad = ctx.createLinearGradient(x, y - e.size * 7, x, y);
    grad.addColorStop(0, `hsla(${e.hue}, 100%, 55%, 0)`);
    grad.addColorStop(1, `hsla(${e.hue + 15}, 100%, 65%, ${a})`);
    ctx.strokeStyle = grad;
    ctx.lineWidth = e.size;
    ctx.lineCap = "round";
    ctx.beginPath();
    ctx.moveTo(x - e.drift * 0.02, y - e.size * 7);
    ctx.lineTo(x, y);
    ctx.stroke();
  }
}

export default function BrahmalokaBurst({ fireKey }) {
  // Showing while the key is one we haven't finished; finishing records it.
  const [doneKey, setDoneKey] = useState(fireKey);
  const active = !!fireKey && fireKey !== doneKey;
  const canvasRef = useRef(null);
  const [calm] = useState(reducedMotion);

  useEffect(() => {
    if (!active) return;
    const root = document.getElementById("root");
    if (!calm) {
      root?.classList.remove("kc-shake");
      void root?.offsetWidth;            // restart the animation if it's mid-run
      root?.classList.add("kc-shake");
    }
    let raf = 0;
    const canvas = canvasRef.current;
    if (canvas && !calm) {
      const dpr = window.devicePixelRatio || 1;
      const w = window.innerWidth, h = window.innerHeight;
      canvas.width = w * dpr; canvas.height = h * dpr;
      const ctx = canvas.getContext("2d");
      ctx.scale(dpr, dpr);
      const storm = makeStorm(w, h);
      const t0 = performance.now();
      const frame = (now) => {
        const f = Math.min(1, (now - t0) / DURATION_MS);
        draw(ctx, w, h, storm, f);
        if (f < 1) raf = requestAnimationFrame(frame);
      };
      raf = requestAnimationFrame(frame);
    }
    const end = setTimeout(() => setDoneKey(fireKey), DURATION_MS);
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(end);
      root?.classList.remove("kc-shake");
    };
  }, [active, fireKey, calm]);

  if (!active) return null;
  return (
    <div style={st.layer} aria-hidden="true">
      {!calm && <canvas ref={canvasRef} style={st.canvas} />}
      <div className="kc-brahma-caption" style={st.caption}>
        <div style={st.title}>{t("brahma.title")}</div>
        <div style={st.line}>{t("brahma.caption")}</div>
      </div>
    </div>
  );
}

const st = {
  // Above everything, including sheets; never takes a pointer event.
  layer: { position: "fixed", inset: 0, zIndex: 1000, pointerEvents: "none", display: "grid", placeItems: "center" },
  canvas: { position: "absolute", inset: 0, width: "100%", height: "100%" },
  caption: { position: "relative", textAlign: "center", padding: "0 24px" },
  title: {
    fontFamily: "var(--font-display)", fontSize: 40, fontWeight: 700, lineHeight: 1.1,
    color: "oklch(0.97 0.05 85)",
    textShadow: "0 0 18px oklch(0.75 0.18 55), 0 0 40px oklch(0.65 0.20 40), 0 2px 2px oklch(0.30 0.08 40 / 0.6)",
  },
  line: {
    marginTop: 8, fontFamily: "var(--font-body)", fontSize: 15, fontStyle: "italic",
    color: "oklch(0.97 0.03 85)", textShadow: "0 0 10px oklch(0.55 0.18 40), 0 1px 2px oklch(0.25 0.05 40)",
  },
};
