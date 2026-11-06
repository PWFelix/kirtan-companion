// Shared by the correction editor and the play-through preview.
//
// Two ideas live here:
//
// 1. THE GRID IS THE MAP. A note is "cell c of beat b"; its time comes from
//    the beat list (see beatmap/mapformat.py). So moving a beat moves its
//    notes, and the editor never stores a note's time.
//
// 2. ONE CLOCK. The song plays through an <audio> element (so it can slow
//    down without dropping pitch, and can seek in an 18-minute file), routed
//    into a WebAudio graph. The chart is played with the app's own samples,
//    scheduled a little ahead on the AudioContext clock from the element's
//    current position. Both meet in one graph so they can be panned apart:
//    the recording in one ear, the chart in the other, is the fastest way to
//    hear where they disagree.

// The four strokes, in the app's lane/stroke codes, named by their bols.
// Order is the highway's right-handed order: bayan on the left, open strokes
// on the outside (Ge Khe | Te Ta).
export const STROKES = [
  { lane: "bayan", s: "O", bol: "Ge", label: "bayan open", color: "#7a4a2a" },
  { lane: "bayan", s: "X", bol: "Khe", label: "bayan closed", color: "#7a4a2a" },
  { lane: "dayan", s: "X", bol: "Te", label: "dayan closed", color: "#c0622f" },
  { lane: "dayan", s: "O", bol: "Ta", label: "dayan open", color: "#c0622f" },
];

export const bolOf = (lane, s) => STROKES.find((k) => k.lane === lane && k.s === s).bol;

export async function loadMap() {
  const res = await fetch("/map/map.json", { cache: "no-store" });
  if (!res.ok) throw new Error("no map.json in the served folder");
  return res.json();
}

export async function loadEnvelope(m) {
  try {
    const res = await fetch("/map/" + (m.analysis || "envelope.json"));
    return res.ok ? res.json() : null;
  } catch {
    return null;
  }
}

// ---- grid maths ---------------------------------------------------------

/** End of beat b (the next beat, or one more beat-length past the last). */
export function beatEnd(beats, b) {
  if (b + 1 < beats.length) return beats[b + 1];
  return beats[b] + (beats[b] - (beats[b - 1] ?? beats[b] - 0.5));
}

export function cellTime(beats, cpb, b, c) {
  const a = beats[b];
  return a + ((beatEnd(beats, b) - a) * c) / cpb;
}

export const noteTime = (m, n) => cellTime(m.beats, m.grid.cellsPerBeat, n.b, n.c);

/** Index of the last beat at or before t (-1 before the first). */
export function beatAt(beats, t) {
  let lo = 0, hi = beats.length - 1;
  if (!beats.length || t < beats[0]) return -1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (beats[mid] <= t) lo = mid; else hi = mid - 1;
  }
  return lo;
}

/** Nearest grid cell {b, c} to time t, or null off the grid. */
export function nearestCell(beats, cpb, t) {
  let b = beatAt(beats, t);
  if (b < 0) return beats.length ? { b: 0, c: 0 } : null;
  const a = beats[b], len = beatEnd(beats, b) - a;
  let c = Math.round(((t - a) / len) * cpb);
  if (c >= cpb) {
    if (b + 1 >= beats.length) return { b, c: cpb - 1 };
    b += 1; c = 0;
  }
  return { b, c };
}

/** Position of beat b in its cycle (0 = sam). */
export function beatInCycle(m, b) {
  const n = m.grid.beatsPerCycle;
  return (((b - m.grid.samBeat) % n) + n) % n;
}

/** 1-based cycle number of beat b (cycles count from the first sam). */
export function cycleOf(m, b) {
  return Math.floor((b - m.grid.samBeat) / m.grid.beatsPerCycle) + 1;
}

/** Local BPM at beat b, a median over a few beats so one sloppy beat doesn't spike it. */
export function bpmAt(beats, b, span = 4) {
  const ibis = [];
  for (let i = Math.max(1, b - span); i <= Math.min(beats.length - 1, b + span); i++) {
    ibis.push(beats[i] - beats[i - 1]);
  }
  if (!ibis.length) return 0;
  ibis.sort((x, y) => x - y);
  return 60 / ibis[ibis.length >> 1];
}

/** Notes sorted by time, with their times, for scheduling and drawing. */
export function timedNotes(m) {
  return m.notes
    .map((n) => ({ ...n, t: noteTime(m, n) }))
    .sort((x, y) => x.t - y.t);
}

/** First index in a time-sorted array with .t >= t. */
export function lowerBound(arr, t) {
  let lo = 0, hi = arr.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (arr[mid].t < t) lo = mid + 1; else hi = mid;
  }
  return lo;
}

// ---- audio --------------------------------------------------------------

const SAMPLE_FILES = {
  "dayan:O": ["dayan/open_1.wav", "dayan/open_2.wav", "dayan/open_3.wav"],
  "dayan:X": ["dayan/closed_1.wav"],
  "bayan:O": ["bayan/open_1.wav", "bayan/open_2.wav", "bayan/open_3.wav"],
  "bayan:X": ["bayan/closed_1.wav"],
};

