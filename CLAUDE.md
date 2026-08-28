# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kirtan Companion — a browser-based accompaniment and learning tool for ISKCON Hare Krishna kirtan, playing mridanga (and later other instruments) so people can chant without needing another player. React + Vite + Tone.js, JavaScript (no TypeScript). Read `PROJECT_PLAN.md` for vision, user groups (A/B/C), scope, principles, and roadmap — it is the source of truth and is kept up to date.

## Commands

- `npm run dev` — start the Vite dev server (HMR).
- `npm run build` — production build to `dist/`.
- `npm run lint` — ESLint flat config (`eslint.config.js`).
- `npm run preview` — preview the production build.

No test script exists yet.

## Architecture

The project is built around a strict "engine separate from interface" split. The UI **must** talk to the engine only through `KirtanEngine` — never reach into `SoundPlayer` or `Sequencer` directly.

```
UI (React)  ──commands──▶  KirtanEngine (facade)
                              ├── SoundPlayer  (Tone.js audio + per-end gains)
                              └── Sequencer    (Tone.Transport, emits "step")
UI  ◀──events──  KirtanEngine  ◀──  Sequencer/SoundPlayer
```

Files:
- `src/engine/KirtanEngine.js` — the facade. Public surface: `loadSounds`, `unlock`, `setBeat`, `setBpm`, `setVolume`, `setEndMuted`, `start`, `stop`, plus `on/off` for `"ready" | "started" | "stopped" | "step"`.
- `src/engine/SoundPlayer.js` — loads/plays audio. **Routes each player by name prefix** into per-end gain channels: a sound named `dayan_*` goes through the dayan gain, `bayan_*` through bayan, anything else straight to master. This is what makes `setEndMuted("dayan", true)` work and is the contract you must honour when adding new instruments.
- `src/engine/Sequencer.js` — owns `Tone.Transport`, schedules `_tick` via `scheduleRepeat(..., _stepInterval())`. `_stepInterval()` is currently hard-coded to `"8n"`; the comment in that file is the marker for where 12/16-step timing should be added.
- `src/engine/EventEmitter.js` — tiny pub-sub used as the base class for the engine and sequencer.

### UI layer

Split by **state ownership**, not by screen count. If a piece of state is only meaningful inside one screen (which sheet is open, which tab is browsed), it lives in that screen; if it spans screens or touches the engine, it lives in a hook or in App.

- `src/App.jsx` — the shell. Routes on a `view` string (`"home" | "beats" | "editor" | "settings"`), shows the splash until Begin, and owns the one piece of state that spans everything: **which beat is loaded** (`beatId`). `selectBeat` is the seam between the library and the transport, which is why it belongs to neither.
- `src/hooks/useTransport.js` — the engine plus its React mirror (`playing`, `step`, `bpm`, volumes, mutes) and every command that writes to it. **This is the only place that calls `engine.*`**, apart from `BeatEditor`, which is handed the engine outright because it takes the transport over while open. Also exports `MIN_BPM` / `MAX_BPM`.
- `src/hooks/useBeatLibrary.js` — custom beats and categories (kirtan progressions), plus all their `localStorage` persistence (`kirtan-custom-beats`, `kirtan-user-categories`, `kirtan-active-category`). Pure data; touches no audio.
- `src/hooks/useLandscape.js` — the media query behind Home's two layouts.
- `src/views/HomeView.jsx` — the playing screen, portrait and landscape, plus the quick-pick, mixer and tempo sheets they share. Owns only which sheet is open.
- `src/views/BeatsView.jsx` — the library list, category tabs, drag-to-reorder, and its four sheets. Owns the browsed tab, the open sheet and the pending confirmation.
- `src/views/SettingsView.jsx` — placeholder shell.
- `src/ui/` — shared chrome: `icons.jsx` (every inline SVG), `BottomNav.jsx`, `ScrollFadeRow.jsx`, and `styles.js` (only the style objects two or more screens use).
- `src/BeatStrip.jsx` — the beat visualiser used by every screen. Reads `LANES` from `src/data/lanes.js`; mini variant renders `primary` lanes only.
- `src/BeatEditor.jsx` — custom-beat creation. Its own file because it owns ~14 pieces of draft state (grids, cursor, meter groups, undo stack) that mean nothing outside editing; unmounting discards the draft for free. Contract is four props in, `onSave(beat)` out.

> `src/BeatIndicator.jsx` is **orphaned** — nothing imports it; `BeatStrip.jsx` replaced it. `strokes.js`'s comments still refer to it.

### Data layer

- `src/data/beats.js` — pure beat data. Each beat: `{ id, name, note, bpm, steps, dayan: [...], bayan: [...] }`. Stroke values: `"O"` open, `"X"` closed, `null` rest.
- `src/data/strokes.js` — **single source of truth for stroke visuals.** Each entry maps a stroke letter to `{ shape, color, label }`. Adding a new stroke type is one entry here; the indicator picks it up automatically. If you add a brand-new shape name, add its drawing case in `BeatIndicator.renderShape`.
- `src/data/stepLabels.js` — pure helper turning a step count into `"1 + 2 + ..."` style labels (handles 8, 12, 16).

## Conventions

- **Sound files live in `public/sounds/`** and the manifest passed to `engine.loadSounds()` uses the prefix routing convention above. Currently: `dayan_open.wav`, `dayan_closed.wav`, `bayan_open.wav`, `bayan_closed.wav`.
- **Adding a new instrument row** (e.g. karatalas) is a three-step pattern, no engine refactor needed:
  1. Add an entry to `LANES` in `src/data/lanes.js` (the strip, the mixer and the editor all read it).
  2. Add a matching pattern array to each beat in `beats.js` (e.g. `kartal: [...]`).
  3. Name the sound files with the matching prefix (e.g. `kartal_open.wav`) so `SoundPlayer` routes them through their own gain.
- **CSS** lives in `src/index.css` only (design tokens are CSS variables in `:root`). Component styles are inline-style objects in each `.jsx` — conventionally a module-level `const st = {...}` below the component. `src/ui/styles.js` is the one deliberate exception, for chrome that two or more screens share; anything a single screen draws stays in that screen's file.
- The codebase intentionally favours small, well-commented files that explain *why* (see the headers of `Sequencer.js`, `SoundPlayer.js`, `strokes.js`). New files should follow the same tone.

## Gotchas

Hard-won rules that are easy to violate and expensive to debug — read these before touching hooks or the engine.

- **`useTransport()` is call-once — a second call builds a phantom audio graph.** Every call constructs its own `KirtanEngine` (a fresh `SoundPlayer` + `Sequencer`), so the hook is invoked exactly once — in `App.jsx` — and the returned object is handed down as the `transport` prop; views destructure what they need from that prop. Calling `useTransport()` again inside a view spins up a second, disconnected audio graph, and every engine write then lands on the engine nobody listens to: the control goes **silently inert while `npm run lint` and `npm run build` both stay green**. Consume the prop — never re-call the hook. Self-check: `grep -rn "useTransport(" src` must show exactly one call site besides the definition.
- **Hydrating state at mount — don't `setState` synchronously in the effect body.** The `react-hooks` config **errors** (not warns) on a synchronous `setState` directly in a `useEffect` body (`react-hooks/set-state-in-effect`), so the "obvious" `useEffect(() => setState(load()), [])` fails `npm run lint`. Read the stored value in a **lazy `useState` initialiser** instead — it runs once, at mount — and keep only the external-system sync (e.g. pushing loaded values into the engine) inside the effect.
