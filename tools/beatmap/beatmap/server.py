"""
The editor's local web server. Standard library only.

Routes:
  /                 the correction editor  (editor/index.html)
  /preview          the play-through preview (editor/preview.html)
  /editor/...       the editor's static files
  /map/...          files in the map folder (map.json, audio, envelope)
  /sounds/...       the app's own drum samples (Android assets), so the
                    editor plays the chart with the exact sounds of the app
  POST /api/save    write map.json (the previous one is kept as map.json.bak)

Audio needs HTTP Range support: without it the browser can't seek in an
18-minute file, and seeking is most of what a corrector does.
"""

import json
import mimetypes
import os
import shutil
import threading
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import mapformat

TOOL_DIR = Path(__file__).resolve().parent.parent
EDITOR_DIR = TOOL_DIR / "editor"
SOUNDS_DIR = TOOL_DIR.parent.parent / "android" / "app" / "src" / "main" / "assets" / "sounds"


def make_handler(map_dir: Path):
    roots = {"/editor/": EDITOR_DIR, "/map/": map_dir, "/sounds/": SOUNDS_DIR}

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # keep the terminal quiet; errors still raise

        def _resolve(self, path):
            path = path.split("?", 1)[0]
            if path in ("/", "/index.html"):
                return EDITOR_DIR / "index.html"
            if path == "/preview":
                return EDITOR_DIR / "preview.html"
            for prefix, root in roots.items():
                if path.startswith(prefix):
                    p = (root / path[len(prefix):]).resolve()
                    if root.resolve() in p.parents or p == root.resolve():
                        return p
            return None

        def do_GET(self):
            p = self._resolve(self.path)
            if p is None or not p.is_file():
                self.send_error(404)
                return
            size = p.stat().st_size
            ctype = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
            if p.suffix == ".js":
                ctype = "text/javascript"
            start, end = 0, size - 1
            rng = self.headers.get("Range")
            if rng and rng.startswith("bytes="):
                a, _, b = rng[6:].partition("-")
                start = int(a) if a else max(0, size - int(b))
                end = int(b) if (a and b) else size - 1
                end = min(end, size - 1)
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with open(p, "rb") as f:
                f.seek(start)
                left = end - start + 1
                try:
                    while left > 0:
                        chunk = f.read(min(1 << 16, left))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        left -= len(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # the browser cancelled a range it no longer wants

        def do_POST(self):
            if self.path != "/api/save":
                self.send_error(404)
                return
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            try:
                m = json.loads(body)
                mapformat.validate(m)
            except (ValueError, KeyError, TypeError) as e:
                self._json(400, {"error": str(e)})
                return
            m["edited"] = True
            target = map_dir / "map.json"
            if target.exists():
                shutil.copyfile(target, map_dir / "map.json.bak")
            tmp = map_dir / "map.json.tmp"
            tmp.write_text(json.dumps(m, indent=1))
            os.replace(tmp, target)
            self._json(200, {"ok": True})

        def _json(self, code, obj):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return Handler


def serve(map_dir: Path, port=8765, open_browser=True):
    # The default port is often taken by some other local tool; walk forward
    # to a free one rather than fail.
    for p in range(port, port + 20):
        try:
            httpd = ThreadingHTTPServer(("127.0.0.1", p), make_handler(map_dir))
            break
        except OSError:
            continue
    else:
        raise SystemExit(f"no free port in {port}-{port + 19}")
    url = f"http://127.0.0.1:{p}/"
    print(f"Editing {map_dir}\n  editor:  {url}\n  preview: {url}preview\nCtrl-C to stop.")
    if open_browser:
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
