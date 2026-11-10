import * as sh from "./styles.js";
import { t } from "../i18n/index.js";

/**
 * MigrationSheet — "bring your on-device beats into your account?"
 *
 * Shown once, right after a first sign-in, when the account is empty but the
 * device has guest beats (see useCloudMigration). Two outcomes: Move them up,
 * or Not now. After a successful move it flips to a short confirmation so the
 * user sees where their work went, rather than the sheet just vanishing.
 *
 * It's dumb on purpose — every decision lives in the hook; this only renders
 * the counts and the buttons.
 */
function MigrationSheet({ counts, done, busy, error, onMigrate, onDismiss, onAcknowledge }) {
  // "3 beats and 1 playlist": each count is its own plural key, joined by a
  // translated "and", so word order stays the dictionary's business.
  const list = (c) => [
    c.beats ? t("count.beats", { n: c.beats }) : null,
    c.categories ? t("count.playlists", { n: c.categories }) : null,
  ].filter(Boolean).join(t("common.and"));
  const what = list(counts);
  const one = counts.beats + counts.categories === 1;

  if (done) {
    return (
      <div style={sh.sheetBackdrop} onClick={onAcknowledge}>
        <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("migrate.movedLabel")}
          onClick={(e) => e.stopPropagation()}>
          <div style={sh.sheetHead}>
            <h2 style={sh.sheetName}>{t("migrate.movedTitle")}</h2>
          </div>
          <p style={st.body}>
            {t(done.beats + done.categories === 1 ? "migrate.movedOne" : "migrate.movedMany", { what: list(done) })}
          </p>
          <button onClick={onAcknowledge} style={st.primary}>{t("common.done")}</button>
        </div>
      </div>
    );
  }

  return (
    <div style={sh.sheetBackdrop} onClick={busy ? undefined : onDismiss}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("migrate.askLabel")}
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("migrate.askTitle")}</h2>
        </div>
        <p style={st.body}>
          {t(one ? "migrate.askOne" : "migrate.askMany", { what })}
        </p>
        {error && <p role="alert" style={st.error}>{error}</p>}
        <div style={st.actions}>
          <button onClick={onDismiss} disabled={busy} style={st.secondary}>{t("common.notNow")}</button>
          <button onClick={onMigrate} disabled={busy}
            style={{ ...st.primary, width: "auto", flex: 2, marginTop: 0, opacity: busy ? 0.6 : 1 }}>
            {busy ? t("migrate.moving") : t("migrate.move")}
          </button>
        </div>
      </div>
    </div>
  );
}

const st = {
  body: { margin: "var(--space-3) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", lineHeight: 1.55 },
  error: { margin: "var(--space-3) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--danger)", lineHeight: 1.5 },
  actions: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-5)" },
  secondary: { flex: 1, minHeight: 50, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  primary: { width: "100%", minHeight: 50, marginTop: "var(--space-5)", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", cursor: "pointer" },
};

export default MigrationSheet;
