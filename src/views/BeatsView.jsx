import { useState, useRef, useEffect } from "react";
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
import { InfoIcon, StartIcon, ShareIcon, GlobeIcon, CheckIcon, CheckDot, RadioDot } from "../ui/icons.jsx";
import { encodeBeat, encodeCategory, decodeShare, codeFromInput, shareUrl } from "../data/shareCodec.js";

// Whether the platform has a native share sheet (iOS/Android do, most
// desktop browsers don't). Checked once — it can't change mid-session.
const CAN_NATIVE_SHARE = typeof navigator !== "undefined" && typeof navigator.share === "function";

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
  onSelect, onStart, onStartBeat, onEdit, onDeleteBeat,
  pendingShare, onImportShare, onDismissShare, nav,
}) {
  const {
    allBeats, customBeats, isCustomBeat, categories, categoryBeats, catName,
    createCategory, deleteCategory, toggleBeatInCategory, reorderCategory,
    error, dismissError,
  } = library;

  const [browseTab, setBrowseTab] = useState("builtin");
  const [detailBeat, setDetailBeat] = useState(null);   // beat shown in the info sheet
  const [createCatOpen, setCreateCatOpen] = useState(false);
  const [newCatName, setNewCatName] = useState("");
  const [addBeatsOpen, setAddBeatsOpen] = useState(false);
  // What's being shared out: { kind:"beat", beat } | { kind:"category", name, beats }.
  const [shareTarget, setShareTarget] = useState(null);
  const [copied, setCopied] = useState(null);           // "link" | "code", briefly
  const [codeInput, setCodeInput] = useState("");
  const [codeError, setCodeError] = useState("");
  // What's about to come IN, awaiting the user's yes. Seeded from a share
  // link if the app was opened with one (App decoded it before we mounted),
  // and set again whenever a code is pasted. Nothing is written until Add.
  const [importPreview, setImportPreview] = useState(() => pendingShare ?? null);
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

  // The sheet closes straight away and the new tab is opened once the
  // library confirms the id — which it mints, so there's nothing to switch
  // to until it comes back. A failed save leaves the user where they were,
  // with the reason in the strip at the top of the screen.
  async function handleCreateCategory() {
    const name = newCatName.trim();
    if (!name) return;
    setNewCatName("");
    setCreateCatOpen(false);
    const id = await createCategory(name);
    if (id) setBrowseTab(id);
  }
  function handleDeleteCategory(catId) {
    deleteCategory(catId);
    if (browseTab === catId) setBrowseTab("builtin");
  }

  // ── Sharing out ──────────────────────────────────────────────────────
  // The code is derived, not stored: re-deriving on each render is cheaper
  // than keeping it in state in sync with the sheet.
  const shareCode = !shareTarget ? ""
    : shareTarget.kind === "beat"
      ? encodeBeat(shareTarget.beat)
      : encodeCategory(shareTarget.name, shareTarget.beats);
  const shareLink = shareTarget ? shareUrl(shareCode) : "";
  const linkInputRef = useRef(null);
  const copiedTimer = useRef(null);

  // The "Copied" label is a timeout, so it has to be cancelled if the sheet
  // closes or the view unmounts first.
  useEffect(() => () => clearTimeout(copiedTimer.current), []);
  function flagCopied(which) {
    clearTimeout(copiedTimer.current);
    setCopied(which);
    copiedTimer.current = setTimeout(() => setCopied(null), 1600);
  }
  async function copyText(text, which) {
    try {
      await navigator.clipboard.writeText(text);
      flagCopied(which);
    } catch {
      // Blocked, or a non-secure context (plain http on a LAN). Select the
      // link box instead so a manual copy is one keystroke away.
      linkInputRef.current?.select();
    }
  }
  async function nativeShare() {
    try {
      await navigator.share({ title: shareTarget.kind === "beat" ? shareTarget.beat.name : shareTarget.name, url: shareLink });
    } catch { /* dismissed the OS sheet — nothing to do */ }
  }
  function closeShare() {
    setShareTarget(null);
    clearTimeout(copiedTimer.current);
    setCopied(null);
  }

  // ── Sharing in ───────────────────────────────────────────────────────
  function handlePastedCode() {
    const payload = decodeShare(codeFromInput(codeInput));
    if (!payload) {
      setCodeError("That code isn't complete, or isn't a beat code.");
      return;
    }
    setCodeError("");
    setCodeInput("");
    setImportPreview(payload);
  }
  async function acceptImport() {
    const payload = importPreview;
    setImportPreview(null);
    // Land the user where the beats actually went — a list gets its own tab,
    // a single beat goes to Your beats. The ids only exist after the import
    // mints them, which is why the tab comes back from the call.
    const { catId } = (await onImportShare(payload)) ?? {};
    setBrowseTab(catId ?? "custom");
  }
  function dismissImport() {
    setImportPreview(null);
    onDismissShare();
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

      {/* Storage failures land here — a full or blocked store used to be
          swallowed, so a beat would save, appear in the list, and be gone on
          the next launch. This is the only screen that writes, so it's the
          only one that needs to say so. */}
      {error && (
        <div role="alert" style={st.errorStrip}>
          <span style={st.errorText}>{error}</span>
          <button onClick={dismissError} aria-label="Dismiss message"
            style={st.errorClose}>×</button>
        </div>
      )}

      {/* Category tabs: the three fixed ones, the user's own, and +.
          Edge fades signal when the row scrolls.

          "browse" is a BROWSE-ONLY pseudo-tab — the seat the community beat
          library will take (PROJECT_PLAN §7); for now it's where a shared
          code comes in. It is not a progression, so it must never reach
          categoryBeats/catName/setActiveCat. It renders no beat rows, which
          is what keeps it out of the only path that could send it there
          (onSelect(b, browseTab)). */}
      <ScrollFadeRow rowStyle={sh.catTabs}>
        {["builtin", "browse", "custom", ...categories.map(c => c.id)].map(id => (
          <button key={id} onClick={() => setBrowseTab(id)}
            style={{ ...sh.catTab, ...(browseTab === id ? sh.catTabActive : null),
              ...(id === "browse" ? st.browseTab : null) }}
            aria-pressed={browseTab === id}>
            {id === "browse" ? <><GlobeIcon />Browse</> : catName(id)}
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

        {browseTab === "browse" && (
          <>
            <p style={sh.emptyHint}>
              A library of beats shared by other devotees is coming. Until then,
              this is where a beat someone sent you comes in — paste their code
              or link below. Opening a share link does the same thing.
            </p>
            <div style={st.pasteRow}>
              <input type="text" value={codeInput}
                onChange={(e) => { setCodeInput(e.target.value); setCodeError(""); }}
                onKeyDown={(e) => { if (e.key === "Enter") handlePastedCode(); }}
                placeholder="Paste a share code or link"
                aria-label="Share code" aria-invalid={!!codeError}
                style={st.catNameInput} />
              <button onClick={handlePastedCode} disabled={!codeInput.trim()}
                style={{ ...st.sheetStartBtn, flex: "0 0 auto", padding: "0 22px", opacity: codeInput.trim() ? 1 : 0.5 }}>
                Open
              </button>
            </div>
            {codeError && <p style={st.codeError} role="alert">{codeError}</p>}
          </>
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
              <button onClick={() => setShareTarget({
                  kind: "category", name: browsedCat.name, beats: categoryBeats(browsedCat.id),
                })}
                disabled={categoryBeats(browsedCat.id).length === 0}
                aria-label={`Share the list ${browsedCat.name}`}
                style={{ ...st.addBeatsBtn, ...st.iconLabel, flex: "0 0 auto", padding: "0 16px",
                  opacity: categoryBeats(browsedCat.id).length ? 1 : 0.5 }}>
                <ShareIcon />Share
              </button>
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
              {/* Share sits in the head rather than the action row below so
                  Edit/Start keep their 1:2 balance — three buttons squeezed
                  into one row makes "Customize" too narrow to read. */}
              <div style={st.sheetHeadBtns}>
                <button onClick={() => setShareTarget({ kind: "beat", beat: detailBeat })}
                  aria-label={`Share ${detailBeat.name}`} style={st.sheetHeadBtn}>
                  <ShareIcon />
                </button>
                <button onClick={() => setDetailBeat(null)} aria-label="Close" style={sh.sheetClose}>×</button>
              </div>
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

      {/* Share sheet — the same payload two ways. The link works once the app
          is hosted; the bare code is copy-pasteable today and survives
          messengers that mangle long URLs. There's no toast anywhere in this
          app, so "Copied" is the button label for a moment instead. */}
      {shareTarget && (
        <div style={sh.sheetBackdrop} onClick={closeShare}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Share"
            onClick={(e) => e.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>
                Share {shareTarget.kind === "beat" ? shareTarget.beat.name : shareTarget.name}
              </h2>
              <button onClick={closeShare} aria-label="Close" style={sh.sheetClose}>×</button>
            </div>
            <p style={sh.emptyHint}>
              {shareTarget.kind === "beat"
                ? "Anyone who opens this link gets a copy of the beat. Nothing is uploaded — the whole beat travels inside the link itself."
                : `All ${shareTarget.beats.length} beats in this list travel inside the link. Nothing is uploaded.`}
            </p>
            <input ref={linkInputRef} type="text" readOnly value={shareLink}
              onFocus={(e) => e.target.select()} aria-label="Share link"
              style={{ ...st.catNameInput, marginTop: "var(--space-4)", width: "100%" }} />
            <div style={st.shareActions}>
              {/* Native share takes its own row (flexBasis 100%) so the two
                  copy buttons get half a sheet each rather than a third. */}
              {CAN_NATIVE_SHARE && (
                <button onClick={nativeShare} style={{ ...st.sheetStartBtn, ...st.iconLabel, flex: "1 0 100%" }}>
                  <ShareIcon />Share
                </button>
              )}
              <button onClick={() => copyText(shareLink, "link")} style={{ ...st.sheetEditBtn, ...st.iconLabel }}>
                {copied === "link" ? <><CheckIcon />Copied</> : "Copy link"}
              </button>
              <button onClick={() => copyText(shareCode, "code")} style={{ ...st.sheetEditBtn, ...st.iconLabel }}>
                {copied === "code" ? <><CheckIcon />Copied</> : "Copy code"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Import confirmation — the last stop before anything is written. The
          preview is the real BeatStrip, so what you approve is what you get.
          An unusable code lands here too, as one friendly line. */}
      {importPreview && (
        <div style={sh.sheetBackdrop} onClick={dismissImport}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="Add shared beat"
            onClick={(e) => e.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>
                {importPreview.kind === "invalid" ? "Can't open that link" : "Someone shared this"}
              </h2>
              <button onClick={dismissImport} aria-label="Close" style={sh.sheetClose}>×</button>
            </div>

            {importPreview.kind === "invalid" && (
              <>
                <p style={st.sheetDesc}>
                  That link isn't complete, or isn't a beat link. Ask whoever sent
                  it to copy it again — long links sometimes get cut in half.
                </p>
                <div style={st.sheetActions}>
                  <button onClick={dismissImport} style={st.sheetStartBtn}>Close</button>
                </div>
              </>
            )}

            {importPreview.kind === "beat" && (
              <>
                <span style={sh.beatRowName}>{importPreview.beat.name}</span>
                <span style={sh.beatRowMeta}>
                  {importPreview.beat.steps} cells · suggested {importPreview.beat.bpm} BPM
                </span>
                <div style={{ margin: "var(--space-4) 0" }}>
                  <BeatStrip beat={importPreview.beat} />
                </div>
                <p style={sh.emptyHint}>It'll be saved with your own beats.</p>
                <div style={st.sheetActions}>
                  <button onClick={dismissImport} style={st.sheetEditBtn}>Cancel</button>
                  <button onClick={acceptImport} style={st.sheetStartBtn}>Add beat</button>
                </div>
              </>
            )}

            {importPreview.kind === "category" && (
              <>
                <p style={st.sheetDesc}>
                  Add {importPreview.beats.length}{" "}
                  {importPreview.beats.length === 1 ? "beat" : "beats"} and the
                  list “{importPreview.name}”?
                </p>
                <div style={sh.pickList}>
                  {importPreview.beats.map((b, i) => (
                    <div key={i} style={sh.pickRow}>
                      <div style={sh.beatRowTop}>
                        <span style={sh.beatRowName}>{b.name}</span>
                        <span style={{ ...sh.beatRowMeta, flex: 1 }}>{b.steps} cells</span>
                      </div>
                      <BeatStrip beat={b} mini />
                    </div>
                  ))}
                </div>
                <div style={st.sheetActions}>
                  <button onClick={dismissImport} style={st.sheetEditBtn}>Cancel</button>
                  <button onClick={acceptImport} style={st.sheetStartBtn}>Add all</button>
                </div>
              </>
            )}
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

  // ── Storage error strip ──
  // --danger rather than --clay: --clay is the app's ACTION colour and reads
  // as something to press. This is the only place outside a destructive
  // confirm that should look like a warning.
  errorStrip: { flexShrink: 0, display: "flex", alignItems: "flex-start", gap: "var(--space-2)", padding: "10px 12px", borderRadius: 14, border: "1px solid var(--danger)", background: "var(--head-worn)" },
  errorText: { flex: 1, minWidth: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--danger)", lineHeight: 1.45 },
  errorClose: { flexShrink: 0, width: 28, height: 28, marginTop: -2, border: "none", background: "transparent", color: "var(--danger)", fontSize: 20, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", borderRadius: 8 },

  // ── Category actions ──
  // Wraps since Share joined the row — three controls don't fit a narrow phone.
  catActions: { display: "flex", flexWrap: "wrap", gap: "var(--space-3)", marginTop: "var(--space-2)" },
  addBeatsBtn: { flex: 1, minHeight: 46, borderRadius: 14, border: "2px dashed var(--rule)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer", whiteSpace: "nowrap" },
  deleteCatBtn: { flexShrink: 0, minHeight: 46, padding: "0 14px", borderRadius: 14, border: "none", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer" },
  catNameInput: { flex: 1, minWidth: 0, padding: "12px 14px", borderRadius: 14, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", background: "var(--surface-paper)", outline: "none" },

  // ── Sharing ──
  browseTab: { display: "inline-flex", alignItems: "center", gap: 6 },
  pasteRow: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-2)" },
  codeError: { margin: "var(--space-2) 0 0", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--clay)", lineHeight: 1.5 },
  // The head's controls as one cluster, so space-between keeps the title
  // left and both buttons right.
  sheetHeadBtns: { flexShrink: 0, display: "flex", alignItems: "center", gap: 2 },
  sheetHeadBtn: { width: 44, height: 44, margin: "-8px 0 0 0", border: "none", background: "transparent", color: "var(--syahi-soft)", cursor: "pointer", display: "grid", placeItems: "center" },
  // Wraps, unlike sheetActions: three buttons don't fit one phone row.
  shareActions: { display: "flex", flexWrap: "wrap", gap: "var(--space-3)", marginTop: "var(--space-5)" },
  // Buttons in this file are plain text; these carry an icon too, and a
  // button's default centring leaves the glyph sitting on the text baseline.
  iconLabel: { display: "inline-flex", alignItems: "center", justifyContent: "center", gap: 8 },

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
