/**
 * BpmRange — the tempo slider, with the gesture that opens Brahmaloka time.
 * Same design as Android's BpmSlider (ui/components/Brahmaloka.kt).
 *
 * A GRAB SLIDER. A transparent layer over the native range takes the pointer:
 * touching anywhere grabs the thumb where it is, and the drag moves it RELATIVE
 * to that. The native input jumps to the touch point, so in Brahmaloka a tap left
 * of the thumb landed below 200 and threw you out. The real <input> stays beneath
 * for the keyboard and screen readers.
 *
 * THE BUILD-UP. Hold at 200 (thumb pressed, or an arrow key held) and the slider
 * pushes back: over BRAHMA_HOLD_MS it shakes harder (.kc-building), the thumb's
 * glow swells (.kc-charging) and the phone ticks faster, then it breaks through
 * and `onUnlock` fires. That's the "pushing through something" feedback, and
 * what makes Brahmaloka findable.
 *
 * Drags are applied as deltas on the range in force, so when the range zooms
 * out (40–500) under a finger still pressed, it simply carries on at the new
 * scale — no fling to ~490.
 */
import { useState, useRef, useEffect } from "react";
import { NORMAL_MAX_BPM } from "../data/meter.js";

export const BRAHMA_HOLD_MS = 1400;   // keep in step with kc-charge / kc-build in index.css

const HOLD_KEYS = new Set(["ArrowRight", "ArrowUp", "End", "PageUp"]);
const THUMB_PAD = 11;                 // px the native thumb's centre is inset at each end
// Ticks that quicken as the pressure builds (ms on, ms off...).
const TICKS = [8, 220, 8, 180, 8, 140, 8, 110, 8, 85, 8, 65, 8, 50, 8, 40, 8, 35, 8, 30, 8];

export default function BpmRange({ bpm, brahmaloka, min, max, onChange, onUnlock, disabled, className = "", style, title, ariaLabel }) {
  const [pressed, setPressed] = useState(false);
  const live = useRef({ bpm, min, max, onChange, onUnlock });
  useEffect(() => { live.current = { bpm, min, max, onChange, onUnlock }; });
  const drag = useRef(null);

  const charging = pressed && !disabled && !brahmaloka && bpm >= NORMAL_MAX_BPM;
  useEffect(() => {
    if (!charging) return;
    navigator.vibrate?.(TICKS);
    const id = setTimeout(() => live.current.onUnlock(), BRAHMA_HOLD_MS);
    return () => { clearTimeout(id); navigator.vibrate?.(0); };
  }, [charging]);

  function down(e) {
    if (disabled) return;
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { id: e.pointerId, x: e.clientX, value: live.current.bpm };
    setPressed(true);
  }
  function move(e) {
    const d = drag.current;
    if (!d || d.id !== e.pointerId) return;
    const dx = e.clientX - d.x;
    d.x = e.clientX;
    if (!dx) return;
    const { min: lo, max: hi, onChange: change, bpm: now } = live.current;
    const track = Math.max(1, e.currentTarget.getBoundingClientRect().width - 2 * THUMB_PAD);
    d.value = Math.min(hi, Math.max(lo, d.value + (dx / track) * (hi - lo)));
    const v = Math.round(d.value);
    if (v !== now) change(v);
  }
  function up(e) {
    if (drag.current?.id !== e.pointerId) return;
    drag.current = null;
    setPressed(false);
  }

  const fill = ((bpm - min) / (max - min)) * 100;
  const cls = "kc-range" + (brahmaloka ? " kc-brahma" : "") + (charging ? " kc-charging" : "") + (className ? " " + className : "");
  return (
    <span className={charging ? "kc-building" : undefined}
      style={{ position: "relative", display: "flex", alignItems: "center", flex: 1, minWidth: style?.minWidth, opacity: style?.opacity }}>
      <input className={cls} type="range" min={min} max={max} value={bpm} disabled={disabled}
        title={title} aria-label={ariaLabel}
        onChange={(e) => onChange(Number(e.target.value))}
        onKeyDown={(e) => { if (HOLD_KEYS.has(e.key)) setPressed(true); }}
        onKeyUp={() => setPressed(false)}
        onBlur={() => setPressed(false)}
        style={{ "--fill": fill + "%", flex: 1, width: "100%", margin: 0 }} />
      <span aria-hidden="true" onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}
        style={{ position: "absolute", inset: "-8px 0", touchAction: "none", cursor: disabled ? "not-allowed" : "grab" }} />
    </span>
  );
}
