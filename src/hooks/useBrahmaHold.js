/**
 * useBrahmaHold — the gesture that opens Brahmaloka time.
 *
 * The tempo slider tops out at 200 (data/meter.js). Hold it THERE — thumb
 * pressed, or an arrow key held — for BRAHMA_HOLD_MS and `onUnlock` fires. A
 * hold, not a touch, so a fast drag to the end doesn't set it off by accident;
 * and the swelling glow on the thumb (.kc-charging) says something is coming.
 *
 * Returns `charging` (for the class), `rangeProps` to spread on the input, and
 * `guard(fn)` to wrap its onChange. The guard matters: the range becomes 190–500
 * under a finger still pressed at the right-hand end, and the browser would map
 * that finger's next move straight to ~490. So after an unlock, changes are
 * swallowed until the finger lifts (or the key is released).
 * Pure UI: the caller decides what unlocking means.
 */
import { useState, useRef, useEffect } from "react";
import { NORMAL_MAX_BPM } from "../data/meter.js";

export const BRAHMA_HOLD_MS = 900;   // keep in step with kc-charge in index.css

const HOLD_KEYS = new Set(["ArrowRight", "ArrowUp", "End", "PageUp"]);

export function useBrahmaHold({ bpm, brahmaloka, onUnlock }) {
  const [pressed, setPressed] = useState(false);
  const onUnlockRef = useRef(onUnlock);
  const swallowRef = useRef(false);
  useEffect(() => { onUnlockRef.current = onUnlock; });

  const charging = pressed && !brahmaloka && bpm >= NORMAL_MAX_BPM;

  useEffect(() => {
    if (!charging) return;
    // `pressed` stays true: the same press must end before the slider moves again.
    const id = setTimeout(() => { swallowRef.current = true; onUnlockRef.current(); }, BRAHMA_HOLD_MS);
    return () => clearTimeout(id);
  }, [charging]);

  // A drag can end anywhere on the page, not just over the input.
  useEffect(() => {
    if (!pressed) return;
    const up = () => { swallowRef.current = false; setPressed(false); };
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    return () => {
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
    };
  }, [pressed]);

  const rangeProps = {
    onPointerDown: () => setPressed(true),
    onKeyDown: (e) => { if (HOLD_KEYS.has(e.key)) setPressed(true); },
    onKeyUp: () => { swallowRef.current = false; setPressed(false); },
    onBlur: () => { swallowRef.current = false; setPressed(false); },
  };
  const guard = (fn) => (e) => { if (!swallowRef.current) fn(e); };
  return { charging, rangeProps, guard };
}
