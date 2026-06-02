/**
 * SoundPlayer
 * -----------
 * Loads sound files using Tone.js and plays them on command.
 * Each sound is held in its own Tone.Player.
 *
 * Knows nothing about beats, timing patterns, or React.
 * Its only job: "load these sounds" and "play this sound now".
 */
import * as Tone from "tone";

export class SoundPlayer {
  constructor() {
    // A place to keep each loaded sound, looked up by a name.
    // e.g. "dayan_open" → the Tone.Player holding that sound.
    this._players = new Map();
  }

  /**
   * Load a set of sounds.
   * @param {Object} manifest - maps a name to a file path, e.g.
   *   { dayan_open: "/sounds/dayan_open.wav" }
   * @returns {Promise} resolves when ALL sounds have finished loading.
   */
  async load(manifest) {
    // We create a Tone.Player for each sound. Each Player loading
    // is asynchronous (takes time), so we collect all the "loading
    // promises" and wait for all of them together.
    const loadingPromises = [];

    for (const [name, url] of Object.entries(manifest)) {
      // Create a promise that resolves when THIS player has loaded.
      const promise = new Promise((resolve, reject) => {
        const player = new Tone.Player({
          url: url,
          onload: () => resolve(),          // success
          onerror: (e) => reject(e),         // failure
        }).toDestination();                  // route it to the speakers

        // Remember this player by its name so we can play it later.
        this._players.set(name, player);
      });

      loadingPromises.push(promise);
    }

    // Wait for every sound to finish loading before we say we're done.
    await Promise.all(loadingPromises);
  }

  /**
   * Play a loaded sound immediately.
   * @param {string} name - the name from the manifest, e.g. "dayan_open"
   */
  play(name, time) {
    const player = this._players.get(name);
    if (!player) {
      console.warn(`SoundPlayer: no sound named "${name}"`);
      return;
    }
    player.start(time);  // play at the exact scheduled time (or now if undefined)
  }
}