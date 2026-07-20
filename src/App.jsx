import { useState, useEffect, useRef, useCallback } from "react";
import { KirtanEngine } from "./engine/KirtanEngine.js";
import { BEATS } from "./data/beats.js";
import { LANES } from "./data/lanes.js";
import BeatEditor from "./BeatEditor.jsx";
import BeatStrip from "./BeatStrip.jsx";
import Splash from "./Splash.jsx";
import Wordmark from "./Wordmark.jsx";

const MIN_BPM = 40, MAX_BPM = 200;
const SAVED_KEY = "kirtan-custom-beats";
const CATS_KEY = "kirtan-user-categories";
const ACTIVE_CAT_KEY = "kirtan-active-category";

function loadUserCategories() {
  try { return JSON.parse(localStorage.getItem(CATS_KEY)) || []; }
  catch { return []; }
}

// Landscape = rotated phones, propped tablets, and most laptop windows.
// (Desktop monitors taller than 900px keep the centred portrait column.)
const LANDSCAPE_Q = "(orientation: landscape) and (max-height: 900px)";

function useLandscape() {
  const [landscape, setLandscape] = useState(
    () => window.matchMedia(LANDSCAPE_Q).matches
  );
  useEffect(() => {
    const mq = window.matchMedia(LANDSCAPE_Q);
    const onChange = (e) => setLandscape(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
  return landscape;
}

function loadSavedBeats() {
  try { return JSON.parse(localStorage.getItem(SAVED_KEY)) || []; }
  catch (e) { return []; }
}

// ── Bottom navigation ───────────────────────────────────────────────────────
// A persistent tab bar of FOUR EQUAL cells (icon + small label). Equal widths
// matter: mixed sizes made the wide middle buttons swallow near-miss taps
// aimed at the small end buttons. The active destination is filled clay.
// Switching pages does NOT stop playback (except the editor, which takes over
// the engine) so you can browse while a beat plays.
// Padlock for the tempo lock — open when free, closed when locked. An icon
// (not the words Lock/Locked) so the button's size never changes with state,
// which would resize the flexible slider beside it.
function LockIcon({ locked }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      {locked
        ? <path d="M8 11V7a4 4 0 0 1 8 0v4" />
        : <path d="M8 11V7a4 4 0 0 1 7.7-1.5" />}
    </svg>
  );
}

function HomeIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 10.2 12 3l9 7.2V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />
    </svg>
  );
}
function CogIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

function BeatsIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <path d="M4 6v12M9 9v6M14 4v16M19 8v8" />
    </svg>
  );
}
function PencilIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
    </svg>
  );
}

function BottomNav({ view, onHome, onBeats, onEditor, onSettings }) {
  const cell = (v) => ({ ...st.navCell, ...(view === v ? st.navActive : null) });
  const cur = (v) => (view === v ? "page" : undefined);
  return (
    <nav style={st.nav} aria-label="Primary">
      <button onClick={onHome} aria-current={cur("home")} style={cell("home")}>
        <HomeIcon /><span style={st.navLabel}>Home</span>
      </button>
      <button onClick={onBeats} aria-current={cur("beats")} style={cell("beats")}>
        <BeatsIcon /><span style={st.navLabel}>Beats</span>
      </button>
      <button onClick={onEditor} aria-current={cur("editor")} style={cell("editor")}>
        <PencilIcon /><span style={st.navLabel}>Editor</span>
      </button>
      <button onClick={onSettings} aria-current={cur("settings")} style={cell("settings")}>
        <CogIcon /><span style={st.navLabel}>Settings</span>
      </button>
    </nav>
  );
}

// Horizontal scroller with gradient edge fades: content visibly "melts"
// off whichever side has more to see, and the fade vanishes at that end —
// the standard cue that a row scrolls. Used by the category tab bar.
function ScrollFadeRow({ children, rowStyle }) {
  const ref = useRef(null);
  const [fades, setFades] = useState({ left: false, right: false });

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const left = el.scrollLeft > 2;
      const right = el.scrollLeft < el.scrollWidth - el.clientWidth - 2;
      setFades(f => (f.left === left && f.right === right ? f : { left, right }));
    };
    update();
    el.addEventListener("scroll", update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => { el.removeEventListener("scroll", update); ro.disconnect(); };
    // children: re-measure when tabs are added/removed (content width
    // changes don't fire the ResizeObserver, which watches el's own box).
  }, [children]);

  return (
    <div style={{ position: "relative", flexShrink: 0 }}>
      <div ref={ref} style={rowStyle}>{children}</div>
      <div aria-hidden="true" style={{ ...st.edgeFade, left: 0,
        background: "linear-gradient(90deg, var(--head), transparent)",
        opacity: fades.left ? 1 : 0 }} />
      <div aria-hidden="true" style={{ ...st.edgeFade, right: 0,
        background: "linear-gradient(270deg, var(--head), transparent)",
        opacity: fades.right ? 1 : 0 }} />
    </div>
  );
}

function InfoIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5" />
      <path d="M12 7.5h.01" />
    </svg>
  );
}

function MixerIcon() {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <path d="M5 21v-6M5 11V3M12 21v-10M12 7V3M19 21v-4M19 13V3" />
      <path d="M2 14h6M9 10h6M16 16h6" />
    </svg>
  );
}
function SpeakerIcon({ muted }) {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M11 5 6 9H3v6h3l5 4z" />
      {muted
        ? <path d="m16 9 6 6M22 9l-6 6" />
        : <path d="M15.5 8.5a5 5 0 0 1 0 7" />}
    </svg>
  );
}

// Membership check for the add-beats sheet — square, clay tick when in.
function CheckDot({ checked }) {
  return (
    <span aria-hidden="true" style={{ flexShrink: 0, width: 24, height: 24, borderRadius: 8,
      display: "grid", placeItems: "center", fontSize: 15, fontWeight: 800, lineHeight: 1,
      border: `2px solid ${checked ? "var(--clay)" : "var(--rule)"}`,
      background: checked ? "var(--clay)" : "transparent",
      color: "var(--on-clay)" }}>
      {checked ? "✓" : ""}
    </span>
  );
}

// Selection radio for the beat list — hollow ring, saffron dot when chosen.
function RadioDot({ selected }) {
  return (
    <span aria-hidden="true" style={{ flexShrink: 0, width: 24, height: 24, borderRadius: "50%",
      display: "grid", placeItems: "center",
      border: `2px solid ${selected ? "var(--accent-action)" : "var(--rule)"}` }}>
      {selected && <span style={{ width: 12, height: 12, borderRadius: "50%", background: "var(--accent-action)" }} />}
    </span>
  );
}

