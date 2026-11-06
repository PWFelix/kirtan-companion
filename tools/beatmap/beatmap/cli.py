"""
beatmap - the command line.

  beatmap extract SONG.mp3 [--title T] [--artist A] [--out maps/<slug>]
      Separate the mridanga (Demucs), track a moving beat grid, and write a
      DRAFT map plus the audio the editor needs into one folder.

  beatmap serve [maps/<slug>] [--port 8765]
      Open the correction editor (and the play-through preview) on that map
      in the browser.

Re-running extract on the same folder reuses the separated stems (the slow
part), so tuning the detection is quick. It refuses to overwrite a map.json
that has been edited in the editor unless you pass --force, because a draft
must never silently replace someone's corrections.
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

from . import analyse, mapformat

TOOL_DIR = Path(__file__).resolve().parent.parent


def slugify(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-") or "song"


def run(cmd):
    print("  $", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run(cmd, check=True)


def separate(src, out_dir, device):
    """Demucs two-stem split: drums (the mridanga, here) and everything else."""
    drums, backing = out_dir / "drums.wav", out_dir / "backing.wav"
    if drums.exists() and backing.exists():
        print("- stems: reusing", drums.name, "and", backing.name)
        return drums, backing
    print("- separating the mridanga (Demucs htdemucs, two stems)...")
    tmp = out_dir / "_demucs"
    run([sys.executable, "-m", "demucs", "-n", "htdemucs", "--two-stems", "drums",
         "-d", device, "-o", str(tmp), str(src)])
    found = next(tmp.glob("htdemucs/*/drums.wav"))
    shutil.move(str(found), drums)
    shutil.move(str(found.parent / "no_drums.wav"), backing)
    shutil.rmtree(tmp)
    return drums, backing


def encode_mp3(src, dst):
    """Constant bitrate, so the browser can seek precisely by byte range."""
    if dst.exists():
        return
    run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-ac", "2", "-ar", "44100",
         "-c:a", "libmp3lame", "-b:a", "192k", str(dst)])


def cmd_extract(a):
    src = Path(a.song).expanduser().resolve()
    title = a.title or src.stem
    out = Path(a.out) if a.out else TOOL_DIR / "maps" / slugify(title)
    out.mkdir(parents=True, exist_ok=True)
    map_path = out / "map.json"
    if map_path.exists() and not a.force:
        existing = json.loads(map_path.read_text())
        if existing.get("edited"):
            sys.exit(f"{map_path} has been corrected in the editor; use --force to replace it.")

    drums_wav, backing_wav = separate(src, out, a.device)
    print("- encoding audio for the editor...")
    encode_mp3(src, out / "mix.mp3")
    encode_mp3(drums_wav, out / "drums.mp3")
    encode_mp3(backing_wav, out / "backing.mp3")

    print("- tracking the beat...")
    y = analyse.load_mono(drums_wav)
    beats, _ = analyse.track_beats(y)
    tempo = analyse.tempo_curve(beats)
    if tempo:
        print(f"  {len(beats)} beats, tempo {min(p['bpm'] for p in tempo):.0f}"
              f"-{max(p['bpm'] for p in tempo):.0f} BPM")

    print("- drafting strokes...")
    notes = analyse.extract_notes(y, beats, cells_per_beat=a.cells,
                                  sensitivity=a.sensitivity)
    sam = analyse.guess_sam(notes, len(beats), a.cycle)
    print(f"  {len(notes)} notes ({sum(n['lane'] == 'dayan' for n in notes)} dayan, "
          f"{sum(n['lane'] == 'bayan' for n in notes)} bayan); sam guessed at beat {sam}")

    (out / "envelope.json").write_text(json.dumps(analyse.envelope(y), separators=(",", ":")))
    m = mapformat.new_map(
        title=title, artist=a.artist or "", source_file=src.name,
        duration=len(y) / analyse.SR, beats=beats, notes=notes,
        cells_per_beat=a.cells, beats_per_cycle=a.cycle, sam_beat=sam, tempo=tempo)
    mapformat.validate(m)
    map_path.write_text(json.dumps(m, indent=1))
    print(f"- wrote {map_path}\n  next: beatmap serve {out}")


def cmd_serve(a):
    from .server import serve
    target = Path(a.map_dir) if a.map_dir else None
    if target is None:
        maps = sorted((TOOL_DIR / "maps").glob("*/map.json"))
        if not maps:
            sys.exit("no maps yet: run `beatmap extract SONG.mp3` first")
        target = maps[0].parent
    serve(target.resolve(), port=a.port, open_browser=not a.no_browser)


def main(argv=None):
    p = argparse.ArgumentParser(prog="beatmap", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("extract", help="draft a map from a recording")
    e.add_argument("song")
    e.add_argument("--title")
    e.add_argument("--artist")
    e.add_argument("--out", help="map folder (default maps/<title-slug>)")
    e.add_argument("--cells", type=int, default=4, help="cells per beat (default 4)")
    e.add_argument("--cycle", type=int, default=8, help="beats per cycle (default 8)")
    e.add_argument("--sensitivity", type=float, default=1.0,
                   help=">1 finds more (fainter) strokes, <1 fewer")
    e.add_argument("--device", default="mps", help="Demucs device: mps, cuda or cpu")
    e.add_argument("--force", action="store_true", help="replace an edited map")
    e.set_defaults(fn=cmd_extract)

    s = sub.add_parser("serve", help="open the editor on a map folder")
    s.add_argument("map_dir", nargs="?")
    s.add_argument("--port", type=int, default=8765)
    s.add_argument("--no-browser", action="store_true")
    s.set_defaults(fn=cmd_serve)

    a = p.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
