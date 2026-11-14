import { useState } from "react";
import * as sh from "../ui/styles.js";
import { t } from "../i18n/index.js";
import { LoopIcon, FolderIcon, SaveIcon, PlusIcon, TrashIcon } from "../ui/icons.jsx";
import { isDirty, MAX_BARS } from "../data/sequences.js";

/**
 * SequencePanel — Home's running order: beats in order, each for a number of
 * bars, shown as a row of chips under the strip. Tapping a chip opens its
 * sheet (bars, move, change beat, remove); "Add beat" asks HomeView to open
 * the beat picker. Loop, Open and Save sit in the header.
 *
 * Owns only which of its sheets is open; the sequence itself is the
 * transport's (it feeds the engine). `lookup(id)` turns an entry's beat id
 * into the beat, or null when it has been deleted.
 */
export default function SequencePanel({ seq, lookup, onEdit, onToggleLoop, onSave, onOpen, onNew, onDelete, onAdd, onChangeBeat }) {
  const { working, saved, playingEntry, playingBar } = seq;
  const [entrySheet, setEntrySheet] = useState(null); // entry index
  const [sheet, setSheet] = useState(null);           // "save" | "open"
  const loop = working.loop;
  const entry = entrySheet !== null ? working.entries[entrySheet] : null;

  return (
    <section style={st.panel} aria-label={t("seq.title")}>
      <div style={st.head}>
        <div style={st.titles}>
          <span style={st.label}>{t("seq.title")}</span>
          <span style={st.name}>{working.name || (working.entries.length ? t("seq.untitled") : "")}</span>
        </div>
        <button onClick={onToggleLoop} aria-pressed={loop}
          aria-label={loop ? t("seq.loopOn") : t("seq.loopOff")} title={loop ? t("seq.loopOn") : t("seq.loopOff")}
          style={{ ...st.iconBtn, background: loop ? "var(--clay)" : "transparent",
            borderColor: loop ? "var(--clay)" : undefined, color: loop ? "var(--on-clay)" : "var(--syahi-soft)" }}>
          <LoopIcon />
        </button>
        <button onClick={() => setSheet("open")} aria-label={t("seq.open")} title={t("seq.open")}
          aria-haspopup="dialog" style={st.iconBtn}>
          <FolderIcon />
        </button>
        <button onClick={() => setSheet("save")} disabled={working.entries.length === 0}
          aria-label={t("seq.save")} title={t("seq.save")} aria-haspopup="dialog"
          style={{ ...st.iconBtn, opacity: working.entries.length ? 1 : 0.35 }}>
          <SaveIcon />
        </button>
      </div>

      <div style={st.chips}>
        {working.entries.length === 0 && <span style={{ ...sh.emptyHint, alignSelf: "center" }}>{t("seq.empty")}</span>}
        {working.entries.map((e, i) => {
          const b = lookup(e.beatId);
          const live = playingEntry === i;
          return (
            <button key={i} onClick={() => setEntrySheet(i)} aria-current={live ? "step" : undefined}
              style={{ ...st.chip, ...(live ? st.chipLive : null), opacity: b ? 1 : 0.5 }}>
              <span style={st.chipName}>{b ? b.name : t("seq.missing")}</span>
              <span style={{ ...st.chipMeta, color: live ? "var(--on-clay)" : undefined }}>
                {live && playingBar ? t("seq.barOf", { bar: playingBar, n: e.bars }) : t("seq.bars", { n: e.bars })}
              </span>
            </button>
          );
        })}
        <button onClick={onAdd} style={st.addChip}>
          <PlusIcon /> {t("seq.add")}
        </button>
      </div>

      {entry && (
        <div style={sh.sheetBackdrop} onClick={() => setEntrySheet(null)}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={lookup(entry.beatId)?.name ?? t("seq.missing")}
            onClick={(ev) => ev.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>{lookup(entry.beatId)?.name ?? t("seq.missing")}</h2>
              <button onClick={() => setEntrySheet(null)} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
            </div>
            <span style={{ ...st.label, marginTop: "var(--space-4)" }}>{t("seq.barsLabel")}</span>
            <div style={st.stepper}>
              <button onClick={() => onEdit("bars", entrySheet, entry.bars - 1)} disabled={entry.bars <= 1}
                aria-label={t("seq.fewerBars")} style={{ ...st.stepBtn, opacity: entry.bars <= 1 ? 0.35 : 1 }}>‹</button>
              <span style={st.stepValue}>{t("seq.bars", { n: entry.bars })}</span>
              <button onClick={() => onEdit("bars", entrySheet, entry.bars + 1)} disabled={entry.bars >= MAX_BARS}
                aria-label={t("seq.moreBars")} style={{ ...st.stepBtn, opacity: entry.bars >= MAX_BARS ? 0.35 : 1 }}>›</button>
            </div>
            <div style={st.row2}>
              <button disabled={entrySheet === 0} style={{ ...st.secondary, opacity: entrySheet === 0 ? 0.35 : 1 }}
                onClick={() => { onEdit("move", entrySheet, entrySheet - 1); setEntrySheet(entrySheet - 1); }}>
                ‹ {t("seq.moveEarlier")}
              </button>
              <button disabled={entrySheet === working.entries.length - 1}
                style={{ ...st.secondary, opacity: entrySheet === working.entries.length - 1 ? 0.35 : 1 }}
                onClick={() => { onEdit("move", entrySheet, entrySheet + 1); setEntrySheet(entrySheet + 1); }}>
                {t("seq.moveLater")} ›
              </button>
            </div>
            <button style={{ ...st.secondary, marginTop: "var(--space-3)" }}
              onClick={() => { const i = entrySheet; setEntrySheet(null); onChangeBeat(i); }}>
              {t("seq.changeBeat")}
            </button>
            <button style={{ ...st.secondary, marginTop: "var(--space-3)", color: "var(--clay)" }}
              onClick={() => { onEdit("remove", entrySheet); setEntrySheet(null); }}>
              <TrashIcon /> {t("seq.remove")}
            </button>
          </div>
        </div>
      )}

      {sheet === "save" && (
        <SaveSheet working={working} canOverwrite={saved.some((s) => s.id === working.savedId)}
          onSave={(name, asNew) => { onSave(name, asNew); setSheet(null); }}
          onClose={() => setSheet(null)} />
      )}

      {sheet === "open" && (
        <div style={sh.sheetBackdrop} onClick={() => setSheet(null)}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("seq.savedTitle")}
            onClick={(ev) => ev.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>{t("seq.savedTitle")}</h2>
              <button onClick={() => setSheet(null)} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
            </div>
            {isDirty(working, saved) && <p style={{ ...sh.emptyHint, marginTop: "var(--space-3)" }}>{t("seq.replaceWarning")}</p>}
            <div style={sh.pickList}>
              {saved.length === 0 && <p style={sh.emptyHint}>{t("seq.noneSaved")}</p>}
              {saved.map((s) => {
                const sel = s.id === working.savedId;
                const bars = s.entries.reduce((n, e) => n + e.bars, 0);
                return (
                  <div key={s.id} style={{ ...st.savedRow, borderColor: sel ? "var(--clay)" : "var(--rule)" }}>
                    <button onClick={() => { onOpen(s.id); setSheet(null); }} style={st.savedOpen}>
                      <span style={sh.beatRowName}>{s.name || t("seq.untitled")}</span>
                      <span style={sh.beatRowMeta}>
                        {t("seq.beats", { n: s.entries.length })} · {t("seq.bars", { n: bars })}
                      </span>
                    </button>
                    <button onClick={() => onDelete(s.id)} aria-label={t("common.delete")} style={st.iconBtn}>
                      <TrashIcon />
                    </button>
                  </div>
                );
              })}
            </div>
            <button style={{ ...st.secondary, marginTop: "var(--space-4)" }}
              onClick={() => { onNew(); setSheet(null); }}>
              <PlusIcon /> {t("seq.new")}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

// Naming is the whole job of this sheet, so the field takes focus on open.
// Opened from a saved sequence, it offers both overwrite and "as new".
function SaveSheet({ working, canOverwrite, onSave, onClose }) {
  const [name, setName] = useState(working.name);
  const ok = name.trim().length > 0;
  return (
    <div style={sh.sheetBackdrop} onClick={onClose}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("seq.save")}
        onClick={(ev) => ev.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("seq.save")}</h2>
          <button onClick={onClose} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder={t("seq.nameHint")}
          aria-label={t("seq.nameHint")} maxLength={60} style={st.nameInput}
          onKeyDown={(e) => { if (e.key === "Enter" && ok) onSave(name, false); }} />
        <button disabled={!ok} onClick={() => onSave(name, false)}
          style={{ ...st.primary, opacity: ok ? 1 : 0.5 }}>
          {canOverwrite ? t("seq.saveChanges") : t("seq.save")}
        </button>
        {canOverwrite && (
          <button disabled={!ok} onClick={() => onSave(name, true)}
            style={{ ...st.secondary, marginTop: "var(--space-3)", opacity: ok ? 1 : 0.5 }}>
            {t("seq.saveAsNew")}
          </button>
        )}
      </div>
    </div>
  );
}

const st = {
  panel: { display: "flex", flexDirection: "column", gap: "var(--space-2)", flexShrink: 0 },
  head: { display: "flex", alignItems: "center", gap: "var(--space-2)" },
  titles: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column" },
  label: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  name: { fontFamily: "var(--font-display)", fontWeight: 600, fontSize: 17, color: "var(--ink-primary)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" },
  iconBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", display: "grid", placeItems: "center", cursor: "pointer" },
  chips: { display: "flex", gap: "var(--space-2)", overflowX: "auto", scrollbarWidth: "none", paddingBottom: 2 },
  chip: { flexShrink: 0, display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 2, padding: "8px 14px", borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", cursor: "pointer", textAlign: "left" },
  chipLive: { background: "var(--clay)", borderColor: "var(--clay)", color: "var(--on-clay)" },
  chipName: { fontFamily: "var(--font-body)", fontWeight: 700, fontSize: "var(--text-body-sm)", whiteSpace: "nowrap" },
  chipMeta: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", color: "var(--ink-secondary)", whiteSpace: "nowrap" },
  addChip: { flexShrink: 0, display: "flex", alignItems: "center", gap: 6, padding: "0 14px", minHeight: 48, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--clay)", fontFamily: "var(--font-body)", fontWeight: 700, fontSize: "var(--text-body-sm)", cursor: "pointer", whiteSpace: "nowrap" },
  stepper: { display: "flex", alignItems: "center", justifyContent: "center", gap: "var(--space-4)", marginTop: "var(--space-2)" },
  stepBtn: { width: 48, height: 48, borderRadius: 14, border: "var(--rule-hairline)", background: "var(--head-worn)", color: "var(--syahi)", fontSize: 24, cursor: "pointer" },
  stepValue: { minWidth: 110, textAlign: "center", fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: 20, fontWeight: 600, color: "var(--syahi)" },
  row2: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-4)" },
  secondary: { flex: 1, minHeight: 46, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 8 },
  primary: { minHeight: 48, marginTop: "var(--space-4)", borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  nameInput: { marginTop: "var(--space-4)", minHeight: 48, padding: "0 14px", borderRadius: 12, border: "var(--rule-hairline)", background: "var(--head-worn)", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: 16 },
  savedRow: { display: "flex", alignItems: "center", gap: "var(--space-2)", padding: "6px 6px 6px 12px", borderRadius: 14, border: "var(--rule-hairline)", background: "var(--head-worn)" },
  savedOpen: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 2, border: "none", background: "transparent", cursor: "pointer", textAlign: "left", padding: "4px 0" },
};