function App() {
  const engineRef = useRef(null);
  if (engineRef.current === null) engineRef.current = new KirtanEngine();
  const engine = engineRef.current;
  const isLandscape = useLandscape();

  const [view, setView] = useState("home"); // "home" | "beats" | "editor" | "settings"
  const [entered, setEntered] = useState(false); // splash shown until Begin
  const [detailBeat, setDetailBeat] = useState(null); // beat shown in the Beats-page info sheet
  const [pickerOpen, setPickerOpen] = useState(false); // Home quick-pick sheet
  const [pickerTab, setPickerTab] = useState("builtin"); // category shown in the quick-pick
  const [mixerOpen, setMixerOpen] = useState(false);   // mixer sheet
  const [endVolumes, setEndVolumes] = useState({});    // per-lane faders, default 1

  // ── Categories ──
  // Two are always present: "builtin" and "custom". User categories are
  // ordered lists of beat ids — each one is a kirtan progression. The
  // ACTIVE category is the one the current beat was chosen from: Home's
  // chevrons and quick-pick cycle within it.
  const [categories, setCategories] = useState(loadUserCategories);
  const [activeCat, setActiveCat] = useState(() => localStorage.getItem(ACTIVE_CAT_KEY) || "builtin");
  const [browseTab, setBrowseTab] = useState("builtin"); // which tab the Beats page shows
  const [createCatOpen, setCreateCatOpen] = useState(false);
  const [newCatName, setNewCatName] = useState("");
  const [addBeatsOpen, setAddBeatsOpen] = useState(false); // add-beats sheet for the browsed category
  const [editorInitial, setEditorInitial] = useState(null); // beat to pre-fill, or null for a new beat

  const [customBeats, setCustomBeats] = useState(loadSavedBeats);
  const allBeats = [...BEATS, ...customBeats];

  const [ready, setReady]     = useState(false);
  const [beatId, setBeatId]   = useState(BEATS[0].id);
  const [bpm, setBpm]         = useState(BEATS[0].bpm);
  const [playing, setPlaying] = useState(false);
  const [step, setStep]       = useState(-1);
  const [volume, setVolume]   = useState(0.9);
  const [mutedEnds, setMutedEnds] = useState({});
  const [tempoLocked, setTempoLocked] = useState(false);

  const tapTimesRef = useRef([]);
  const beat = allBeats.find(b => b.id === beatId) || allBeats[0];

  // Stable identity so BeatStrip's rAF effect doesn't re-subscribe on
  // every render (the engine ref never changes).
  const getPhase = useCallback(() => engine.getPhase(), [engine]);

  useEffect(() => {
    engine.loadSounds({
      dayan_open:   "/sounds/dayan_open.wav",
      dayan_closed: "/sounds/dayan_closed.wav",
      bayan_open:   "/sounds/bayan_open.wav",
      bayan_closed: "/sounds/bayan_closed.wav",
    });
    engine.on("ready",   () => setReady(true));
    engine.on("started", () => setPlaying(true));
    engine.on("stopped", () => setPlaying(false));
    engine.on("step",    (s) => setStep(s));
    engine.setVolume(volume);
  }, []);

  async function togglePlay() {
    if (!ready) return;            // sounds still loading — ignore the tap
    await engine.unlock();
    if (playing) engine.stop();
    else { engine.setBpm(bpm); engine.setBeat(beat); engine.start(); }
  }

  // From the Beats page: jump to Home and start the selected beat playing.
  async function startBeat() {
    setView("home");
    if (!ready || playing) return;
    await engine.unlock();
    engine.setBpm(bpm); engine.setBeat(beat); engine.start();
  }

  // From the info sheet: select THAT beat and start it. Takes the beat as an
  // argument because React state (beatId) won't have updated yet this tick.
  async function startFromDetail(b) {
    setDetailBeat(null);
    selectBeat(b);
    setView("home");
    if (!ready || playing) return; // playing: selectBeat already switched the loop live
    await engine.unlock();
    engine.setBpm(tempoLocked ? bpm : b.bpm);
    engine.setBeat(b);
    engine.start();
  }

  function saveCategories(updated) {
    setCategories(updated);
    try { localStorage.setItem(CATS_KEY, JSON.stringify(updated)); } catch { /* storage full/blocked */ }
  }

  /** The ordered beats of a category ("builtin" | "custom" | user cat id). */
  function categoryBeats(catId) {
    if (catId === "builtin") return BEATS;
    if (catId === "custom") return customBeats;
    const c = categories.find(c => c.id === catId);
    if (!c) return allBeats;
    return c.beatIds.map(id => allBeats.find(b => b.id === id)).filter(Boolean);
  }
  function catName(catId) {
    if (catId === "builtin") return "Built in";
    if (catId === "custom") return "Your beats";
    return categories.find(c => c.id === catId)?.name ?? "Beats";
  }

  // Selecting a beat FROM a category makes that category the active
  // cycling context for Home's chevrons and quick-pick.
  function selectBeat(b, fromCat) {
    setBeatId(b.id);
    engine.setBeat(b);
    if (!tempoLocked) { setBpm(b.bpm); engine.setBpm(b.bpm); }
    if (fromCat) {
      setActiveCat(fromCat);
      try { localStorage.setItem(ACTIVE_CAT_KEY, fromCat); } catch { /* non-fatal */ }
    }
  }

  // Home chevrons: step through the ACTIVE category in its order (wraps).
  // Switches the loop live if playing — the mid-kirtan "next step in the
  // progression" move.
  function cycleBeat(dir) {
    let list = categoryBeats(activeCat);
    if (list.length === 0) list = allBeats;
    const i = list.findIndex(b => b.id === beatId);
    const next = i === -1 ? list[0] : list[(i + dir + list.length) % list.length];
    selectBeat(next);
  }

  function createCategory() {
    const name = newCatName.trim();
    if (!name) return;
    const cat = { id: "cat_" + Date.now(), name, beatIds: [] };
    saveCategories([...categories, cat]);
    setNewCatName("");
    setCreateCatOpen(false);
    setBrowseTab(cat.id);
  }
  function deleteCategory(catId) {
    const c = categories.find(c => c.id === catId);
    if (!c) return;
    if (!window.confirm(`Delete the category “${c.name}”? (The beats themselves are kept.)`)) return;
    saveCategories(categories.filter(c => c.id !== catId));
    if (browseTab === catId) setBrowseTab("builtin");
    if (activeCat === catId) {
      setActiveCat("builtin");
      try { localStorage.setItem(ACTIVE_CAT_KEY, "builtin"); } catch { /* non-fatal */ }
    }
  }
  function toggleBeatInCategory(catId, id) {
    saveCategories(categories.map(c => c.id !== catId ? c : {
      ...c,
      beatIds: c.beatIds.includes(id) ? c.beatIds.filter(x => x !== id) : [...c.beatIds, id],
    }));
  }
  // Reorder within a progression: swap the beat one slot up/down.
  // Used by the drag handler (one swap per midpoint crossed) and by
  // arrow keys on a focused drag handle.
  function moveInCategory(catId, id, dir) {
    saveCategories(categories.map(c => {
      if (c.id !== catId) return c;
      const ids = [...c.beatIds];
      const i = ids.indexOf(id), j = i + dir;
      if (i === -1 || j < 0 || j >= ids.length) return c;
      [ids[i], ids[j]] = [ids[j], ids[i]];
      return { ...c, beatIds: ids };
    }));
  }

  // ── Drag-to-reorder (user categories only) ──
  // HTML5 drag events don't exist on touch, so this is pointer-based:
  // grabbing a row's handle captures the pointer; crossing a neighbour
  // row's vertical midpoint swaps one position (the list re-renders and
  // the drag continues from the new slot). Each swap persists.
  const dragRef = useRef(null);        // { catId, beatId, index } while dragging
  const rowRefs = useRef([]);          // row elements of the browsed category
  const [dragIndex, setDragIndex] = useState(-1); // for the dragged row's styling

  function startDrag(e, catId, beatId, index) {
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    dragRef.current = { catId, beatId, index };
    setDragIndex(index);
  }
  function dragMove(e) {
    const d = dragRef.current;
    if (!d) return;
    const prevEl = rowRefs.current[d.index - 1];
    const nextEl = rowRefs.current[d.index + 1];
    if (prevEl) {
      const r = prevEl.getBoundingClientRect();
      if (e.clientY < r.top + r.height / 2) {
        moveInCategory(d.catId, d.beatId, -1);
        d.index -= 1; setDragIndex(d.index);
        return;
      }
    }
    if (nextEl) {
      const r = nextEl.getBoundingClientRect();
      if (e.clientY > r.top + r.height / 2) {
        moveInCategory(d.catId, d.beatId, 1);
        d.index += 1; setDragIndex(d.index);
      }
    }
  }
  function endDrag() {
    dragRef.current = null;
    setDragIndex(-1);
  }
  function changeBpm(value) { setBpm(value); engine.setBpm(value); }
  function changeVolume(value) { setVolume(value); engine.setVolume(value); }
  function changeEndVolume(end, value) {
    setEndVolumes(prev => ({ ...prev, [end]: value }));
    engine.setEndVolume(end, value);
  }

  function toggleMute(end) {
    setMutedEnds(prev => {
      const next = { ...prev, [end]: !prev[end] };
      engine.setEndMuted(end, !!next[end]);
      return next;
    });
  }

  function handleTap() {
    const now = Date.now();
    const taps = tapTimesRef.current;
    taps.push(now);
    if (taps.length > 4) taps.shift();
    if (taps.length >= 2) {
      const gaps = [];
      for (let i = 1; i < taps.length; i++) gaps.push(taps[i] - taps[i - 1]);
      if (gaps[gaps.length - 1] > 2000) { tapTimesRef.current = [now]; return; }
      const avgGap = gaps.reduce((a, b) => a + b, 0) / gaps.length;
      const clamped = Math.max(MIN_BPM, Math.min(MAX_BPM, Math.round(60000 / avgGap)));
      changeBpm(clamped);
    }
  }

  const isCustomBeat = (id) => customBeats.some(b => b.id === id);

  // Upsert by id: editing a custom beat overwrites its entry, a new beat
  // (or a fork of a built-in) appends. Only ever touches customBeats, so the
  // read-only BEATS are always safe.
  function handleSaveBeat(newBeat) {
    setCustomBeats(prev => {
      const exists = prev.some(b => b.id === newBeat.id);
      const updated = exists
        ? prev.map(b => (b.id === newBeat.id ? newBeat : b))
        : [...prev, newBeat];
      try { localStorage.setItem(SAVED_KEY, JSON.stringify(updated)); } catch (e) {}
      return updated;
    });
    selectBeat(newBeat, "custom"); // reflect the saved beat in the engine + main view (engine already stopped)
  }

  // Open the editor blank (new beat).
  function openNewBeat() {
    engine.stop(); setPlaying(false);
    setEditorInitial(null);
    setView("editor");
  }
  // Open the editor on a beat (defaults to the loaded one). Custom beats edit
  // in place (same id); built-ins fork into a fresh custom copy that the
  // original never sees.
  function openEditBeat(target = beat) {
    engine.stop(); setPlaying(false);
    const seed = isCustomBeat(target.id)
      ? target
      : {
          ...target,
          id: "custom_" + target.name.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now(),
          name: target.name + " (custom)",
          note: "Custom",
          description: undefined,
        };
    setEditorInitial(seed);
    setView("editor");
  }
  function deleteCustomBeat(id, e) {
    e.stopPropagation();
    const updated = customBeats.filter(b => b.id !== id);
    setCustomBeats(updated);
    try { localStorage.setItem(SAVED_KEY, JSON.stringify(updated)); } catch (e) {}
    // Drop the beat from any user categories referencing it.
    if (categories.some(c => c.beatIds.includes(id))) {
      saveCategories(categories.map(c => ({ ...c, beatIds: c.beatIds.filter(x => x !== id) })));
    }
    if (beatId === id) selectBeat(BEATS[0], "builtin");
  }

  // The Begin tap does double duty: it satisfies the browser's "no sound
  // before a user gesture" rule (engine.unlock) on the way into the app.
  async function handleBegin() {
    await engine.unlock();
    setEntered(true);
  }

  if (!entered) {
    return <Splash onBegin={handleBegin} ready={ready} />;
  }

  // Persistent tab bar + the fixed (non-scrolling) screen frame shared by the
  // Home, Beats and Settings pages. The Editor keeps its own scrolling frame.
  const screenFixed = { ...st.screen, minHeight: 0 };
  const bottomNav = (
    <BottomNav
      view={view}
      onHome={() => setView("home")}
      onBeats={() => setView("beats")}
      onEditor={openNewBeat}
      onSettings={() => setView("settings")}
    />
  );

  // ── Editor view ──
  if (view === "editor") {
    return (
      <BeatEditor engine={engine} initialBeat={editorInitial} onSave={handleSaveBeat} nav={bottomNav}
        onClose={() => { engine.stop(); setPlaying(false); setEditorInitial(null); setView("home"); }} />
    );
  }

  // ── Beats view — library list: built-ins and the user's beats in
  //    separate sections. Row tap = select (the fast path); the ⓘ opens
  //    an info sheet with the full strip, the description, and
  //    Edit / Start. Rows preview the real pattern via a mini strip. ──
  if (view === "beats") {
    // The row is a div, not a button: nesting the info/delete buttons inside
    // a row-button is invalid HTML and confuses screen readers. The name area
    // is the keyboard-reachable select control; the whole row stays tappable
    // via the div's onClick (inner buttons stop propagation).
    const inUserCat = categories.some(c => c.id === browseTab);
    const renderRow = (b, i) => {
      const sel = b.id === beatId;
      const dragging = inUserCat && dragIndex === i;
      return (
        <div key={b.id} onClick={() => selectBeat(b, browseTab)}
          ref={inUserCat ? (el) => { rowRefs.current[i] = el; } : undefined}
          style={{ ...st.beatRow,
            borderColor: dragging || sel ? "var(--clay)" : "var(--rule)",
            background: dragging ? "var(--head-sunken)" : "var(--head-worn)" }}>
          <div style={st.beatRowTop}>
            {inUserCat && (
              /* Drag handle: pointer-drag to reorder; arrow keys work too. */
              <button
                onPointerDown={(e) => startDrag(e, browseTab, b.id, i)}
                onPointerMove={dragMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(e) => {
                  if (e.key === "ArrowUp") { e.preventDefault(); moveInCategory(browseTab, b.id, -1); }
                  if (e.key === "ArrowDown") { e.preventDefault(); moveInCategory(browseTab, b.id, 1); }
                }}
                aria-label={`Reorder ${b.name} — drag, or use arrow keys`}
                style={st.dragHandle}>≡</button>
            )}
            <button onClick={() => selectBeat(b, browseTab)} aria-pressed={sel} style={st.beatRowSelect}>
              <span style={st.beatRowName}>{b.name}</span>
              <span style={st.beatRowMeta}>{b.note} · {b.steps} cells</span>
            </button>
            <button onClick={(e) => { e.stopPropagation(); setDetailBeat(b); }}
              aria-label={`About ${b.name}`} style={st.infoBtn}><InfoIcon /></button>
            {inUserCat && (
              <button onClick={(e) => { e.stopPropagation(); toggleBeatInCategory(browseTab, b.id); }}
                aria-label={`Remove ${b.name} from this category`} style={st.deleteBtn}>×</button>
            )}
            {!inUserCat && isCustomBeat(b.id) && (
              <button onClick={(e) => deleteCustomBeat(b.id, e)} aria-label={`Delete ${b.name}`}
                style={st.deleteBtn}>×</button>
            )}
            <RadioDot selected={sel} />
          </div>
          <BeatStrip beat={b} mini />
        </div>
      );
    };

    const browsedCat = categories.find(c => c.id === browseTab);
    const builtinGroups = [...new Set(BEATS.map(b => b.group))];

    return (
      <div className="kc-screen" style={screenFixed}>
        <header style={st.subHeader}>
          <span style={{ width: 44 }} aria-hidden="true" />
          <h1 style={st.subTitle}>Beats</h1>
          <span style={{ width: 44 }} aria-hidden="true" />
        </header>

        {/* Category tabs: the two fixed ones, the user's own, and +.
            Edge fades signal when the row scrolls. */}
        <ScrollFadeRow rowStyle={st.catTabs}>
          {["builtin", "custom", ...categories.map(c => c.id)].map(id => (
            <button key={id} onClick={() => setBrowseTab(id)}
              style={{ ...st.catTab, ...(browseTab === id ? st.catTabActive : null) }}
              aria-pressed={browseTab === id}>
              {catName(id)}
            </button>
          ))}
          <button onClick={() => setCreateCatOpen(true)} aria-label="New category"
            style={{ ...st.catTab, flexShrink: 0 }}>+</button>
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
              ? <p style={st.emptyHint}>Nothing here yet — beats you build or customize will appear here.</p>
              : customBeats.map(renderRow)
          )}

          {browsedCat && (
            <>
              {categoryBeats(browsedCat.id).length === 0 && (
                <p style={st.emptyHint}>
                  An empty progression. Add beats in the order you want the kirtan
                  to move through them — Home's ‹ › will follow that order.
                </p>
              )}
              {categoryBeats(browsedCat.id).map(renderRow)}
              <div style={st.catActions}>
                <button onClick={() => setAddBeatsOpen(true)} style={st.addBeatsBtn}>+ Add beats</button>
                <button onClick={() => deleteCategory(browsedCat.id)} style={st.deleteCatBtn}>
                  Delete category
                </button>
              </div>
            </>
          )}
        </section>
        <button onClick={startBeat} disabled={!ready} style={{ ...st.startBtn, opacity: ready ? 1 : 0.5 }}>
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5 19 12 7 19 Z" fill="currentColor" /></svg>
          Start · {beat.name}
        </button>
        {bottomNav}

        {/* Info sheet — slides over the list; backdrop tap or × closes. */}
        {detailBeat && (
          <div style={st.sheetBackdrop} onClick={() => setDetailBeat(null)}>
            <div style={st.sheet} role="dialog" aria-modal="true" aria-label={detailBeat.name}
              onClick={(e) => e.stopPropagation()}>
              <div style={st.sheetHead}>
                <h2 style={st.sheetName}>{detailBeat.name}</h2>
                <button onClick={() => setDetailBeat(null)} aria-label="Close" style={st.sheetClose}>×</button>
              </div>
              <span style={st.beatRowMeta}>
                {detailBeat.note} · {detailBeat.steps} cells · suggested {detailBeat.bpm} BPM
              </span>
              <div style={{ margin: "var(--space-4) 0" }}>
                <BeatStrip beat={detailBeat} />
              </div>
              <p style={st.sheetDesc}>
                {detailBeat.description || "One of your own beats, built in the editor."}
              </p>
              <div style={st.sheetActions}>
                <button onClick={() => { setDetailBeat(null); openEditBeat(detailBeat); }} style={st.sheetEditBtn}>
                  {isCustomBeat(detailBeat.id) ? "Edit" : "Customize"}
                </button>
                <button onClick={() => startFromDetail(detailBeat)} disabled={!ready}
                  style={{ ...st.sheetStartBtn, opacity: ready ? 1 : 0.5 }}>
                  Start
                </button>
              </div>
            </div>
          </div>
        )}

        {/* New-category sheet — name it, create it, land in its tab. */}
        {createCatOpen && (
          <div style={st.sheetBackdrop} onClick={() => setCreateCatOpen(false)}>
            <div style={st.sheet} role="dialog" aria-modal="true" aria-label="New category"
              onClick={(e) => e.stopPropagation()}>
              <div style={st.sheetHead}>
                <h2 style={st.sheetName}>New category</h2>
                <button onClick={() => setCreateCatOpen(false)} aria-label="Close" style={st.sheetClose}>×</button>
              </div>
              <p style={st.emptyHint}>
                A category is a kirtan progression: an ordered set of beats that
                Home's ‹ › will move through.
              </p>
              <div style={{ display: "flex", gap: "var(--space-3)", marginTop: "var(--space-4)" }}>
                <input type="text" value={newCatName} onChange={(e) => setNewCatName(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") createCategory(); }}
                  placeholder="e.g. Sunday feast kirtan" autoFocus style={st.catNameInput} />
                <button onClick={createCategory} disabled={!newCatName.trim()}
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
          <div style={st.sheetBackdrop} onClick={() => setAddBeatsOpen(false)}>
            <div style={st.sheet} role="dialog" aria-modal="true" aria-label={`Add beats to ${browsedCat.name}`}
              onClick={(e) => e.stopPropagation()}>
              <div style={st.sheetHead}>
                <h2 style={st.sheetName}>Add to {browsedCat.name}</h2>
                <button onClick={() => setAddBeatsOpen(false)} aria-label="Done" style={st.sheetClose}>×</button>
              </div>
              <div style={st.pickList}>
                {allBeats.map(b => {
                  const inCat = browsedCat.beatIds.includes(b.id);
                  return (
                    <button key={b.id} onClick={() => toggleBeatInCategory(browsedCat.id, b.id)}
                      aria-pressed={inCat}
                      style={{ ...st.pickRow, borderColor: inCat ? "var(--clay)" : "var(--rule)" }}>
                      <div style={st.beatRowTop}>
                        <span style={st.beatRowName}>{b.name}</span>
                        <span style={{ ...st.beatRowMeta, flex: 1 }}>{b.note}</span>
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
      </div>
    );
  }

  // ── Settings view ──
  if (view === "settings") {
    return (
      <div className="kc-screen" style={screenFixed}>
        <header style={st.subHeader}>
          <button onClick={() => setView("home")} style={st.backBtn} aria-label="Back">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M15 5 8 12l7 7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <h1 style={st.subTitle}>Settings</h1>
          <span style={{ width: 44 }} />
        </header>
        <section style={{ ...st.controls, flex: 1 }}>
          <p style={st.subtitle}>More settings coming soon.</p>
        </section>
        {bottomNav}
      </div>
    );
  }

  // ── Home view ──
  const fillPct = ((bpm - MIN_BPM) / (MAX_BPM - MIN_BPM)) * 100;

  const playIcon = playing ? (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="5" y="4" width="5" height="16" rx="1.5" fill="currentColor" />
      <rect x="14" y="4" width="5" height="16" rx="1.5" fill="currentColor" />
    </svg>
  ) : (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M8 4.5 20 12 8 19.5 Z" fill="currentColor" />
    </svg>
  );

  // Quick-pick sheet — shared by both Home orientations. Opens on the
  // ACTIVE category but carries its own tab row, so the user can hop to
  // another progression without visiting the Beats page. Picking a beat
  // makes its tab the active cycling context and dismisses.
  function openPicker() {
    setPickerTab(activeCat);
    setPickerOpen(true);
  }
  const pickerBeats = categoryBeats(pickerTab);
  const beatPicker = pickerOpen && (
    <div style={st.sheetBackdrop} onClick={() => setPickerOpen(false)}>
      <div style={st.sheet} role="dialog" aria-modal="true" aria-label="Choose a beat"
        onClick={(e) => e.stopPropagation()}>
        <div style={st.sheetHead}>
          <h2 style={st.sheetName}>Choose a beat</h2>
          <button onClick={() => setPickerOpen(false)} aria-label="Close" style={st.sheetClose}>×</button>
        </div>
        <ScrollFadeRow rowStyle={st.catTabs}>
          {["builtin", "custom", ...categories.map(c => c.id)].map(id => (
            <button key={id} onClick={() => setPickerTab(id)}
              style={{ ...st.catTab, ...(pickerTab === id ? st.catTabActive : null) }}
              aria-pressed={pickerTab === id}>
              {catName(id)}
            </button>
          ))}
        </ScrollFadeRow>
        <div style={st.pickList}>
          {pickerBeats.length === 0 && (
            <p style={st.emptyHint}>This category is empty — add beats to it on the Beats page.</p>
          )}
          {pickerBeats.map(b => {
            const sel = b.id === beatId;
            return (
              <button key={b.id} onClick={() => { selectBeat(b, pickerTab); setPickerOpen(false); }}
                style={{ ...st.pickRow, borderColor: sel ? "var(--clay)" : "var(--rule)" }}>
                <div style={st.beatRowTop}>
                  <span style={st.beatRowName}>{b.name}</span>
                  <span style={{ ...st.beatRowMeta, flex: 1 }}>{b.note}</span>
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

  // Mixer sheet — master fader plus one fader + mute per lane. Shared by
  // both Home orientations (and it's where muting properly lives; the
  // strip's small lane labels remain a shortcut).
  const mixerSheet = mixerOpen && (
    <div style={st.sheetBackdrop} onClick={() => setMixerOpen(false)}>
      <div style={st.sheet} role="dialog" aria-modal="true" aria-label="Mixer"
        onClick={(e) => e.stopPropagation()}>
        <div style={st.sheetHead}>
          <h2 style={st.sheetName}>Mixer</h2>
          <button onClick={() => setMixerOpen(false)} aria-label="Close" style={st.sheetClose}>×</button>
        </div>
        <div style={st.mixRows}>
          <div style={st.mixRow}>
            <span style={st.mixLabel}>Master</span>
            <input className="kc-range" type="range" min={0} max={100} value={Math.round(volume * 100)}
              onChange={(e) => changeVolume(Number(e.target.value) / 100)}
              style={{ "--fill": volume * 100 + "%", flex: 1 }} aria-label="Master volume" />
            <span style={{ width: 44 }} aria-hidden="true" />
          </div>
          {LANES.map(l => {
            const v = endVolumes[l.id] ?? 1;
            const muted = !!mutedEnds[l.id];
            return (
              <div key={l.id} style={st.mixRow}>
                <span style={{ ...st.mixLabel, color: l.color }}>{l.label}</span>
                <input className="kc-range" type="range" min={0} max={100} value={Math.round(v * 100)}
                  onChange={(e) => changeEndVolume(l.id, Number(e.target.value) / 100)}
                  style={{ "--fill": v * 100 + "%", flex: 1, opacity: muted ? 0.35 : 1 }}
                  aria-label={`${l.label} volume`} />
                <button onClick={() => toggleMute(l.id)} aria-pressed={muted}
                  aria-label={muted ? `Unmute ${l.label}` : `Mute ${l.label}`}
                  style={{ ...st.muteBtn, background: muted ? "var(--head-sunken)" : "transparent",
                    color: muted ? "var(--clay)" : "var(--syahi-soft)" }}>
                  <SpeakerIcon muted={muted} />
                </button>
              </div>
            );
          })}
        </div>
        <p style={{ ...st.emptyHint, marginTop: "var(--space-4)" }}>
          100% is balanced for phone speakers — the bass end is already boosted.
        </p>
      </div>
    </div>
  );

  // ── Home, landscape: the propped-up layout. Phone-height landscape
  //    leaves ~120-200px for everything besides the strip and nav, so
  //    the ENTIRE transport collapses into one 44px rail: edit, name,
  //    BPM + slider, Lock/Tap, Play. Volume sits out of landscape (the
  //    mixer button will take its slot). The strip gets all remaining
  //    height, cells sized from the slot via container units. ──
  if (isLandscape) {
    return (
      <div className="kc-screen" style={st.landScreen}>
        <main style={st.landStripWrap}>
          <BeatStrip
            beat={beat}
            step={step}
            playing={playing}
            getPhase={getPhase}
            mutedEnds={mutedEnds}
            onToggleMute={toggleMute}
          />
        </main>

        <div style={st.landRail}>
          <button onClick={() => openEditBeat()} style={st.landEditBtn}
            aria-label={isCustomBeat(beat.id) ? "Edit this beat" : "Customize this beat"}>
            <PencilIcon />
          </button>
          <button onClick={() => cycleBeat(-1)} aria-label="Previous beat" style={st.chevBtnSm}>‹</button>
          <button onClick={openPicker} style={st.landNameBtn}
            aria-haspopup="dialog" aria-expanded={pickerOpen}>
            {beat.name} <span style={st.nameCaret}>▾</span>
          </button>
          <button onClick={() => cycleBeat(1)} aria-label="Next beat" style={st.chevBtnSm}>›</button>
          <span style={st.landBpmNum}>{bpm}</span>
          <span style={st.bpmUnit}>BPM</span>
          <input className="kc-range" type="range" min={MIN_BPM} max={MAX_BPM} value={bpm}
            onChange={(e) => changeBpm(Number(e.target.value))} disabled={tempoLocked}
            style={{ "--fill": fillPct + "%", flex: 1, minWidth: 90, opacity: tempoLocked ? 0.5 : 1, cursor: tempoLocked ? "not-allowed" : "pointer" }}
            aria-label="Tempo" />
          <button onClick={() => setTempoLocked(v => !v)}
            style={{ ...st.lockBtn,
              background: tempoLocked ? "var(--clay)" : "transparent",
              color: tempoLocked ? "var(--on-clay)" : "var(--syahi-soft)" }}
            aria-label={tempoLocked ? "Unlock tempo" : "Lock tempo"}
            aria-pressed={tempoLocked}>
            <LockIcon locked={tempoLocked} />
          </button>
          <button onClick={handleTap} style={st.tapBtn} aria-label="Tap tempo">Tap</button>
          <button onClick={() => setMixerOpen(true)} style={st.landEditBtn} aria-label="Mixer"
            aria-haspopup="dialog" aria-expanded={mixerOpen}>
            <MixerIcon />
          </button>
          <button onClick={togglePlay} disabled={!ready}
            style={{ ...st.landPlayBtn, opacity: ready ? 1 : 0.5 }}
            aria-label={playing ? "Pause" : "Play"}>
            {playIcon}
          </button>
        </div>

        <div style={st.landNavWrap}>{bottomNav}</div>
        {beatPicker}
        {mixerSheet}
      </div>
    );
  }

  return (
    <div className="kc-screen" style={screenFixed}>
      {/* Small live wordmark — same component as the splash hero. */}
      <header style={st.header}>
        <Wordmark style={{ "--wm-size": "clamp(18px, 3.6vh, 24px)" }} />
      </header>

      {/* Beat switcher: ‹ › step through beats; tapping the name opens
          the quick-pick sheet. Pencil edits the loaded beat. */}
      <div style={st.beatHead}>
        <button onClick={() => cycleBeat(-1)} aria-label="Previous beat" style={st.chevBtn}>‹</button>
        <button onClick={openPicker} style={st.nameBtn}
          aria-haspopup="dialog" aria-expanded={pickerOpen}>
          <h1 style={st.beatName}>{beat.name} <span style={st.nameCaret}>▾</span></h1>
          <span style={st.beatMeta}>{beat.note} · {beat.steps} cells</span>
        </button>
        <button onClick={() => cycleBeat(1)} aria-label="Next beat" style={st.chevBtn}>›</button>
        <button onClick={() => openEditBeat()} style={st.landEditBtn}
          aria-label={isCustomBeat(beat.id) ? "Edit this beat" : "Customize this beat"}>
          <PencilIcon />
        </button>
      </div>

      {/* The strip — vertically centred in whatever space is left. */}
      <main style={st.stripWrap}>
        <BeatStrip
          beat={beat}
          step={step}
          playing={playing}
          getPhase={getPhase}
          mutedEnds={mutedEnds}
          onToggleMute={toggleMute}
        />
      </main>

      <section style={st.controls}>
        {/* BPM as signage: the number IS the label. Becomes tappable for
            precise entry in an upcoming commit. */}
        <div>
          <div style={st.bpmRow}>
            <span style={st.bpmNum}>{bpm}</span>
            <span style={st.bpmUnit}>BPM</span>
          </div>
          <div style={st.tempoRow}>
            <input className="kc-range" type="range" min={MIN_BPM} max={MAX_BPM} value={bpm}
              onChange={(e) => changeBpm(Number(e.target.value))} disabled={tempoLocked}
              style={{ "--fill": fillPct + "%", flex: 1, opacity: tempoLocked ? 0.5 : 1, cursor: tempoLocked ? "not-allowed" : "pointer" }}
              aria-label="Tempo" />
            <button onClick={() => setTempoLocked(v => !v)}
              style={{ ...st.lockBtn,
                background: tempoLocked ? "var(--clay)" : "transparent",
                color: tempoLocked ? "var(--on-clay)" : "var(--syahi-soft)" }}
              aria-label={tempoLocked ? "Unlock tempo" : "Lock tempo"}
              aria-pressed={tempoLocked}>
              <LockIcon locked={tempoLocked} />
            </button>
            <button onClick={handleTap} style={st.tapBtn} aria-label="Tap tempo">Tap</button>
          </div>
        </div>

        {/* Loudness lives in the mixer: master + per-drum faders. */}
        <button onClick={() => setMixerOpen(true)} style={st.mixerBtn}
          aria-haspopup="dialog" aria-expanded={mixerOpen}>
          <MixerIcon /> Mixer
        </button>
      </section>

      {/* The clay play bar — full width at thumb height: impossible to
          miss, impossible to fumble. */}
      <button onClick={togglePlay} disabled={!ready}
        style={{ ...st.playBtn, opacity: ready ? 1 : 0.5 }}
        aria-label={playing ? "Pause" : "Play"}>
        {playIcon}
        {playing ? "Pause" : "Play"}
      </button>

      {bottomNav}
      {beatPicker}
      {mixerSheet}
    </div>
  );
}

const st = {
  // NOTE: keep this padding identical to BeatEditor's st.screen so the bottom
  // nav sits at the exact same spot on every page and never shifts on switch.
  screen: { width: "100%", maxWidth: 430, minHeight: "100dvh", margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-6) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-4) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))", gap: "var(--space-5)" },
  header: { flexShrink: 0, display: "flex", justifyContent: "center" },
  beatHead: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)" },
  beatName: { margin: 0, maxWidth: "100%", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)", lineHeight: 1.15, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  beatMeta: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", fontWeight: 500 },
  nameBtn: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", alignItems: "center", gap: 2, padding: 0, border: "none", background: "transparent", cursor: "pointer" },
  nameCaret: { fontSize: "0.55em", color: "var(--syahi-soft)", verticalAlign: "middle" },
  chevBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", fontSize: 26, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", paddingBottom: 4 },
  chevBtnSm: { flexShrink: 0, width: 32, height: 44, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 24, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", paddingBottom: 4 },
  landNameBtn: { flexShrink: 1, minWidth: 56, padding: 0, border: "none", background: "transparent", cursor: "pointer", fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "1.125rem", color: "var(--syahi)", lineHeight: 1.1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" },
  pickList: { display: "flex", flexDirection: "column", gap: "var(--space-2)", marginTop: "var(--space-3)" },
  pickRow: { display: "flex", flexDirection: "column", gap: "var(--space-2)", padding: "10px 12px", borderRadius: 14, border: "var(--rule-hairline)", background: "var(--head-worn)", cursor: "pointer", textAlign: "left", width: "100%" },
  stripWrap: { flex: 1, minHeight: 0, display: "flex", alignItems: "center" },
  bpmRow: { display: "flex", alignItems: "baseline", justifyContent: "center", gap: 8, marginBottom: "var(--space-2)" },
  mixerBtn: { width: "100%", minHeight: 46, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 9 },
  mixRows: { display: "flex", flexDirection: "column", gap: "var(--space-4)", marginTop: "var(--space-4)" },
  mixRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  mixLabel: { flexShrink: 0, width: 64, fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  muteBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", cursor: "pointer", display: "grid", placeItems: "center" },
  playBtn: { flexShrink: 0, width: "100%", minHeight: 58, borderRadius: 16, border: "none", background: "var(--clay)", color: "var(--on-clay)", display: "flex", alignItems: "center", justifyContent: "center", gap: 10, cursor: "pointer", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase" },
  subHeader: { display: "flex", flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" },
  subTitle: { margin: 0, fontFamily: "var(--font-display)", fontWeight: 400, fontSize: "var(--text-display-lg)", letterSpacing: "0.01em", color: "var(--ink-primary)" },
  backBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-secondary)", display: "grid", placeItems: "center", cursor: "pointer" },
  subtitle: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
  controls: { flexShrink: 0, display: "flex", flexDirection: "column", gap: "var(--space-5)" },
  controlLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-display-md)", letterSpacing: "0.12em", textTransform: "uppercase", color: "var(--ink-secondary)", fontWeight: 600, marginBottom: "var(--space-3)" },

  // ── Beats page (library list) ──
  catTabs: { display: "flex", gap: "var(--space-2)", overflowX: "auto", paddingBottom: 2, scrollbarWidth: "none" },
  edgeFade: { position: "absolute", top: 0, bottom: 2, width: 30, pointerEvents: "none", transition: "opacity 150ms ease" },
  catTab: { flexShrink: 0, minHeight: 40, padding: "0 16px", borderRadius: 999, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer", whiteSpace: "nowrap" },
  catTabActive: { background: "var(--clay)", borderColor: "var(--clay)", color: "var(--on-clay)" },
  catActions: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-2)" },
  addBeatsBtn: { flex: 1, minHeight: 46, borderRadius: 14, border: "2px dashed var(--rule)", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  deleteCatBtn: { flexShrink: 0, minHeight: 46, padding: "0 14px", borderRadius: 14, border: "none", background: "transparent", color: "var(--syahi-soft)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 600, cursor: "pointer" },
  // touchAction none: the handle owns vertical pointer movement while
  // dragging (otherwise the page scrolls instead of reordering).
  dragHandle: { flexShrink: 0, width: 36, height: 40, border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 19, cursor: "grab", display: "grid", placeItems: "center", borderRadius: 10, touchAction: "none" },
  catNameInput: { flex: 1, minWidth: 0, padding: "12px 14px", borderRadius: 14, border: "var(--rule-hairline)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", background: "var(--surface-paper)", outline: "none" },
  beatList: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", gap: "var(--space-3)", padding: "2px" },
  sectionLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  emptyHint: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", lineHeight: 1.5 },
  beatRow: { display: "flex", flexDirection: "column", gap: "var(--space-2)", padding: "12px 14px", borderRadius: 16, border: "var(--rule-hairline)", background: "var(--head-worn)", cursor: "pointer", textAlign: "left", width: "100%" },
  beatRowTop: { display: "flex", alignItems: "center", gap: "var(--space-2)" },
  beatRowSelect: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2, padding: 0, border: "none", background: "transparent", textAlign: "left", cursor: "pointer" },
  infoBtn: { flexShrink: 0, width: 40, height: 40, borderRadius: 12, border: "none", background: "transparent", color: "var(--syahi-soft)", display: "grid", placeItems: "center", cursor: "pointer" },
  deleteBtn: { flexShrink: 0, width: 40, height: 40, border: "none", background: "transparent", color: "var(--ink-secondary)", fontSize: 20, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", borderRadius: 12 },
  beatRowName: { fontFamily: "var(--font-display)", fontWeight: 600, fontSize: 17, letterSpacing: "0.01em", color: "var(--ink-primary)" },
  beatRowMeta: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 500, color: "var(--ink-secondary)" },

  // ── Beat info sheet ──
  sheetBackdrop: { position: "fixed", inset: 0, background: "oklch(0.24 0.02 60 / 0.35)", display: "flex", alignItems: "flex-end", justifyContent: "center", zIndex: 10 },
  sheet: { width: "100%", maxWidth: 430, maxHeight: "85dvh", overflowY: "auto", background: "var(--head)", borderRadius: "20px 20px 0 0", padding: "var(--space-5) var(--space-5) calc(var(--space-6) + env(safe-area-inset-bottom))", display: "flex", flexDirection: "column" },
  sheetHead: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" },
  sheetName: { margin: 0, fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)" },
  sheetClose: { flexShrink: 0, width: 44, height: 44, margin: "-8px -8px 0 0", border: "none", background: "transparent", color: "var(--syahi-soft)", fontSize: 26, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center" },
  sheetDesc: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", color: "var(--ink-primary)", lineHeight: 1.55 },
  sheetActions: { display: "flex", gap: "var(--space-3)", marginTop: "var(--space-5)" },
  sheetEditBtn: { flex: 1, minHeight: 50, borderRadius: 14, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, cursor: "pointer" },
  sheetStartBtn: { flex: 2, minHeight: 50, borderRadius: 14, border: "none", background: "var(--clay)", color: "var(--on-clay)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", cursor: "pointer" },
  startBtn: { flexShrink: 0, width: "100%", padding: "16px", borderRadius: 18, border: "none", background: "var(--accent-action)", color: "var(--on-action)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 800, letterSpacing: "0.02em", cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 10 },

  // ── Bottom navigation — iOS-style pill wrapping the four buttons ──
  nav: { flexShrink: 0, marginTop: "auto", display: "flex", alignItems: "stretch", gap: "var(--space-1)", padding: "var(--space-1)", borderRadius: 999, border: "var(--rule-hairline)", background: "var(--surface-raised)" },
  navCell: { flex: 1, minWidth: 0, minHeight: 52, borderRadius: 999, border: "none", background: "transparent", color: "var(--ink-secondary)", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 2, cursor: "pointer", padding: "4px 0" },
  navLabel: { fontFamily: "var(--font-body)", fontSize: 10, fontWeight: 700, letterSpacing: "0.04em", lineHeight: 1 },
  navActive: { background: "var(--accent-action)", color: "var(--on-action)" },
  tempoHead: { display: "flex", alignItems: "baseline", justifyContent: "space-between" },
  tempoRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  tapBtn: { flexShrink: 0, minHeight: 44, padding: "0 16px", borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontWeight: 700, fontSize: "var(--text-body-sm)", cursor: "pointer", letterSpacing: "0.04em" },
  lockBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", cursor: "pointer", display: "grid", placeItems: "center" },
  bpmNum: { fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "var(--text-numeric-xl)", fontWeight: 600, color: "var(--syahi)", lineHeight: 1 },
  bpmUnit: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 700, letterSpacing: "0.08em", color: "var(--syahi-soft)" },

  // ── Home, landscape (the propped-up layout) ──
  landScreen: { width: "100%", maxWidth: 1100, minHeight: 0, margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-3) + env(safe-area-inset-top)) calc(var(--space-4) + env(safe-area-inset-right)) calc(var(--space-2) + env(safe-area-inset-bottom)) calc(var(--space-4) + env(safe-area-inset-left))", gap: "var(--space-3)" },
  landName: { flexShrink: 1, minWidth: 60, fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "1.125rem", color: "var(--syahi)", lineHeight: 1.1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" },
  // Cells sized from the WRAPPER's height (container query units), not
  // the viewport's, so the strip can never outgrow its slot and spill
  // over the rail. 100cqh = wrapper height; ~96px covers the strip's
  // fixed parts (numbers row, two lane labels, internal gaps); the
  // remainder splits between the two lanes.
  landStripWrap: { flex: 1, minHeight: 0, display: "flex", alignItems: "center", containerType: "size", "--ks-cellh": "clamp(18px, calc((100cqh - 96px) / 2), 72px)" },
  landRail: { flexShrink: 0, display: "flex", alignItems: "center", gap: "var(--space-2)", minHeight: 44 },
  landEditBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--syahi-soft)", display: "grid", placeItems: "center", cursor: "pointer" },
  landBpmNum: { flexShrink: 0, fontFamily: "var(--font-numeric)", fontVariantNumeric: "tabular-nums", fontSize: "1.5rem", fontWeight: 600, color: "var(--syahi)", lineHeight: 1, marginLeft: "var(--space-2)" },
  landPlayBtn: { flexShrink: 0, minWidth: 92, height: 44, borderRadius: 12, border: "none", background: "var(--clay)", color: "var(--on-clay)", display: "grid", placeItems: "center", cursor: "pointer", marginLeft: "var(--space-2)" },
  landNavWrap: { flexShrink: 0, width: "100%", maxWidth: 430, alignSelf: "center" },
};

export default App;
