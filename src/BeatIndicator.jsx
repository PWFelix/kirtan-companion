import { useMemo, useEffect, useRef } from "react";

/**
 * BeatIndicator — the tal chakra
 * ------------------------------
 * Renders the beat as a circular rhythm cycle instead of a left-to-right strip.
 * Two concentric rings of step positions:
 *   Outer ring = dayan (right hand, higher pitch)
 *   Inner ring = bayan (left hand, lower pitch)
 * Step 0 sits at the top — this is `sam`, where the cycle resolves — and is
 * emphasised with a saffron tick. The active step pulses by position.
 *
 * Performance contract: the engine emits "step" many times per second, so the
 * SVG STRUCTURE (rings, markers, bols, sam, centre text) is memo'd and rebuilt
 * only when the beat (or mute) changes — never per step. The active-step
 * highlight is applied imperatively by toggling `data-active` on the affected
 * <g> nodes via refs; CSS (.kc-chakra rules in index.css) animates the pulse
 * with transitions. React never re-renders the markers on a step tick.
 *
 * Props (leaf display component — same external contract as before):
 *   beat         - { name, steps, beatsPerBar, dayan[], bayan[], ... }
 *   step         - current step index (-1 = stopped)
 *   playing      - whether playback is active
 *   mutedEnds    - e.g. { dayan: true } — dims that ring, suppresses its pulse
 *   onToggleMute - optional (end) => void; taps on a ring toggle its mute
 *
 * `playhead`, `compact`, `labelStyle` and `bpm` are still accepted (App passes
 * them) but the chakra doesn't need them: it has one intrinsic size and the
 * pulse is CSS-transition driven, not timed from bpm.
 */

// ── Geometry constants (SVG user units; viewBox is 0 0 320 320) ──
const VIEW = 320;
const CX = 160, CY = 160;
const R_OUT = 132;          // dayan ring radius
const R_IN = 90;            // bayan ring radius
const LBL_OUT = R_OUT;      // dayan bols sit centred inside their ring marker
const LBL_IN = R_IN;        // bayan bols sit centred inside their ring marker
const HIT_W = 30;           // width of the invisible tap-to-mute hit ring
const R_MARK = 9;           // normal marker radius
const R_SAM = 10.5;         // sam marker is a touch larger (~1.15×)

// Marker/bol sizes shrink once the ring gets crowded so 24- and 32-cell beats
// stay legible. Keyed to the arc spacing on the TIGHTER inner ring; the clamp
// upper bounds are the original constants, so anything up to 16 cells renders
// pixel-identical to before — only denser rings scale down.
function computeSizes(steps) {
  const clamp = (lo, hi, v) => Math.max(lo, Math.min(hi, v));
  const markSpacing = (2 * Math.PI * R_IN) / steps;   // markers ride the inner ring
  const bolSpacing = (2 * Math.PI * LBL_IN) / steps;  // inner bols sit tighter still
  const markR = clamp(4.5, R_MARK, markSpacing * 0.42);
  return {
    markR,
    samR: markR * (R_SAM / R_MARK),
    bolFont: clamp(7, 13, bolSpacing * 0.6),           // 13 = the CSS .kc-bol size
  };
}

