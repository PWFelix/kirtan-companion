import { useEffect, useState } from "react";
import { HomeIcon, BeatsIcon, PencilIcon, CapIcon, CogIcon } from "./icons.jsx";

/**
 * BottomNav — the persistent tab bar.
 *
 * FIVE EQUAL cells (icon + small label). Equal widths matter: mixed sizes
 * made the wide middle buttons swallow near-miss taps aimed at the small
 * end buttons.
 *
 * Five is the ceiling here. The screen frame is 430px wide with 20px of
 * padding either side, so a cell is (min(vw,430) − 40) / 5: 67px on a
 * 375pt phone, 56px on a 320pt one — still over the 44pt minimum target,
 * but a sixth tab would drop below it on small screens.
 *
 * THE BUTTON IS NOT THE PILL. Each <button> is a full-height, full-width
 * slice of the bar with no radius, and the clay pill sits 4px inside it.
 * When the pill *was* the button, everything outside its rounded corners —
 * and the bar's own padding — swallowed taps and did nothing. Now the five
 * slices tile the bar edge to edge with no dead area.
 *
 * And the pill is ONE element, not five backgrounds: a single absolutely
 * positioned block that slides to the active tab (transform + transition in
 * index.css). Every cell is the same width, so a step is exactly its own
 * width plus the 8px between neighbours — no measuring, no refs.
 *
 * Switching pages does NOT stop playback (except the editor, which takes
 * over the engine) so you can browse while a beat plays.
 */

/**
 * Where the pill was last drawn, in tab index; -1 before the first render.
 *
 * It has to live outside the component. App hands the same <BottomNav>
 * element to a DIFFERENT screen component on every navigation, and React
 * rebuilds a subtree whose type changed — so the bar remounts, and any
 * state or ref inside it would come back already set to the destination
 * with nothing to travel from. That is why the first attempt at this
 * animation did nothing at all. Module scope survives the remount, and only
 * one bar is ever on screen, so there is nothing to collide with.
 */
let lastActive = -1;
function BottomNav({ view, onHome, onBeats, onEditor, onLearn, onSettings }) {
  const tabs = [
    { v: "home", label: "Home", Icon: HomeIcon, onClick: onHome },
    { v: "beats", label: "Beats", Icon: BeatsIcon, onClick: onBeats },
    { v: "editor", label: "Editor", Icon: PencilIcon, onClick: onEditor },
    { v: "learn", label: "Learn", Icon: CapIcon, onClick: onLearn },
    { v: "settings", label: "Settings", Icon: CogIcon, onClick: onSettings },
  ];

  // -1 on a view with no tab of its own: the pill simply isn't drawn.
  const active = tabs.findIndex((t) => t.v === view);

  // Paint the bar as it was, THEN move it. `pos` — not `active` — drives the
  // pill and the ink, so the first frame after a remount reproduces the tab
  // you came from; the effect below hands over to the real one.
  const [pos, setPos] = useState(lastActive < 0 ? active : lastActive);

  // Did this bar mount because the user just switched tabs, as opposed to the
  // app starting up? Read once, from the first render's stale `pos`: it is the
  // only moment that still knows where we came from. The destination icon pops
  // on arrival; on a cold start nothing should move.
  const [arriving] = useState(() => pos !== active);

  useEffect(() => {
    lastActive = active;
    if (pos === active) return;
    // Next frame, not this one. The browser has to actually paint the start
    // position before the change to the end position reads as a transition —
    // set both within a single frame and it sees only the final value, and
    // the pill jumps. One frame (~16ms) of the old position is invisible.
    const id = requestAnimationFrame(() => setPos(active));
    return () => cancelAnimationFrame(id);
  }, [active, pos]);

  return (
    <nav style={st.nav} aria-label="Primary">
      {pos >= 0 && (
        <span
          className="kc-nav-pill"
          aria-hidden="true"
          style={{ ...st.pill, transform: `translateX(calc(${pos} * (100% + ${GAP}px)))` }}
        />
      )}
      {tabs.map(({ v, label, Icon, onClick }, i) => (
        <button
          key={v}
          onClick={onClick}
          className="kc-nav-cell"
          aria-current={view === v ? "page" : undefined}
          // Ink follows the pill (`pos`), not the route, so it can lag behind
          // it. `data-lit` hands that same "has the pill" state to CSS, which
          // times lighting up and going dark differently. aria-current stays on
          // the real view — a screen reader should never hear the animation.
          data-lit={i === pos}
          style={{ ...st.navCell, color: i === pos ? "var(--on-action)" : "var(--ink-secondary)" }}
        >
          <span className={i === active && arriving ? "kc-nav-icon kc-nav-pop" : "kc-nav-icon"}>
            <Icon />
          </span>
          <span style={st.navLabel}>{label}</span>
        </button>
      ))}
    </nav>
  );
}

// The pill's inset on every side, so one step across is `100% + GAP`.
const GAP = 8;

const st = {
  // iOS-style pill wrapping the five buttons. No padding or gap of its own:
  // the cells carry it, so no part of the bar is unclickable. `relative` is
  // what the sliding pill positions against.
  nav: { position: "relative", flexShrink: 0, marginTop: "auto", display: "flex", alignItems: "stretch", padding: 0, borderRadius: 999, border: "var(--rule-hairline)", background: "var(--surface-raised)" },
  // One fifth of the bar, less the 4px it sits in from each side.
  pill: { position: "absolute", left: GAP / 2, top: GAP / 2, bottom: GAP / 2, width: `calc(20% - ${GAP}px)`, borderRadius: 999, background: "var(--accent-action)" },
  // 60 = the 52px pill plus 4px of breathing room above and below. z-index
  // lifts the icon and label over the pill sliding beneath them.
  navCell: { position: "relative", zIndex: 1, flex: 1, minWidth: 0, minHeight: 60, padding: "4px", border: "none", background: "transparent", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 2, cursor: "pointer" },
  navLabel: { fontFamily: "var(--font-body)", fontSize: 10, fontWeight: 700, letterSpacing: "0.04em", lineHeight: 1 },
};

export default BottomNav;
