import { useState, useEffect, useRef, useCallback } from "react";
import { KirtanEngine } from "./engine/KirtanEngine.js";
import { BEATS } from "./data/beats.js";
import BeatEditor from "./BeatEditor.jsx";
import BeatStrip from "./BeatStrip.jsx";
import Splash from "./Splash.jsx";

const MIN_BPM = 40, MAX_BPM = 200;
const SAVED_KEY = "kirtan-custom-beats";

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

// Little waveform glyph built from a beat's dayan pattern — tall bar = open,
// short bar = closed, faint stub = rest. Purely decorative (aria-hidden).
function BeatGlyph({ pattern }) {
  const bw = 3, gap = 2, h = 26;
  const w = pattern.length * (bw + gap);
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden="true" style={{ flexShrink: 0 }}>
      {pattern.map((c, i) => {
        const bh = c === "O" ? 22 : c === "X" ? 13 : 5;
        return <rect key={i} x={i * (bw + gap)} y={(h - bh) / 2} width={bw} height={bh} rx={1.5}
          fill="var(--accent-saffron)" opacity={c ? 0.6 : 0.3} />;
      })}
    </svg>
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

  function selectBeat(b) {
    setBeatId(b.id);
    engine.setBeat(b);
    if (!tempoLocked) { setBpm(b.bpm); engine.setBpm(b.bpm); }
  }
  function changeBpm(value) { setBpm(value); engine.setBpm(value); }
  function changeVolume(value) { setVolume(value); engine.setVolume(value); }

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
    selectBeat(newBeat); // reflect the saved beat in the engine + main view (engine already stopped)
  }

  // Open the editor blank (new beat).
  function openNewBeat() {
    engine.stop(); setPlaying(false);
    setEditorInitial(null);
    setView("editor");
  }
  // Open the editor on the loaded beat. Custom beats edit in place (same id);
  // built-ins fork into a fresh custom copy that the original never sees.
  function openEditBeat() {
    engine.stop(); setPlaying(false);
    const seed = isCustomBeat(beat.id)
      ? beat
      : {
          ...beat,
          id: "custom_" + beat.name.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now(),
          name: beat.name + " (custom)",
          note: "Custom",
        };
    setEditorInitial(seed);
    setView("editor");
  }
  function deleteCustomBeat(id, e) {
    e.stopPropagation();
    const updated = customBeats.filter(b => b.id !== id);
    setCustomBeats(updated);
    try { localStorage.setItem(SAVED_KEY, JSON.stringify(updated)); } catch (e) {}
    if (beatId === id) selectBeat(BEATS[0]);
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

  // ── Beats view — library-first "Choose a beat" list ──
  if (view === "beats") {
    return (
      <div className="kc-screen" style={screenFixed}>
        <header style={st.subHeader}>
          <span style={{ width: 44 }} aria-hidden="true" />
          <h1 style={st.subTitle}>Choose a beat</h1>
          <span style={{ width: 44 }} aria-hidden="true" />
        </header>
        <section style={st.beatList}>
          {allBeats.map(b => {
            const sel = b.id === beatId;
            const isCustom = b.note === "Custom";
            // The row is a div, not a button: nesting the delete button inside
            // a row-button is invalid HTML and confuses screen readers. The
            // name area is the keyboard-reachable select control; the whole
            // row stays tappable via the div's onClick (delete stops
            // propagation so it never also selects).
            return (
              <div key={b.id} onClick={() => selectBeat(b)}
                style={{ ...st.beatRow, borderColor: sel ? "var(--accent-action)" : "var(--rule)" }}>
                <BeatGlyph pattern={b.dayan} />
                <button onClick={() => selectBeat(b)} aria-pressed={sel} style={st.beatRowSelect}>
                  <span style={st.beatRowName}>{b.name}</span>
                  <span style={st.beatRowMeta}>{b.steps}-step · {b.note}</span>
                </button>
                {isCustom && (
                  <button onClick={(e) => deleteCustomBeat(b.id, e)} aria-label={`Delete ${b.name}`}
                    style={st.deleteBtn}>×</button>
                )}
                <RadioDot selected={sel} />
              </div>
            );
          })}
        </section>
        <button onClick={startBeat} disabled={!ready} style={{ ...st.startBtn, opacity: ready ? 1 : 0.5 }}>
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5 19 12 7 19 Z" fill="currentColor" /></svg>
          Start · {beat.name}
        </button>
        {bottomNav}
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
  const volPct  = volume * 100;

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
          <button onClick={openEditBeat} style={st.landEditBtn}
            aria-label={isCustomBeat(beat.id) ? "Edit this beat" : "Customize this beat"}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
            </svg>
          </button>
          <span style={st.landName}>{beat.name}</span>
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
          <button onClick={togglePlay} disabled={!ready}
            style={{ ...st.landPlayBtn, opacity: ready ? 1 : 0.5 }}
            aria-label={playing ? "Pause" : "Play"}>
            {playIcon}
          </button>
        </div>

        <div style={st.landNavWrap}>{bottomNav}</div>
      </div>
    );
  }

  return (
    <div className="kc-screen" style={screenFixed}>
      {/* Small wordmark only — the full brand moment moves to the splash
          page (upcoming commit), where the dharma wheel becomes the hero. */}
      <header style={st.header}>
        <img
          src="/images/kirtan-companion-stacked1.svg"
          alt="Kirtan Companion"
          style={st.brandName}
        />
      </header>

      {/* Beat name + meta, with the edit shortcut alongside. */}
      <div style={st.beatHead}>
        <div style={{ minWidth: 0 }}>
          <h1 style={st.beatName}>{beat.name}</h1>
          <span style={st.beatMeta}>{beat.note} · {beat.steps} cells</span>
        </div>
        <button onClick={openEditBeat} style={st.editBtn}>
          {isCustomBeat(beat.id) ? "Edit" : "Customize"}
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

        {/* Temporary compact volume — replaced by the mixer commit. */}
        <div style={st.volRow}>
          <span style={st.volLabel}>Volume</span>
          <input className="kc-range" type="range" min={0} max={100} value={volPct}
            onChange={(e) => changeVolume(Number(e.target.value) / 100)}
            style={{ "--fill": volPct + "%", flex: 1 }} aria-label="Volume" />
        </div>
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
    </div>
  );
}

