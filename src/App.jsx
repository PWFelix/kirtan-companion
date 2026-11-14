import { useState, useMemo, useEffect, useCallback } from "react";
import { BEATS } from "./data/beats.js";
import { useTransport } from "./hooks/useTransport.js";
import { useBeatLibrary } from "./hooks/useBeatLibrary.js";
import { useLandscape } from "./hooks/useLandscape.js";
import { useAuth } from "./hooks/useAuth.js";
import { useCloudMigration } from "./hooks/useCloudMigration.js";
import { useCommunityLibrary } from "./hooks/useCommunityLibrary.js";
import { isCommunity } from "./data/communityTracking.js";
import { beatsProvider } from "./storage/index.js";
import { createSupabaseProvider } from "./storage/supabaseProvider.js";
import BeatEditor from "./BeatEditor.jsx";
import Splash from "./Splash.jsx";
import BottomNav from "./ui/BottomNav.jsx";
import AuthSheet from "./ui/AuthSheet.jsx";
import MigrationSheet from "./ui/MigrationSheet.jsx";
import HomeView from "./views/HomeView.jsx";
import BeatsView from "./views/BeatsView.jsx";
import LearnView from "./views/LearnView.jsx";
import SettingsView from "./views/SettingsView.jsx";
import { readShareFromLocation } from "./data/shareCodec.js";
import { loadSession, saveSession } from "./storage/sessionPrefs.js";
import { isDirty, loadSession as loadEditorSession, saveSession as saveEditorSession } from "./data/editorDraft.js";
import UnsavedSheet from "./ui/UnsavedSheet.jsx";

