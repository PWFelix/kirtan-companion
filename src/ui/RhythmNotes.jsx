import { useState } from "react";
import * as sh from "./styles.js";
import { t } from "../i18n/index.js";

/**
 * RhythmNotes — a rhythm's prose: what it is, where it comes from, when to
 * play it, how, with which fingers. Stored as the beat's `description` (the
 * field the built-ins always carried), so it saves, syncs and publishes with
 * the beat; share LINKS drop it to stay short (see data/shareCodec.js).
 * Written in the editor, read from Home and Learn.
 */

/** Long enough for history and fingering, short enough for a payload. */
export const MAX_NOTES = 4000;

/** The small link under a beat's name; renders nothing when there are no notes. */
export function NotesLink({ notes, onOpen, style }) {
  if (!notes?.trim()) return null;
  return (
    <button onClick={onOpen} style={{ ...st.link, ...style }} aria-haspopup="dialog">
      {t("notes.open")}
    </button>
  );
}

/** Reading the notes. */
export function NotesSheet({ title, notes, onClose }) {
  return (
    <div style={sh.sheetBackdrop} onClick={onClose}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{title}</h2>
          <button onClick={onClose} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <p style={st.body}>{notes}</p>
      </div>
    </div>
  );
}

/** Writing the notes. Done hands back the trimmed text; closing keeps the old. */
export function NotesEditorSheet({ initial, onDone, onClose }) {
  const [text, setText] = useState(initial ?? "");
  return (
    <div style={sh.sheetBackdrop} onClick={onClose}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("notes.title")} onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("notes.title")}</h2>
          <button onClick={onClose} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <p style={st.hint}>{t("notes.hint")}</p>
        <textarea value={text} maxLength={MAX_NOTES} onChange={(e) => setText(e.target.value)}
          aria-label={t("notes.title")} style={st.area} />
        <button onClick={() => onDone(text.trim())} style={st.done}>{t("common.done")}</button>
      </div>
    </div>
  );
}

const st = {
  link: { border: "none", background: "transparent", color: "var(--clay)", fontWeight: 700, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", cursor: "pointer", padding: "4px 8px" },
  body: { margin: 0, whiteSpace: "pre-wrap", color: "var(--syahi)", lineHeight: 1.55, maxHeight: "60vh", overflowY: "auto" },
  hint: { margin: "0 0 var(--space-2)", color: "var(--syahi-soft)", fontSize: "var(--text-body-sm)", lineHeight: 1.45 },
  area: { width: "100%", boxSizing: "border-box", minHeight: 200, padding: 12, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi)", fontFamily: "var(--font-body)", fontSize: 15, lineHeight: 1.5, resize: "vertical" },
  done: { marginTop: "var(--space-3)", width: "100%", minHeight: 48, borderRadius: 12, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontWeight: 700, fontFamily: "var(--font-body)", fontSize: 16, cursor: "pointer" },
};
