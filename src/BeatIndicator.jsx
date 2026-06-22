import { strokeVisual } from "./data/strokes.js";

/**
 * BeatIndicator
 * -------------
 * One pill (oval) per drum end, with shape cells laid out inside.
 * Tap a pill to mute/unmute that end (if `onToggleMute` is provided).
 *
 * Designed to grow when more instruments are added:
 *   1. Add an entry to ROWS below (label + pattern key).
 *   2. Give each beat a matching pattern array (e.g. `beat.kartal`).
 *   3. Name the sound files with the same prefix (e.g. `kartal_open`)
 *      — SoundPlayer routes by prefix into per-end gains automatically.
 *
 * Props:
 *   beat         - { dayan, bayan, steps, ... }
 *   step         - current step index (-1 = not playing)
 *   playing      - whether playback is active
 *   playhead     - "cells" (active cell highlights) | "line" (gliding bar)
 *   mutedEnds    - e.g. { dayan: true } — drives the muted look
 *   onToggleMute - optional (end) => void; if provided, pills become tappable
 *   compact      - smaller version (used on the main screen)
 *   labelStyle   - "simple" (Top/Bottom) | "traditional" (Dayan/Bayan)
 *   bpm          - current tempo; used to time the line playhead's glide
 */

// Each entry = one row. Add new instruments here in future.
const ROWS = [
  { end: "dayan", label: "Top",    traditional: "Dayan", patternKey: "dayan" },
  { end: "bayan", label: "Bottom", traditional: "Bayan", patternKey: "bayan" },
  // { end: "kartal", label: "Kartal", traditional: "Karatalas", patternKey: "kartal" },
];

