# Kirtan Companion — iOS

A native Swift/SwiftUI port of the Kirtan Companion web app: a browser-free way for
ISKCON devotees to play along at kirtan, with a mridanga (dayan + bayan) and karatalas
that keep time so you can sing.

This is a **native** port — not React Native, not Flutter. It mirrors the Android/Kotlin
port (`android/`) feature-for-feature, but is written in SwiftUI + `AVAudioEngine` and
targeting **iOS 17+**.

## Building & running

```sh
# from this directory
xcodebuild -project KirtanCompanion.xcodeproj -scheme KirtanCompanion \
  -destination "generic/platform=iOS Simulator" -configuration Debug \
  -derivedDataPath ./build build
```

Then install and launch in a simulator:

```sh
xcrun simctl install <device-id> build/Build/Products/Debug-iphonesimulator/KirtanCompanion.app
xcrun simctl launch <device-id> com.kirtan.companion.ios
```

`typecheck.sh` runs a fast `swiftc` pass over all sources (no full build) for quick iteration.

There is a hidden launch argument for unattended verification — `… launch <device>
com.kirtan.companion.ios -kirtanAutostart` skips the splash, plays the first beat, and
writes a small state report to the app's `tmp/autostart.txt` so the audio path (decode →
arm → start → a clock that keeps ticking) can be confirmed without a human tapping.

## Layout

The port keeps the web app's strict separation of **engine from interface** and the
Android port's layering:

```
Sources/
  engine/     the audio core — a pure DSP graph, no UI, no AVAudioSession
    WavDecoder · BiquadFilter · GainRamp · Voice · EndChannel
    MusicalClock · Sequencer · SoundPlayer · SoftClip
    AudioGraph (AVAudioEngine + source node) · SampleBank · KirtanEngine (facade)
  data/       the value types + pure logic every layer speaks in
    Beat · LaneId · Stroke(s) · Bols · Meter · Eq · Tuning · ShippedBeats · Beats*
    StepLabels · JsonEl · Share*
  storage/    local persistence + the cloud
    KirtanStore (one JSON file, serial queue) · LibraryJson · EqPrefs
    LibraryRepository (observable) · BeatsProvider / LocalBeatsProvider
    SupabaseConfig · AuthSession · Pkce · SupabaseHttp · SupabaseClient
    SupabaseBeatsProvider (cloud beats) · CommunityClient (public library)
  playback/   device facts + output management, kept OUT of the engine
    AudioDeviceConfig · PlaybackController (session, Now Playing, remote, interruptions)
  ui/         SwiftUI views + the observable view models
    theme/Theme (palette) · transport/TransportViewModel
    home · beats · settings · editor · community (Browse) ·
      components (BeatStrip, MixerSheet, SignInButton, SafariView)
  App/        composition root
    KirtanApp (@main) · AppContainer (DI) · AuthViewModel · RootView · NavigationShell
```

`*` — `Beats.swift` is **generated** from the canonical `src/data/beats.js` by
`scripts/generateIosBeats.mjs`. Edit the JS and regenerate; do not hand-edit it.

### The engine

`KirtanEngine` is a plain, lock-synchronised object with a stable command surface
(`start/stop`, `setBeat/Bpm/Volume/EndVolume/EndMuted/EqBand/EndPitch`, `playStroke`,
`getPhase/getStep`). On iOS the audio thread is owned by the OS: `AudioGraph` runs an
`AVAudioSourceNode` whose render block calls `KirtanEngine.render(fromFrame:count:out:)`,
which drains any queued pads, collects the sequencer's step events for the frame range,
plays the strokes, and mixes per-end → master into the output buffers. The engine never
allocates in the render path — scratch buffers are stored and mutated in place.

This differs from the Android port (which pulls samples out on a manual `AudioTrack`
thread) — on iOS the platform paces the render, which is simpler and sample-accurate.

Polyphony follows the web: each stroke owns a small fixed pool of voices — one slot per
sample variant (three for an open stroke, one for a closed) — and a trigger restarts a
slot in place, round-robin, avoiding the most recently played one. That bounds overlap to
the number of variants and staggers their decays, so rings overlap and shimmer while the
mix stays well below full scale. An unbounded voice stack (spawn-and-steal) instead sums
to several times unity and drives the soft clip into a harsh buzz — so the bounded pool
is a deliberate match to the web's `Tone.Player` restart model, not a cost saving.

### Per-end routing (the contract)

Each sample routes by name prefix to an instrument channel: `dayan_*` → dayan channel,
`bayan_*` → bayan, `kartal_*` → kartal, anything else straight to master. The dayan and
bayan channels each carry a 5-band EQ + gain + a makeup; the kartal channel is gain only.
Adding a new instrument is: a `LaneId` case, a pattern in `Beats`, and lane-prefixed
sound files — no engine change.

