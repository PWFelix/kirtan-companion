import { useState } from "react";
import BeatStrip from "../BeatStrip.jsx";
import ScrollFadeRow from "./ScrollFadeRow.jsx";
import * as sh from "./styles.js";
import { t, noteLabel } from "../i18n/index.js";
import { RadioDot } from "./icons.jsx";

/**
 * BeatPickerSheet — the quick-pick, shared by Home and Learn.
 *
 * Opens on the ACTIVE category but carries its own tab row (Built in · Your
 * beats · each list), so the player can reach their own beats or hop to
 * another progression without visiting the Beats page. Picking calls
 * `onSelect(beat, tab)`, which makes that tab the active cycling context, so
 * the ‹ › arrows then step through the same list.
 *
 * Mount it only while open: the browsed tab is local and starts fresh each time.
 */
function BeatPickerSheet({ library, beatId, onSelect, onClose }) {
  const { categories, activeCat, categoryBeats, catName } = library;
  const [tab, setTab] = useState(activeCat);
  const beats = categoryBeats(tab);

  return (
    <div style={sh.sheetBackdrop} onClick={onClose}>
      <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={t("home.chooseBeat")}
        onClick={(e) => e.stopPropagation()}>
        <div style={sh.sheetHead}>
          <h2 style={sh.sheetName}>{t("home.chooseBeat")}</h2>
          <button onClick={onClose} aria-label={t("common.close")} style={sh.sheetClose}>×</button>
        </div>
        <ScrollFadeRow rowStyle={sh.catTabs}>
          {["builtin", "custom", ...categories.map(c => c.id)].map(id => (
            <button key={id} onClick={() => setTab(id)}
              style={{ ...sh.catTab, ...(tab === id ? sh.catTabActive : null) }}
              aria-pressed={tab === id}>
              {catName(id)}
            </button>
          ))}
        </ScrollFadeRow>
        <div style={sh.pickList}>
          {beats.length === 0 && (
            <p style={sh.emptyHint}>{t("home.categoryEmpty")}</p>
          )}
          {beats.map(b => {
            const sel = b.id === beatId;
            return (
              <button key={b.id} onClick={() => { onSelect(b, tab); onClose(); }}
                style={{ ...sh.pickRow, borderColor: sel ? "var(--clay)" : "var(--rule)" }}>
                <div style={sh.beatRowTop}>
                  <span style={sh.beatRowName}>{b.name}</span>
                  <span style={{ ...sh.beatRowMeta, flex: 1 }}>{noteLabel(b.note)}</span>
                  <RadioDot selected={sel} />
                </div>
                <BeatStrip beat={b} mini />
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export default BeatPickerSheet;
