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
 *   setEndVolume(end, v)  - per-end volume 0..1 (mixer track faders)
 *   setEndMuted(end, b)   - mute/unmute a drum end
 *   setEqBand(end, i, dB) - set one EQ band's gain, -12..12 dB
 *                           (dayan/bayan only; kartal has no EQ chain)
 *   setEndPitch(end, c)   - retune an end by cents, -600..600
 *                           (dayan/bayan only; kartal is never tuned)
 *   start()               - begin playing
 *   stop()                - stop playing
 *   getPhase()            - bar phase in [0, 1) for the UI playhead
 *   on("step", cb)        - playhead advanced (derived from phase)
 *   on("ready", cb)       - sounds finished loading
 *   on("started"/"stopped", cb)
 *
 * Knows NOTHING about React, the screen, or the UI.
 */
import * as Tone from "tone";
import { EventEmitter } from "./EventEmitter.js";
import { SoundPlayer } from "./SoundPlayer.js";
import { Sequencer } from "./Sequencer.js";
import { enableMediaPlayback } from "./audioUnlock.js";
import { heardContextTime, reportedLatencyMs } from "./outputClock.js";

export class KirtanEngine extends EventEmitter {
  constructor() {
    super();

    this._soundPlayer = new SoundPlayer();
    this._sequencer = new Sequencer(this._soundPlayer);
    this._displayDelayMs = 0;

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

    // iOS: route our Web Audio through the MEDIA channel so the silent
    // switch doesn't mute us (see audioUnlock.js). Harmless elsewhere.
    enableMediaPlayback();

    // iOS also SUSPENDS the audio context when the page is backgrounded
    // or a call comes in, and doesn't always resume it. Whenever the
    // page comes back, nudge both the context and the keep-alive loop.
    if (!this._resumeHandlerInstalled) {
      this._resumeHandlerInstalled = true;
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState !== "visible") return;
        const ctx = Tone.getContext();
        if (ctx.state !== "running") ctx.resume();
        enableMediaPlayback();
      });
    }
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
 /**
   * Mute or unmute an instrument channel (for practice isolation).
   * @param {"dayan"|"bayan"|"kartal"} end
   * @param {boolean} muted
   */
  setEndMuted(end, muted) {
    this._soundPlayer.setEndMuted(end, muted);
  }

  /**
   * Per-end volume — the mixer's track faders.
   * @param {"dayan"|"bayan"|"kartal"} end
   * @param {number} value 0..1
   */
  setEndVolume(end, value) {
    this._soundPlayer.setEndVolume(end, value);
  }

  /**
   * One band of an end's EQ — the mixer's per-end equalizers. Delegates
   * to the SoundPlayer, where unknown ends/bands (including kartal,
   * which has no chain) are ignored safely.
   * @param {"dayan"|"bayan"} end
   * @param {number} bandIndex 0..4
   * @param {number} db -12..12 dB
   */
  setEqBand(end, bandIndex, db) {
    this._soundPlayer.setEqBand(end, bandIndex, db);
  }

  /**
   * Per-end tuning — the mixer's pitch sliders. Delegates to the
   * SoundPlayer, which retunes every player under the end's prefix and
   * ignores unknown ends (including kartal) safely.
   * @param {"dayan"|"bayan"} end
   * @param {number} cents -600..600
   */
  setEndPitch(end, cents) {
    this._soundPlayer.setEndPitch(end, cents);
  }

  /**
   * Play one stroke sample immediately — for the editor's pads, which
   * sound as you tap them. Name is the manifest key, e.g. "dayan_open".
   * No-op if audio isn't unlocked yet (silently fails, like play()).
   */
  playStroke(name) {
    this._soundPlayer.play(name);
  }

  start() {
    this._sequencer.start();
    this.emit("started");
  }

  stop() {
    this._sequencer.stop();
    this.emit("stopped");
  }

  /**
   * Bar phase in [0, 1) at the moment being HEARD, moved later by the user's
   * display delay. 0 while stopped. See outputClock.js for why this is not
   * simply Tone.Transport.progress.
   */
  getPhase() {
    const heard = heardContextTime(Tone.getContext().rawContext, performance.now());
    return this._sequencer.getPhaseAt(heard - this._displayDelayMs / 1000);
  }

  // ── Display sync ───────────────────────────────────────────────────

  /** Extra display delay (ms) on top of what the browser reports. */
  setDisplayDelayMs(ms) {
    this._displayDelayMs = Number.isFinite(ms) ? ms : 0;
  }

  /** The browser's own output latency, already allowed for; null if unknown. */
  getReportedLatencyMs() {
    return reportedLatencyMs(Tone.getContext().rawContext);
  }

  /**
   * For the sync test: how far a tap at `perfMs` (an event timeStamp) is from
   * the nearest step, in ms, by what the browser SAYS is being heard (no user
   * delay). Positive: the browser thinks the click already sounded — the
   * delay it failed to report.
   */
  offsetFromNearestStepMs(perfMs) {
    const beat = this._sequencer.beat;
    if (!beat) return 0;
    const heard = heardContextTime(Tone.getContext().rawContext, perfMs);
    const position = this._sequencer.getPhaseAt(heard) * beat.steps;
    return (position - Math.round(position)) * this._sequencer.stepMs();
  }

  get isReady() {
    return this._isReady;
  }
}
