import { useState } from "react";
import * as sh from "../ui/styles.js";
import { t } from "../i18n/index.js";
import { BackIcon, ChevronRightIcon } from "../ui/icons.jsx";
import AboutView from "./AboutView.jsx";
import SyncView from "./SyncView.jsx";

/**
 * SettingsView — a near-empty shell, plus the way into About.
 *
 * It exists as its own screen so the fourth nav tab always lands somewhere.
 * About opens IN PLACE (local state, not an App view) because it means
 * nothing outside Settings — the same rule the rest of the UI layer follows.
 */
function SettingsView({ onBack, nav, transport }) {
  const [aboutOpen, setAboutOpen] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  if (aboutOpen) return <AboutView onBack={() => setAboutOpen(false)} nav={nav} />;
  if (syncOpen) return <SyncView transport={transport} onBack={() => setSyncOpen(false)} nav={nav} />;

  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        <button onClick={onBack} style={sh.backBtn} aria-label={t("common.back")}>
          <BackIcon />
        </button>
        <h1 style={sh.subTitle}>{t("settings.title")}</h1>
        <span style={{ width: 44 }} />
      </header>
      <section style={{ ...sh.controls, flex: 1 }}>
        <p style={st.subtitle}>{t("settings.comingSoon")}</p>
        <button onClick={() => setSyncOpen(true)} style={st.row}>
          <span style={st.rowText}>
            <span>{t("sync.rowTitle")}</span>
            <span style={st.rowSub}>
              {t("sync.rowSub")}
              {" "}{transport.displayDelayMs === 0 ? t("sync.rowNoDelay") : t("sync.rowDelay", { ms: transport.displayDelayMs })}
            </span>
          </span>
          <ChevronRightIcon />
        </button>
        <button onClick={() => setAboutOpen(true)} style={st.row}>
          <span>{t("about.title")}</span>
          <ChevronRightIcon />
        </button>
      </section>
      {nav}
    </div>
  );
}

const st = {
  rowText: { display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 2, padding: "10px 0", textAlign: "left" },
  rowSub: { fontSize: "var(--text-body-sm)", fontWeight: 400, color: "var(--ink-secondary)", lineHeight: 1.4 },
  subtitle: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
  row: {
    display: "flex", alignItems: "center", justifyContent: "space-between",
    minHeight: 52, padding: "0 16px", borderRadius: 16, border: "var(--rule-hairline)",
    background: "var(--head-worn)", color: "var(--syahi)", cursor: "pointer",
    fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 600,
  },
};

export default SettingsView;
