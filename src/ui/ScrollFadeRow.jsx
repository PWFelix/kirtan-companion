import { useState, useRef, useEffect } from "react";

/**
 * ScrollFadeRow — horizontal scroller with gradient edge fades.
 *
 * Content visibly "melts" off whichever side has more to see, and the fade
 * vanishes at that end — the standard cue that a row scrolls. Used by the
 * category tab bar on the Beats page and in Home's quick-pick sheet.
 *
 * The fade colour is --head because both callers sit on the head surface;
 * a row over a different background would need the gradient parameterised.
 */
function ScrollFadeRow({ children, rowStyle }) {
  const ref = useRef(null);
  const [fades, setFades] = useState({ left: false, right: false });

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const left = el.scrollLeft > 2;
      const right = el.scrollLeft < el.scrollWidth - el.clientWidth - 2;
      setFades(f => (f.left === left && f.right === right ? f : { left, right }));
    };
    update();
    el.addEventListener("scroll", update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => { el.removeEventListener("scroll", update); ro.disconnect(); };
    // children: re-measure when tabs are added/removed (content width
    // changes don't fire the ResizeObserver, which watches el's own box).
  }, [children]);

  return (
    <div style={{ position: "relative", flexShrink: 0 }}>
      <div ref={ref} style={rowStyle}>{children}</div>
      <div aria-hidden="true" style={{ ...edgeFade, left: 0,
        background: "linear-gradient(90deg, var(--head), transparent)",
        opacity: fades.left ? 1 : 0 }} />
      <div aria-hidden="true" style={{ ...edgeFade, right: 0,
        background: "linear-gradient(270deg, var(--head), transparent)",
        opacity: fades.right ? 1 : 0 }} />
    </div>
  );
}

const edgeFade = { position: "absolute", top: 0, bottom: 2, width: 30, pointerEvents: "none", transition: "opacity 150ms ease" };

export default ScrollFadeRow;
