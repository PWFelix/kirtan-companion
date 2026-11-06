# beatmap: charts from real kirtan recordings

Tooling for **Mrdanga Hero play-throughs**. It takes a kirtan recording,
isolates the mridanga, follows the beat as the kirtan speeds up, and drafts a
stroke chart. A person who plays then corrects the draft in a desktop editor,
and the result plays back as a Guitar-Hero-style highway synced to the
artist's own recording.

It is a proof of concept for showing artists. It lives apart from the apps
(nothing outside `tools/beatmap/` is touched) and runs only on your own
computer.

```
song.mp3 ──extract──▶ maps/<song>/            ──serve──▶ editor     (correct)
                        map.json   the chart              play-through (preview)
                        mix / drums / backing .mp3
                        envelope.json (attack curves the editor draws)
```

## Install (once)

Needs Python 3.11 (via [uv](https://docs.astral.sh/uv/)) and `ffmpeg`.

```sh
uv tool install -p 3.11 --editable tools/beatmap
```

That puts `beatmap` on your PATH, with its own environment outside the repo.
Install it this way and don't create a `.venv` inside `tools/`: the repo's
ESLint would wander into the JavaScript bundled with the Python packages.
The first `extract` downloads the Demucs model (~80 MB).

## Use

```sh
beatmap extract "~/Music/.../Mishra Kalavati.mp3" --artist "Govinda das"
beatmap serve tools/beatmap/maps/mishra-kalavati
```

`extract` takes about a minute for an 18-minute kirtan on an M-series Mac
(Demucs runs on `mps`; pass `--device cpu` elsewhere). Re-running it reuses
the separated stems, so tuning is quick (`--sensitivity 1.3` finds fainter
strokes, `0.8` fewer). It won't replace a map you have corrected unless you
pass `--force`.

`serve` opens the editor. The play-through is linked from its top bar
(`/preview`).

### The editor

| Do | How |
|---|---|
| play / pause | Space |
| slow down (pitch is kept) | Speed, or `[` `]` |
| hear the full mix / the mridanga alone / the backing alone | `1` `2` `3` |
| hear the chart, played with the app's own samples | `C` |
| a click on every beat (accent on sam) | `M` |
| recording in the left ear, chart in the right | `P` (split ears) |
| add a stroke; confirm a faint (unsure) one | click its cell in a lane |
| remove a stroke | click it again once it's confirmed |
| change a stroke (e.g. Ta → Te) | click the other lane in the same cell |
| fix the grid | drag a beat line in the ruler; its strokes move with it |
| insert / delete a beat | Alt-click the ruler (on a line deletes it) |
| set sam | `S` (at the mouse, or at the playhead) |
| jump to the next unsure stroke | `N` |
| seek | click anywhere, the overview strip, or ← → (Shift: 10 s) |
| zoom | `-` `=` or Ctrl/⌘ + scroll |
| undo / redo, save | ⌘Z / ⇧⌘Z, ⌘S |

Faint strokes with a dashed ring are ones the extractor was unsure of. Start
there. Saving writes `map.json` and keeps the previous one as `map.json.bak`.

A good workflow: first hear the mridanga alone with beat clicks, and fix the
grid and sam. Then turn on the chart in split ears at 75% speed, and fix
strokes cycle by cycle, using `N` to hop between unsure ones.

## The map format

`map.json` (format `mrdanga-hero-map`, version 1). See
`beatmap/mapformat.py` for the full contract. In short:

- `beats`: the time in seconds of every beat. This is the tempo map. A kirtan
  isn't at one tempo, so each beat is stored rather than a BPM.
- `grid`: `cellsPerBeat` (4), `beatsPerCycle` (8), and `samBeat`, the index
  of one sam.
- `notes`: `{b, c, lane, s, conf?}`, which is cell `c` of beat `b`. A note's
  time is derived from the grid, so fixing the grid carries the notes along.
  `lane`/`s` are the app's codes: dayan O = Ta, dayan X = Te, bayan O = Ge,
  bayan X = Khe. `conf` exists only on draft notes nobody has confirmed yet.
- `tempo`: a smoothed `{t, bpm}` curve, for display only.

Kartal is left out for now.

## How the draft is made (and how good it is)

See the header of `beatmap/analyse.py`. In brief: Demucs `htdemucs` splits the
recording into drums (the mridanga) and everything else. A beat tracker is
guided by a smoothed, time-varying tempo curve, so it follows the
accelerando. Each 16th-note cell is then tested for a dayan or bayan strike
against the local playing level. Open vs closed is decided by how much the
stroke rings after the attack.

On *Mishra Kalavati* (Govinda das, 17:48, 92 → 140 BPM), measured against the
stroke onsets in the separated mridanga track:

| at | BPM | strokes found | chart strokes that are real |
|---|---|---|---|
| 1:00 | 92 | 72% | 89% |
| 4:00 | 96 | 81% | 87% |
| 7:00 | 108 | 78% | 87% |
| 10:00 | 129 | 82% | 80% |
| 13:00 | 140 | 88% | 77% |
| 16:00 | 136 | 83% | 74% |

So the grid holds, and most strokes are placed. Open/closed labelling is the
weakest part and is what the corrector spends time on. Each corrected map is
labelled data, so a trained stroke classifier is the natural next step once a
few exist.

## Rights

`maps/` is git-ignored because it holds the artists' audio. Never commit it.
A `map.json` on its own contains no audio.
