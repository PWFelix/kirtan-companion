/**
 * useBrahmaHold — the gesture that opens Brahmaloka time.
 *
 * The tempo slider tops out at 200 (data/meter.js). Hold it THERE — thumb
 * pressed, or an arrow key held — for BRAHMA_HOLD_MS and `onUnlock` fires. A
 * hold, not a touch, so a fast drag to the end doesn't set it off by accident;
 * and the swelling glow on the thumb (.kc-charging) says something is coming.
 *
 * Returns `charging` (for the class) and `rangeProps` to spread on the input.
 * Pure UI: the caller decides what unlocking means.
 */
import { useState, useRef, useEffect } from "react";
import { NORMAL_MAX_BPM } from "../data/meter.js";

export const BRAHMA_HOLD_MS = 900;   // keep in step with kc-charge in index.css

const HOLD_KEYS = new Set(["ArrowRight", "ArrowUp", "End", "PageUp"]);

export function useBrahmaHold({ bpm, brahmaloka, onUnlock }) {
  const [pressed, setPressed] = useState(false);
  const onUnlockRef = useRef(onUnlock);
  useEffect(() => { onUnlockRef.current = onUnlock; });

  const charging = pressed && !brahmaloka && bpm >= NORMAL_MAX_BPM;

  useEffect(() => {
    if (!charging) return;
    const id = setTimeout(() => { setPressed(false); onUnlockRef.current(); }, BRAHMA_HOLD_MS);
    return () => clearTimeout(id);
  }, [charging]);

  // A drag can end anywhere on the page, not just over the input.
  useEffect(() => {
    if (!pressed) return;
    const up = () => setPressed(false);
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
    onKeyUp: () => setPressed(false),
    onBlur: () => setPressed(false),
  };
  return { charging, rangeProps };
}
