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
import * as sh from "../ui/styles.js";
import {
  InfoIcon, StartIcon, ShareIcon, GlobeIcon, CheckIcon, CheckDot, RadioDot,
  ChevronRightIcon, SearchIcon, BackIcon,
} from "../ui/icons.jsx";
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
 * BeatsView — the library, as a stack of full-width section cards.
 *
 * The page is a SHORT LIST OF SECTIONS the user drills into, not a tab bar
 * over one long list:
 *   - Search — a card with a live search box; matches from the whole library
 *     appear inline as you type. Never leaves the landing.
 *   - Browse — a card that opens the (future) community library; today it's
 *     where a pasted share code comes in.
 *   - Built in / Your beats / each user category — a card apiece that opens a
 *     dedicated PAGE listing that category's beats, with its edit controls.
 *
 * Everything is one component because it's all one screen's worth of state:
 * which page is open lives here (`page`) and dies with the screen, exactly
 * like the old tab did. App only hears the outcomes (select this, delete that).
 *
 * ONE LAYOUT RULE, from the design brief: any control that ADDS or EDITS sits
 * at the TOP of its page, above the list, so it's found without scrolling.
 * The list — which can be long — scrolls beneath it.
 */
function BeatsView({
  library, beat, beatId, ready,
  onSelect, onStart, onStartBeat, onEdit, onNewBeat, onDeleteBeat,
  pendingShare, onImportShare, onDismissShare, nav,
}) {
  const {
    allBeats, customBeats, isCustomBeat, categories, categoryBeats, catName,
    createCategory, deleteCategory, toggleBeatInCategory, reorderCategory,
    error, dismissError,
  } = library;

  // Which page of the Beats screen is showing. "landing" is the section list;
  // "browse" is the paste/community page; "category" drills into one library
  // (id is "builtin" | "custom" | a user category id).
  const [page, setPage] = useState({ name: "landing" });
  const [search, setSearch] = useState("");                 // library search box
  const [detailBeat, setDetailBeat] = useState(null);       // beat shown in the info sheet
  const [createCatOpen, setCreateCatOpen] = useState(false);
  const [newCatName, setNewCatName] = useState("");
  const [addBeatsOpen, setAddBeatsOpen] = useState(false);
  // What's being shared out: { kind:"beat", beat } | { kind:"category", name, beats }.
  const [shareTarget, setShareTarget] = useState(null);
  const [copied, setCopied] = useState(null);               // "link" | "code", briefly
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

  // The category the "category" page is showing, resolved to the live object
  // (null on the two fixed pages and everywhere off a user category). The
  // add-beats sheet and the top action row both read it, so it's derived once.
  const browsedCat = page.name === "category"
    ? categories.find(c => c.id === page.id) ?? null
    : null;

  // The whole beat row is the drag surface AND the tap-to-select target, so
  // the sensor distinguishes the two by a HOLD: press ~220ms to pick up and
  // reorder; a quick tap stays a tap (selects); a swipe that moves past the
  // tolerance before the delay elapses is left to the browser (list scrolls).
  const dragSensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { delay: 220, tolerance: 8 } }),
  );

  const goLanding = () => setPage({ name: "landing" });
  // Back steps up one level: a playlist's page returns to the Playlists hub;
  // everything else (the hub, Browse, the two fixed libraries) returns to the
  // landing.
  const back = () => {
    if (page.name === "category" && page.id !== "builtin" && page.id !== "custom") {
      setPage({ name: "playlists" });
    } else {
      goLanding();
    }
  };

  // The sheet closes straight away and the new category's page is opened once
  // the library confirms the id — which it mints, so there's nothing to open
  // until it comes back. A failed save leaves the user where they were, with
  // the reason in the strip at the top of the screen.
  async function handleCreateCategory() {
    const name = newCatName.trim();
    if (!name) return;
    setNewCatName("");
    setCreateCatOpen(false);
    const id = await createCategory(name);
    if (id) setPage({ name: "category", id });
  }
  function handleDeleteCategory(catId) {
    deleteCategory(catId);
    setPage({ name: "playlists" });
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
    // Land the user where the beats actually went — a list gets its own page,
    // a single beat goes to Your beats. The ids only exist after the import
    // mints them, which is why the id comes back from the call.
    const { catId } = (await onImportShare(payload)) ?? {};
    setPage({ name: "category", id: catId ?? "custom" });
  }
  function dismissImport() {
    setImportPreview(null);
    onDismissShare();
  }

  // ── Beat row ───────────────────────────────────────────────────────────
  // The row is a div, not a button: nesting the info/delete buttons inside a
  // row-button is invalid HTML and confuses screen readers. The whole row IS
  // the control: tap (or Enter) selects; inside a progression, press-and-hold
  // picks it up to reorder.
  //
  // `context` is where the row is being shown — a category id, or "search" —
  // and drives three things: whether selecting sets an active category (search
  // sets none, since a match can come from anywhere), whether the − remove
  // button shows (only inside a user category), and whether the × delete shows
  // (only on the Your-beats page). `drag` carries the sortable bits (empty
  // when the row can't be dragged).
  const stopPD = (e) => e.stopPropagation();
  const rowBody = (b, context, drag = {}) => {
    const sel = b.id === beatId;
    const inUserCat = categories.some(c => c.id === context);
    const deletable = context === "custom" && isCustomBeat(b.id);
    const { setNodeRef, style, listeners, isDragging } = drag;
    return (
      <div ref={setNodeRef} {...listeners}
        role="button" tabIndex={0} aria-pressed={sel}
        aria-label={inUserCat ? `${b.name} — tap to select, hold to reorder` : b.name}
        onClick={() => onSelect(b, context === "search" ? undefined : context)}
        onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); onSelect(b, context === "search" ? undefined : context); } }}
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
                  `Remove “${b.name}” from “${catName(context)}”? The beat itself is kept.`,
                  "Remove",
                  () => toggleBeatInCategory(context, b.id)
                );
              }}
              aria-label={`Remove ${b.name} from ${catName(context)}`}
              style={st.deleteBtn}>−</button>
          )}
          <button onPointerDown={stopPD} onClick={(e) => { e.stopPropagation(); setDetailBeat(b); }}
            aria-label={`About ${b.name}`} style={st.infoBtn}><InfoIcon /></button>
          {deletable && (
            <button onPointerDown={stopPD} onClick={(e) => {
                e.stopPropagation();
                askConfirm(
                  `Delete the beat “${b.name}”? This can't be undone, and it also leaves any playlists it's in.`,
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
  const renderRow = (b, context) => (
    <div key={b.id} style={{ display: "contents" }}>{rowBody(b, context)}</div>
  );

  // ── Landing: the section cards ─────────────────────────────────────────
  // A card the user opens into a page — name, a one-line count/hint, chevron.
  const categoryCard = (id, desc) => {
    const count = categoryBeats(id).length;
    return (
      <button key={id} onClick={() => setPage({ name: "category", id })}
        aria-label={`Open ${catName(id)}, ${count} ${count === 1 ? "beat" : "beats"}`}
        style={st.card}>
        <div style={st.cardTitleWrap}>
          <span style={st.cardTitle}>{catName(id)}</span>
          <span style={sh.beatRowMeta}>
            {desc ? `${desc} · ` : ""}{count} {count === 1 ? "beat" : "beats"}
          </span>
        </div>
        <span style={st.chevron}><ChevronRightIcon /></span>
      </button>
    );
  };

  const q = search.trim().toLowerCase();
  const results = q ? allBeats.filter(b => b.name.toLowerCase().includes(q)) : [];

  function renderLanding() {
    return (
      <>
        {/* Search — the box is the whole point of the card, so it sits up top
            with matches unfurling beneath it as you type. */}
        <div style={st.searchCard}>
          <span style={st.cardTitle}>Search</span>
          <div style={st.searchField}>
            <span style={st.searchIcon}><SearchIcon /></span>
            <input type="text" value={search} onChange={(e) => setSearch(e.target.value)}
              placeholder="Find a beat by name" aria-label="Search your beats"
              style={st.searchInput} />
            {search && (
              <button onClick={() => setSearch("")} aria-label="Clear search"
                style={st.searchClear}>×</button>
            )}
          </div>
          {!q && <p style={sh.emptyHint}>Search every beat in your library by name.</p>}
          {q && results.length === 0 && (
            <p style={sh.emptyHint}>No beats match “{search.trim()}”.</p>
          )}
          {results.length > 0 && (
            <div style={st.results}>
              {results.map(b => renderRow(b, "search"))}
            </div>
          )}
        </div>

        {/* Browse — opens the community page (paste-a-code today). */}
        <button onClick={() => setPage({ name: "browse" })}
          aria-label="Open Browse" style={st.card}>
          <div style={st.cardTitleWrap}>
            <span style={st.cardTitleRow}><GlobeIcon size={18} />Browse</span>
            <span style={sh.beatRowMeta}>Beats and playlists shared by other devotees</span>
          </div>
          <span style={st.chevron}><ChevronRightIcon /></span>
        </button>

        {categoryCard("builtin", "Ships with the app")}

        {/* Playlists — the user's own progressions live behind one card, so
            the landing stays short. It sits between the two fixed libraries.
            (Internally these are still "categories"; "playlist" is the name
            the user sees.) */}
        <button onClick={() => setPage({ name: "playlists" })}
          aria-label={`Open Playlists, ${categories.length} ${categories.length === 1 ? "playlist" : "playlists"}`}
          style={st.card}>
          <div style={st.cardTitleWrap}>
            <span style={st.cardTitle}>Playlists</span>
            <span style={sh.beatRowMeta}>
              Your kirtan progressions · {categories.length} {categories.length === 1 ? "playlist" : "playlists"}
            </span>
          </div>
          <span style={st.chevron}><ChevronRightIcon /></span>
        </button>

        {categoryCard("custom", "Built or saved by you")}
      </>
    );
  }

  // ── Playlists hub ──────────────────────────────────────────────────────
  // Every user playlist, plus the add action at the top. Opening one drills
  // into its own category page (which is why back from there returns here,
  // not to the landing).
  function renderPlaylists() {
    return (
      <>
        <button onClick={() => setCreateCatOpen(true)} style={st.newCatBtn}>
          + New playlist
        </button>
        {categories.length === 0
          ? <p style={sh.emptyHint}>
              No playlists yet. A playlist is a kirtan progression — an ordered
              set of beats that Home's ‹ › moves through. Make one to start.
            </p>
          : categories.map(c => categoryCard(c.id, "Progression"))}
      </>
    );
  }

  // ── Browse page ────────────────────────────────────────────────────────
  function renderBrowse() {
    return (
      <>
        {/* The paste box is the one thing to DO here, so it leads. */}
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
        <p style={sh.emptyHint}>
          A library of beats and playlists shared by other devotees is coming.
          Until then, this is where a beat someone sent you comes in — paste
          their code or link above. Opening a share link does the same thing.
        </p>
      </>
    );
  }

  // ── Category page ──────────────────────────────────────────────────────
  function renderCategory() {
    const id = page.id;
    if (id === "builtin") {
      const groups = [...new Set(BEATS.map(b => b.group))];
      return (
        <>
          <p style={sh.emptyHint}>The beats that come with the app. Open any one to customize your own copy.</p>
          {groups.map(g => (
            <div key={g} style={{ display: "contents" }}>
              <div style={st.sectionLabel}>{g}</div>
              {categoryBeats("builtin").filter(b => b.group === g).map(b => renderRow(b, "builtin"))}
            </div>
          ))}
        </>
      );
    }

    if (id === "custom") {
      return (
        <>
          {/* Add = make a new beat; the edit action leads the page. */}
          <div style={st.pageActions}>
            <button onClick={onNewBeat} style={st.addBeatsBtn}>+ New beat</button>
          </div>
          {customBeats.length === 0
            ? <p style={sh.emptyHint}>Nothing here yet — beats you build or customize appear here.</p>
            : customBeats.map(b => renderRow(b, "custom"))}
        </>
      );
    }

    // A user playlist (progression). If it vanished under us (deleted in
    // another tab), fall back to the hub rather than render nothing.
    if (!browsedCat) { setPage({ name: "playlists" }); return null; }
    const beats = categoryBeats(browsedCat.id);
    return (
      <>
        {/* Every edit control for the progression, at the top. */}
        <div style={st.pageActions}>
          <button onClick={() => setAddBeatsOpen(true)} style={st.addBeatsBtn}>+ Add beats</button>
          <button onClick={() => setShareTarget({
              kind: "category", name: browsedCat.name, beats,
            })}
            disabled={beats.length === 0}
            aria-label={`Share the list ${browsedCat.name}`}
            style={{ ...st.addBeatsBtn, ...st.iconLabel, flex: "0 0 auto", padding: "0 16px",
              opacity: beats.length ? 1 : 0.5 }}>
            <ShareIcon />Share
          </button>
          <button onClick={() => askConfirm(
              `Delete the playlist “${browsedCat.name}”? The beats themselves are kept.`,
              "Delete",
              () => handleDeleteCategory(browsedCat.id)
            )}
            style={st.deleteCatBtn}>
            Delete
          </button>
        </div>
        {beats.length === 0 && (
          <p style={sh.emptyHint}>
            An empty progression. Add beats in the order you want the kirtan to
            move through them — Home's ‹ › will follow that order. Press and hold
            a beat to drag it into place.
          </p>
        )}
        <DndContext sensors={dragSensors} collisionDetection={closestCenter}
          onDragEnd={({ active, over }) => {
            if (over) reorderCategory(browsedCat.id, active.id, over.id);
          }}>
          <SortableContext items={beats.map(b => b.id)} strategy={verticalListSortingStrategy}>
            {beats.map(b => (
              <SortableRow key={b.id} id={b.id}>
                {(bits) => rowBody(b, browsedCat.id, bits)}
              </SortableRow>
            ))}
          </SortableContext>
        </DndContext>
      </>
    );
  }

  // Header title tracks the page; the fixed pages read their name from the
  // library so a renamed category updates here too.
  const title = page.name === "landing" ? "Beats"
    : page.name === "browse" ? "Browse"
    : page.name === "playlists" ? "Playlists"
    : catName(page.id);

  return (
    <div className="kc-screen" style={sh.screenFixed}>
      <header style={sh.subHeader}>
        {page.name === "landing"
          ? <span style={{ width: 44 }} aria-hidden="true" />
          : <button onClick={back} aria-label="Back" style={sh.backBtn}><BackIcon /></button>}
        <h1 style={sh.subTitle}>{title}</h1>
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

      <section style={st.scroll}>
        {page.name === "landing" && renderLanding()}
        {page.name === "playlists" && renderPlaylists()}
        {page.name === "browse" && renderBrowse()}
        {page.name === "category" && renderCategory()}
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

      {/* New-category sheet — name it, create it, land in its page. */}
      {createCatOpen && (
        <div style={sh.sheetBackdrop} onClick={() => setCreateCatOpen(false)}>
          <div style={sh.sheet} role="dialog" aria-modal="true" aria-label="New playlist"
            onClick={(e) => e.stopPropagation()}>
            <div style={sh.sheetHead}>
              <h2 style={sh.sheetName}>New playlist</h2>
              <button onClick={() => setCreateCatOpen(false)} aria-label="Close" style={sh.sheetClose}>×</button>
            </div>
            <p style={sh.emptyHint}>
              A playlist is a kirtan progression: an ordered set of beats that
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
  // The single scrolling region, shared by every page. Cards on the landing,
  // rows on a category page — same column, same gap.
  scroll: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", gap: "var(--space-3)", padding: "2px" },
  sectionLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--syahi-soft)" },

  // ── Section cards (landing) ──
  // Full-width, tappable, with the name and a one-line hint on the left and a
  // chevron on the right that says "this opens a page".
  card: { display: "flex", alignItems: "center", gap: "var(--space-3)", width: "100%", padding: "18px", borderRadius: 18, border: "var(--rule-hairline)", background: "var(--head-worn)", textAlign: "left", cursor: "pointer" },
  cardTitleWrap: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 3 },
  cardTitle: { fontFamily: "var(--font-display)", fontWeight: 600, fontSize: 20, letterSpacing: "0.01em", color: "var(--ink-primary)" },
  cardTitleRow: { display: "inline-flex", alignItems: "center", gap: 8, fontFamily: "var(--font-display)", fontWeight: 600, fontSize: 20, letterSpacing: "0.01em", color: "var(--ink-primary)" },
  chevron: { flexShrink: 0, color: "var(--syahi-soft)", display: "grid", placeItems: "center" },
  // New-category leads the category group; dashed so it reads as "add", not as
  // one of the categories themselves.
  newCatBtn: { width: "100%", minHeight: 52, borderRadius: 16, border: "2px dashed var(--rule)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },

  // ── Search card ──
  searchCard: { display: "flex", flexDirection: "column", gap: "var(--space-3)", width: "100%", padding: "18px", borderRadius: 18, border: "var(--rule-hairline)", background: "var(--head-worn)" },
  searchField: { display: "flex", alignItems: "center", gap: "var(--space-2)", padding: "0 12px", borderRadius: 14, border: "var(--rule-hairline)", background: "var(--surface-paper)" },
  searchIcon: { flexShrink: 0, color: "var(--syahi-soft)", display: "grid", placeItems: "center" },
  searchInput: { flex: 1, minWidth: 0, padding: "12px 0", border: "none", background: "transparent", outline: "none", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)" },
  searchClear: { flexShrink: 0, width: 28, height: 28, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 20, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center" },
  results: { display: "flex", flexDirection: "column", gap: "var(--space-2)" },

  // ── Beat rows ──
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

  // ── Category page: top action row ──
  // Wraps on a narrow phone — three controls (Add / Share / Delete) don't fit
  // one line. This is the "actions at the top" row.
  pageActions: { display: "flex", flexWrap: "wrap", gap: "var(--space-3)" },
  addBeatsBtn: { flex: 1, minHeight: 46, borderRadius: 14, border: "2px dashed var(--rule)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer", whiteSpace: "nowrap" },
  deleteCatBtn: { flexShrink: 0, minHeight: 46, padding: "0 14px", borderRadius: 14, border: "none", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer" },
  catNameInput: { flex: 1, minWidth: 0, padding: "12px 14px", borderRadius: 14, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", background: "var(--surface-paper)", outline: "none" },

  // ── Sharing ──
  pasteRow: { display: "flex", gap: "var(--space-3)" },
  codeError: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--clay)", lineHeight: 1.5 },
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