// Pure geometry: where each step sits on each ring, plus pulse-group ticks.
function computeGeometry(beat) {
  const { steps, beatsPerBar = 4, dayan, bayan } = beat;
  const sizes = computeSizes(steps);

  const pts = [];
  for (let i = 0; i < steps; i++) {
    const ang = (i / steps) * 2 * Math.PI - Math.PI / 2; // step 0 → top (sam)
    const cos = Math.cos(ang), sin = Math.sin(ang);
    pts.push({
      i,
      isSam: i === 0,
      dayan: {
        mx: CX + cos * R_OUT, my: CY + sin * R_OUT,
        lx: CX + cos * LBL_OUT, ly: CY + sin * LBL_OUT,
        bol: dayan[i],
      },
      bayan: {
        mx: CX + cos * R_IN, my: CY + sin * R_IN,
        lx: CX + cos * LBL_IN, ly: CY + sin * LBL_IN,
        bol: bayan[i],
      },
    });
  }

  // One tick per quarter-note pulse, crossing both rings. Only when the steps
  // divide evenly into the bar (all current beats do); otherwise skip.
  const stepsPerBeat = steps / beatsPerBar;
  let ticks = [];
  if (Number.isInteger(stepsPerBeat) && stepsPerBeat > 1) {
    ticks = Array.from({ length: beatsPerBar }, (_, k) => {
      const ang = ((k * stepsPerBeat) / steps) * 2 * Math.PI - Math.PI / 2;
      const cos = Math.cos(ang), sin = Math.sin(ang);
      return {
        x1: CX + cos * (R_IN - 14), y1: CY + sin * (R_IN - 14),
        x2: CX + cos * (R_OUT + 12), y2: CY + sin * (R_OUT + 12),
      };
    });
  }

  return { pts, ticks, sizes };
}

