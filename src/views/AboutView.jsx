import * as sh from "../ui/styles.js";
import { t } from "../i18n/index.js";
import { BackIcon } from "../ui/icons.jsx";
import Wordmark from "../Wordmark.jsx";

/**
 * AboutView — the name, the splash's mridanga, its gloss and the dedication.
 *
 * Opened from Settings. The drawing sits behind the lockup in the splash's
 * faint clay (same mask technique as .kc-splash-wheel), so it reads as the
 * splash again, quietly. Mirrors the apps' About screens.
 */
function AboutView({ onBack, nav }) {
  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        <button onClick={onBack} style={sh.backBtn} aria-label={t("settings.backTo")}>
          <BackIcon />
        </button>
        <h1 style={sh.subTitle}>{t("about.title")}</h1>
        <span style={{ width: 44 }} />
      </header>
      <section style={st.body}>
        <div style={st.hero}>
          <div style={st.drawing} aria-hidden="true" />
          <Wordmark style={{ "--wm-size": "34px", position: "relative" }} />
        </div>
        <p style={st.gloss}>
          {t("about.gloss")}
        </p>
        <p style={st.verse}>paraṁ vijayate śrī-kṛṣṇa-saṅkīrtanam</p>
        <p style={st.line}>{t("about.production")}</p>
        <p style={st.glories}>{t("about.glories")}</p>
      </section>
      {nav}
    </div>
  );
}

const text = { margin: 0, fontFamily: "var(--font-body)", textAlign: "center", lineHeight: 1.5 };
const st = {
  body: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", alignItems: "center", gap: "var(--space-4)" },
  hero: { position: "relative", width: "100%", height: 320, flexShrink: 0, display: "grid", placeItems: "center" },
  drawing: {
    position: "absolute", inset: 0, background: "var(--clay)", opacity: 0.22,
    WebkitMask: "url(/images/splash-mridanga.png) center / contain no-repeat",
    mask: "url(/images/splash-mridanga.png) center / contain no-repeat",
  },
  gloss: { ...text, fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", maxWidth: 360 },
  verse: { ...text, fontSize: 18, fontStyle: "italic", fontWeight: 500, color: "var(--clay)" },
  line: { ...text, fontSize: 15, color: "var(--syahi)" },
  glories: { ...text, fontSize: 16, fontWeight: 700, color: "var(--syahi)" },
};

export default AboutView;
