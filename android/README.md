# Kirtan Companion — Android

A native Android port of the [web app](../) in **Kotlin + Jetpack Compose**.

Same product, same beat data, same share links — but with the one thing a browser
tab cannot do: **keep the drum playing with the screen off**, behind a
`mediaPlayback` foreground service with a media notification and lock-screen
controls. For the primary user, chanting at morning arti with the phone
face-down, that is not a polish item. It is the product requirement, and it is
why this exists as a native app rather than a WebView around the website.

---

## Build and run

```bash
cd android
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # the pure data + engine tests
./gradlew installDebug           # to a device or a running emulator
```

`local.properties` is git-ignored and holds `sdk.dir`. It also takes optional
`SUPABASE_URL` / `SUPABASE_ANON_KEY`; **both default to empty, and empty means
the cloud features are simply not offered** — the app is fully functional on
device storage alone, exactly like the web build with no `VITE_SUPABASE_*` set.

Toolchain: Gradle wrapper 8.14.5, AGP 8.13.2, Kotlin 2.2.21, compileSdk 36,
minSdk 26. See the header of [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
for why every dependency is pinned exactly where it is — the short version is
that the newest release of several libraries requires compileSdk 37, which is
beyond AGP 8.13's supported maximum.

`minSdk 26` is not arbitrary: it is where `AudioTrack.Builder` exposes
`PERFORMANCE_MODE_LOW_LATENCY`, which the mixer's buffer sizing depends on.

---

## Architecture

The web app's founding invariant — *"engine separate from interface; the UI talks
to the engine only through `KirtanEngine`"* — is what made this port tractable.
That seam already existed, so everything behind it could be reimplemented without
any screen needing to know the engine had changed.

```
UI (Compose) ──commands──▶ KirtanEngine (facade)
                             ├── SoundPlayer  (voices, per-end EQ, faders, master)
                             ├── Sequencer    (step boundaries from the clock)
                             └── MusicalClock (frames ↔ ticks)
UI ◀──events── KirtanEngine        ▲
                                   │ pulls one block at a time
                             AudioRenderer (thread + AudioTrack)
```

| Web | Android | Notes |
|---|---|---|
| `src/data/*` | `data/` | Pure Kotlin, no Android imports, JVM-testable |
| `src/engine/KirtanEngine.js` | `engine/KirtanEngine.kt` | Same public vocabulary, method for method |
| `src/engine/SoundPlayer.js` | `engine/SoundPlayer.kt` + `EndChannel.kt` + `Voice.kt` | Web Audio's graph is reimplemented as a mixer |
| `src/engine/Sequencer.js` | `engine/Sequencer.kt` + `MusicalClock.kt` | Scheduling **inverted** — see below |
| *(Web Audio provides it)* | `engine/AudioRenderer.kt` | The thread, buffer and device we now own |
| *(Web Audio provides it)* | `engine/BiquadFilter.kt` | Coefficient-compatible with `BiquadFilterNode` |
| `src/storage/BeatsProvider.js` | `storage/BeatsProvider.kt` | Same contract, `suspend` instead of `async` |
| `src/data/shareCodec.js` | `data/ShareCodec.kt` | **Byte-identical** wire format, verified |
| `src/hooks/useTransport.js` | `ui/transport/TransportViewModel.kt` | |
| `src/hooks/useBeatLibrary.js` | `storage/LibraryRepository.kt` | |

### The one deliberate inversion: scheduling

The web engine **pushes**. `Tone.Transport.scheduleRepeat` registers a JS
callback, the transport fires it slightly ahead of the audio time it names, and
the callback passes that time to `player.start(time)`. Correct timing therefore
depends on a callback landing inside a lookahead window — which is why
`Sequencer.js` carries a same-step guard, a float epsilon, and a comment about
callbacks arriving "a hair late".

Here the renderer **pulls**. Ticks are a pure linear function of frames written,
so a step's exact frame is computed in advance and its voice is started at that
offset *inside the block being rendered*. There is no scheduler in the signal
path, hence no jitter to guard against. The blocking `AudioTrack.write` is the
only clock, and it is the hardware's — so the sequencer cannot drift against the
audio, because there is exactly one clock.

What was preserved verbatim is the **derivation**, because that fixed a real bug
rather than a theoretical one: the step is never counted, it is recomputed from
bar phase (`step = floor(phase × steps + ε)`). A counted step is grid-relative —
"step 12" means nothing without "out of 16" — so switching from a 16-step to an
8-step beat mid-play would index past the end of the new pattern.

### Why a hand-written mixer

`SoundPool`, `MediaPlayer` and `ExoPlayer` all want to own playback. None can be
summed through a per-end biquad chain at a caller-chosen gain, which is what the
routing in `SoundPlayer.js` specifies:

```
dayan/bayan:  voices → that end's EQ chain → end gain ─┐
kartal:       voices ─────────────────────→ end gain ─┼→ master gain → out
```

The EQ sits **pre-fader**, so tone shaping never disturbs volume, mute or the
bayan's fixed 1.25 makeup gain. The karatalas carry **no EQ** — by design, they
are a separate instrument rather than a head of the mridanga.

Voices are summed per END before the chain, not filtered individually: that is
what the web graph does (every player connects to the same chain head, so the
filters see the sum and share one set of delay registers), and it is roughly five
times cheaper.

---

## Wire compatibility

A shared beat carries **no id** — deliberately, so a stranger's link cannot name
one of your beats and quietly replace it. The consequence is that each client
resolves built-in ids against its *own* compiled list, so **the two platforms must
agree byte for byte** or the same link silently encodes a different beat on each.

That is asserted, not hoped for. `app/src/test/resources/share-vectors.json` is
generated by running the web app's own `src/data/shareCodec.js` under Node:

```bash
node scripts/generateShareVectors.mjs   # from the repo root, not android/
```

`ShareCodecWireTest` then requires the Kotlin encoder to produce the **identical
string** for every built-in beat. Regenerate the fixture after changing either
codec.

---

## Testing

The pure layers carry no Android imports, so they test on the JVM in milliseconds
— no device, no emulator, no Robolectric:

- `data/` — beat invariants, meter derivation, labels, tuning, bols
- `data/ShareCodec` — wire compatibility against real web-generated vectors, plus
  the trust boundary (hostile payloads must be rejected or stripped safe)
- `engine/` — clock arithmetic and its exact inverses, biquad coefficients against
  known filter behaviour, WAV decoding at 16- and 24-bit, round-robin selection
- `engine/MixerRenderTest` — renders real blocks **offline** through the whole DSP
  path and checks the audio numerically: a stroke placed at frame 137 is silent
  through frame 136; muting one end leaves the other untouched; a flat EQ chain is
  transparent; the limiter is monotonic, unity below the knee and bounded above it;
  and a full bar of every shipped beat fires every stroke exactly once.

`MixerRenderTest` is only possible because `SoundPlayer.attach` takes a plain map
of samples rather than a `SampleBank`. Keeping asset loading out of the mixer is
what makes "does this engine actually make sound" a laptop question.

Audio **quality** still needs a real device: emulator audio is a poor proxy for
latency, and the two places latency is audible are the editor's pads and tap
tempo. A running loop is unaffected, because constant latency is inaudible.

### What was verified on a device

The app was built, installed on an API 36 emulator and driven end to end:

- Splash → Begin → Home, with the wheel, wordmark and tilak rendering as designed.
- Play starts the loop: the playhead tracks the audio clock, and the sounding step
  turns syahi-black across every lane.
- `PlaybackService` promotes to a **foreground service** with
  `types=mediaPlayback` and a transport-category media notification — the property
  that makes the drum survive a locked screen, and the reason this app is native.
- The sample bank logs the designed degradation: the missing third karatala variant
  warns and is skipped; all six strokes load; the device negotiates 48 kHz and the
  44.1 kHz recordings resample.

Three bugs were found **only** by running it, which is the argument for doing so:
a `SharedFlow` without replay dropping the `Ready` event (leaving the Play button
permanently disabled), a dropped `ready = true` in the event handler, and
`SimpleBasePlayer` throwing `IllegalStateException` when touched off the main
thread. None of these is visible to a compiler or a JVM test.

## What is NOT ported

Stated plainly, so nothing here reads as more finished than it is:

- **The beat editor.** `BeatEditor.jsx` is 551 lines of intricate draft state — a
  meter-group model supporting uneven signatures like 7/8, a 60-deep undo stack,
  zoom paging over the grid, lane isolation for authoring the cymbal row, and pads
  that write-sound-advance in one gesture. The Editor tab currently shows an
  honest placeholder. Everything it depends on is already here and tested: the
  meter and label derivation, the bol names, the share codec it round-trips
  through, and `KirtanEngine.playStroke` for the pads.
- **Community / Browse UI.** The client exists in `storage/CommunityClient.kt`;
  only its screen is missing.
- **Share and import sheets.** The codec and the deep-link intake are complete and
  tested; inbound `kirtan://beat?c=…` links ARE handled in `MainActivity`. What is
  missing is the sheet that shares a beat and the field that pastes a code.
- **The sign-in sheet.** The PKCE client, session persistence, the
  `kirtan://auth/callback` manifest route and its one-chance-only handling in
  `MainActivity` are all wired; `AppContainer` follows the session and swaps the
  library provider between local and cloud. What is missing is the UI that calls
  `beginOAuth()` and opens the returned URL in a Custom Tab.
  **Before any of it can work against a real project**, `kirtan://auth/callback`
  must be added to the Supabase dashboard's Authentication → URL Configuration →
  Redirect URLs, or GoTrue refuses the flow before the provider is reached.
- **The landscape Home layout** (transport collapsed into one 44dp rail), the
  tempo sheet's snapping BPM wheel, and the strip's loop-position bar.

