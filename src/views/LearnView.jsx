import * as sh from "../ui/styles.js";
import { BackIcon } from "../ui/icons.jsx";

/**
 * LearnView — a placeholder shell for now.
 *
 * Same reasoning as SettingsView: the tab exists before its contents do, so
 * the nav always lands somewhere and the header / back-button chrome is
 * already in the shape the real lessons will use.
 */
function LearnView({ onBack, nav }) {
  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        <button onClick={onBack} style={sh.backBtn} aria-label="Back">
          <BackIcon />
        </button>
        <h1 style={sh.subTitle}>Learn</h1>
        <span style={{ width: 44 }} />
      </header>
      <section style={{ ...sh.controls, flex: 1 }}>
        <p style={st.subtitle}>Lessons are coming soon.</p>
      </section>
      {nav}
    </div>
  );
}

const st = {
  subtitle: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
};

export default LearnView;
