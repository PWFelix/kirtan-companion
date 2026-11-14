/**
 * TagPicker.jsx
 * -------------
 * The curated vocabulary as toggle chips, grouped (tala, feel, use, part,
 * level), in a bottom sheet. The editor uses it for the author's tags, which
 * are published with the beat; the beat info sheet uses it for the user's
 * private tags. Tags the vocabulary doesn't know are kept untouched.
 *
 * TagFilter is the one-line chip row the library pages and Community browse
 * filter by. Mirrors Android's TagPicker.kt / iOS's TagPicker.swift.
 */

import { TAG_GROUPS, ALL_TAG_IDS, knownTags } from "../data/tags.js";
import { t } from "../i18n/index.js";
import * as sh from "./styles.js";

export function TagPicker({ title, hint, selected, onChange, onClose }) {
  const chosen = new Set(selected);
  const toggle = (id) => {
    const next = chosen.has(id) ? selected.filter((x) => x !== id) : [...selected, id];
    onChange(next);
  };
  return (
    <div style={sh.sheetBackdrop} onClick={onClose}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={title}
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{title}</h2>
          <button onClick={onClose} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        {hint && <p style={st.hint}>{hint}</p>}
        {TAG_GROUPS.map((g) => (
          <div key={g.id} style={st.group}>
            <div style={st.groupLabel}>{t(g.labelKey)}</div>
            <div style={st.chips}>
              {g.tags.map((tag) => {
                const on = chosen.has(tag.id);
                return (
                  <button key={tag.id} onClick={() => toggle(tag.id)} aria-pressed={on}
                    style={{ ...st.chip, ...(on ? st.chipOn : null) }}>
                    {t(tag.labelKey)}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
        <button onClick={onClose} style={st.done}>{t("common.done")}</button>
      </div>
    </div>
  );
}

/**
 * "All" plus the tags in use (`available`, or the whole vocabulary). Hidden
 * when there's nothing to filter by.
 */
export function TagFilter({ value, onChange, available = ALL_TAG_IDS }) {
  const tags = knownTags(available);
  if (tags.length === 0) return null;
  return (
    <div style={{ ...sh.catTabs, flexShrink: 0 }} role="group" aria-label={t("tags.filter")}>
      <button onClick={() => onChange(null)} aria-pressed={!value}
        style={{ ...sh.catTab, ...(!value ? sh.catTabActive : null) }}>{t("tags.all")}</button>
      {tags.map((tag) => (
        <button key={tag.id} onClick={() => onChange(value === tag.id ? null : tag.id)}
          aria-pressed={value === tag.id}
          style={{ ...sh.catTab, ...(value === tag.id ? sh.catTabActive : null) }}>
          {t(tag.labelKey)}
        </button>
      ))}
    </div>
  );
}

const st = {
  hint: { margin: "var(--space-2) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)" },
  group: { marginTop: "var(--space-4)", display: "flex", flexDirection: "column", gap: "var(--space-2)" },
  groupLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  chips: { display: "flex", flexWrap: "wrap", gap: "var(--space-2)" },
  chip: { minHeight: 36, padding: "0 14px", borderRadius: 999, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer" },
  chipOn: { background: "var(--clay)", borderColor: "var(--clay)", color: "var(--on-clay)" },
  done: { marginTop: "var(--space-5)", minHeight: 48, borderRadius: 999, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body)", fontWeight: 700, cursor: "pointer" },
};
