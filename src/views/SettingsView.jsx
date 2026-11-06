import { useState } from "react";
import * as sh from "../ui/styles.js";
import { BackIcon, ChevronRightIcon } from "../ui/icons.jsx";
import AboutView from "./AboutView.jsx";

/**
 * SettingsView — a near-empty shell, plus the way into About.
 *
 * It exists as its own screen so the fourth nav tab always lands somewhere.
 * About opens IN PLACE (local state, not an App view) because it means
 * nothing outside Settings — the same rule the rest of the UI layer follows.
 */
function SettingsView({ onBack, nav }) {
  const [aboutOpen, setAboutOpen] = useState(false);
  if (aboutOpen) return <AboutView onBack={() => setAboutOpen(false)} nav={nav} />;

  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        <button onClick={onBack} style={sh.backBtn} aria-label="Back">
          <BackIcon />
        </button>
        <h1 style={sh.subTitle}>Settings</h1>
        <span style={{ width: 44 }} />
      </header>
      <section style={{ ...sh.controls, flex: 1 }}>
        <p style={st.subtitle}>More settings coming soon.</p>
        <button onClick={() => setAboutOpen(true)} style={st.row}>
          <span>About</span>
          <ChevronRightIcon />
        </button>
      </section>
      {nav}
    </div>
  );
}

const st = {
  subtitle: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
  row: {
    display: "flex", alignItems: "center", justifyContent: "space-between",
    minHeight: 52, padding: "0 16px", borderRadius: 16, border: "var(--rule-hairline)",
    background: "var(--head-worn)", color: "var(--syahi)", cursor: "pointer",
    fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 600,
  },
};

export default SettingsView;
