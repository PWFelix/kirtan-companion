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
 */
import * as Tone from "tone";

export class SoundPlayer {
  constructor() {
    this._players = new Map();

    // Master volume — everything passes through here last.
    this._masterGain = new Tone.Gain(1).toDestination();

    // Per-end volumes, feeding into master. Muting an end = gain 0.
    this._endGains = {
      dayan: new Tone.Gain(1).connect(this._masterGain),
      bayan: new Tone.Gain(1).connect(this._masterGain),
    };
  }

  async load(manifest) {
    const loadingPromises = [];
    for (const [name, url] of Object.entries(manifest)) {
      const promise = new Promise((resolve, reject) => {
        const player = new Tone.Player({
          url,
          onload: () => resolve(),
          onerror: (e) => reject(e),
        });
        // Route the player to the right end's gain based on its name.
        const end = name.startsWith("dayan") ? "dayan"
                  : name.startsWith("bayan") ? "bayan"
                  : null;
        if (end) {
          player.connect(this._endGains[end]);
        } else {
          player.connect(this._masterGain);
        }
        this._players.set(name, player);
      });
      loadingPromises.push(promise);
    }
    await Promise.all(loadingPromises);
  }

  play(name, time) {
    const player = this._players.get(name);
    if (!player) {
      console.warn(`SoundPlayer: no sound named "${name}"`);
      return;
    }
    player.start(time);
  }

  /** Master volume. @param {number} value 0..1 */
  setVolume(value) {
    const safe = Math.max(0, Math.min(1, value));
    this._masterGain.gain.rampTo(safe, 0.05);
  }

  /**
   * Mute or unmute one drum end.
   * @param {"dayan"|"bayan"} end
   * @param {boolean} muted
   */
  setEndMuted(end, muted) {
    const gain = this._endGains[end];
    if (!gain) return;
    gain.gain.rampTo(muted ? 0 : 1, 0.05);
  }
}
