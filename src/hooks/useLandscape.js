import { useState, useEffect } from "react";

// Landscape = rotated phones, propped tablets, and most laptop windows.
// (Desktop monitors taller than 900px keep the centred portrait column.)
const LANDSCAPE_Q = "(orientation: landscape) and (max-height: 900px)";

/**
 * True when the viewport is the short-and-wide shape that Home's
 * propped-up rail layout is built for. Subscribes to the media query so
 * a rotation switches layouts without a resize listener.
 */
export function useLandscape() {
  const [landscape, setLandscape] = useState(
    () => window.matchMedia(LANDSCAPE_Q).matches
  );
  useEffect(() => {
    const mq = window.matchMedia(LANDSCAPE_Q);
    const onChange = (e) => setLandscape(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
  return landscape;
}
