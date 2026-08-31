import { useState, useRef, useEffect, useCallback } from "react";
import { KirtanEngine } from "../engine/KirtanEngine.js";
import { BEATS } from "../data/beats.js";
import { MIN_BPM, MAX_BPM } from "../data/meter.js";
import { EQ_MIN_DB, EQ_MAX_DB } from "../data/eq.js";
import { TUNE_MIN_CENTS, TUNE_MAX_CENTS } from "../data/tuning.js";
import { loadEqPrefs, saveEqPrefs } from "../storage/eqPrefs.js";

// The bounds now live in data/meter.js so pure data modules (the share codec)
// can clamp a BPM without importing React, Tone.js and the engine along with
// it. Re-exported here because this is where the app has always found them.
export { MIN_BPM, MAX_BPM };

// EQ prefs persist DEBOUNCED: a slider drag fires changeEqBand many times a
// second, and a synchronous localStorage write per event janks the drag on
// slower devices/browsers. React state and the engine DSP still update
// immediately — only the write coalesces (last change wins after a short
// idle).
const EQ_SAVE_DEBOUNCE_MS = 250;

/**
 * useTransport — the engine and its React mirror, as one unit.
 *
 * The UI must talk to the engine only through KirtanEngine (see CLAUDE.md);
 * this hook is the single place that does. It owns the engine instance, the
 * React state that MIRRORS it (playing, step, bpm, volumes, mutes, per-end
 * EQ bands/tuning/panels) and every command that writes to it. Nothing else
 * in the app calls engine.* — the one exception is BeatEditor, which is handed the
 * engine outright because it takes the transport over entirely while it's
 * open.
 *
 * WHY A HOOK AND NOT PROPS: these ~8 pieces of state are meaningless apart
 * from each other and from the engine. Split across App they read as eight
 * unrelated useStates; together they're one object a screen can destructure.
 *
 * NOTE ON REFS: bpm is mirrored into bpmRef and written SYNCHRONOUSLY by
 * every command. Rapid taps on ± then stack correctly (plain `bpm + delta`
 * reuses a stale value, so fast clicks did nothing / bounced), and a
 * play() in the same tick as a loadBeat() sees the new tempo, not the old.
 * The EQ bands/tuning/panels get the same treatment (eqBandsRef, eqTuneRef,
 * eqOpenRef, tuneOpenRef): the handlers persist the WHOLE prefs object, so
 * computing it from a stale render closure would resurrect a just-changed
 * band or panel when two changes land before a re-render (fast drags,
 * double-clicks).
 */
