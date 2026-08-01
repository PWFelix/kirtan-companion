import { HomeIcon, BeatsIcon, PencilIcon, CogIcon } from "./icons.jsx";

/**
 * BottomNav — the persistent tab bar.
 *
 * FOUR EQUAL cells (icon + small label). Equal widths matter: mixed sizes
 * made the wide middle buttons swallow near-miss taps aimed at the small
 * end buttons. The active destination is filled clay.
 *
 * Switching pages does NOT stop playback (except the editor, which takes
 * over the engine) so you can browse while a beat plays.
 *
 * App builds one of these and hands it to whichever screen is showing, so
 * the bar is literally the same element across a navigation — it never
 * remounts and never shifts.
 */
function BottomNav({ view, onHome, onBeats, onEditor, onSettings }) {
  const cell = (v) => ({ ...st.navCell, ...(view === v ? st.navActive : null) });
  const cur = (v) => (view === v ? "page" : undefined);
  return (
    <nav style={st.nav} aria-label="Primary">
      <button onClick={onHome} aria-current={cur("home")} style={cell("home")}>
        <HomeIcon /><span style={st.navLabel}>Home</span>
      </button>
      <button onClick={onBeats} aria-current={cur("beats")} style={cell("beats")}>
        <BeatsIcon /><span style={st.navLabel}>Beats</span>
      </button>
      <button onClick={onEditor} aria-current={cur("editor")} style={cell("editor")}>
        <PencilIcon /><span style={st.navLabel}>Editor</span>
      </button>
      <button onClick={onSettings} aria-current={cur("settings")} style={cell("settings")}>
        <CogIcon /><span style={st.navLabel}>Settings</span>
      </button>
    </nav>
  );
}

const st = {
  // iOS-style pill wrapping the four buttons.
  nav: { flexShrink: 0, marginTop: "auto", display: "flex", alignItems: "stretch", gap: "var(--space-1)", padding: "var(--space-1)", borderRadius: 999, border: "var(--rule-hairline)", background: "var(--surface-raised)" },
  navCell: { flex: 1, minWidth: 0, minHeight: 52, borderRadius: 999, border: "none", background: "transparent", color: "var(--ink-secondary)", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 2, cursor: "pointer", padding: "4px 0" },
  navLabel: { fontFamily: "var(--font-body)", fontSize: 10, fontWeight: 700, letterSpacing: "0.04em", lineHeight: 1 },
  navActive: { background: "var(--accent-action)", color: "var(--on-action)" },
};

export default BottomNav;
