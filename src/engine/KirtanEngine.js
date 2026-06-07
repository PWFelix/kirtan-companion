/**
 * KirtanEngine
 * ------------
 * The single front door to the whole engine ("facade").
 * React — and later mobile, and anything else — talks ONLY to this.
 *
 * It owns the SoundPlayer and Sequencer internally and wires them
 * together. The outside world never touches those pieces directly.
 *
 * Public vocabulary:
 *   loadSounds(manifest)  - load the audio files
 *   unlock()              - unlock browser audio (first user tap)
 *   setBeat(beat)         - choose which beat to play
 *   setBpm(bpm)           - set the tempo
 *   setVolume(value)      - set master volume (0..1)
 *   start()               - begin playing
 *   stop()                - stop playing
 *   on("step", cb)        - playhead advanced
 *   on("ready", cb)       - sounds finished loading
 *   on("started"/"stopped", cb)
 *
 * Knows NOTHING about React, the screen, or the UI.
 */
import * as Tone from "tone";
import { EventEmitter } from "./EventEmitter.js";
import { SoundPlayer } from "./SoundPlayer.js";
import { Sequencer } from "./Sequencer.js";

export class KirtanEngine extends EventEmitter {
  constructor() {
    super();

    this._soundPlayer = new SoundPlayer();
    this._sequencer = new Sequencer(this._soundPlayer);

    this._isReady = false;

    // Forward the sequencer's "step" event out as our own.
    this._sequencer.on("step", (step) => {
      this.emit("step", step);
    });
  }

  async loadSounds(manifest) {
    await this._soundPlayer.load(manifest);
    this._isReady = true;
    this.emit("ready");
  }

  async unlock() {
    await Tone.start();
  }

  setBeat(beat) {
    this._sequencer.setBeat(beat);
  }

  setBpm(bpm) {
    this._sequencer.setBpm(bpm);
  }

  /** Set master volume. @param {number} value 0..1 */
  setVolume(value) {
    this._soundPlayer.setVolume(value);
  }

  start() {
    this._sequencer.start();
    this.emit("started");
  }

  stop() {
    this._sequencer.stop();
    this.emit("stopped");
  }

  get isReady() {
    return this._isReady;
  }
}
