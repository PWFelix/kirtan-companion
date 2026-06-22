/**
 * Sequencer
 * ---------
 * Plays a beat IN TIME using Tone.js's Transport (its musical clock).
 *
 * The MODEL:
 *   - The Transport is the source of truth for "where in the bar are we?".
 *   - We loop the Transport over one bar of length `beatsPerBar` pulses.
 *     `Tone.Transport.progress` then gives us the phase: a fraction in
 *     [0, 1) through the current bar.
 *   - On every tick, the active step is DERIVED:
 *         step = floor(phase * beat.steps)
 *     so step is always valid for whichever beat is currently loaded.
 *
 * Why phase rather than a stored counter?
 *   A counter is grid-relative — "step 12" is meaningless without
 *   "out of 16". If the user switches to an 8-step beat mid-play, the
 *   counter is suddenly nonsense (index 12 in an 8-cell array).
 *   Phase is grid-independent: the musical position survives a beat
 *   switch, and the new step is recomputed against the new grid.
 *   It is also SELF-CORRECTING — because we re-read the transport every
 *   tick, the step can never silently drift out of sync with the clock.
 *
 * Public surface (unchanged):
 *   setBeat(beat), setBpm(bpm), start(), stop(),
 *   getPhase()  → bar phase in [0, 1) for the UI playhead,
 *   emits "step" (derived index) on every tick.
 */
import * as Tone from "tone";
import { EventEmitter } from "./EventEmitter.js";

// Tiny tolerance added before flooring phase→step. getTicksAtTime()
// returns a float, so a value that should be exactly e.g. 3.0 at a step
// boundary can arrive as 2.9999999; without this nudge Math.floor would
// give 2, the same-step guard would fire, and the beat would be DROPPED.
// 1e-6 is far larger than the floating-point error (~1e-9) yet far
// smaller than half a step, so it only cancels undershoot — it can never
// push us forward into the wrong step.
const STEP_EPSILON = 1e-6;

export class Sequencer extends EventEmitter {
  constructor(soundPlayer) {
    super();
    this._soundPlayer = soundPlayer;
    this._beat = null;
    this._loopId = null;
    // Remember the last step we fired sounds for, so a tiny scheduling
    // edge case (two callbacks landing on the same integer step, e.g.
    // right after a beat switch) can't produce a double hit. Reset to
    // -1 on stop / start.
    this._lastFiredStep = -1;
  }

  setBeat(beat) {
    this._beat = beat;
    // Update transport's loop length to the new bar — this is the
    // ONLY remap a beat switch needs. The transport keeps running,
    // so phase is preserved across the switch.
    this._applyTransportLoop();
    // If we're playing, rebuild the scheduleRepeat at the new step
    // interval. startTime = 0 keeps callbacks on the bar grid, so
    // downbeats stay tight.
    if (this._loopId !== null) this._reschedule();
  }

  setBpm(bpm) {
    // Tone.Transport.bpm is the global pulse. Because the loop bound
    // ("1m") and the step interval (now expressed in ticks, "...i")
    // are BOTH tempo-relative musical-time units, they auto-scale
    // together — no resched needed on a tempo change.
    Tone.Transport.bpm.value = bpm;
  }

  start() {
    if (!this._beat) {
      console.warn("Sequencer: no beat loaded");
      return;
    }
    this._applyTransportLoop();
    // Start the bar at phase 0 so the visual playhead and the audio
    // both begin on the downbeat.
    Tone.Transport.position = 0;
    this._lastFiredStep = -1;
    this._reschedule();
    Tone.Transport.start();
  }

  stop() {
    if (this._loopId !== null) {
      Tone.Transport.clear(this._loopId);
      this._loopId = null;
    }
    Tone.Transport.stop();
    Tone.Transport.position = 0;
    Tone.Transport.loop = false;
    this._lastFiredStep = -1;
  }

  /**
   * Bar phase in [0, 1) while playing, 0 while stopped.
   * The UI uses this to position a continuous playhead independently
   * of the step grid.
   */
  getPhase() {
    if (Tone.Transport.state !== "started") return 0;
    return Tone.Transport.progress;
  }

