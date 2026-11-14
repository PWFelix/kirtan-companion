// The editor timeline's arithmetic and the remembered view choice, kept out of
// ui/EditorTimeline.jsx so that file exports only its component.

/** The timeline's arithmetic. Positions are in cells, unwrapped (the loop repeats). */
export const timelineMath = {
  wrap: (row, steps) => (steps <= 0 ? 0 : ((row % steps) + steps) % steps),
  rowAt: (pos, dyUp, cell) => Math.round(pos + dyUp / cell),
  nearest(index, from, steps) {
    if (steps <= 0) return 0;
    return index + Math.floor((from - index) / steps + 0.5) * steps;
  },
  cycleOf: (row, steps) => (steps <= 0 ? 0 : Math.floor(row / steps)),
};

export const EDITOR_VIEW_KEY = "kirtan-editor-view";
export function loadEditorView() {
  try { return localStorage.getItem(EDITOR_VIEW_KEY) === "strip" ? "strip" : "timeline"; } catch { return "timeline"; }
}
export function saveEditorView(v) {
  try { localStorage.setItem(EDITOR_VIEW_KEY, v); } catch { /* blocked: lasts the session */ }
}