export class Player {
  constructor(m) {
    this.m = m;
    this.ctx = new AudioContext();
    this.rate = 1;
    this.source = "mix";
    this.chartOn = false;
    this.clickOn = false;
    this.split = false;
    this.notes = [];
    this.onEnded = null;

    this.songGain = this.ctx.createGain();
    this.songPan = this.ctx.createStereoPanner();
    this.chartGain = this.ctx.createGain();
    this.chartPan = this.ctx.createStereoPanner();
    this.chartGain.gain.value = 0.8;
    this.songGain.connect(this.songPan).connect(this.ctx.destination);
    this.chartGain.connect(this.chartPan).connect(this.ctx.destination);

    // One element per source so switching is instant. They are kept in step.
    this.els = {};
    for (const [key, file] of Object.entries(m.audio)) {
      const el = new Audio("/map/" + file);
      el.preload = "auto";
      el.preservesPitch = true;
      el.addEventListener("ended", () => this.onEnded?.());
      const node = this.ctx.createMediaElementSource(el);
      const g = this.ctx.createGain();
      g.gain.value = key === this.source ? 1 : 0;
      node.connect(g).connect(this.songGain);
      this.els[key] = { el, gain: g };
    }
    this.samples = {};
    this._rr = 0;
    this._timer = null;
    this._scheduledUntil = 0;
  }

  async loadSamples() {
    const load = async (f) => {
      const buf = await (await fetch("/sounds/" + f)).arrayBuffer();
      return this.ctx.decodeAudioData(buf);
    };
    for (const [k, files] of Object.entries(SAMPLE_FILES)) {
      this.samples[k] = await Promise.all(files.map(load));
    }
  }

  get el() { return this.els[this.source].el; }
  get playing() { return !this.el.paused; }
  get duration() { return this.el.duration || this.m.source.duration; }
  get time() { return this.el.currentTime; }

  setNotes(timed) {
    this.notes = timed;
    this._scheduledUntil = this.time; // reschedule from here with the new chart
  }

  setSource(key) {
    if (!this.els[key] || key === this.source) return;
    const t = this.time, wasPlaying = this.playing;
    for (const [k, { el, gain }] of Object.entries(this.els)) {
      gain.gain.value = k === key ? 1 : 0;
      if (k !== key) el.pause();
    }
    this.source = key;
    this.el.currentTime = t;
    this.el.playbackRate = this.rate;
    if (wasPlaying) this.el.play();
  }

  setRate(r) {
    this.rate = r;
    for (const { el } of Object.values(this.els)) el.playbackRate = r;
    this._scheduledUntil = this.time;
  }

  setSplit(on) {
    this.split = on;
    this.songPan.pan.value = on ? -0.7 : 0;
    this.chartPan.pan.value = on ? 0.7 : 0;
  }

  async play() {
    await this.ctx.resume();
    this.el.playbackRate = this.rate;
    await this.el.play();
    this._scheduledUntil = this.time;
    clearInterval(this._timer);
    this._timer = setInterval(() => this._schedule(), 25);
  }

  pause() {
    this.el.pause();
    clearInterval(this._timer);
  }

  toggle() { return this.playing ? this.pause() : this.play(); }

  seek(t) {
    const d = this.duration;
    this.el.currentTime = Math.max(0, Math.min(isFinite(d) ? d - 0.05 : t, t));
    this._scheduledUntil = this.el.currentTime;
  }

  // Look-ahead scheduler: every 25 ms, queue chart strokes (and beat clicks)
  // that fall in the next ~150 ms of SONG time, converted to context time.
  _schedule() {
    if (!this.playing) return;
    const now = this.time;
    const ahead = 0.15 * this.rate;
    const from = Math.max(this._scheduledUntil, now);
    const to = now + ahead;
    if (to <= from) return;
    const base = this.ctx.currentTime + 0.03; // small pad against jitter
    const at = (t) => base + (t - now) / this.rate;
    if (this.chartOn) {
      for (let i = lowerBound(this.notes, from); i < this.notes.length && this.notes[i].t < to; i++) {
        const n = this.notes[i];
        const bank = this.samples[`${n.lane}:${n.s}`];
        if (!bank) continue;
        const src = this.ctx.createBufferSource();
        src.buffer = bank[this._rr++ % bank.length];
        src.connect(this.chartGain);
        src.start(at(n.t));
      }
    }
    if (this.clickOn) {
      const beats = this.m.beats;
      for (let b = Math.max(0, beatAt(beats, from)); b < beats.length && beats[b] < to; b++) {
        if (beats[b] < from) continue;
        this._click(at(beats[b]), beatInCycle(this.m, b) === 0);
      }
    }
    this._scheduledUntil = to;
  }

  _click(when, accent) {
    const o = this.ctx.createOscillator(), g = this.ctx.createGain();
    o.frequency.value = accent ? 1760 : 1100;
    g.gain.setValueAtTime(accent ? 0.5 : 0.25, when);
    g.gain.exponentialRampToValueAtTime(0.001, when + 0.05);
    o.connect(g).connect(this.chartGain);
    o.start(when);
    o.stop(when + 0.06);
  }
}

export function fmtTime(t) {
  if (!isFinite(t)) return "--:--";
  const m = Math.floor(t / 60), s = t - m * 60;
  return `${m}:${s.toFixed(1).padStart(4, "0")}`;
}