  // ── Internals ──────────────────────────────────────────────────────

  _beatsPerBar() {
    // Default to 4 so any legacy beat object still works.
    return this._beat?.beatsPerBar ?? 4;
  }

  _applyTransportLoop() {
    if (!this._beat) return;
    // timeSignature is the bar's numerator — Tone interprets "1m"
    // as that many quarter-note pulses.
    Tone.Transport.timeSignature = this._beatsPerBar();
    Tone.Transport.loopStart = 0;
    Tone.Transport.loopEnd = "1m";
    Tone.Transport.loop = true;
  }

  /**
   * One step expressed in TICKS (Tone's native musical-time unit).
   *
   *   ticksPerBar  = PPQ * beatsPerBar      (PPQ = pulses per quarter note, 192 by default)
   *   ticksPerStep = ticksPerBar / steps
   *
   * Why ticks and not seconds?
   *   Ticks are tempo-relative: the same tick count always means the
   *   same musical position, so it stretches/shrinks with BPM exactly
   *   like the "1m" loop bound does. The two clocks can therefore never
   *   drift apart. (A seconds value would be FIXED real time and would
   *   drift the instant the tempo changed — the old jitter bug.)
   *
   * For every standard kirtan grid this divides evenly, so Math.round
   * is a no-op:
   *   8 steps  / 4 beats → 192*4/8  =  96 ticks  (was "8n")
   *   12 steps / 4 beats → 192*4/12 =  64 ticks  (was "8t", eighth-triplet)
   *   16 steps / 4 beats → 192*4/16 =  48 ticks  (was "16n")
   * The round only ever matters for an exotic, non-dividing ratio, and
   * even then a sub-tick rounding beats a seconds-based drift.
   */
  _stepInterval() {
    const ticksPerStep =
      (Tone.Transport.PPQ * this._beatsPerBar()) / this._beat.steps;
    return Math.round(ticksPerStep) + "i";
  }

  _reschedule() {
    if (this._loopId !== null) Tone.Transport.clear(this._loopId);
    this._loopId = Tone.Transport.scheduleRepeat(
      (time) => this._tick(time),
      this._stepInterval(),
      0,   // anchor to bar-time 0 → callbacks always land on the grid
    );
  }

  /**
   * Runs once per step. We read the Transport's tick clock AT THE
   * SCHEDULED audio time (not "now") so timing is sample-accurate
   * even if the JS callback fires a hair late.
   */
  _tick(time) {
    if (!this._beat) return;

    // Phase = where in the bar are we, as a fraction in [0, 1).
    // PPQ * beatsPerBar = ticks per bar; ticks % that = ticks into bar.
    const ticks = Tone.Transport.getTicksAtTime(time);
    const loopTicks = Tone.Transport.PPQ * this._beatsPerBar();
    const phase = (ticks % loopTicks) / loopTicks;

    // STEP_EPSILON cancels floating-point undershoot before flooring so
    // a boundary value like 2.9999999 snaps to 3 instead of dropping to 2.
    const step = Math.floor(phase * this._beat.steps + STEP_EPSILON);

    // Same-step guard: if a resched (e.g. a beat switch) lands a callback
    // on the integer step we just fired, don't double-hit. With the
    // epsilon fix above this no longer misfires on FP rounding, so it now
    // only ever catches a genuine duplicate.
    if (step === this._lastFiredStep) return;
    this._lastFiredStep = step;

    const dayanHit = this._beat.dayan[step];
    const bayanHit = this._beat.bayan[step];

    if (dayanHit === "O") this._soundPlayer.play("dayan_open", time);
    else if (dayanHit === "X") this._soundPlayer.play("dayan_closed", time);

    if (bayanHit === "O") this._soundPlayer.play("bayan_open", time);
    else if (bayanHit === "X") this._soundPlayer.play("bayan_closed", time);

    this.emit("step", step);
  }
}
