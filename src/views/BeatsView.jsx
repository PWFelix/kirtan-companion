import { useState } from "react";
import {
  DndContext, closestCenter, PointerSensor, useSensor, useSensors,
} from "@dnd-kit/core";
import {
  SortableContext, verticalListSortingStrategy, useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { BEATS } from "../data/beats.js";
import BeatStrip from "../BeatStrip.jsx";
import ScrollFadeRow from "../ui/ScrollFadeRow.jsx";
import * as sh from "../ui/styles.js";
import { InfoIcon, StartIcon, CheckDot, RadioDot } from "../ui/icons.jsx";

// Sortable row wrapper (dnd-kit). MODULE-LEVEL so React keeps its identity
// across re-renders — a component defined inside the view would remount every
// render and break the drag mid-gesture (the trap the hand-rolled version
// kept falling into). It owns only the drag mechanics and hands them back
// through a render-prop; the row's content stays a closure in the view.
function SortableRow({ id, children }) {
  const { listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    ...(isDragging
      ? { zIndex: 5, position: "relative", boxShadow: "0 8px 22px oklch(0.24 0.02 60 / 0.28)" }
      : null),
  };
  // `listeners` spread on the WHOLE row (the delay sensor tells hold-drag from
  // tap-select); the exempt buttons stop pointer-down so they never drag.
  return children({ setNodeRef, style, isDragging, listeners });
}

/**
 * BeatsView — the library.
 *
 * Built-ins, the user's own beats, and each user category (a kirtan
 * progression) as tabs. Row tap = select, the fast path; the ⓘ opens an
 * info sheet with the full strip, the description, and Edit / Start. Rows
 * preview the real pattern via a mini strip.
 *
 * STATE THIS SCREEN OWNS: which tab is browsed, which sheet is open, and
 * the pending confirmation. All of it is meaningless anywhere else and dies
 * with the screen, which is exactly why it lives here and not in App —
 * App only gets told the outcomes (select this beat, delete that one).
 */
function BeatsView({
  library, beat, beatId, ready,
  onSelect, onStart, onStartBeat, onEdit, onDeleteBeat, nav,
}) {
  const {
    allBeats, customBeats, isCustomBeat, categories, categoryBeats, catName,
    createCategory, deleteCategory, toggleBeatInCategory, reorderCategory,
  } = library;

  const [browseTab, setBrowseTab] = useState("builtin");
  const [detailBeat, setDetailBeat] = useState(null);   // beat shown in the info sheet
  const [createCatOpen, setCreateCatOpen] = useState(false);
  const [newCatName, setNewCatName] = useState("");
  const [addBeatsOpen, setAddBeatsOpen] = useState(false);
  // One confirmation sheet guards every destructive action (delete a beat,
  // remove from a category, delete a category): { message, label, onConfirm }.
  const [confirmAction, setConfirmAction] = useState(null);
  const askConfirm = (message, label, onConfirm) =>
    setConfirmAction({ message, label, onConfirm });

  // The whole beat row is the drag surface AND the tap-to-select target, so
  // the sensor distinguishes the two by a HOLD: press ~220ms to pick up and
  // reorder; a quick tap stays a tap (selects); a swipe that moves past the
  // tolerance before the delay elapses is left to the browser (list scrolls).
  const dragSensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { delay: 220, tolerance: 8 } }),
  );

  function handleCreateCategory() {
    const name = newCatName.trim();
    if (!name) return;
    const id = createCategory(name);
    setNewCatName("");
    setCreateCatOpen(false);
    setBrowseTab(id);
  }
  function handleDeleteCategory(catId) {
    deleteCategory(catId);
    if (browseTab === catId) setBrowseTab("builtin");
  }

  const inUserCat = categories.some(c => c.id === browseTab);

  // The row is a div, not a button: nesting the info/delete buttons inside a
  // row-button is invalid HTML and confuses screen readers. The whole row IS
  // the control: tap (or Enter) selects; in a progression, press-and-hold
  // picks it up to reorder. `drag` carries the sortable bits (empty on
  // non-sortable tabs). The remove/info/delete buttons stop pointer-down so
  // holding them never starts a drag, and stop click so tapping them never
  // selects.
  const stopPD = (e) => e.stopPropagation();
  const rowBody = (b, drag = {}) => {
    const sel = b.id === beatId;
    const { setNodeRef, style, listeners, isDragging } = drag;
    return (
      <div ref={setNodeRef} {...listeners}
        role="button" tabIndex={0} aria-pressed={sel}
        aria-label={inUserCat ? `${b.name} — tap to select, hold to reorder` : b.name}
        onClick={() => onSelect(b, browseTab)}
        onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); onSelect(b, browseTab); } }}
        style={{ ...st.beatRow, borderColor: sel ? "var(--clay)" : "var(--rule)",
          ...(listeners ? { cursor: isDragging ? "grabbing" : "grab", touchAction: "manipulation" } : null),
          ...style }}>
        <div style={sh.beatRowTop}>
          <div style={st.beatRowSelect}>
            <span style={sh.beatRowName}>{b.name}</span>
            <span style={sh.beatRowMeta}>{b.note} · {b.steps} cells</span>
          </div>
          {inUserCat && (
            <button onPointerDown={stopPD} onClick={(e) => {
                e.stopPropagation();
                askConfirm(
                  `Remove “${b.name}” from “${catName(browseTab)}”? The beat itself is kept.`,
                  "Remove",
                  () => toggleBeatInCategory(browseTab, b.id)
                );
              }}
              aria-label={`Remove ${b.name} from ${catName(browseTab)}`}
              style={st.deleteBtn}>−</button>
          )}
          <button onPointerDown={stopPD} onClick={(e) => { e.stopPropagation(); setDetailBeat(b); }}
            aria-label={`About ${b.name}`} style={st.infoBtn}><InfoIcon /></button>
          {!inUserCat && isCustomBeat(b.id) && (
            <button onPointerDown={stopPD} onClick={(e) => {
                e.stopPropagation();
                askConfirm(
                  `Delete the beat “${b.name}”? This can't be undone, and it also leaves any categories it's in.`,
                  "Delete",
                  () => onDeleteBeat(b.id)
                );
              }}
              aria-label={`Delete ${b.name}`} style={st.deleteBtn}>×</button>
          )}
          <RadioDot selected={sel} />
        </div>
        <BeatStrip beat={b} mini />
      </div>
    );
  };
  // Non-sortable tabs (built-in, custom) render the body directly.
  const renderRow = (b) => <div key={b.id} style={{ display: "contents" }}>{rowBody(b)}</div>;
  // User-category rows are draggable to reorder the progression.
  const renderSortableRow = (b) => (
    <SortableRow key={b.id} id={b.id}>
      {(bits) => rowBody(b, bits)}
    </SortableRow>
  );

  const browsedCat = categories.find(c => c.id === browseTab);
  const builtinGroups = [...new Set(BEATS.map(b => b.group))];

  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        <span style={{ width: 44 }} aria-hidden="true" />
        <h1 style={sh.subTitle}>Beats</h1>
        <span style={{ width: 44 }} aria-hidden="true" />
      </header>

      {/* Category tabs: the two fixed ones, the user's own, and +.
          Edge fades signal when the row scrolls. */}
      <ScrollFadeRow rowStyle={sh.catTabs}>
        {["builtin", "custom", ...categories.map(c => c.id)].map(id => (
          <button key={id} onClick={() => setBrowseTab(id)}
            style={{ ...sh.catTab, ...(browseTab === id ? sh.catTabActive : null) }}
            aria-pressed={browseTab === id}>
            {catName(id)}
          </button>
        ))}
        <button onClick={() => setCreateCatOpen(true)} aria-label="New category"
          style={{ ...sh.catTab, flexShrink: 0 }}>+</button>
      </ScrollFadeRow>

      <section style={st.beatList}>
        {browseTab === "builtin" && builtinGroups.map(g => (
          <div key={g} style={{ display: "contents" }}>
            <div style={st.sectionLabel}>{g}</div>
            {BEATS.filter(b => b.group === g).map(renderRow)}
          </div>
        ))}

        {browseTab === "custom" && (
          customBeats.length === 0
            ? <p style={sh.emptyHint}>Nothing here yet — beats you build or customize will appear here.</p>
            : customBeats.map(renderRow)
        )}

        {browsedCat && (
          <>
            {categoryBeats(browsedCat.id).length === 0 && (
              <p style={sh.emptyHint}>
                An empty progression. Add beats in the order you want the kirtan
                to move through them — Home's ‹ › will follow that order. Press and
                hold a beat to drag it into place.
              </p>
            )}
            <DndContext sensors={dragSensors} collisionDetection={closestCenter}
              onDragEnd={({ active, over }) => {
                if (over) reorderCategory(browsedCat.id, active.id, over.id);
              }}>
              <SortableContext items={categoryBeats(browsedCat.id).map(b => b.id)}
                strategy={verticalListSortingStrategy}>
                {categoryBeats(browsedCat.id).map(renderSortableRow)}
              </SortableContext>
            </DndContext>
            <div style={st.catActions}>
              <button onClick={() => setAddBeatsOpen(true)} style={st.addBeatsBtn}>+ Add beats</button>
              <button onClick={() => askConfirm(
                  `Delete the category “${browsedCat.name}”? The beats themselves are kept.`,
                  "Delete",
                  () => handleDeleteCategory(browsedCat.id)
                )}
                style={st.deleteCatBtn}>
                Delete category
              </button>
            </div>
          </>
        )}
      </section>
      <button onClick={onStart} disabled={!ready} style={{ ...st.startBtn, opacity: ready ? 1 : 0.5 }}>
        <StartIcon />
        Start · {beat.name}
      </button>
      {nav}

      {/* Info sheet — slides over the list; backdrop tap or × closes. */}
      {detailBeat && (
        <div style={sh.sheetBackdrop} onClick={() => setDetailBeat(null)}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={detailBeat.name}
            onClick={(e) => e.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>{detailBeat.name}</h2>
              <button onClick={() => setDetailBeat(null)} aria-label="Close" style={sh.sheetClose}>×</button>
            </div>
            <span style={sh.beatRowMeta}>
              {detailBeat.note} · {detailBeat.steps} cells · suggested {detailBeat.bpm} BPM
            </span>
            <div style={{ margin: "var(--space-4) 0" }}>
              <BeatStrip beat={detailBeat} />
            </div>
            <p style={st.sheetDesc}>
              {detailBeat.description || "One of your own beats, built in the editor."}
            </p>
            <div style={st.sheetActions}>
              <button onClick={() => { setDetailBeat(null); onEdit(detailBeat); }} style={st.sheetEditBtn}>
                {isCustomBeat(detailBeat.id) ? "Edit" : "Customize"}
              </button>
              <button onClick={() => { setDetailBeat(null); onStartBeat(detailBeat); }} disabled={!ready}
                style={{ ...st.sheetStartBtn, opacity: ready ? 1 : 0.5 }}>
                Start
              </button>
            </div>
          </div>
        </div>
      )}

      {/* New-category sheet — name it, create it, land in its tab. */}
      {createCatOpen && (
        <div style={sh.sheetBackdrop} onClick={() => setCreateCatOpen(false)}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="New category"
            onClick={(e) => e.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>New category</h2>
              <button onClick={() => setCreateCatOpen(false)} aria-label="Close" style={sh.sheetClose}>×</button>
            </div>
            <p style={sh.emptyHint}>
              A category is a kirtan progression: an ordered set of beats that
              Home's ‹ › will move through.
            </p>
            <div style={{ display: "flex", gap: "var(--space-3)", marginTop: "var(--space-4)" }}>
              <input type="text" value={newCatName} onChange={(e) => setNewCatName(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") handleCreateCategory(); }}
                placeholder="e.g. Sunday feast kirtan" autoFocus style={st.catNameInput} />
              <button onClick={handleCreateCategory} disabled={!newCatName.trim()}
                style={{ ...st.sheetStartBtn, flex: "0 0 auto", padding: "0 22px", opacity: newCatName.trim() ? 1 : 0.5 }}>
                Create
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add-beats sheet — toggle any beat in/out of the browsed
          category; order of adding = order of the progression. */}
      {addBeatsOpen && browsedCat && (
        <div style={sh.sheetBackdrop} onClick={() => setAddBeatsOpen(false)}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label={`Add beats to ${browsedCat.name}`}
            onClick={(e) => e.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>Add to {browsedCat.name}</h2>
              <button onClick={() => setAddBeatsOpen(false)} aria-label="Done" style={sh.sheetClose}>×</button>
            </div>
            <div style={sh.pickList}>
              {allBeats.map(b => {
                const inCat = browsedCat.beatIds.includes(b.id);
                return (
                  <button key={b.id} onClick={() => toggleBeatInCategory(browsedCat.id, b.id)}
                    aria-pressed={inCat}
                    style={{ ...sh.pickRow, borderColor: inCat ? "var(--clay)" : "var(--rule)" }}>
                    <div style={sh.beatRowTop}>
                      <span style={sh.beatRowName}>{b.name}</span>
                      <span style={{ ...sh.beatRowMeta, flex: 1 }}>{b.note}</span>
                      <CheckDot checked={inCat} />
                    </div>
                  </button>
                );
              })}
            </div>
            <button onClick={() => setAddBeatsOpen(false)}
              style={{ ...st.sheetStartBtn, marginTop: "var(--space-4)" }}>Done</button>
          </div>
        </div>
      )}

      {/* Confirmation sheet — guards all destructive actions. Backdrop
          tap cancels; only the explicit red button proceeds. */}
      {confirmAction && (
        <div style={sh.sheetBackdrop} onClick={() => setConfirmAction(null)}>
          <div style={sh.sheet} role="alertdialog" aria-modal="true" aria-label="Are you sure?"
            onClick={(e) => e.stopPropagation()}>
            <p style={st.confirmMsg}>{confirmAction.message}</p>
            <div style={st.sheetActions}>
              <button onClick={() => setConfirmAction(null)} style={st.sheetEditBtn}>Cancel</button>
              <button onClick={() => { confirmAction.onConfirm(); setConfirmAction(null); }}
                style={st.confirmDangerBtn}>
                {confirmAction.label}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const st = {
  beatList: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", gap: "var(--space-3)", padding: "2px" },
  sectionLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  beatRow: { display: "flex", flexDirection: "column", gap: "var(--space-2)", padding: "12px 14px", borderRadius: 16, border: "var(--rule-hairline)", background: "var(--head-worn)", cursor: "pointer", textAlign: "left", width: "100%" },
  beatRowSelect: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2, padding: 0, border: "none", background: "transparent", textAlign: "left", cursor: "pointer" },
  infoBtn: { flexShrink: 0, width: 40, height: 40, borderRadius: 12, border: "none", background: "transparent", color: "var(--syahi-soft)", display: "grid", placeItems: "center", cursor: "pointer" },
  deleteBtn: { flexShrink: 0, width: 40, height: 40, border: "none", background: "transparent", color: "var(--ink-secondary)", fontSize: 20, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", borderRadius: 12 },

  // ── Category actions ──
  catActions: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-2)" },
  addBeatsBtn: { flex: 1, minHeight: 46, borderRadius: 14, border: "2px dashed var(--rule)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  deleteCatBtn: { flexShrink: 0, minHeight: 46, padding: "0 14px", borderRadius: 14, border: "none", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer" },
  catNameInput: { flex: 1, minWidth: 0, padding: "12px 14px", borderRadius: 14, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", background: "var(--surface-paper)", outline: "none" },

  // ── Sheet bodies and actions ──
  sheetDesc: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", lineHeight: 1.55 },
  confirmMsg: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", lineHeight: 1.55 },
  sheetActions: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-5)" },
  sheetEditBtn: { flex: 1, minHeight: 50, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  sheetStartBtn: { flex: 2, minHeight: 50, borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", cursor: "pointer" },
  confirmDangerBtn: { flex: 1, minHeight: 50, borderRadius: 14, border: "none", background: "var(--danger)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.02em", cursor: "pointer" },
  startBtn: { flexShrink: 0, width: "100%", padding: "16px", borderRadius: 18, border: "none", background: "var(--accent-action)", color: "var(--on-action)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 800, letterSpacing: "0.02em", cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 10 },
};

export default BeatsView;
