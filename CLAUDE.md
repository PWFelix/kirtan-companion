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

- `src/App.jsx` — single-page shell. Holds the engine in a `useRef` (created once), owns all React state (current beat, bpm, mute state, volume, view). Switches between views via a `view` string (`"main" | "editor"` — and an in-flight `"practice"` lives in untracked `src/PracticeView.jsx`).
- `src/BeatIndicator.jsx` — pure visualization. One oval pill per drum end, shape cells inside, smooth gliding playhead line driven by a CSS keyframe (`kc-glide` in `index.css`). Tappable pills call back via `onToggleMute(end)`. The `ROWS` constant at the top is the **single place** to add a new instrument row (e.g. karatalas).
- `src/BeatEditor.jsx` — custom-beat creation, persisted to `localStorage` under `kirtan-custom-beats`.

### Data layer

- `src/data/beats.js` — pure beat data. Each beat: `{ id, name, note, bpm, steps, dayan: [...], bayan: [...] }`. Stroke values: `"O"` open, `"X"` closed, `null` rest.
- `src/data/strokes.js` — **single source of truth for stroke visuals.** Each entry maps a stroke letter to `{ shape, color, label }`. Adding a new stroke type is one entry here; the indicator picks it up automatically. If you add a brand-new shape name, add its drawing case in `BeatIndicator.renderShape`.
- `src/data/stepLabels.js` — pure helper turning a step count into `"1 + 2 + ..."` style labels (handles 8, 12, 16).

## Conventions

- **Sound files live in `public/sounds/`** and the manifest passed to `engine.loadSounds()` uses the prefix routing convention above. Currently: `dayan_open.wav`, `dayan_closed.wav`, `bayan_open.wav`, `bayan_closed.wav`.
- **Adding a new instrument row** (e.g. karatalas) is a three-step pattern, no engine refactor needed:
  1. Add an entry to `ROWS` in `BeatIndicator.jsx`.
  2. Add a matching pattern array to each beat in `beats.js` (e.g. `kartal: [...]`).
  3. Name the sound files with the matching prefix (e.g. `kartal_open.wav`) so `SoundPlayer` routes them through their own gain.
- **CSS** lives in `src/index.css` only (design tokens are CSS variables in `:root`). Component styles are inline-style objects in each `.jsx`, not modules — match the existing pattern.
- The codebase intentionally favours small, well-commented files that explain *why* (see the headers of `Sequencer.js`, `SoundPlayer.js`, `strokes.js`). New files should follow the same tone.
