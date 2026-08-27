# Karatala (kartal) samples

Drop the karatala recordings here. `SoundPlayer` (`src/engine/SoundPlayer.js`)
looks for these exact filenames — same layout as `dayan/` and `bayan/`:

- `open_1.wav`, `open_2.wav`, `open_3.wav` — the ringing/open strike.
  Multiple takes drive the round-robin so repeats never sound identical;
  one file is enough to start (drop just `open_1.wav`), more is better.
- `closed_1.wav` — the damped/choked strike.

Until these files exist the app still runs: `SoundPlayer.load()` skips a
missing sample with a warning, the kartal lane just stays silent. As soon as
the files are here the existing beats' `1-2-3` cymbal lines play automatically.

WAV, mono or stereo, trimmed tight to the transient (no leading silence) —
matching the drum samples already in this folder's siblings.
