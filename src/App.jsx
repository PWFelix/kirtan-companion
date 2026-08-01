import { useState } from "react";
import { BEATS } from "./data/beats.js";
import { useTransport } from "./hooks/useTransport.js";
import { useBeatLibrary } from "./hooks/useBeatLibrary.js";
import { useLandscape } from "./hooks/useLandscape.js";
import BeatEditor from "./BeatEditor.jsx";
import Splash from "./Splash.jsx";
import BottomNav from "./ui/BottomNav.jsx";
import HomeView from "./views/HomeView.jsx";
import BeatsView from "./views/BeatsView.jsx";
import SettingsView from "./views/SettingsView.jsx";

/**
 * App — the shell. It routes, and it owns the one piece of state that spans
 * everything else: WHICH BEAT IS LOADED.
 *
 * Everything else has a home of its own:
 *   useTransport   — the engine and its React mirror (playing, tempo, mixer)
 *   useBeatLibrary — saved beats, categories, and their persistence
 *   views/*        — one screen each, owning only its own sheets and tabs
 *
 * The split is by STATE OWNERSHIP, not by screen count. `beatId` stays here
 * because selecting a beat has to touch both halves — the library (which
 * category was it chosen from) and the transport (point the engine at it) —
 * so `selectBeat` is the seam between them and belongs to neither.
 */
function App() {
  const transport = useTransport();
  const library = useBeatLibrary();
  const isLandscape = useLandscape();

  const [view, setView] = useState("home"); // "home" | "beats" | "editor" | "settings"
  const [entered, setEntered] = useState(false); // splash shown until Begin
  const [beatId, setBeatId] = useState(BEATS[0].id);
  const [editorInitial, setEditorInitial] = useState(null); // beat to pre-fill, or null for a new beat
  // Where the editor's Back button returns to, or null when it was opened
  // from the nav tab (then there's no Back — the nav bar navigates).
  const [editorReturn, setEditorReturn] = useState(null);

  const beat = library.allBeats.find(b => b.id === beatId) || library.allBeats[0];

  // Selecting a beat FROM a category makes that category the active cycling
  // context for Home's chevrons and quick-pick. Switches the loop live if
  // playing — the mid-kirtan "next step in the progression" move.
  function selectBeat(b, fromCat) {
    setBeatId(b.id);
    transport.loadBeat(b);
    if (fromCat) library.setActiveCat(fromCat);
  }

  // Home chevrons: step through the ACTIVE category in its order (wraps).
  function cycleBeat(dir) {
    let list = library.categoryBeats(library.activeCat);
    if (list.length === 0) list = library.allBeats;
    const i = list.findIndex(b => b.id === beatId);
    const next = i === -1 ? list[0] : list[(i + dir + list.length) % list.length];
    selectBeat(next);
  }

  // The Beats page's Start button: jump to Home and play what's already
  // loaded — it doesn't re-select, so a nudged tempo survives.
  function startCurrent() {
    setView("home");
    transport.play(beat);
  }
  // Start from the info sheet: select THAT beat first (which adopts its
  // suggested tempo unless locked), then play it. Deliberately passes no
  // category — reading a beat's details doesn't change what Home cycles.
  function startFromDetail(b) {
    selectBeat(b);
    setView("home");
    transport.play(b);
  }

  // ── Editor ──
  // Opening the editor stops playback: it takes the engine over entirely.

  // Blank (new beat) — from the nav tab, so no Back.
  function openNewBeat() {
    transport.stop();
    setEditorInitial(null);
    setEditorReturn(null);
    setView("editor");
  }
  // On a beat (defaults to the loaded one). `returnTo` is the view Back should
  // return to (a drill-in from Home/Beats); null = no Back. Custom beats edit
  // in place (same id); built-ins fork into a fresh custom copy that the
  // original never sees.
  function openEditBeat(target = beat, returnTo = null) {
    transport.stop();
    const seed = library.isCustomBeat(target.id)
      ? target
      : {
          ...target,
          id: "custom_" + target.name.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now(),
          name: target.name + " (custom)",
          note: "Custom",
          description: undefined,
        };
    setEditorInitial(seed);
    setEditorReturn(returnTo);
    setView("editor");
  }
  function closeEditor(to) {
    transport.stop();
    setEditorInitial(null);
    setView(to);
  }
  function handleSaveBeat(newBeat) {
    library.saveBeat(newBeat);
    // Reflect the saved beat in the engine + main view (engine already stopped).
    selectBeat(newBeat, "custom");
  }
  // Deleting the loaded beat falls back to the first built-in.
  function handleDeleteBeat(id) {
    library.deleteBeat(id);
    if (beatId === id) selectBeat(BEATS[0], "builtin");
  }

  // The Begin tap does double duty: it satisfies the browser's "no sound
  // before a user gesture" rule on the way into the app.
  async function handleBegin() {
    await transport.unlock();
    setEntered(true);
  }

  if (!entered) {
    return <Splash onBegin={handleBegin} ready={transport.ready} />;
  }

  // Built once and handed to whichever screen is showing, so the bar is the
  // same element across a navigation and never shifts.
  const bottomNav = (
    <BottomNav
      view={view}
      onHome={() => setView("home")}
      onBeats={() => setView("beats")}
      onEditor={openNewBeat}
      onSettings={() => setView("settings")}
    />
  );

  if (view === "editor") {
    return (
      <BeatEditor engine={transport.engine} initialBeat={editorInitial}
        onSave={handleSaveBeat} nav={bottomNav}
        onBack={editorReturn ? () => closeEditor(editorReturn) : undefined}
        onClose={() => closeEditor("home")} />
    );
  }

  if (view === "beats") {
    return (
      <BeatsView
        library={library}
        beat={beat}
        beatId={beatId}
        ready={transport.ready}
        onSelect={selectBeat}
        onStart={startCurrent}
        onStartBeat={startFromDetail}
        onEdit={(b) => openEditBeat(b, "beats")}
        onDeleteBeat={handleDeleteBeat}
        nav={bottomNav}
      />
    );
  }

  if (view === "settings") {
    return <SettingsView onBack={() => setView("home")} nav={bottomNav} />;
  }

  return (
    <HomeView
      transport={transport}
      library={library}
      beat={beat}
      beatId={beatId}
      isLandscape={isLandscape}
      onSelect={selectBeat}
      onCycle={cycleBeat}
      onEdit={(b) => openEditBeat(b, "home")}
      nav={bottomNav}
    />
  );
}

export default App;