export function useTransport() {
  // Lazy initialiser, not a ref: the engine is constructed exactly once and
  // the identity is stable for the component's life, but unlike a ref it's a
  // legal read during render (which is where children like BeatStrip get it).
  const [engine] = useState(() => new KirtanEngine());

  const [ready, setReady] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [step, setStep] = useState(-1);
  const [bpm, setBpm] = useState(BEATS[0].bpm);
  const [volume, setVolume] = useState(0.9);
  const [endVolumes, setEndVolumes] = useState({});  // per-lane faders, default 1
  const [mutedEnds, setMutedEnds] = useState({});
  const [tempoLocked, setTempoLocked] = useState(false);
  // Per-end EQ: five band gains in dB, the tuning offset in cents, plus
  // each panel's open/closed state.
  // Hydrated once at mount from stored prefs via eqPrefs.js (which falls
  // back to flat/centred/closed and never throws) — this hook never touches
  // localStorage itself. The prefs are read ONCE and all slices derive
  // from that single snapshot: two reads would double the localStorage
  // access + JSON parse and could disagree if storage changed between
  // them. The mount effect brings the engine to match.
  const [eqPrefs] = useState(() => loadEqPrefs());
  const [eqBands, setEqBands] = useState({ dayan: eqPrefs.dayan.bands, bayan: eqPrefs.bayan.bands });
  const [eqTune, setEqTune] = useState({ dayan: eqPrefs.dayan.tune, bayan: eqPrefs.bayan.tune });
  const [eqOpen, setEqOpen] = useState({ dayan: eqPrefs.dayan.open, bayan: eqPrefs.bayan.open });
  const [tuneOpen, setTuneOpen] = useState({ dayan: eqPrefs.dayan.tuneOpen, bayan: eqPrefs.bayan.tuneOpen });

  const bpmRef = useRef(bpm);
  const lockedRef = useRef(tempoLocked);
  const eqBandsRef = useRef(eqBands);
  const eqTuneRef = useRef(eqTune);
  const eqOpenRef = useRef(eqOpen);
  const tuneOpenRef = useRef(tuneOpen);
  const eqSaveTimerRef = useRef(null);
  const tapTimesRef = useRef([]);
  useEffect(() => { lockedRef.current = tempoLocked; }, [tempoLocked]);

  useEffect(() => {
    // Attach listeners BEFORE kicking off the async load so "ready" can't be
    // missed, and push the hydrated EQ gains + per-end tuning from inside the
    // "ready" handler. setEndPitch walks SoundPlayer's registered player pool,
    // which stays empty until load() resolves — applying it here (rather than
    // synchronously after loadSounds) is what keeps persisted tuning from
    // being silently dropped on first load. The state itself hydrates in the
    // lazy initialisers above.
    //
    // Read from the REFS, not the mount-time closure: the mixer UI is live
    // before "ready", so a user can nudge EQ/tuning while samples are still
    // loading. Those changes land in eqBandsRef/eqTuneRef (setEndPitch no-ops
    // pre-ready — no players yet), so hydrating from the refs replays the
    // freshest values into the engine. Using the stale closure values would
    // clobber those pre-ready adjustments the moment loading finished.
    engine.on("ready", () => {
      for (const end of ["dayan", "bayan"]) {
        eqBandsRef.current[end].forEach((db, i) => engine.setEqBand(end, i, db));
        engine.setEndPitch(end, eqTuneRef.current[end]);
      }
      setReady(true);
    });
    engine.on("started", () => setPlaying(true));
    engine.on("stopped", () => setPlaying(false));
    engine.on("step",    (s) => setStep(s));
    engine.setVolume(volume);
    engine.loadSounds({
      dayan_open:   "/sounds/dayan_open.wav",
      dayan_closed: "/sounds/dayan_closed.wav",
      bayan_open:   "/sounds/bayan_open.wav",
      bayan_closed: "/sounds/bayan_closed.wav",
      // Only the KEYS matter here — SoundPlayer resolves the real files from
      // its own STROKE_SAMPLES map. Registering the kartal strokes is what
      // makes them audible once the recordings land in public/sounds/kartal/.
      kartal_open:   "/sounds/kartal_open.wav",
      kartal_closed: "/sounds/kartal_closed.wav",
    });
    // Mount only: the engine is a stable singleton; `volume` is read here
    // purely as an initial value, and EQ/tuning hydrate from their refs
    // inside the "ready" handler. Re-running this would double-subscribe.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Unmount only: flush a still-pending debounced EQ save so the last drag
  // position isn't lost, then drop the timer.
  useEffect(() => () => {
    if (eqSaveTimerRef.current == null) return;
    clearTimeout(eqSaveTimerRef.current);
    saveEqPrefs({
      dayan: { bands: eqBandsRef.current.dayan, tune: eqTuneRef.current.dayan, open: eqOpenRef.current.dayan, tuneOpen: tuneOpenRef.current.dayan },
      bayan: { bands: eqBandsRef.current.bayan, tune: eqTuneRef.current.bayan, open: eqOpenRef.current.bayan, tuneOpen: tuneOpenRef.current.bayan },
    });
  }, []);

  // Stable identity so BeatStrip's rAF effect doesn't re-subscribe on
  // every render (the engine ref never changes).
  const getPhase = useCallback(() => engine.getPhase(), [engine]);

  const clampBpm = (v) => Math.min(MAX_BPM, Math.max(MIN_BPM, v));

  // Same contract the (unexported) bandOf helper in eqPrefs.js applies on
  // load and the engine applies on set: coerce to a finite number
  // (non-finite flattens to 0), then clamp into the shared EQ range
  // (data/eq.js). Doing it HERE means React state, the persisted prefs and
  // the engine's DSP can never disagree about the value.
  const clampDb = (v) => {
    const num = Number(v);
    return Math.min(EQ_MAX_DB, Math.max(EQ_MIN_DB, Number.isFinite(num) ? num : 0));
  };

  // The tuning offset's version of clampDb: same contract, the shared TUNE
  // range from data/tuning.js, so a drag, a stored pref and the engine's
  // playbackRate can never disagree about the value.
  const clampCents = (v) => {
    const num = Number(v);
    return Math.min(TUNE_MAX_CENTS, Math.max(TUNE_MIN_CENTS, Number.isFinite(num) ? num : 0));
  };

  function changeBpm(value) {
    bpmRef.current = value;
    setBpm(value);
    engine.setBpm(value);
  }
  function nudgeBpm(delta) {
    changeBpm(clampBpm(bpmRef.current + delta));
  }
  function commitBpm(value) {
    changeBpm(clampBpm(value));
  }

  // Tap tempo: average the gaps between the last few taps. A gap over 2s
  // means they started a new count, so the history resets.
  function tapTempo() {
    const now = Date.now();
    const taps = tapTimesRef.current;
    taps.push(now);
    if (taps.length > 4) taps.shift();
    if (taps.length >= 2) {
      const gaps = [];
      for (let i = 1; i < taps.length; i++) gaps.push(taps[i] - taps[i - 1]);
      if (gaps[gaps.length - 1] > 2000) { tapTimesRef.current = [now]; return; }
      const avgGap = gaps.reduce((a, b) => a + b, 0) / gaps.length;
      changeBpm(clampBpm(Math.round(60000 / avgGap)));
    }
  }

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

  // Per-end EQ, mirroring the volume/mute pattern above. The shape carries
  // dayan and bayan only — kartal has no equaliser — so an unknown end is
  // dropped here exactly as the engine ignores it.

  // Debounced persistence of the WHOLE prefs object through eqPrefs.js
  // (which warns, never throws, on failure). The save reads the refs when
  // it FIRES, not when it's scheduled, so a fast drag or double toggle
  // always persists the freshest state — never a stale closure's.
  function scheduleEqSave() {
    clearTimeout(eqSaveTimerRef.current);
    eqSaveTimerRef.current = setTimeout(() => {
      // The handle is stale once we fire: null it FIRST so the unmount
      // flush only runs for a genuinely pending save, never re-writing
      // prefs this callback just persisted.
      eqSaveTimerRef.current = null;
      saveEqPrefs({
        dayan: { bands: eqBandsRef.current.dayan, tune: eqTuneRef.current.dayan, open: eqOpenRef.current.dayan, tuneOpen: tuneOpenRef.current.dayan },
        bayan: { bands: eqBandsRef.current.bayan, tune: eqTuneRef.current.bayan, open: eqOpenRef.current.bayan, tuneOpen: tuneOpenRef.current.bayan },
      });
    }, EQ_SAVE_DEBOUNCE_MS);
  }

  function changeEqBand(end, bandIndex, db) {
    const bands = eqBandsRef.current;
    if (!bands[end]) return;
    const gain = clampDb(db);
    const nextBands = { ...bands, [end]: bands[end].map((v, i) => (i === bandIndex ? gain : v)) };
    eqBandsRef.current = nextBands;
    setEqBands(nextBands);
    engine.setEqBand(end, bandIndex, gain);
    scheduleEqSave();
  }
  // Per-end tuning, same shape as changeEqBand: ref + state + engine +
  // debounced save. The shape carries dayan and bayan only — kartal is
  // never tuned — so an unknown end drops here as the engine ignores it.
  function changeEqTune(end, cents) {
    const tune = eqTuneRef.current;
    if (!(end in tune)) return;
    const nextTune = { ...tune, [end]: clampCents(cents) };
    eqTuneRef.current = nextTune;
    setEqTune(nextTune);
    engine.setEndPitch(end, nextTune[end]);
    scheduleEqSave();
  }
  function toggleEqPanel(end) {
    const open = eqOpenRef.current;
    if (!(end in open)) return;
    const nextOpen = { ...open, [end]: !open[end] };
    eqOpenRef.current = nextOpen;
    setEqOpen(nextOpen);
    scheduleEqSave();
  }
  // The tuning panel's disclosure, mirroring toggleEqPanel — the two are
  // independent surfaces (three lane buttons: mute / EQ / tuning), so each
  // persists its own open state.
  function toggleTunePanel(end) {
    const open = tuneOpenRef.current;
    if (!(end in open)) return;
    const nextOpen = { ...open, [end]: !open[end] };
    tuneOpenRef.current = nextOpen;
    setTuneOpen(nextOpen);
    scheduleEqSave();
  }

  // Point the engine at a beat WITHOUT starting it. Adopts the beat's own
  // suggested tempo unless the user has locked the tempo — the "keep this
  // speed while I change beats mid-kirtan" affordance.
  function loadBeat(b) {
    engine.setBeat(b);
    if (!lockedRef.current) changeBpm(b.bpm);
  }

  async function togglePlay(b) {
    if (!ready) return;            // sounds still loading — ignore the tap
    await engine.unlock();
    if (playing) engine.stop();
    else { engine.setBpm(bpmRef.current); engine.setBeat(b); engine.start(); }
  }

  // Start `b` unconditionally (no-op if already playing — selecting a beat
  // mid-playback already switched the loop live).
  async function play(b) {
    if (!ready || playing) return;
    await engine.unlock();
    engine.setBpm(bpmRef.current);
    engine.setBeat(b);
    engine.start();
  }

  function stop() { engine.stop(); setPlaying(false); }

  // The Begin tap does double duty: it satisfies the browser's "no sound
  // before a user gesture" rule on the way into the app.
  const unlock = () => engine.unlock();

  return {
    engine, ready, playing, step, getPhase,
    bpm, changeBpm, nudgeBpm, commitBpm, tapTempo, clampBpm,
    tempoLocked, setTempoLocked,
    volume, changeVolume, endVolumes, changeEndVolume, mutedEnds, toggleMute,
    eqBands, eqOpen, changeEqBand, toggleEqPanel, eqTune, changeEqTune,
    tuneOpen, toggleTunePanel,
    loadBeat, togglePlay, play, stop, unlock,
  };
}
