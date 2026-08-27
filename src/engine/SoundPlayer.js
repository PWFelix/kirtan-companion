/**
 * SoundPlayer
 * -----------
 * Loads sound files using Tone.js and plays them on command.
 *
 * Routing (the signal path each sound takes to the speakers):
 *   each player -> its END's gain -> master gain -> speakers
 *
 * The "end" gains (dayan / bayan) let us mute or solo a drum end —
 * used by the practice view to isolate top or bottom. Sounds whose
 * name starts with "dayan" route through the dayan gain; "bayan"
 * through the bayan gain; anything else goes straight to master.
 *
 * Round-robin: a real drum never sounds identical twice in a row, so
 * each stroke maps to an ARRAY of samples. Playing a stroke picks a
 * random sample that isn't the one we just played. Single-sample
 * strokes (e.g. closed) fall out of this naturally — the pool of
 * "anything but the last one" is empty, so we replay the only sample.
 * Adding more variants later is just adding files to the array below;
 * no code changes needed.
 */
import * as Tone from "tone";

// Stroke name -> list of sample URLs. This is the single place that
// knows the on-disk layout; load() keys off the manifest but pulls
// the actual files from here.
const STROKE_SAMPLES = {
  dayan_open: [
    "/sounds/dayan/open_1.wav",
    "/sounds/dayan/open_2.wav",
    "/sounds/dayan/open_3.wav",
  ],
  dayan_closed: ["/sounds/dayan/closed_1.wav"],
  bayan_open: [
    "/sounds/bayan/open_1.wav",
    "/sounds/bayan/open_2.wav",
    "/sounds/bayan/open_3.wav",
  ],
  bayan_closed: ["/sounds/bayan/closed_1.wav"],
  // Karatalas — same layout convention (open = ringing strike, closed =
  // damped/choked). Drop the real recordings in public/sounds/kartal/;
  // until they exist, load() below skips them and the app still starts.
  kartal_open: [
    "/sounds/kartal/open_1.wav",
    "/sounds/kartal/open_2.wav",
    "/sounds/kartal/open_3.wav",
  ],
  kartal_closed: ["/sounds/kartal/closed_1.wav"],
};

export class SoundPlayer {
  constructor() {
    // name -> { players: Tone.Player[], lastIndex: number }
    this._players = new Map();

    // Master volume — everything passes through here last.
    this._masterGain = new Tone.Gain(1).toDestination();

    // Per-end channels, feeding into master. Each end's FINAL gain is
    //   volume × makeup × (muted ? 0 : 1)
    // (see _applyEnd) — volume and mute are separate facts, so unmuting
    // restores the user's volume instead of blindly ramping to 1.
    this._endGains = {
      dayan: new Tone.Gain(1).connect(this._masterGain),
      bayan: new Tone.Gain(1).connect(this._masterGain),
      kartal: new Tone.Gain(1).connect(this._masterGain),
    };

    // Phone speakers reproduce the bayan's bass far more weakly than the
    // dayan's ring, so the bayan carries fixed MAKEUP gain: the mixer's
    // "100%" means "balanced on a phone", not "raw sample level". The
    // karatalas are bright and cut through on their own, so they sit at 1;
    // any end without an entry defaults to 1 (see _applyEnd).
    this._endMakeup = { dayan: 1, bayan: 1.25, kartal: 1 };

    this._endState = {
      dayan: { volume: 1, muted: false },
      bayan: { volume: 1, muted: false },
      kartal: { volume: 1, muted: false },
    };
    Object.keys(this._endState).forEach((end) => this._applyEnd(end));
  }

  /** Recompute one end's gain from its volume/mute state + makeup. */
  _applyEnd(end) {
    const gain = this._endGains[end];
    const s = this._endState[end];
    if (!gain || !s) return;
    const target = s.muted ? 0 : s.volume * (this._endMakeup[end] ?? 1);
    gain.gain.rampTo(target, 0.05);
  }

  async load(manifest) {
    const loadingPromises = [];
    // We use the manifest's KEYS (stroke names) to decide what to load;
    // the actual files come from STROKE_SAMPLES (engine-owned paths).
    for (const name of Object.keys(manifest)) {
      const urls = STROKE_SAMPLES[name];
      if (!urls) {
        console.warn(`SoundPlayer: no samples mapped for "${name}"`);
        continue;
      }

      // Route every sample for this stroke to the right end's gain.
      const end = name.startsWith("dayan") ? "dayan"
                : name.startsWith("bayan") ? "bayan"
                : name.startsWith("kartal") ? "kartal"
                : null;
      const destination = end ? this._endGains[end] : this._masterGain;

      // A missing/failed sample RESOLVES to null rather than rejecting, so one
      // absent file can't sink the whole load() (which would leave "ready"
      // unfired and the app stuck on the loading screen). This is what lets us
      // ship a stroke's manifest entry BEFORE its recordings exist — e.g. the
      // karatalas: the app starts, that stroke is simply skipped below.
      const players = urls.map((url) => {
        return new Promise((resolve) => {
          const player = new Tone.Player({
            url,
            onload: () => resolve(player),
            onerror: () => {
              console.warn(`SoundPlayer: failed to load "${url}"`);
              resolve(null);
            },
          });
          player.connect(destination);
        });
      });

      const entryPromise = Promise.all(players).then((loaded) => {
        const ok = loaded.filter(Boolean);
        // Register the stroke only if at least one sample loaded; otherwise
        // play() would find an empty pool. A never-registered stroke just
        // warns "no sound named ..." when played — harmless and silent.
        if (ok.length) this._players.set(name, { players: ok, lastIndex: -1 });
      });
      loadingPromises.push(entryPromise);
    }
    await Promise.all(loadingPromises);
  }

  play(name, time) {
    const entry = this._players.get(name);
    if (!entry) {
      console.warn(`SoundPlayer: no sound named "${name}"`);
      return;
    }

    const { players } = entry;
    const index = this._pickIndex(players.length, entry.lastIndex);
    entry.lastIndex = index;
    players[index].start(time);
  }

  /**
   * Pick a random sample index that isn't the last one played.
   * With one sample there's nothing else to pick, so we return 0.
   */
  _pickIndex(count, lastIndex) {
    if (count <= 1) return 0;
    let index = Math.floor(Math.random() * (count - 1));
    if (index >= lastIndex) index += 1; // skip over the last index
    return index;
  }

  /** Master volume. @param {number} value 0..1 */
  setVolume(value) {
    const safe = Math.max(0, Math.min(1, value));
    this._masterGain.gain.rampTo(safe, 0.05);
  }

  /**
   * Mute or unmute one instrument channel.
   * @param {"dayan"|"bayan"|"kartal"} end
   * @param {boolean} muted
   */
  setEndMuted(end, muted) {
    const s = this._endState[end];
    if (!s) return;
    s.muted = muted;
    this._applyEnd(end);
  }

  /**
   * Per-end volume (the mixer's track faders).
   * @param {"dayan"|"bayan"|"kartal"} end
   * @param {number} value 0..1 (1 = balanced level incl. makeup gain)
   */
  setEndVolume(end, value) {
    const s = this._endState[end];
    if (!s) return;
    s.volume = Math.max(0, Math.min(1, value));
    this._applyEnd(end);
  }
}
