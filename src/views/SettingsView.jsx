import * as sh from "../ui/styles.js";
import { BackIcon } from "../ui/icons.jsx";

/**
 * SettingsView — a placeholder shell for now.
 *
 * It exists as its own screen (rather than waiting until there's something
 * in it) so the fourth nav tab always lands somewhere, and so the header /
 * back-button chrome is already in the shape the real settings will use.
 */
function SettingsView({ onBack, nav }) {
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
      </section>
      {nav}
    </div>
  );
}

const st = {
  subtitle: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
};

export default SettingsView;
