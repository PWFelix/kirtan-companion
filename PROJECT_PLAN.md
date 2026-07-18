# Kirtan Companion — Project Plan & Roadmap

A living document outlining the vision, goals, decisions, progress, and roadmap for the Kirtan Companion app. Update it as the project grows.

---

## 1. What This Project Is

A browser-based (later mobile) accompaniment and learning tool for ISKCON Hare Krishna kirtan. It plays mridanga (and later other instruments) so people can chant, sing, learn, or practise kirtan without needing another person to play percussion.

The primary mantra is the Hare Krishna mahamantra, but the app is built to accommodate other mantras later.

---

## 2. Who It Is For (User Groups)

The users fall into three groups. **Group A is the heart of the first version.** The others are catered to in later parts of the app.

**Group A — "Play for me so I can do my part" (FIRST PRIORITY)**
- A devotee who can sing but cannot play mridanga while doing the offering, wanting accompaniment for morning arti without using a recording.
- A small gathering with no drummer wanting a simple drum loop, who are not technical enough to build a custom beat.
- These users want simplicity above all. A few obvious controls, good sound, instant start.

**Group B — "Teach me / help me learn" (LATER)**
- A non-devotee wanting to learn kirtan with no teacher.
- A person learning to sing kirtan.
- A mridanga student working with a teacher who uses the app to guide beats and timing.

**Group C — "Let me create and experiment" (LATER)**
- An experienced musician trying out ideas or recording, who has no second player available.

---

## 3. Core Principles (govern every decision)

1. **Lego blocks** — each piece does one job, exposes a clean interface, and can be reused or swapped without rewriting other pieces.
2. **Engine separate from interface** — the sound/timing machinery knows nothing about the UI. The UI drives the engine through "commands down, events up."
3. **Providers for data** — the app never cares *where* beats or sounds come from (built-in file, device, cloud). The source can change without touching the rest of the app.
4. **Simplest tool that works today, open to tomorrow** — e.g. a plain data file for beats now, a database later, with the architecture ready for the swap.
5. **Professional habits throughout** — small frequent commits, clear messages, good README, version control from day one. This project is also a career springboard.

---

## 4. Technology Decisions

| Decision | Choice | Why |
|---|---|---|
| First platform | Website (mobile-friendly) | Fastest to ship; suits sharing via links |
| Framework | React | Solves UI/state sync; industry standard; path to mobile via React Native |
| Audio library | Tone.js | Purpose-built for music timing; removes hand-built scheduling |
| Build tool | Vite | Modern, fast, live reload |
| Language | JavaScript | Transferable, huge ecosystem, works with Tone.js |
| Version control | git + GitHub | Collaboration and portfolio |
| Eventual mobile | React Native | Reuses React knowledge and much of the code |

---

## 5. First Version Scope (Group A)

What the first usable version must do:
- A diverse curated set of built-in beats from Sita-pati das's book (team chooses final selection).
- Choose a beat.
- Set the tempo (BPM), with tap tempo.
- Start / stop.
- Volume control.
- Steady playback at one chosen tempo (no auto-progression).
- Simple enough to start a kirtan in under 30 seconds.

---

## 6. Timeline & Progress

### ✅ Phase 0 — Discovery & Planning (DONE)
- Defined the app, users, and priorities.
- Chose the technology stack.
- Agreed the core architectural principles.

### ✅ Phase 1 — Environment & Version Control (DONE)
- Installed Node.js, created the Vite + React project.
- Installed Tone.js.
- Set up git, connected to GitHub, made the initial commit.

### ✅ Phase 2 — Engine Foundation (DONE)
- **EventEmitter** — the messaging backbone (events flow up). Tested.
- **SoundPlayer** — loads and plays sounds via Tone.js. Tested (heard a sound).
- **beats.js** — pure beat data, received from outside the sequencer. Created.
- **Sequencer** — plays a beat in time using Tone.js Transport; two independent sound voices; emits a "step" event each tick; step interval isolated for future multi-timing. Tested (heard a looping two-voice rhythm).
- Committed and pushed at each working milestone.

### ✅  Phase 3 — Wrap the Engine (NEXT)
- Combine EventEmitter, SoundPlayer, Sequencer into one clean **KirtanEngine** object so the UI talks to a single thing.
- Define its public interface: load, setBeat, setBpm, start, stop + events.

### ⬜ Phase 4 — First React Interface (Group A)
- Learn React by building: state, props, "data down / events up".
- Wire React to the engine (commands down by direct calls, status up via events).
- Components: Play/Stop button, BPM control with tap tempo, beat picker, volume control.
- A clean, simple, mobile-friendly layout.

### ⬜ Phase 5 — Flexible Step Timing
- Add support for 12-step (dadra taal) and 16-step (double-time) beats.
- Single change in the isolated `_stepInterval()` method, tested against a real 16-step beat.

### ⬜ Phase 6 — Polish the First Version
- Diverse beat selection from the book.
- Real recorded mridanga samples (replace any placeholders).
- Visual beat display (read-only "now playing" strip with playhead).
- Final simple, attractive styling.

---

## 7. Future Features (architecture must welcome; not built yet)

Roughly in likely order:

**Learning & creation**
- Beat editor (grid-based custom beat creation, Group C).
- Visual playhead and notation display (`1 + 2 + 3 + 4 +` style) for learners (Group B).
- Beginner "slow / medium / fast" simple mode.
- Kirtan auto-progression (slow → fast → down) as an optional mode.
- Tihai (ending phrase) support.

**Instruments**
- Karatalas (kartal) sounds and patterns.
- Harmonium — including pitch-shifting one recorded octave to play other notes.
- Other instruments as needed.

**Data, accounts & sharing (requires a backend)**
- Save custom beats — to device first, then cloud.
- Record or upload custom sample sounds.
- User accounts and secure login.
- Share beats (e.g. via link) and download community beats.
- Backend server + database + API.

**Singing & advanced**
- Singing/melody guidance for learning to sing kirtan.
- Support for other mantras.
- (Far future / aspirational) AI that listens to a singer and follows along like a real kirtan player.

**Platform**
- Package the website into iOS and Android apps (React Native).

---

## 8. How We Work Together

- Plan before building; agree scope before code.
- Build one small block at a time, test it in isolation, then connect it.
- Explain the *why*, not just the *how* — this project doubles as learning.
- Commit small and often with clear messages; push working milestones.
- Mark known-provisional code with `TODO` and isolate things likely to change.

---

*Last updated: keep this date current as you revise the plan.*
