/**
 * Sequencer
 * ---------
 * Plays a beat IN TIME using Tone.js's Transport (its musical clock).
 *
 * Receives a beat from outside (never contains beats itself).
 * Receives a SoundPlayer to actually make the sounds.
 * Emits a "step" event each tick so the UI can follow along later.
 *
 * Its single job: "given a beat, play it in a loop, in time."
 */
import * as Tone from "tone";
import { EventEmitter } from "./EventEmitter.js";

// Sequencer extends EventEmitter so it can emit events (like "step")
export class Sequencer extends EventEmitter {
  /**
   * @param {SoundPlayer} soundPlayer - the thing that actually plays sounds.
   *   We RECEIVE it rather than create it — so the sequencer doesn't
   *   need to know how sounds work. (Same separation principle.)
   */
  constructor(soundPlayer) {
    super(); // required when extending a class — sets up EventEmitter

    this._soundPlayer = soundPlayer;
    this._beat = null;        // the current beat (received from outside)
    this._currentStep = 0;    // which step we're on (0-based)
    this._loopId = null;      // a handle so we can cancel the repeat later
  }

  /**
   * Load a beat into the sequencer. Just stores it — doesn't play yet.
   * @param {Object} beat - a beat object from beats.js
   */
  setBeat(beat) {
    this._beat = beat;
  }

  /**
   * Set the tempo.
   * @param {number} bpm
   */
  setBpm(bpm) {
    // Tone.Transport is the global musical clock. Its bpm controls
    // how fast everything scheduled on it runs.
    Tone.Transport.bpm.value = bpm;
  }

  /**
   * Start playing the current beat in a loop.
   */
  start() {
    if (!this._beat) {
      console.warn("Sequencer: no beat loaded");
      return;
    }

    this._currentStep = 0;

   // Schedule using a calculated interval, NOT a hardcoded "8n".
    // Today _stepInterval() always returns "8n", but isolating it here
    // means adding 12/16-step timing later is a one-line change in ONE place.
    this._loopId = Tone.Transport.scheduleRepeat((time) => {
      this._tick(time);
    }, this._stepInterval());

    // Actually start the clock running.
    Tone.Transport.start();
  }

  /**
   * Stop playing.
   */
  stop() {
    // Cancel our repeating function so it stops ticking.
    if (this._loopId !== null) {
      Tone.Transport.clear(this._loopId);
      this._loopId = null;
    }
    // Stop the clock.
    Tone.Transport.stop();
    this._currentStep = 0;
  }

  /**
   * Runs on every eighth-note tick. Plays the current step, then advances.
   * @param {number} time - the exact audio time for this tick (from Tone.js)
   */

    /**
   * How long is one step, as a Tone.js note value?
   *
   * THIS IS THE PLACE that will grow to support double-time and other
   * time signatures. Today every beat is 8 steps so we always return "8n".
   * Later this becomes a switch on this._beat.steps:
   *    8  -> "8n", 16 -> "16n", 12 -> "8t", etc.
   * Because it lives in its own method, that future change touches
   * NOTHING else in the sequencer.
   */
  _stepInterval() {
    return "8n";
  }

  _tick(time) {
    const step = this._currentStep;

    // Look at what each drum end does on this step.
    const dayanHit = this._beat.dayan[step];
    const bayanHit = this._beat.bayan[step];

    // If there's a hit ("O"), play the matching sound at the exact time.
    if (dayanHit) {
      this._soundPlayer.play("dayan_open", time);
    }
        if (bayanHit) {
      this._soundPlayer.play("bayan_open", time);
    }

    // Tell anyone listening which step we're on (for the playhead later).
    this.emit("step", step);

    // Advance to the next step, looping back to 0 at the end.
    this._currentStep = (this._currentStep + 1) % this._beat.steps;
  }
}