function BeatIndicator({
  beat,
  step,
  playing,
  playhead = "cells",
  mutedEnds = {},
  onToggleMute,
  compact = false,
  labelStyle = "simple",
  bpm,
}) {
  const steps = beat.steps;
  const gap = steps === 16 ? 3 : steps === 12 ? 5 : 6;
  const cellSize = compact ? 36 : 44;
  const labelW = compact ? 44 : 54;
  const padX = compact ? 10 : 14;
  const labelGap = 6;

  const rows = ROWS.filter(r => Array.isArray(beat[r.patternKey]));

  // The line is re-anchored on every step rather than running a single
  // bar-long animation: that way a mid-play BPM or beat change only
  // desyncs the line for at most one step before the next remount
  // restarts the keyframe at the new duration.
  // The glide duration MUST equal the sequencer's real step interval, or
  // the line gets yanked to the next cell before it finishes and looks
  // jittery. The engine derives that interval from steps / beatsPerBar
  // (8/4 → 2 per beat, 12/4 → 3 per beat triplets, 16/4 → 4 per beat),
  // so we mirror the same formula here rather than hard-coding it.
  const beatsPerBar = beat.beatsPerBar ?? 4;
  const stepsPerBeat = steps / beatsPerBar;
  const stepIntervalMs = bpm ? 60000 / (bpm * stepsPerBeat) : 200;

  function renderShape(value, active, dim) {
    const v = strokeVisual(value);
    if (v.shape === "rest") {
      return (
        <svg viewBox="0 0 40 40" style={shapeStyle}>
          <circle cx="20" cy="20" r="6" fill="none" stroke="var(--faint)" strokeWidth="2" strokeDasharray="3 3" opacity={dim ? 0.25 : 0.55} />
        </svg>
      );
    }

    const fill = v.color;
    const opacity = dim ? 0.3 : 1;
    // The "glow" is a slightly bigger version of the same shape, behind
    // the main shape, in the same colour. Opacity fades in/out as the
    // line crosses each cell. Each shape's halo matches its silhouette.
    const haloOpacity = active ? 0.45 : 0;
    const haloStyle  = { transition: "opacity 140ms ease" };
    const mainStyle  = { opacity, transition: "opacity 160ms ease" };

    let layers;
    switch (v.shape) {
      case "circle":
        layers = <>
          <circle cx="20" cy="20" r="19" fill={fill} opacity={haloOpacity} style={haloStyle} />
          <circle cx="20" cy="20" r="14" fill={fill} style={mainStyle} />
        </>;
        break;
      case "square":
        layers = <>
          <rect x="2" y="2" width="36" height="36" rx="7" fill={fill} opacity={haloOpacity} style={haloStyle} />
          <rect x="7" y="7" width="26" height="26" rx="5" fill={fill} style={mainStyle} />
        </>;
        break;
      case "diamond":
        layers = <>
          <rect x="3" y="3" width="34" height="34" rx="5" transform="rotate(45 20 20)" fill={fill} opacity={haloOpacity} style={haloStyle} />
          <rect x="8" y="8" width="24" height="24" rx="3" transform="rotate(45 20 20)" fill={fill} style={mainStyle} />
        </>;
        break;
      case "triangle":
        layers = <>
          <path d="M20 1 L38 34 L2 34 Z" fill={fill} opacity={haloOpacity} style={haloStyle} />
          <path d="M20 6 L34 32 L6 32 Z" fill={fill} style={mainStyle} />
        </>;
        break;
      case "ring":
        layers = <>
          <circle cx="20" cy="20" r="16" fill="none" stroke={fill} strokeWidth="6" opacity={haloOpacity} style={haloStyle} />
          <circle cx="20" cy="20" r="13" fill="none" stroke={fill} strokeWidth="4" style={mainStyle} />
        </>;
        break;
      default:
        return null;
    }
    return <svg viewBox="0 0 40 40" style={shapeStyle}>{layers}</svg>;
  }

  function MutedIcon({ size = 28 }) {
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M11 5 L6 9 H3 V15 H6 L11 19 Z" fill="currentColor" stroke="none" />
        <line x1="16" y1="9" x2="22" y2="15" />
        <line x1="22" y1="9" x2="16" y2="15" />
      </svg>
    );
  }

  function renderRow(row) {
    const values = beat[row.patternKey];
    const muted = !!mutedEnds[row.end];
    const tappable = !!onToggleMute;
    const labelText = labelStyle === "traditional" ? row.traditional : row.label;

    return (
      <div
        key={row.end}
        role={tappable ? "button" : undefined}
        tabIndex={tappable ? 0 : undefined}
        onClick={tappable ? () => onToggleMute(row.end) : undefined}
        onKeyDown={tappable ? (e) => {
          if (e.key === " " || e.key === "Enter") { e.preventDefault(); onToggleMute(row.end); }
        } : undefined}
        aria-pressed={tappable ? muted : undefined}
        aria-label={tappable ? `${labelText} drum, ${muted ? "muted, tap to unmute" : "playing, tap to mute"}` : undefined}
        style={{
          position: "relative",
          display: "flex",
          alignItems: "center",
          gap: 6,
          background: muted ? "var(--cell)" : "oklch(0.945 0.028 72)",
          border: `1.5px solid ${muted ? "var(--line)" : "oklch(0.88 0.05 72)"}`,
          borderRadius: 999,
          padding: `${compact ? 5 : 7}px ${compact ? 10 : 14}px`,
          cursor: tappable ? "pointer" : "default",
          transition: "background 160ms ease, border-color 160ms ease",
          userSelect: "none",
          WebkitTapHighlightColor: "transparent",
        }}
      >
        <span style={{
          width: labelW,
          flexShrink: 0,
          textAlign: "center",
          fontSize: compact ? 9.5 : 10.5,
          letterSpacing: "0.12em",
          textTransform: "uppercase",
          fontWeight: 700,
          color: muted ? "var(--faint)" : "var(--muted)",
          transition: "color 160ms ease",
        }}>
          {labelText}
        </span>

        <div style={{ display: "flex", flex: 1, gap, position: "relative" }}>
          {values.map((val, i) => {
            const active = playing && i === step && !muted;
            return (
              <div key={i} style={{
                flex: 1,
                minWidth: 0,
                height: cellSize,
                display: "grid",
                placeItems: "center",
                position: "relative",
              }}>
                {playhead === "cells" && active && (
                  <span style={{
                    position: "absolute",
                    inset: 4,
                    borderRadius: "50%",
                    background: "oklch(0.97 0.04 80)",
                    boxShadow: "0 0 14px oklch(0.85 0.13 78 / 0.55)",
                    transition: "opacity 90ms ease",
                  }} />
                )}
                {renderShape(val, active, muted)}
              </div>
            );
          })}

        </div>

        {muted && (
          <div style={{
            position: "absolute",
            inset: 0,
            display: "grid",
            placeItems: "center",
            color: "var(--muted)",
            pointerEvents: "none",
          }}>
            <MutedIcon size={compact ? 26 : 32} />
          </div>
        )}
      </div>
    );
  }

  // The outer container covers the cells region (label column excluded).
  // Inside it, a one-cell-wide window is positioned at the current step
  // and re-mounted each tick via React `key`, so the keyframe inside
  // always replays from 0% at the current stepIntervalMs.
  const cellsLeftOffset = padX + labelW + labelGap;
  const cellsRightOffset = padX;

  return (
    <div style={{ ...wrapStyle, position: "relative" }}>
      {rows.map(renderRow)}

      {playhead === "line" && playing && step >= 0 && (
        <div style={{
          position: "absolute",
          top: -8, bottom: -8,
          left: cellsLeftOffset,
          right: cellsRightOffset,
          pointerEvents: "none",
        }}>
          <div
            key={step}
            style={{
              position: "absolute",
              top: 0, bottom: 0,
              left: `${(step / steps) * 100}%`,
              width: `${100 / steps}%`,
            }}
          >
            <div style={{
              position: "absolute",
              top: 0, bottom: 0,
              width: 2.5,
              background: "var(--saffron-d)",
              borderRadius: 2,
              transform: "translateX(-50%)",
              boxShadow: "0 0 8px rgba(176,106,24,0.6)",
              animation: `kc-glide-cell ${stepIntervalMs}ms linear forwards`,
            }} />
          </div>
        </div>
      )}
    </div>
  );
}

const wrapStyle  = { display: "flex", flexDirection: "column", gap: 8, width: "100%" };
const shapeStyle = { width: "82%", height: "82%" };

export default BeatIndicator;