## What's in, and what's deferred

**In:** the full audio engine; the beat library (search, sectioned browsing, delete);
the home player (beat strip with a gliding playhead, transport, tempo, the mixer with
per-end volume/mute/EQ/tuning); the beat **editor** (paint a new custom beat, pinch-zoom
the grid, preview it, save it); settings (notation toggles, and — when a cloud is in —
the sign-in/out account); local persistence (library + mixer prefs in one JSON file);
and full **background audio** with lock-screen / Control Center (Now Playing + remote
commands + interruption handling).

The **cloud layer** is a hand-rolled URLSession client (no SDK dependency): OAuth
sign-in via PKCE with a `kirtan://` deep-link round trip, per-user beat/playlist sync
behind the same `BeatsProvider` seam, and the public community library. It is built
**nil-safe** — a build compiled without the project's keys simply hides every cloud
affordance, so a fresh clone runs fully local with no configuration. The keys come from
the shared backend (see `Info.plist`'s `KIRTAN_SUPABASE_*`).

The cloud surfaces are drawn: **Settings** shows the signed-in account (Sign in opens the
OAuth redirect in an in-app browser and the `kirtan://` return lands the session; Sign
out clears it), and **Beats** carries a *Community* segment that browses the public
library — a search scoped to everything / names / authors, a card per item with a mini
pattern preview, star ratings (yours, plus the community average), a one-tap copy that
afterwards reads **In your library** (the copy remembers its `sourceItemId`), and
unpublish for your own items.

**At parity with Android (0.2.0):** a **Feel** setting in the mixer (a deterministic
humanizer: timing drift, accents, a slightly lagging bayan); Share and Import sheets with
`kirtan://beat?c=…` links (or a web base set in `KIRTAN_SHARE_WEB_BASE`); publish /
unpublish from any beat's or list's "…" menu; editing saved beats; creating and renaming
progressions; a first-sign-in offer to move the device library into the account; the
built-in set served from `shipped_beats` (cached, validated, falling back to the compiled
set); maintainer tools to edit or remove a built-in for everyone and promote a community
beat into it; a Learn tab placeholder; the app icon and splash drawing.

The **Beats library** is a sectioned scroll (built-in / your beats / each progression a
beat belongs to) with a live search and per-beat delete. The web's desktop *hub-of-cards*
that drills into per-category pages with drag-reorder is deliberately flattened: a phone
list reads better, and the repository only reorders real user categories (not the virtual
"Your beats" list), so drag-reorder is the one interaction left out.

**Deferred:** beat drag-reorder (above) is omitted because it would need the repository
to also order the virtual "Your beats" list. Guided learning (the Learn tab) is not built
on any platform yet.

## Testing

```sh
xcodebuild -project KirtanCompanion.xcodeproj -scheme KirtanCompanion \
  -destination "platform=iOS Simulator,name=iPhone 18 Pro" \
  -derivedDataPath ./build test
```

Seven suites (94 tests), all run on the simulator: `DataTests` (the beat/meter/share
layer), `EngineTests` (the pure DSP — gain ramps, the biquad, the soft clip, the musical
clock — no live `AVAudioEngine`), `StorageTests` (the pure mixer-prefs codec plus real
round-trips against the store file), and `CloudTests` (the pure halves of the transport:
PKCE, URL building, error mapping, row mapping, search filters — the exact places a
shape mismatch between the two platforms hides), `HumanizerTests` (the Feel), and
`ParityTests` (served built-in validation and the row writer, `sourceItemId` provenance,
category rename, deep links, rating averages). The live network path is not a unit test
(a flaky network would fail CI); it is verified separately with an anonymous community
browse against the configured project.

The test target is a **standalone** bundle that compiles `Sources/data`, `Sources/engine`
and `Sources/storage` directly into itself, rather than `@testable import`-ing the app.
A hand-rolled project can't link an `.app`'s symbols into a test bundle (an app bundle
isn't a library), but it can compile the same sources — so each layer is tested against
the exact code the app ships, with no import. The engine's signal path is lock- and
allocation-verified by the DSP tests; the full audio graph is exercised for real on the
simulator via the `-kirtanAutostart` hook above.

## Notes

- The project is built in **Swift 5 language mode** (not 6): the DSP runs on the audio
  thread inside non-isolated closures, and Swift 6 strict concurrency would force a
  rework of the engine's isolation for no audio benefit.
- The app is **always light** (`preferredColorScheme(.light)`): the palette is a warm,
  matte "rawhide" scheme and the web app ships no dark variant to invert into.
- `Resources/Sounds/` holds the recordings; they are bundled flat with lane-prefixed
  names (`dayan_open_1.wav` …) because the synchronized folder group flattens them to the
  bundle root, and unique names keep the six+ strokes from colliding.
