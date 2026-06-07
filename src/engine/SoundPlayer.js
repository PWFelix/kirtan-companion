/**
 * SoundPlayer
 * -----------
 * Loads sound files using Tone.js and plays them on command.
 * Each sound is held in its own Tone.Player.
 *
 * Also owns a master volume (via a Tone.Gain) that all sounds
 * pass through, so the UI can control overall loudness.
 *
 * Knows nothing about beats, timing patterns, or React.
 */
import * as Tone from "tone";

export class SoundPlayer {
  constructor() {
    // name -> Tone.Player
    this._players = new Map();

    // A single volume control that everything routes through.
    // Tone.Gain is a volume knob: 0 = silent, 1 = full.
    // We connect it to the speakers, and connect every player to IT
    // instead of straight to the speakers — so changing this one
    // knob changes the volume of all sounds at once.
    this._masterGain = new Tone.Gain(1).toDestination();
  }

  /**
   * Load a set of sounds.
   * @param {Object} manifest - { name: url, ... }
   * @returns {Promise} resolves when ALL sounds have loaded.
   */
  async load(manifest) {
    const loadingPromises = [];

    for (const [name, url] of Object.entries(manifest)) {
      const promise = new Promise((resolve, reject) => {
        const player = new Tone.Player({
          url: url,
          onload: () => resolve(),
          onerror: (e) => reject(e),
        }).connect(this._masterGain); // route through the volume knob

        this._players.set(name, player);
      });
      loadingPromises.push(promise);
    }

    await Promise.all(loadingPromises);
  }

  /**
   * Play a loaded sound.
   * @param {string} name
   * @param {number} time - exact time to play (from the sequencer)
   */
  play(name, time) {
    const player = this._players.get(name);
    if (!player) {
      console.warn(`SoundPlayer: no sound named "${name}"`);
      return;
    }
    player.start(time);
  }

  /**
   * Set the master volume.
   * @param {number} value - 0 (silent) to 1 (full)
   */
  setVolume(value) {
    // Clamp to the valid range just in case.
    const safe = Math.max(0, Math.min(1, value));
    // rampTo glides smoothly to the new volume over 0.05s,
    // avoiding clicks/pops from instant volume jumps.
    this._masterGain.gain.rampTo(safe, 0.05);
  }
}
