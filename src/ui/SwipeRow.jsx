/**
 * SwipeRow — a row that slides left to reveal action buttons behind it.
 * ---------------------------------------------------------------------
 * The library's Share / Delete, as on the Android and iOS apps. A swipe only
 * REVEALS: deleting someone's beat takes a deliberate tap and then the
 * confirm sheet, never a flick on the way to scrolling.
 *
 * Plays fair with the two other gestures on the same row:
 *  - Vertical scroll: `touch-action: pan-y` leaves vertical pans to the
 *    browser, and the swipe only engages once the movement is mostly
 *    horizontal.
 *  - Press-and-hold reorder (dnd-kit, 220 ms delay / 8 px tolerance): the
 *    swipe must engage within the first 200 ms. Any earlier sideways motion
 *    has already cancelled the hold, and a held drag never becomes a swipe.
 *
 * Open state is hoisted (`open` / `onOpenChange`) so the list keeps one row
 * open at a time. While open, a tap on the row closes it instead of
 * selecting.
 */
import { useRef, useState } from "react";

const ACTION_W = 76;
const GAP = 4;
const ENGAGE_PX = 10;
const ENGAGE_MS = 200;

function SwipeRow({ open, onOpenChange, actions, children }) {
  const reveal = actions.length * (ACTION_W + GAP);
  const [drag, setDrag] = useState(null);   // live offset while dragging, else null
  const g = useRef(null);                    // gesture bookkeeping, not rendered
  const swiped = useRef(false);

  const base = open ? -reveal : 0;
  const x = drag ?? base;

  function onPointerDown(e) {
    g.current = { x: e.clientX, y: e.clientY, t: e.timeStamp, engaged: false, id: e.pointerId };
    swiped.current = false;
  }
  function onPointerMove(e) {
    const s = g.current;
    if (!s || s.id !== e.pointerId) return;
    const dx = e.clientX - s.x, dy = e.clientY - s.y;
    if (!s.engaged) {
      if (e.timeStamp - s.t > ENGAGE_MS && !open) { g.current = null; return; }
      if (Math.abs(dx) < ENGAGE_PX || Math.abs(dx) < Math.abs(dy)) return;
      s.engaged = true;
      e.currentTarget.setPointerCapture?.(e.pointerId);
    }
    setDrag(Math.min(0, Math.max(-reveal, base + dx)));
  }
  function onPointerEnd() {
    const s = g.current;
    g.current = null;
    if (!s?.engaged) return;
    swiped.current = true;
    const shouldOpen = (drag ?? base) < -reveal / 2;
    setDrag(null);
    onOpenChange(shouldOpen);
  }
  // A swipe ends in a click on the row underneath; swallow it. So does a tap
  // on an open row, which closes it rather than selecting the beat.
  function onClickCapture(e) {
    if (swiped.current) { swiped.current = false; e.stopPropagation(); e.preventDefault(); return; }
    if (open) { e.stopPropagation(); e.preventDefault(); onOpenChange(false); }
  }

  return (
    <div style={sw.wrap}>
      <div style={{ ...sw.actions, visibility: x < 0 ? "visible" : "hidden" }}>
        {actions.map((a) => (
          <button key={a.label} onClick={() => { onOpenChange(false); a.onClick(); }}
            style={{ ...sw.action, background: a.background, color: a.color }}
            tabIndex={open ? 0 : -1} aria-hidden={!open}>
            {a.icon}<span style={sw.label}>{a.label}</span>
          </button>
        ))}
      </div>
      <div
        onPointerDown={onPointerDown} onPointerMove={onPointerMove}
        onPointerUp={onPointerEnd} onPointerCancel={onPointerEnd}
        onClickCapture={onClickCapture}
        style={{ ...sw.slider, transform: `translateX(${x}px)`,
          transition: drag == null ? "transform 220ms ease-out" : "none" }}>
        {children}
      </div>
    </div>
  );
}

const sw = {
  wrap: { position: "relative", touchAction: "pan-y" },
  actions: { position: "absolute", inset: 0, display: "flex", justifyContent: "flex-end", gap: GAP },
  action: {
    width: ACTION_W, border: "none", borderRadius: 16, cursor: "pointer",
    display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 4,
    fontFamily: "var(--font-body)",
  },
  label: { fontSize: 12, fontWeight: 600 },
  slider: { position: "relative", userSelect: "none", WebkitUserSelect: "none" },
};

export default SwipeRow;
