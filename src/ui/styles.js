/**
 * styles.js
 * ---------
 * The style objects that MORE THAN ONE screen uses.
 *
 * The house rule is still "component styles are inline-style objects in
 * the component's own .jsx" — see CLAUDE.md. This file is the deliberate
 * exception for shared chrome: the screen frame, the bottom-sheet stack,
 * the category tab bar and the beat-row typography all appear on two or
 * three screens, and duplicating them is how they drift apart. Anything
 * only one screen draws stays in that screen's file.
 *
 * Values still come from the CSS variables in index.css — nothing here
 * hard-codes a colour or a spacing number.
 */

// NOTE: keep this padding identical to BeatEditor's st.screen so the bottom
// nav sits at the exact same spot on every page and never shifts on switch.
export const screen = {
  width: "100%", maxWidth: 430, minHeight: "100dvh", margin: "0 auto",
  display: "flex", flexDirection: "column",
  padding: "calc(var(--space-6) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-4) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))",
  gap: "var(--space-5)",
};

// The fixed (non-scrolling) frame shared by Home, Beats and Settings — each
// scrolls an inner region instead. The Editor keeps its own copy of this
// (it wants a tighter gap) with the same padding, for the reason above.
export const screenFixed = { ...screen, minHeight: 0 };

// ── Sub-page header (Beats, Settings) ──
export const subHeader = { display: "flex", flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" };
export const subTitle = { margin: 0, fontFamily: "var(--font-display)", fontWeight: 400, fontSize: "var(--text-display-lg)", letterSpacing: "0.01em", color: "var(--ink-primary)" };
export const backBtn = { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-secondary)", display: "grid", placeItems: "center", cursor: "pointer" };
export const controls = { flexShrink: 0, display: "flex", flexDirection: "column", gap: "var(--space-5)" };

// ── Bottom sheets — every modal in the app is one of these ──
export const sheetBackdrop = { position: "fixed", inset: 0, background: "oklch(0.24 0.02 60 / 0.35)", display: "flex", alignItems: "flex-end", justifyContent: "center", zIndex: 10 };
export const sheet = { width: "100%", maxWidth: 430, maxHeight: "85dvh", overflowY: "auto", background: "var(--head)", borderRadius: "20px 20px 0 0", padding: "var(--space-5) var(--space-5) calc(var(--space-6) + env(safe-area-inset-bottom))", display: "flex", flexDirection: "column" };
export const sheetHead = { display: "flex", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" };
export const sheetName = { margin: 0, fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)" };
export const sheetClose = { flexShrink: 0, width: 44, height: 44, margin: "-8px -8px 0 0", border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 26, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center" };

// ── Category tab bar — the Beats page and the Home quick-pick share it ──
export const catTabs = { display: "flex", gap: "var(--space-2)", overflowX: "auto", paddingBottom: 2, scrollbarWidth: "none" };
export const catTab = { flexShrink: 0, minHeight: 40, padding: "0 16px", borderRadius: 999, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer", whiteSpace: "nowrap" };
export const catTabActive = { background: "var(--clay)", borderColor: "var(--clay)", color: "var(--on-clay)" };

// ── Beat rows — the library list and both pick lists render the same
//    name/meta pair, so the type scale lives here ──
export const beatRowTop = { display: "flex", alignItems: "center", gap: "var(--space-2)" };
export const beatRowName = { fontFamily: "var(--font-display)", fontWeight: 600, fontSize: 17, letterSpacing: "0.01em", color: "var(--ink-primary)" };
export const beatRowMeta = { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 500, color: "var(--ink-secondary)" };
export const emptyHint = { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", lineHeight: 1.5 };

// Compact list inside a sheet (quick-pick, add-beats).
export const pickList = { display: "flex", flexDirection: "column", gap: "var(--space-2)", marginTop: "var(--space-3)" };
export const pickRow = { display: "flex", flexDirection: "column", gap: "var(--space-2)", padding: "10px 12px", borderRadius: 14, border: "var(--rule-hairline)", background: "var(--head-worn)", cursor: "pointer", textAlign: "left", width: "100%" };
