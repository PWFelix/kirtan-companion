"""
The beat-map file (map.json), version 1.

WHY NOTES ARE (beat, cell) AND NOT SECONDS. A recorded kirtan has no fixed
tempo: it accelerates for ten minutes and the player breathes inside every
bar. So the map's backbone is the list of BEAT TIMES, and each note says
"cell c of beat b". Its time is derived: beat b's start plus c/cellsPerBeat of
the way to beat b+1. That keeps the corrector's two jobs independent. Fixing
the grid (dragging a beat) carries its notes with it, and fixing a note never
disturbs the grid. It is also how the app thinks (cells in a cycle), so a map
reads as a sequence of the app's beats.

Lanes are the app's lane ids ("dayan", "bayan") and strokes its codes ("O"
open, "X" closed), so the bols match everywhere else: dayan O = Ta,
dayan X = Te, bayan O = Ge, bayan X = Khe. Kartal is left out for now.

`conf` (0..1) is the extractor's confidence. The editor draws low-confidence
notes faintly so a corrector checks them first. A note a person has placed has
no `conf`: it is simply true.
"""

FORMAT = "mrdanga-hero-map"
VERSION = 1

LANES = ("dayan", "bayan")
STROKES = ("O", "X")


def new_map(*, title, artist, source_file, duration, beats, notes,
            cells_per_beat=4, beats_per_cycle=8, sam_beat=0, tempo=None):
    return {
        "format": FORMAT,
        "version": VERSION,
        "title": title,
        "artist": artist,
        "source": {"file": source_file, "duration": round(float(duration), 3)},
        # Paths relative to the map's folder; the editor fetches them.
        "audio": {"mix": "mix.mp3", "drums": "drums.mp3", "backing": "backing.mp3"},
        "analysis": "envelope.json",
        "grid": {
            "cellsPerBeat": cells_per_beat,
            "beatsPerCycle": beats_per_cycle,
            # Index into `beats` of a sam (the first beat of a cycle). Every
            # beatsPerCycle-th beat from here, both ways, is a sam too.
            "samBeat": sam_beat,
        },
        "beats": [round(float(t), 4) for t in beats],
        # Smoothed BPM curve, for display only: [{"t": seconds, "bpm": n}, ...].
        "tempo": tempo or [],
        "notes": notes,
    }


def note_time(m, n):
    """Seconds at which note n sounds, from the beat grid."""
    beats, cpb = m["beats"], m["grid"]["cellsPerBeat"]
    b = n["b"]
    a = beats[b]
    nxt = beats[b + 1] if b + 1 < len(beats) else a + (a - beats[b - 1])
    return a + (nxt - a) * n["c"] / cpb


def validate(m):
    """Raise ValueError on a map the editor or app could not use."""
    if m.get("format") != FORMAT or m.get("version") != VERSION:
        raise ValueError("not a version-1 mrdanga-hero-map")
    beats = m["beats"]
    if any(b2 <= b1 for b1, b2 in zip(beats, beats[1:])):
        raise ValueError("beats must strictly increase")
    cpb = m["grid"]["cellsPerBeat"]
    seen = set()
    for n in m["notes"]:
        if not (0 <= n["b"] < len(beats) and 0 <= n["c"] < cpb):
            raise ValueError(f"note off the grid: {n}")
        if n["lane"] not in LANES or n["s"] not in STROKES:
            raise ValueError(f"unknown lane/stroke: {n}")
        key = (n["b"], n["c"], n["lane"])
        if key in seen:
            raise ValueError(f"two strokes on one hand in one cell: {n}")
        seen.add(key)