const st = {
  // NOTE: keep this padding identical to BeatEditor's st.screen so the bottom
  // nav sits at the exact same spot on every page and never shifts on switch.
  screen: { width: "100%", maxWidth: 430, minHeight: "100dvh", margin: "0 auto", display: "flex", flexDirection: "column", padding: "calc(var(--space-6) + env(safe-area-inset-top)) calc(var(--space-5) + env(safe-area-inset-right)) calc(var(--space-4) + env(safe-area-inset-bottom)) calc(var(--space-5) + env(safe-area-inset-left))", gap: "var(--space-5)" },
  header: { flexShrink: 0, display: "flex", justifyContent: "center" },
  brandName: { display: "block", height: "clamp(30px, 6vh, 42px)", width: "auto" },
  beatHead: { flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" },
  beatName: { margin: 0, fontFamily: "var(--font-display)", fontWeight: 600, fontSize: "var(--text-display-lg)", color: "var(--syahi)", lineHeight: 1.15, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  beatMeta: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--syahi-soft)", fontWeight: 500 },
  stripWrap: { flex: 1, minHeight: 0, display: "flex", alignItems: "center" },
  bpmRow: { display: "flex", alignItems: "baseline", justifyContent: "center", gap: 8, marginBottom: "var(--space-2)" },
  volRow: { display: "flex", alignItems: "center", gap: "var(--space-3)" },
  volLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-xs)", fontWeight: 600, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--syahi-soft)" },
  playBtn: { flexShrink: 0, width: "100%", minHeight: 58, borderRadius: 16, border: "none", background: "var(--clay)", color: "var(--on-clay)", display: "flex", alignItems: "center", justifyContent: "center", gap: 10, cursor: "pointer", fontFamily: "var(--font-body)", fontSize: "var(--text-body-md)", fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase" },
  subHeader: { display: "flex", flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: "var(--space-3)" },
  subTitle: { margin: 0, fontFamily: "var(--font-display)", fontWeight: 400, fontSize: "var(--text-display-lg)", letterSpacing: "0.01em", color: "var(--ink-primary)" },
  backBtn: { flexShrink: 0, width: 44, height: 44, borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-secondary)", display: "grid", placeItems: "center", cursor: "pointer" },
  subtitle: { margin: 0, fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", color: "var(--ink-secondary)", fontWeight: 400, maxWidth: 260, lineHeight: 1.45 },
  editBtn: { flexShrink: 0, minHeight: 44, padding: "0 16px", borderRadius: 12, border: "var(--rule-hairline)", background: "transparent", color: "var(--ink-primary)", fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 700, cursor: "pointer", letterSpacing: "0.02em", display: "grid", placeItems: "center" },
  controls: { flexShrink: 0, display: "flex", flexDirection: "column", gap: "var(--space-5)" },
  controlLabel: { fontFamily: "var(--font-body)", fontSize: "var(--text-display-md)", letterSpacing: "0.12em", textTransform: "uppercase", color: "var(--ink-secondary)", fontWeight: 600, marginBottom: "var(--space-3)" },

  // ── Beats page (library-first list) ──
  beatList: { flex: 1, minHeight: 0, overflowY: "auto", display: "flex", flexDirection: "column", gap: "var(--space-3)", padding: "2px" },
  beatRow: { display: "flex", alignItems: "center", gap: "var(--space-4)", padding: "14px 16px", borderRadius: 18, border: "var(--rule-hairline)", background: "var(--surface-raised)", cursor: "pointer", textAlign: "left", width: "100%" },
  beatRowSelect: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2, padding: 0, border: "none", background: "transparent", textAlign: "left", cursor: "pointer" },
  deleteBtn: { flexShrink: 0, width: 40, height: 44, margin: "-8px -6px", border: "none", background: "transparent", color: "var(--ink-secondary)", fontSize: 20, lineHeight: 1, cursor: "pointer", display: "grid", placeItems: "center", borderRadius: 12 },
  beatRowName: { fontFamily: "var(--font-display)", fontSize: 17, letterSpacing: "0.01em", color: "var(--ink-primary)" },
  beatRowMeta: { fontFamily: "var(--font-body)", fontSize: "var(--text-body-sm)", fontWeight: 500, color: "var(--ink-secondary)" },
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
