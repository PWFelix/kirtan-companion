import { useState, useMemo, useEffect } from "react";
import { useTransport } from "./hooks/useTransport.js";
import { useBeatLibrary } from "./hooks/useBeatLibrary.js";
import { useLandscape } from "./hooks/useLandscape.js";
import { useAuth } from "./hooks/useAuth.js";
import { useCloudMigration } from "./hooks/useCloudMigration.js";
import { beatsProvider, storageErrorMessage } from "./storage/index.js";
import { createSupabaseProvider } from "./storage/supabaseProvider.js";
import { updateShippedBeat } from "./storage/shippedBeatsClient.js";
import { deriveBeat } from "./data/shippedBeats.js";
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
 *   useBeatLibrary — saved beats, the effective built-in list, categories,
 *                    and their persistence
 *   views/*        — one screen each, owning only its own sheets and tabs
 *
 * The split is by STATE OWNERSHIP, not by screen count. `beatId` stays here
 * because selecting a beat has to touch both halves — the library (which
 * category was it chosen from) and the transport (point the engine at it) —
 * so `selectBeat` is the seam between them and belongs to neither.
 */
function App() {
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
  // Created AFTER the library, because the tempo mirror seeds from the head of
  // the built-in list — which is server-sourced, so it lives there and not in
  // a module constant any more.
  const transport = useTransport(library.builtinBeats[0]);
  const isLandscape = useLandscape();
  // Offers to move this device's guest beats into a freshly signed-in, empty
  // account. Self-contained: it watches auth + library and drives its own sheet.
  const migration = useCloudMigration({ auth, library });

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
  // Launched on the head of the built-in list. At first render that IS the
  // compiled head — the server's set arrives asynchronously and nothing waits
  // for it (see useShippedBeats) — so this seeds the app exactly as it always
  // did, and the effect below reconciles it once the real list lands.
  const [beatId, setBeatId] = useState(library.builtinBeats[0].id);
  const [editorInitial, setEditorInitial] = useState(null); // beat to pre-fill, or null for a new beat
  // Where the editor's Back button returns to, or null when it was opened
  // from the nav tab (then there's no Back — the nav bar navigates).
  const [editorReturn, setEditorReturn] = useState(null);
  // The built-in row the open editor is correcting, or null when it is writing
  // to this user's library like every other save.
  //
  // STATE ON THE SESSION rather than a property of the beat, because the same
  // beat can be opened both ways: "Customize" forks a private copy, "Edit for
  // everyone" writes back onto the row every install reads. The editor can't
  // tell them apart — it drafts a beat and hands it back — so only whoever
  // opened it knows which was asked for. EVERY opener sets this, including the
  // two that set it to null: the nav bar is rendered inside the editor, so a
  // maintainer can leave one without going through closeEditor, and a flag that
  // outlived its session would route the next blank editor's save onto a
  // shipped row.
  const [shippedEditId, setShippedEditId] = useState(null);
  // A refused shipped save, for the editor to show. It can't go to the
  // library's error strip: the editor has replaced that screen, and it stays
  // open on a failure so the draft isn't discarded.
  const [editorError, setEditorError] = useState(null);

  const beat = library.allBeats.find(b => b.id === beatId) || library.allBeats[0];

  // Selecting a beat FROM a category makes that category the active cycling
  // context for Home's chevrons and quick-pick. Switches the loop live if
  // playing — the mid-kirtan "next step in the progression" move.
  function selectBeat(b, fromCat) {
    setBeatId(b.id);
    transport.loadBeat(b);
    if (fromCat) library.setActiveCat(fromCat);
  }

  // The built-in list is server-sourced, so it can change under a running app:
  // a maintainer re-orders it, edits a beat, or removes one. Exactly one thing
  // has to be repaired — Home must never be left pointing at a beat that no
  // longer exists. `beat` above would quietly fall back to the first beat in
  // the library while the ENGINE still holds the removed one, so the screen
  // and the sequencer would disagree about what is playing. Going through
  // selectBeat moves both.
  //
  // Everything else is deliberately left alone: a different first beat, or an
  // edited pattern behind an id that still exists, is picked up the next time
  // the user selects a beat. Re-selecting on every refresh would throw away a
  // tempo they had just nudged — mid-kirtan — for nothing they asked for.
  useEffect(() => {
    // Not while the library is still filling in: an id that isn't in the list
    // YET (a custom beat, before its load resolves) is not one that has gone.
    if (library.loading) return;
    const stillThere = library.builtinBeats.some(b => b.id === beatId)
      || library.customBeats.some(b => b.id === beatId);
    if (stillThere) return;
    // The setState is the point of this effect, not an incidental one: it is
    // what keeps `beatId` and the engine — which selectBeat's loadBeat moves —
    // telling the same story.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    selectBeat(library.builtinBeats[0], "builtin");
    // selectBeat is rebuilt every render and closes over nothing the deps
    // don't already name, so listing it would re-run this on every render for
    // no new information.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [library.loading, library.builtinBeats, library.customBeats, beatId]);

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
    transport.stop();
    setEditorInitial(null);
    setEditorReturn(returnTo);
    setShippedEditId(null);
    setEditorError(null);
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
          // No id: that's what makes this a FORK. The library mints one on
          // save, so the built-in it came from is never written over.
          id: null,
          name: target.name + " (custom)",
          note: "Custom",
          description: undefined,
          readOnly: undefined,
        };
    setEditorInitial(seed);
    setEditorReturn(returnTo);
    setShippedEditId(null);
    setEditorError(null);
    setView("editor");
  }
  /**
   * A BUILT-IN beat, in the mode that writes back onto its own row.
   *
   * The seed is the beat exactly as it stands — no "(custom)" suffix, no
   * stripped id, none of the rewriting openEditBeat does to make a fork —
   * because this save mints nothing: the id is the table's primary key and the
   * name is the one every install already shows, so a seed that altered either
   * would rename a beat for everybody as a side effect of correcting it. What
   * the row holds beyond the beat (ordinal, heading, description, provenance)
   * is not on a beat at all, and is read back off the table when saving.
   *
   * Back returns to the Beats screen rather than being a parameter: this mode
   * is only reachable from a built-in's info sheet, which is there.
   */
  function openEditShippedBeat(target) {
    transport.stop();
    setEditorInitial(target);
    setEditorReturn("beats");
    setShippedEditId(target.id);
    setEditorError(null);
    setView("editor");
  }
  function closeEditor(to) {
    transport.stop();
    setEditorInitial(null);
    setView(to);
  }
  async function handleSaveBeat(newBeat) {
    // Routed, not decided: which store a save writes to was settled by whoever
    // opened the editor — see `shippedEditId`.
    if (shippedEditId) return handleSaveShippedBeat(newBeat);
    // Awaited because a new beat's id doesn't exist until the library mints
    // it — `newBeat` here may not have one yet. Nothing is selected if the
    // save failed; the library surfaces the reason on the Beats screen.
    const saved = await library.saveBeat(newBeat);
    // Reflect the saved beat in the engine + main view (engine already stopped).
    if (saved) selectBeat(saved, "custom");
    // Handed back so the editor knows whether it may close: it discards the
    // draft on unmount, so it must stay open if this didn't stick.
    return saved;
  }
  /**
   * The maintainer's in-place edit of a built-in, saved.
   *
   * This goes to `shipped_beats` and not to the library, so nothing is created:
   * the row's primary key is the beat's id and stays the beat's id, which is
   * what keeps every playlist referencing it working. Routing it through
   * `library.saveBeat` — the tempting reuse, since the draft is shaped exactly
   * like a library save — would fork a private copy and leave the beat wrong
   * for everybody else, silently, because an id the library doesn't hold reads
   * to it as "new".
   *
   * The set is then re-read: the same call the Beats screen's "Check for beat
   * updates" makes. The write moved the row's `updated_at`, so the revision
   * differs and the library replaces its built-in list, which is what lets the
   * maintainer see their own correction without a reload — and see a MISTAKE
   * without one, the half that actually matters, since they are the only person
   * who can fix it.
   *
   * A refusal resolves to null with the reason in `editorError`, and null keeps
   * the editor open: unmounting is how it discards a draft.
   */
  async function handleSaveShippedBeat(draft) {
    setEditorError(null);
    try {
      const row = await updateShippedBeat(draft, { id: shippedEditId });
      // The re-read is the only best-effort step here: the write is already in,
      // so one that fails costs the maintainer the immediate sight of their own
      // correction and nothing else — every launch after this one has it.
      const refreshed = await library.checkForBeatUpdates();
      if (refreshed.kind === "failed") {
        console.warn("[shipped] the edited row wasn't re-read", refreshed.message);
      }
      // Derived from the row that was WRITTEN rather than from the draft, so
      // the engine and Home hold what the table now serves: the draft carries
      // the editor's "Custom" note and no description at all. Tagged readOnly
      // because it is a built-in, and that marker is how every screen knows to
      // offer a fork instead of a private edit.
      const saved = { ...deriveBeat(row), readOnly: true };
      // The engine holds a PATTERN, and the list just changed under it. The
      // reconcile effect above can't catch this one — the id still exists,
      // which is the whole point of the edit — so without this the screen would
      // show the corrected beat while the sequencer plays the pre-correction
      // one. Only when it is the loaded beat, though: selecting a beat the
      // maintainer merely corrected would move Home off whatever they were
      // playing.
      if (beatId === saved.id) selectBeat(saved);
      return saved;
    } catch (err) {
      console.error("[shipped] in-place edit failed", err);
      setEditorError(storageErrorMessage(err));
      return null;
    }
  }
  // Deleting the loaded beat falls back to the first built-in — the head of
  // the effective list, which is what the reconcile effect above uses too.
  function handleDeleteBeat(id) {
    library.deleteBeat(id);
    if (beatId === id) selectBeat(library.builtinBeats[0], "builtin");
  }
  // Accepting a share: same shape as saving from the editor — the library
  // takes it, then the engine and main view move to what just arrived. The
  // result goes back to the caller so the library screen can land the user
  // in the tab the beats went into (the ids only exist after the mint).
  async function handleImportShare(payload) {
    const result = await library.importShared(payload);
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

  // Built once and handed to whichever screen is showing, so the bar is the
  // same element across a navigation and never shifts.
  const bottomNav = (
    <BottomNav
      view={view}
      onHome={() => setView("home")}
      onBeats={() => setView("beats")}
      onEditor={() => openNewBeat()}
      onLearn={() => setView("learn")}
      onSettings={() => setView("settings")}
    />
  );

  if (view === "editor") {
    return (
      <BeatEditor engine={transport.engine} initialBeat={editorInitial}
        onSave={handleSaveBeat} nav={bottomNav}
        forEveryone={Boolean(shippedEditId)} saveError={editorError}
        onBack={editorReturn ? () => closeEditor(editorReturn) : undefined}
        // A saved library beat lands Home, as it always has. A shipped edit
        // lands back on the list it changed: the re-read in
        // handleSaveShippedBeat is for seeing the correction — or the mistake —
        // in the built-in section, and Home shows neither unless the edited
        // beat happens to be the loaded one.
        onClose={() => closeEditor(shippedEditId ? "beats" : "home")} />
    );
  }

  if (view === "beats") {
    return (
      <>
        <BeatsView
          library={library}
          beat={beat}
          beatId={beatId}
          ready={transport.ready}
          onSelect={selectBeat}
          onStart={startCurrent}
          onStartBeat={startFromDetail}
          onEdit={(b) => openEditBeat(b, "beats")}
          onEditShipped={openEditShippedBeat}
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
    return <LearnView onBack={() => setView("home")} nav={bottomNav} />;
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