// An inbound share link, read ONCE at module load. Deliberately not in a
// useState initialiser: StrictMode double-invokes those, and this one has a
// side effect (it clears the hash so a hostile payload can't wedge the app
// across refreshes). Module scope runs exactly once per page load.
const INBOUND_SHARE = readShareFromLocation();

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
  const auth = useAuth();
  // The storage seam: signed in on a configured project → the user's cloud
  // library; otherwise this device's localStorage. useBeatLibrary reloads
  // whenever this identity changes, so signing in swaps device → cloud live
  // (and signing out swaps back). Memoised on the user id so it's a stable
  // object across renders and only rebuilds on a real identity change.
  const provider = useMemo(
    () => (auth.configured && auth.user ? createSupabaseProvider(auth.user.id) : beatsProvider),
    // Keyed on the id, not the user object: a new object for the same user
    // must NOT rebuild the provider (it would refetch the whole library).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [auth.configured, auth.user?.id],
  );
  const library = useBeatLibrary(provider);
  const isLandscape = useLandscape();
  // Offers to move this device's guest beats into a freshly signed-in, empty
  // account. Self-contained: it watches auth + library and drives its own sheet.
  const migration = useCloudMigration({ auth, library });
  // Community beats following their originals (status, Update, migration).
  const community = useCommunityLibrary(library);

  // The sign-in sheet. App owns it because auth spans every screen, not just
  // Beats. Rendering is gated on being signed OUT (below), so a session
  // landing makes the sheet vanish without a setState-in-effect.
  const [authOpen, setAuthOpen] = useState(false);

  // A share link lands the user in the library, where the beat it carries is
  // about to be offered to them. The splash gate below still comes first, so
  // the audio-unlock gesture is never skipped.
  const [view, setView] = useState(INBOUND_SHARE ? "beats" : "home"); // "home" | "beats" | "editor" | "learn" | "settings"
  const [pendingShare, setPendingShare] = useState(INBOUND_SHARE);
  const [entered, setEntered] = useState(false); // splash shown until Begin
  // The last loaded beat comes back on the next visit (storage/sessionPrefs.js).
  // An id that no longer exists falls through to the first beat below.
  const [beatId, setBeatId] = useState(() => loadSession().beatId ?? BEATS[0].id);
  // The ONE editor session — { seed, draft, returnTo, key } — held here rather
  // than in the editor so leaving the screen and coming back finds the draft
  // as it was, and kept in localStorage while dirty so a reload does too
  // (data/editorDraft.js). `seed` is the beat it started from (null = new),
  // `returnTo` where Back goes (null = opened from the nav: no Back), `key`
  // remounts the editor when the session is replaced.
  const [editor, setEditor] = useState(() => {
    const restored = loadEditorSession();
    return restored ? { ...restored, key: 1 } : null;
  });
  // An edit asked for while another has unsaved changes: Keep or Discard.
  const [pendingOpen, setPendingOpen] = useState(null);
  const editorDirty = isDirty(editor);
  useEffect(() => {
    const id = setTimeout(() => saveEditorSession(editor), 300);
    return () => clearTimeout(id);
  }, [editor]);
  const onDraftChange = useCallback((draft) => setEditor((s) => (s ? { ...s, draft } : s)), []);
  // Start a session on `seed`, unless it would throw away unsaved work.
  function beginEdit(seed, returnTo, force = false) {
    transport.stop();
    if (!force && editorDirty) {
      // The same beat again (its pencil tapped twice): resume.
      if (seed?.id && seed.id === editor.seed?.id) {
        setEditor((s) => ({ ...s, returnTo }));
        setView("editor");
      } else setPendingOpen({ seed, returnTo });
      return;
    }
    setEditor((s) => ({ seed, draft: null, returnTo, key: (s?.key ?? 0) + 1 }));
    setView("editor");
  }

  const beat = library.allBeats.find(b => b.id === beatId) || library.allBeats[0];

  // Selecting a beat FROM a category makes that category the active cycling
  // context for Home's chevrons and quick-pick. Switches the loop live if
  // playing — the mid-kirtan "next step in the progression" move.
  // The sequencer finds its beats by id; keep it handed the current library
  // (an edited beat then sounds edited inside a playing sequence).
  const { bindSequenceBeats } = transport;
  const { customBeats } = library;
  useEffect(() => { bindSequenceBeats(library.allBeats); },
    // allBeats is rebuilt each render; customBeats is what actually changes it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [customBeats]);

  function selectBeat(b, fromCat) {
    setBeatId(b.id);
    saveSession({ beatId: b.id });
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
  // `returnTo` is the view Back should return to (a drill-in from Beats);
  // null = launched from the nav, so no Back and Close lands Home.
  function openNewBeat(returnTo = null) {
    beginEdit(null, returnTo);
  }
  // The nav tab: unsaved work comes back; otherwise a blank draft.
  function openEditorTab() {
    if (editorDirty) {
      transport.stop();
      setEditor((s) => ({ ...s, returnTo: null }));
      setView("editor");
    } else openNewBeat();
  }
  // On a beat (defaults to the loaded one). `returnTo` is the view Back should
  // return to (a drill-in from Home/Beats); null = no Back. Custom beats edit
  // in place (same id); built-ins fork into a fresh custom copy that the
  // original never sees.
  function openEditBeat(target = beat, returnTo = null) {
    transport.stop();
    // A community beat is someone else's and read-only: editing makes the
    // user's own copy, which remembers its original (Share uses that).
    const seed = isCommunity(target)
      ? {
          ...target,
          id: null,
          sourceItemId: undefined,
          sourceIndex: undefined,
          forkedFrom: target.sourceItemId,
        }
      : library.isCustomBeat(target.id)
      ? target
      : {
          ...target,
          // No id: that's what makes this a FORK. The library mints one on
          // save, so the built-in it came from is never written over.
          id: null,
          name: target.name + " (custom)",
          note: "Custom",
          // The built-in's prose comes along as the copy's starting notes.
          readOnly: undefined,
        };
    beginEdit(seed, returnTo);
  }
  // Back: leaves the draft where it is. Only a save ends the session.
  function closeEditor(to) {
    transport.stop();
    setView(to);
  }
  async function handleSaveBeat(newBeat) {
    // Awaited because a new beat's id doesn't exist until the library mints
    // it — `newBeat` here may not have one yet. Nothing is selected if the
    // save failed; the library surfaces the reason on the Beats screen.
    const saved = await library.saveBeat(newBeat);
    // Reflect the saved beat in the engine + main view (engine already stopped).
    if (saved) {
      selectBeat(saved, "custom");
      setEditor(null);
      saveEditorSession(null);
    }
    // Handed back so the editor knows whether it may close: it discards the
    // draft on unmount, so it must stay open if this didn't stick.
    return saved;
  }
  // Deleting the loaded beat falls back to the first built-in.
  function handleDeleteBeat(id) {
    library.deleteBeat(id);
    if (beatId === id) selectBeat(BEATS[0], "builtin");
  }
  // Accepting a share: same shape as saving from the editor — the library
  // takes it, then the engine and main view move to what just arrived. The
  // result goes back to the caller so the library screen can land the user
  // in the tab the beats went into (the ids only exist after the mint).
  async function handleImportShare(payload, sourceItemId = null) {
    const result = await library.importShared(payload, sourceItemId);
    if (result.beats.length) selectBeat(result.beats[0], result.catId ?? "custom");
    setPendingShare(null);
    return result;
  }

  // The Begin tap does double duty: it satisfies the browser's "no sound
  // before a user gesture" rule on the way into the app.
  async function handleBegin() {
    await transport.unlock();
    setEntered(true);
  }

  // The splash is where the async library load hides. Reading beats is a
  // round trip now, so Begin waits for BOTH the sounds and the library —
  // which for localStorage resolves long before anyone taps it, and is a
  // real gate the day the store is remote. No screen ever renders empty.
  if (!entered) {
    return <Splash onBegin={handleBegin} ready={transport.ready && !library.loading} />;
  }

  // Asked when an edit would replace unsaved work.
  const unsavedSheet = pendingOpen && (
    <UnsavedSheet name={editor?.draft?.name?.trim()}
      onKeep={() => { setPendingOpen(null); setView("editor"); }}
      onDiscard={() => { const p = pendingOpen; setPendingOpen(null); beginEdit(p.seed, p.returnTo, true); }}
      onDismiss={() => setPendingOpen(null)} />
  );

  // Built once and handed to whichever screen is showing, so the bar is the
  // same element across a navigation and never shifts.
  // The unsaved-changes sheet rides along with the nav, which every screen
  // renders, so it can appear over whichever screen asked.
  const bottomNav = (
    <>
    {unsavedSheet}
    <BottomNav
      view={view}
      onHome={() => setView("home")}
      onBeats={() => setView("beats")}
      onEditor={openEditorTab}
      editorDirty={editorDirty}
      onLearn={() => setView("learn")}
      onSettings={() => setView("settings")}
    />
    </>
  );

  if (view === "editor" && editor) {
    // The beat this draft edits was deleted meanwhile: it saves as a new one.
    const seed = editor.seed?.id && !library.isCustomBeat(editor.seed.id) && !editor.seed.readOnly
      ? { ...editor.seed, id: null } : editor.seed;
    return (
      <BeatEditor key={editor.key} engine={transport.engine} initialBeat={seed}
        initialDraft={editor.draft} onDraftChange={onDraftChange} dirty={editorDirty}
        onDiscard={() => setEditor((s) => ({ ...s, draft: null, key: s.key + 1 }))}
        onSave={handleSaveBeat} nav={bottomNav}
        onBack={editor.returnTo ? () => closeEditor(editor.returnTo) : undefined}
        onClose={() => closeEditor("home")} />
    );
  }

  if (view === "beats") {
    return (
      <>
        <BeatsView
          library={library}
          community={community}
          beat={beat}
          beatId={beatId}
          ready={transport.ready}
          onPreview={transport.startPreview}
          onEndPreview={transport.endPreview}
          onSelect={selectBeat}
          onStart={startCurrent}
          onStartBeat={startFromDetail}
          onEdit={(b) => openEditBeat(b, "beats")}
          onNewBeat={() => openNewBeat("beats")}
          onDeleteBeat={handleDeleteBeat}
          pendingShare={pendingShare}
          onImportShare={handleImportShare}
          onDismissShare={() => setPendingShare(null)}
          auth={auth}
          onRequestAuth={() => setAuthOpen(true)}
          nav={bottomNav}
        />
        {authOpen && !auth.user && <AuthSheet auth={auth} onClose={() => setAuthOpen(false)} />}
        {(migration.pending || migration.done) && (
          <MigrationSheet
            counts={migration.counts}
            done={migration.done}
            busy={migration.busy}
            error={migration.error}
            onMigrate={migration.migrate}
            onDismiss={migration.dismiss}
            onAcknowledge={migration.acknowledge}
          />
        )}
      </>
    );
  }

  if (view === "learn") {
    return <LearnView transport={transport} beat={beat} onCycle={cycleBeat}
      library={library} beatId={beatId} onSelect={selectBeat} nav={bottomNav} />;
  }

  if (view === "settings") {
    return <SettingsView onBack={() => setView("home")} nav={bottomNav} transport={transport} />;
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