function BeatIndicator({ beat, step, playing, mutedEnds = {}, onToggleMute, onPlayPause }) {
  const geometry = useMemo(() => computeGeometry(beat), [beat]);

  // One stable ref to the root <svg>. The active-step effect queries the step
  // <g> nodes under it by data-attribute — no per-node refs, so nothing reads
  // a ref during render.
  const rootRef = useRef(null);

  const beatsPerBar = beat.beatsPerBar ?? 4;
  const tappable = onToggleMute != null;

  // Mute and play/pause are handled by event delegation on the wrapper below
  // (which is NOT memo'd), so the fresh `onToggleMute` / `onPlayPause` each
  // render never force the SVG to rebuild. A click on the centre button finds
  // its data-action; a click anywhere inside a ring group finds its data-ring.
  const actionFromEvent = (e) => {
    if (onPlayPause && e.target.closest?.("[data-action='playpause']")) {
      onPlayPause();
      return;
    }
    if (!tappable) return;
    const g = e.target.closest?.("[data-ring]");
    if (g) onToggleMute(g.dataset.ring);
  };
  const actionFromKey = (e) => {
    if (e.key !== " " && e.key !== "Enter") return;
    if (onPlayPause && e.target.closest?.("[data-action='playpause']")) {
      e.preventDefault();
      onPlayPause();
      return;
    }
    if (!tappable) return;
    const g = e.target.closest?.("[data-ring]");
    if (g) { e.preventDefault(); onToggleMute(g.dataset.ring); }
  };

  // The whole SVG is memo'd on [beat, mutedEnds] — NOT on `step`. React bails
  // out of reconciling this subtree while the element reference is unchanged,
  // so markers/labels never re-render on a step tick. (Verifiable: the DEV log
  // below fires once per beat switch, not per step.)
  const chakra = useMemo(() => {
    if (import.meta.env.DEV) console.log("[chakra] build structure", beat.id);
    const { pts, ticks, sizes } = geometry;
    const dayMuted = !!mutedEnds.dayan;
    const bayMuted = !!mutedEnds.bayan;

    const renderRing = (label, ringKey, muted) => (
      <g
        className={"kc-ring" + (muted ? " kc-muted" : "")}
        data-ring={ringKey}
        role={tappable ? "button" : undefined}
        tabIndex={tappable ? 0 : undefined}
        aria-label={tappable ? `${label} drum, ${muted ? "muted, tap to unmute" : "playing, tap to mute"}` : undefined}
      >
        {/* Fat transparent hit-ring so the thin guide circle is easy to tap. */}
        <circle cx={CX} cy={CY} r={ringKey === "dayan" ? R_OUT : R_IN}
          fill="none" stroke="transparent" strokeWidth={HIT_W}
          style={{ cursor: tappable ? "pointer" : "default" }} />
        {pts.map((p) => {
          const s = p[ringKey];
          const silent = s.bol == null;
          return (
            <g key={p.i} data-i={p.i} data-active="false">
              <circle
                className={silent ? "kc-silent" : "kc-marker"}
                cx={s.mx} cy={s.my} r={p.isSam && !silent ? sizes.samR : sizes.markR} />
              {!silent && (
                <text className="kc-bol" x={s.lx} y={s.ly} style={{ fontSize: sizes.bolFont }}
                  textAnchor="middle" dominantBaseline="central">{s.bol}</text>
              )}
            </g>
          );
        })}
      </g>
    );

    return (
      <svg ref={rootRef} className="kc-chakra" viewBox={`0 0 ${VIEW} ${VIEW}`} width="100%"
        role="img" aria-label={`${beat.name}, ${beatsPerBar} by 4, ${beat.steps} steps`}>
        {/* Pulse-group ticks, behind everything. */}
        {ticks.map((t, k) => (
          <line key={k} className="kc-tick" x1={t.x1} y1={t.y1} x2={t.x2} y2={t.y2} />
        ))}

        {/* Faint guide rings. */}
        <circle className="kc-guide" cx={CX} cy={CY} r={R_OUT} fill="none" />
        <circle className="kc-guide" cx={CX} cy={CY} r={R_IN} fill="none" />

        {renderRing("Top", "dayan", dayMuted)}
        {renderRing("Bottom", "bayan", bayMuted)}

        {/* Sam marker — small triangle just OUTSIDE the outer ring at the top,
            pointing inward at step 0 where the cycle resolves. */}
        <path className="kc-sam"
          d={`M${CX},${CY - R_OUT - 13} l-6,-9 l12,0 z`} />

        {/* Centre: play/pause button — the primary action. The <g> carries
            data-action="playpause"; the wrapper's delegated handler turns a
            tap into onPlayPause(). */}
        {playing ? (
          <g className="kc-pause-btn" data-action="playpause"
             role="button" tabIndex={0} aria-label="Pause" style={{ cursor: "pointer" }}>
            <circle cx={CX} cy={CY} r={28} fill="transparent" />
            <rect className="kc-pause-icon" x={CX - 8} y={CY - 10} width={5} height={20} rx={1.5} />
            <rect className="kc-pause-icon" x={CX + 3} y={CY - 10} width={5} height={20} rx={1.5} />
          </g>
        ) : (
          <g className="kc-play-btn" data-action="playpause"
             role="button" tabIndex={0} aria-label="Play" style={{ cursor: "pointer" }}>
            <circle cx={CX} cy={CY} r={28} fill="transparent" />
            <path className="kc-play-icon"
              d={`M${CX - 11},${CY - 16} L${CX - 11},${CY + 16} L${CX + 17},${CY} Z`} />
          </g>
        )}
      </svg>
    );
  }, [geometry, beat, beatsPerBar, mutedEnds, tappable, playing]);

  // Active-step highlight: flip data-active on the matching step <g> nodes only.
  // This runs on every step but only touches a handful of DOM attributes —
  // React does NOT re-render the memo'd SVG. CSS animates the pulse from the
  // toggle. Reading rootRef.current here is an effect, so it's allowed.
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const apply = (ring, muted) => {
      root.querySelectorAll(`[data-ring="${ring}"] [data-i]`).forEach((node) => {
        const i = Number(node.dataset.i);
        node.dataset.active = (playing && i === step && !muted) ? "true" : "false";
      });
    };
    apply("dayan", !!mutedEnds.dayan);
    apply("bayan", !!mutedEnds.bayan);
    // `geometry` is included so the highlight re-applies after a beat switch
    // rebuilds the markers (the new nodes default to data-active="false").
  }, [step, playing, mutedEnds, geometry]);

  // Thin wrapper (re-renders per step, but it's just a <div>) delegates centre
  // and ring taps to the fresh onPlayPause / onToggleMute; the memo'd {chakra}
  // inside is untouched.
  return (
    <div onClick={actionFromEvent} onKeyDown={actionFromKey}>
      {chakra}
    </div>
  );
}

export default BeatIndicator;
