"""
From a separated drum stem to a draft chart.

The steps, and why each one is shaped the way it is:

1. BEAT GRID WITH A MOVING TEMPO. A kirtan starts near 90 BPM and ends past
   130, so a single-tempo beat tracker (librosa's default) drifts off the
   grid within a minute. We first estimate a per-frame tempo, median-smooth it
   over ~10 s (tempo changes over phrases, not individual beats), fold it into
   a sane quarter-note range to avoid half/double-time errors, and hand that
   CURVE to the beat tracker as its prior.

2. ONE DECISION PER CELL, NOT PER ONSET. Classifying each detected onset in
   isolation was unreliable in our trials (open/closed/bayan features overlap
   on a live recording). Asking "did the dayan strike in this 16th-note cell?"
   is a far easier question: we take the peak spectral flux inside the cell's
   window and compare it against the LOCAL level of the playing (a rolling
   percentile), because a kirtan grows from soft to loud and one global
   threshold drops the quiet opening.

3. OPEN VS CLOSED BY RING. An open stroke (Ta, Ge) rings; a closed one
   (Te, Khe) is choked. We measure how much band energy survives ~140 ms after
   the attack and split each hand's strokes in two with a 1-D two-means.
   The distance from that split is the note's confidence.

4. SAM IS A GUESS. The cycle's first beat usually carries the heaviest bayan,
   so we pick the phase (mod beatsPerCycle) with the most bayan energy. The
   corrector confirms or moves it with one key.

Everything here is heuristic and meant to be corrected. It is tuned to make the
person correcting it fast, not to be right on its own.
"""

import numpy as np
import librosa
from scipy.ndimage import median_filter, percentile_filter

SR = 22050
HOP = 256  # ~11.6 ms frames

# Spectral bands (Hz). The bayan's fundamental and glide sit under 200 Hz;
# the dayan's ring and slap live from ~300 Hz to ~5 kHz.
BAYAN_BAND = (40, 200)
DAYAN_BAND = (300, 5000)
DAYAN_RING_BAND = (400, 1500)

RING_DELAY_S = 0.14


def load_mono(path):
    y, _ = librosa.load(path, sr=SR, mono=True)
    return y


def _band_energy(S, freqs, band):
    m = (freqs >= band[0]) & (freqs < band[1])
    return np.log1p(S[m].sum(axis=0))


def _flux(e):
    return np.maximum(0.0, np.diff(e, prepend=e[0]))


def track_beats(y, min_bpm=65, max_bpm=170, smooth_s=10.0):
    """Beat times (s) following a slowly changing tempo."""
    env = librosa.onset.onset_strength(y=y, sr=SR, hop_length=HOP)
    local = librosa.feature.tempo(onset_envelope=env, sr=SR, hop_length=HOP,
                                  aggregate=None, start_bpm=100, max_tempo=320)
    # Fold into one octave so a stretch of eighth-note playing doesn't read
    # as double time.
    local = np.where(local > max_bpm, local / 2, local)
    local = np.where(local < min_bpm, local * 2, local)
    size = max(3, int(smooth_s * SR / HOP) | 1)
    curve = median_filter(local, size=size, mode="nearest")
    _, beats = librosa.beat.beat_track(onset_envelope=env, sr=SR, hop_length=HOP,
                                       bpm=curve, units="time", tightness=120,
                                       trim=False)
    return np.asarray(beats, dtype=float), env


def tempo_curve(beats, every=4, smooth_beats=9):
    """A smoothed [{t, bpm}] for display: median BPM over a few beats."""
    if len(beats) < 3:
        return []
    bpm = 60.0 / np.diff(beats)
    bpm = median_filter(bpm, size=smooth_beats, mode="nearest")
    return [{"t": round(float(beats[i]), 2), "bpm": round(float(bpm[i]), 1)}
            for i in range(0, len(bpm), every)]


def _two_means(x):
    """Split 1-D values into two groups. Returns (threshold, spread)."""
    lo, hi = np.percentile(x, 25), np.percentile(x, 75)
    for _ in range(30):
        t = (lo + hi) / 2
        a, b = x[x <= t], x[x > t]
        if len(a) == 0 or len(b) == 0:
            break
        lo, hi = a.mean(), b.mean()
    return (lo + hi) / 2, max(1e-6, (hi - lo) / 2)


