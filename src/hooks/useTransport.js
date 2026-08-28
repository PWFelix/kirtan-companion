import { useState, useRef, useEffect, useCallback } from "react";
import { KirtanEngine } from "../engine/KirtanEngine.js";
import { BEATS } from "../data/beats.js";
import { MIN_BPM, MAX_BPM } from "../data/meter.js";
import { loadEqPrefs, saveEqPrefs } from "../storage/eqPrefs.js";

// The bounds now live in data/meter.js so pure data modules (the share codec)
// can clamp a BPM without importing React, Tone.js and the engine along with
// it. Re-exported here because this is where the app has always found them.
export { MIN_BPM, MAX_BPM };

/**
 * useTransport — the engine and its React mirror, as one unit.
 *
 * The UI must talk to the engine only through KirtanEngine (see CLAUDE.md);
 * this hook is the single place that does. It owns the engine instance, the
 * React state that MIRRORS it (playing, step, bpm, volumes, mutes, per-end
 * EQ bands/panels) and every command that writes to it. Nothing else in the
 * app calls engine.* — the one exception is BeatEditor, which is handed the
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
 * The EQ bands/panels get the same treatment (eqBandsRef, eqOpenRef): the
 * handlers persist the WHOLE prefs object, so computing it from a stale
 * render closure would resurrect a just-changed band or panel when two
 * changes land before a re-render (fast drags, double-clicks).
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
  // Per-end EQ: five band gains in dB plus each panel's open/closed state.
  // Hydrated once at mount from stored prefs via eqPrefs.js (which falls
  // back to flat/closed and never throws) — this hook never touches
  // localStorage itself. The prefs are read ONCE and both slices derive
  // from that single snapshot: two reads would double the localStorage
  // access + JSON parse and could disagree if storage changed between
  // them. The mount effect brings the engine to match.
  const [eqPrefs] = useState(() => loadEqPrefs());
  const [eqBands, setEqBands] = useState({ dayan: eqPrefs.dayan.bands, bayan: eqPrefs.bayan.bands });
  const [eqOpen, setEqOpen] = useState({ dayan: eqPrefs.dayan.open, bayan: eqPrefs.bayan.open });

  const bpmRef = useRef(bpm);
  const lockedRef = useRef(tempoLocked);
  const eqBandsRef = useRef(eqBands);
  const eqOpenRef = useRef(eqOpen);
  const tapTimesRef = useRef([]);
  useEffect(() => { lockedRef.current = tempoLocked; }, [tempoLocked]);

  useEffect(() => {
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
    engine.on("ready",   () => setReady(true));
    engine.on("started", () => setPlaying(true));
    engine.on("stopped", () => setPlaying(false));
    engine.on("step",    (s) => setStep(s));
    engine.setVolume(volume);
    // Push the hydrated EQ gains into the engine so its chains match the
    // sliders from the first sound on (the state itself hydrates in the
    // lazy initialisers above).
    for (const end of ["dayan", "bayan"]) {
      eqBands[end].forEach((db, i) => engine.setEqBand(end, i, db));
    }
    // Mount only: the engine is a stable singleton; `volume` and `eqBands`
    // are read here purely as initial values. Re-running this would
    // double-subscribe.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Stable identity so BeatStrip's rAF effect doesn't re-subscribe on
  // every render (the engine ref never changes).
  const getPhase = useCallback(() => engine.getPhase(), [engine]);

  const clampBpm = (v) => Math.min(MAX_BPM, Math.max(MIN_BPM, v));

  // Same contract the (unexported) bandOf helper in eqPrefs.js applies on
  // load and the engine applies on set: coerce to a finite number
  // (non-finite flattens to 0), then clamp into [-12, 12]. Doing it HERE
  // means React state, the persisted prefs and the engine's DSP can never
  // disagree about the value.
  const clampDb = (v) => {
    const num = Number(v);
    return Math.min(12, Math.max(-12, Number.isFinite(num) ? num : 0));
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
  // dropped here exactly as the engine ignores it. Every change persists
  // whole-object through eqPrefs.js (which warns, never throws, on failure).
  function changeEqBand(end, bandIndex, db) {
    const bands = eqBandsRef.current;
    if (!bands[end]) return;
    const gain = clampDb(db);
    const nextBands = { ...bands, [end]: bands[end].map((v, i) => (i === bandIndex ? gain : v)) };
    eqBandsRef.current = nextBands;
    setEqBands(nextBands);
    engine.setEqBand(end, bandIndex, gain);
    saveEqPrefs({
      dayan: { bands: nextBands.dayan, open: eqOpenRef.current.dayan },
      bayan: { bands: nextBands.bayan, open: eqOpenRef.current.bayan },
    });
  }
  function toggleEqPanel(end) {
    const open = eqOpenRef.current;
    if (!(end in open)) return;
    const nextOpen = { ...open, [end]: !open[end] };
    eqOpenRef.current = nextOpen;
    setEqOpen(nextOpen);
    saveEqPrefs({
      dayan: { bands: eqBandsRef.current.dayan, open: nextOpen.dayan },
      bayan: { bands: eqBandsRef.current.bayan, open: nextOpen.bayan },
    });
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
    eqBands, eqOpen, changeEqBand, toggleEqPanel,
    loadBeat, togglePlay, play, stop, unlock,
  };
}
