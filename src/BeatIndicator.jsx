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
}) {
  const steps = beat.steps;
  const gap = steps === 16 ? 3 : steps === 12 ? 5 : 6;
  const cellSize = compact ? 36 : 44;
  const labelW = compact ? 44 : 54;

  const rows = ROWS.filter(r => Array.isArray(beat[r.patternKey]));

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
    const glow = active ? "drop-shadow(0 0 6px rgba(216,138,43,0.85))" : "none";
    const opacity = dim ? 0.3 : 1;
    const common = { fill, style: { filter: glow, opacity, transition: "filter 90ms ease, opacity 160ms ease" } };

    switch (v.shape) {
      case "circle":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><circle cx="20" cy="20" r="14" {...common} /></svg>;
      case "square":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><rect x="7" y="7" width="26" height="26" rx="5" {...common} /></svg>;
      case "diamond":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><rect x="8" y="8" width="24" height="24" rx="3" transform="rotate(45 20 20)" {...common} /></svg>;
      case "triangle":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><path d="M20 6 L34 32 L6 32 Z" {...common} /></svg>;
      case "ring":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><circle cx="20" cy="20" r="13" fill="none" stroke={fill} strokeWidth="4" style={common.style} /></svg>;
      default:
        return null;
    }
  }

  function MutedIcon({ size = 13 }) {
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
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
    const linePct = playing && step >= 0 ? ((step + 0.5) / steps) * 100 : 0;
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
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          gap: 3,
          fontSize: compact ? 9.5 : 10.5,
          letterSpacing: "0.12em",
          textTransform: "uppercase",
          fontWeight: 700,
          color: muted ? "var(--faint)" : "var(--muted)",
          transition: "color 160ms ease",
        }}>
          {muted && <MutedIcon size={compact ? 10 : 11} />}
          <span>{labelText}</span>
        </span>

        <div style={{ display: "flex", flex: 1, gap, position: "relative" }}>
          {values.map((val, i) => {
            const active = playhead === "cells" && playing && i === step && !muted;
            return (
              <div key={i} style={{
                flex: 1,
                minWidth: 0,
                height: cellSize,
                display: "grid",
                placeItems: "center",
                position: "relative",
              }}>
                {active && (
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

          {playhead === "line" && playing && !muted && (
            <div style={{
              position: "absolute",
              top: -4, bottom: -4,
              left: `${linePct}%`,
              width: 2.5,
              background: "var(--saffron-d)",
              borderRadius: 2,
              transform: "translateX(-50%)",
              boxShadow: "0 0 8px rgba(176,106,24,0.6)",
              transition: "left 90ms linear",
              pointerEvents: "none",
            }} />
          )}
        </div>
      </div>
    );
  }

  return (
    <div style={wrapStyle}>
      {rows.map(renderRow)}
    </div>
  );
}

const wrapStyle  = { display: "flex", flexDirection: "column", gap: 8, width: "100%" };
const shapeStyle = { width: "82%", height: "82%" };

export default BeatIndicator;
