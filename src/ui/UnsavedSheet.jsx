import * as sh from "./styles.js";
import { t } from "../i18n/index.js";

/**
 * UnsavedSheet — asked when opening an edit would replace a draft that has
 * unsaved changes (there is only ever one draft; see data/editorDraft.js).
 * Keep editing goes back to it; Discard and continue starts the new edit.
 */
export default function UnsavedSheet({ name, onKeep, onDiscard, onDismiss }) {
  return (
    <div style={sh.sheetBackdrop} onClick={onDismiss}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("editor.unsavedTitle")}
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("editor.unsavedTitle")}</h2>
          <button onClick={onDismiss} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <p style={st.body}>{t("editor.unsavedBody", { name: name || t("editor.unsavedNew") })}</p>
        <button onClick={onKeep} style={st.primary}>{t("editor.keepEditing")}</button>
        <button onClick={onDiscard} style={st.secondary}>{t("editor.discardContinue")}</button>
      </div>
    </div>
  );
}

const btn = { width: "100%", minHeight: 52, borderRadius: 14, fontFamily: "var(--font-body)", fontSize: 16, fontWeight: 600, cursor: "pointer", marginTop: "var(--space-2)" };
const st = {
  body: { margin: "var(--space-3) 0 var(--space-3)", fontFamily: "var(--font-body)", fontSize: "var(--text-body)", color: "var(--ink-secondary)" },
  primary: { ...btn, border: "none", background: "var(--clay)", color: "var(--on-action)" },
  secondary: { ...btn, border: "1px solid var(--rule)", background: "transparent", color: "var(--syahi)" },
};