def extract_notes(y, beats, cells_per_beat=4, sensitivity=1.0):
    """Draft notes [{b, c, lane, s, conf}] on the beat grid."""
    S = np.abs(librosa.stft(y, n_fft=2048, hop_length=HOP))
    freqs = librosa.fft_frequencies(sr=SR, n_fft=2048)
    frame_t = librosa.frames_to_time(np.arange(S.shape[1]), sr=SR, hop_length=HOP)

    e_bayan = _band_energy(S, freqs, BAYAN_BAND)
    e_dayan = _band_energy(S, freqs, DAYAN_BAND)
    e_ring_d = _band_energy(S, freqs, DAYAN_RING_BAND)
    f_bayan, f_dayan = _flux(e_bayan), _flux(e_dayan)

    # Where the drum is actually playing: 1 s RMS against the song's loudest.
    rms = librosa.feature.rms(y=y, hop_length=HOP)[0]
    rms = np.convolve(rms, np.ones(86) / 86, mode="same")
    playing = rms > 0.06 * np.percentile(rms, 99)

    ring_frames = int(round(RING_DELAY_S * SR / HOP))

    # One candidate per (cell, hand): the flux peak inside the cell's window.
    cells = []  # (b, c, frame_dayan, peak_dayan, frame_bayan, peak_bayan)
    for b in range(len(beats) - 1):
        a, nxt = beats[b], beats[b + 1]
        step = (nxt - a) / cells_per_beat
        for c in range(cells_per_beat):
            centre = a + step * c
            m = np.where((frame_t >= centre - step * 0.45) & (frame_t < centre + step * 0.45))[0]
            if len(m) == 0:
                continue
            i_d = m[np.argmax(f_dayan[m])]
            i_b = m[np.argmax(f_bayan[m])]
            cells.append((b, c, i_d, f_dayan[i_d], i_b, f_bayan[i_b]))
    if not cells:
        return []
    cells_arr = np.array([(x[3], x[5]) for x in cells])

    # Local level of the playing: the 90th percentile of cell peaks over ~8 s.
    # A strike must clear a fraction of it (sensitivity lowers the bar).
    win = max(9, int(8.0 / (np.median(np.diff(beats)) / cells_per_beat)) | 1)
    level_d = percentile_filter(cells_arr[:, 0], 90, size=win, mode="nearest")
    level_b = percentile_filter(cells_arr[:, 1], 90, size=win, mode="nearest")
    k = 0.42 / sensitivity

    hits = []  # (lane, b, c, frame, peak, level)
    for (b, c, i_d, p_d, i_b, p_b), ld, lb in zip(cells, level_d, level_b):
        if not playing[i_d]:
            continue
        if p_d > k * ld and p_d > 0.05:
            hits.append(("dayan", b, c, i_d, p_d, ld))
        if p_b > k * lb * 1.15 and p_b > 0.05:
            hits.append(("bayan", b, c, i_b, p_b, lb))

    # Open vs closed: energy that survives the attack, per hand.
    def ring(lane, i):
        e = e_ring_d if lane == "dayan" else e_bayan
        j = min(i + ring_frames, len(e) - 1)
        return e[j] - e[i]

    notes = []
    for lane in ("dayan", "bayan"):
        lane_hits = [h for h in hits if h[0] == lane]
        if not lane_hits:
            continue
        rings = np.array([ring(lane, h[3]) for h in lane_hits])
        thr, spread = _two_means(rings)
        for h, r in zip(lane_hits, rings):
            open_ = r > thr
            # Confidence: how clearly it struck AND how clearly it rang/choked.
            strike = min(1.0, (h[4] / (k * h[5] + 1e-9) - 1.0))
            kind = min(1.0, abs(r - thr) / spread)
            notes.append({
                "b": int(h[1]), "c": int(h[2]), "lane": lane,
                "s": "O" if open_ else "X",
                "conf": round(float(0.5 * strike + 0.5 * kind), 2),
            })
    notes.sort(key=lambda n: (n["b"], n["c"], n["lane"]))
    return notes


def guess_sam(notes, n_beats, beats_per_cycle):
    """The beat phase (mod cycle) with the most bayan weight on the beat."""
    weight = np.zeros(beats_per_cycle)
    for n in notes:
        if n["lane"] == "bayan" and n["c"] == 0:
            weight[n["b"] % beats_per_cycle] += 1.0 + (n["s"] == "O")
    return int(np.argmax(weight)) if weight.any() else 0


def envelope(y, fps=100):
    """Compact per-frame curves for the editor to draw (0..99 ints)."""
    hop = SR // fps
    S = np.abs(librosa.stft(y, n_fft=2048, hop_length=hop))
    freqs = librosa.fft_frequencies(sr=SR, n_fft=2048)

    def norm(x):
        top = np.percentile(x, 99.5) or 1.0
        return np.clip(np.round(99 * x / top), 0, 99).astype(int).tolist()

    rms = librosa.feature.rms(y=y, hop_length=hop)[0]
    return {
        "fps": fps,
        "rms": norm(rms),
        "dayan": norm(_flux(_band_energy(S, freqs, DAYAN_BAND))),
        "bayan": norm(_flux(_band_energy(S, freqs, BAYAN_BAND))),
    }
