/**
 * KirtanEngine
 * ------------
 * The single front door to the whole engine ("facade").
 * React — and later mobile, and anything else — talks ONLY to this.
 *
 * It owns the SoundPlayer and Sequencer internally and wires them
 * together. The outside world never touches those pieces directly.
 *
 * Public vocabulary (all React needs to know):
 *   loadSounds(manifest)  - load the audio files
 *   setBeat(beat)         - choose which beat to play
 *   setBpm(bpm)           - set the tempo
 *   start()               - begin playing
 *   stop()                - stop playing
 *   on("step", cb)        - listen for the playhead advancing
 *   on("ready", cb)       - listen for "sounds finished loading"
 *
 * Knows NOTHING about React, the screen, or the UI.
 */
import * as Tone from "tone";
import { EventEmitter } from "./EventEmitter.js";
import { SoundPlayer } from "./SoundPlayer.js";
import { Sequencer } from "./Sequencer.js";

export class KirtanEngine extends EventEmitter {
  constructor() {
    super(); // set up our own event system

    // Build the internal pieces and wire them together.
    // The outside world never sees these — they're private.
    this._soundPlayer = new SoundPlayer();
    this._sequencer = new Sequencer(this._soundPlayer);

    this._isReady = false;

    // FORWARD the sequencer's events out as our own.
    // The sequencer says "step" to us privately; we say "step"
    // to the world. React listens to US, never to the sequencer.
    this._sequencer.on("step", (step) => {
      this.emit("step", step);
    });
  }

  /**
   * Load the audio files. Emits "ready" when done.
   * @param {Object} manifest - { name: url, ... }
   */
  async loadSounds(manifest) {
    await this._soundPlayer.load(manifest);
    this._isReady = true;
    this.emit("ready");
  }

  /**
   * Unlock the browser's audio system. Must be called from inside
   * a user gesture (a click/tap) the first time. Safe to call again.
   */
  async unlock() {
    await Tone.start();
  }

  /** Choose which beat to play. @param {Object} beat */
  setBeat(beat) {
    this._sequencer.setBeat(beat);
  }

  /** Set the tempo. @param {number} bpm */
  setBpm(bpm) {
    this._sequencer.setBpm(bpm);
  }

  /** Start playing. Emits "started". */
  start() {
    this._sequencer.start();
    this.emit("started");
  }

  /** Stop playing. Emits "stopped". */
  stop() {
    this._sequencer.stop();
    this.emit("stopped");
  }

  /** Whether the sounds have finished loading. */
  get isReady() {
    return this._isReady;
  }
}