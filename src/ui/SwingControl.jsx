/**
 * Swing slider, shared by the mixer (a live nudge) and the editor (saved with
 * the beat). One component so the two read and behave the same; the hint
 * below says which of the two it is.
 */
import { t } from "../i18n/index.js";
import { SWING_STRAIGHT, SWING_MAX } from "../data/swing.js";

export default function SwingControl({ value, onChange, hint }) {
  const fill = ((value - SWING_STRAIGHT) / (SWING_MAX - SWING_STRAIGHT)) * 100;
  const label = value <= SWING_STRAIGHT ? t("swing.straight")
    : value >= SWING_MAX ? t("swing.triplet") : `${value}%`;
  return (
    <div style={st.wrap}>
      <div style={st.row}>
        <span style={st.title}>{t("swing.title")}</span>
        <input className="kc-range" type="range" min={SWING_STRAIGHT} max={SWING_MAX} step={1} value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          style={{ "--fill": fill + "%", flex: 1 }} aria-label={t("swing.title")} aria-valuetext={label} />
        <span style={st.value}>{label}</span>
      </div>
      {hint && <p style={st.hint}>{hint}</p>}
    </div>
  );
}

const st = {
  wrap: { display: "flex", flexDirection: "column", gap: "var(--space-1)" },
  row: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  title: { flexShrink: 0, width: 64, fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  value: { flexShrink: 0, minWidth: "5.5em", textAlign: "right", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, color: "var(--syahi)" },
  hint: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", color: "var(--syahi-soft)", lineHeight: 1.4 },
};
