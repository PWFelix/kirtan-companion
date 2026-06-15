import { strokeVisual } from "./data/strokes.js";

/**
 * BeatIndicator
 * -------------
 * A reusable two-line beat display (top end + bottom end).
 * Each cell shows a SHAPE + COLOUR based on its stroke type,
 * read from strokes.js — so adding new stroke types is a
 * one-line change there, and it appears here automatically.
 *
 * Props:
 *   beat      - the beat object ({ dayan, bayan, steps })
 *   step      - current step index (-1 = not playing)
 *   playing   - whether playback is active
 *   playhead  - "cells" : the active column lights up (main screen)
 *             - "line"  : a smooth vertical bar glides across (practice)
 *   topMuted / bottomMuted - dim a row (for practice isolation)
 *   compact   - smaller version (used on the main screen hero area)
 */
function BeatIndicator({ beat, step, playing, playhead = "cells", topMuted = false, bottomMuted = false, compact = false }) {
  const steps = beat.steps;
  const gap = steps === 16 ? 3 : steps === 12 ? 5 : 6;
  const cellSize = compact ? 38 : 46;

  // Draw a single shape (an SVG) for a stroke type.
  function renderShape(value, active) {
    const v = strokeVisual(value);
    if (v.shape === "rest") {
      // An empty step: a soft hollow ring so it reads as "nothing plays here"
      // while still being clearly visible against the cell.
      return (
        <svg viewBox="0 0 40 40" style={shapeStyle}>
          <circle cx="20" cy="20" r="8" fill="none" stroke="var(--faint)" strokeWidth="2" strokeDasharray="3 3" opacity="0.7" />
        </svg>
      );
    }

    const fill = v.color;
    const glow = active ? "drop-shadow(0 0 5px " + "rgba(216,138,43,0.8))" : "none";
    const common = { fill, style: { filter: glow, transition: "filter 90ms ease" } };

    switch (v.shape) {
      case "circle":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><circle cx="20" cy="20" r="15" {...common} /></svg>;
      case "square":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><rect x="6" y="6" width="28" height="28" rx="5" {...common} /></svg>;
      case "diamond":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><rect x="7" y="7" width="26" height="26" rx="4" transform="rotate(45 20 20)" {...common} /></svg>;
      case "triangle":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><path d="M20 5 L35 33 L5 33 Z" {...common} /></svg>;
      case "ring":
        return <svg viewBox="0 0 40 40" style={shapeStyle}><circle cx="20" cy="20" r="14" fill="none" stroke={fill} strokeWidth="4.5" style={common.style} /></svg>;
      default:
        return null;
    }
  }

  function renderRow(values, muted) {
    return (
      <div style={{ ...rowStyle, opacity: muted ? 0.28 : 1 }}>
        <div style={{ ...cellsStyle, gap }}>
          {values.map((val, i) => {
            const active = playhead === "cells" && playing && i === step;
            return (
              <div key={i} style={{
                ...cellStyle,
                height: cellSize,
                background: active ? "oklch(0.96 0.03 80)" : "var(--cell)",
                border: "1.5px solid var(--line)",
                borderRadius: 9,
                transition: "background 90ms ease",
              }}>
                {renderShape(val, active)}
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  // For the smooth line: position as a percentage across the row.
  // We place the line at the centre of the current step's cell.
  const linePct = playing && step >= 0 ? ((step + 0.5) / steps) * 100 : 0;

  return (
    <div style={{ ...wrapStyle, position: "relative" }}>
      {renderRow(beat.dayan, topMuted)}
      {renderRow(beat.bayan, bottomMuted)}

      {/* Smooth vertical playhead line (practice mode) */}
      {playhead === "line" && playing && (
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
  );
}

const wrapStyle  = { display: "flex", flexDirection: "column", gap: 8, width: "100%" };
const rowStyle   = { display: "flex", transition: "opacity 150ms ease" };
const cellsStyle = { display: "flex", flex: 1 };
const cellStyle  = { flex: 1, display: "grid", placeItems: "center", minWidth: 0 };
const shapeStyle = { width: "88%", height: "88%" };

export default BeatIndicator;
