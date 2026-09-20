package com.kirtan.companion.engine

import android.content.res.AssetManager
import android.util.Log
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/** What the engine tells the outside world. Commands go down, events come up. */
sealed interface EngineEvent {
    /** Sample decoding finished; the engine can now be started. */
    data object Ready : EngineEvent

    data object Started : EngineEvent
    data object Stopped : EngineEvent

    /**
     * A step boundary passed. Emitted from the audio thread as the block
     * containing it is rendered, so it is sample-aligned with the sound — but
     * the UI playhead should still read [KirtanEngine.getPhase] per frame rather
     * than animating off these, because a step event is ~10 per second and a
     * playhead wants 60.
     */
    data class Step(val step: Int) : EngineEvent

    /** The output device went away, or never opened. Playback cannot continue. */
    data object OutputFailed : EngineEvent
}

/**
 * The single front door to the whole engine.
 *
 * The UI — and the playback service — talks ONLY to this. It owns the clock, the
 * mixer and the audio thread internally and wires them together; nothing outside
 * touches those pieces directly. That rule is the one architectural invariant
 * carried over unchanged from the web app, and it is why this port was possible
 * at all: `KirtanEngine.js` already defined the seam, so reimplementing
 * everything behind it meant no screen needed to know the engine had changed.
 *
 * Public vocabulary, deliberately identical to the JS facade so the two can be
 * reasoned about side by side:
 *
 *   loadSounds()            decode the bundled samples (suspending, off-thread)
 *   arm() / release()       open / close the output device
 *   setBeat(beat)           choose which beat plays
 *   setBpm(bpm)             set the tempo
 *   setVolume(value)        master volume 0..1
 *   setEndVolume(lane, v)   per-end volume 0..1
 *   setEndMuted(lane, b)    mute/unmute an instrument
 *   setEqBand(lane, i, dB)  one EQ band, dayan/bayan only
 *   setEndPitch(lane, c)    retune an end by cents, dayan/bayan only
 *   start() / stop()        begin / end playback
 *   playStroke(lane, open)  sound one stroke now — the editor's pads
 *   getPhase() / getStep()  bar position, for the playhead
 *   events                  Ready | Started | Stopped | Step | OutputFailed
 *
 * KNOWS NOTHING ABOUT COMPOSE, THE SCREENS, OR ANDROID LIFECYCLE. The one
 * Android type it takes is [AssetManager], to read the bundled recordings.
 *
 * ── WHY arm() IS SEPARATE FROM start() ──
 * Web Audio renders continuously once its context is running, so the web
 * engine's `playStroke` works whenever the page is open. A stream-based
 * AudioTrack only produces blocks while its thread runs, so if the thread lived
 * only between start() and stop(), the editor's pads — and auditioning a beat
 * before playing it — would be silent. [arm] therefore opens the device and
 * starts the thread, which then renders SILENCE until the clock is started. That
 * is the same trade the browser makes, and it is what makes pad latency
 * independent of whether the transport happens to be running.
 */
class KirtanEngine(
    private val assets: AssetManager,
    val sampleRate: Int,
) : BlockRenderer {

    companion object {
        private const val TAG = "KirtanEngine"

        /**
         * Frames rendered per pass. 256 is ~5.3 ms at 48 kHz: small enough that
         * a stroke is placed within a few samples of its intended position,
         * large enough that the thread is not waking hundreds of times a second.
         */
        const val BLOCK_SIZE = 256
    }

    private val clock = MusicalClock(sampleRate)
    private val soundPlayer = SoundPlayer(sampleRate, BLOCK_SIZE)
    private val sequencer = Sequencer(clock)
    private val renderer = AudioRenderer(sampleRate, BLOCK_SIZE, this)
    private val bank = SampleBank(assets)

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    /**
     * Pads awaiting a trigger, drained at the top of the next block.
     *
     * A pad tap arrives on the UI thread, but the voice list belongs to the
     * audio thread and is not synchronised — so the request is queued rather
     * than acted on. The queue costs one block of latency at most, which is
     * already the buffer delay, and it keeps every mutation of the voice list on
     * one thread.
     */
    private val pendingPads = ConcurrentLinkedQueue<StrokeKey>()

    // Reused across blocks: the audio thread must not allocate.
    private val stepScratch = ArrayList<StepEvent>(8)

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    /** The beat currently loaded, mirrored for the UI. */
    @Volatile
    var currentBeat: Beat? = null
        private set

    val bpm: Int get() = clock.bpm.toInt()

    /** Output device is open and the audio thread is running. */
    val isArmed: Boolean get() = renderer.isRunning

    /**
     * Short writes since the thread started. A rising count means the device is
     * not keeping up — the first thing to look at if playback ever stutters.
     */
    val underruns: Int get() = renderer.underruns

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Decode every bundled sample at the output rate.
     *
     * Suspending and IO-dispatched because resampling eleven stereo recordings
     * is tens of milliseconds of work — long enough to drop frames if it ran on
     * the main thread during the splash screen, which is exactly when it runs.
     *
     * A stroke whose files are missing or undecodable is skipped rather than
     * failing the load, so one absent recording cannot leave the app stuck
     * waiting for a [EngineEvent.Ready] that never comes.
     */
    suspend fun loadSounds(): Boolean = withContext(Dispatchers.IO) {
        val loaded = try {
            bank.load(sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "sample load failed", e)
            emptySet()
        }
        if (loaded.isEmpty()) {
            Log.e(TAG, "no samples decoded; the engine will be silent")
            false
        } else {
            soundPlayer.attach(bank.decoded())
            isReady = true
            _events.tryEmit(EngineEvent.Ready)
            true
        }
    }

    /**
     * Open the output device and start the audio thread.
     * Safe to call repeatedly; a failure emits [EngineEvent.OutputFailed] and
     * returns false rather than throwing, so the app can still be browsed.
     */
    fun arm(): Boolean {
        if (renderer.isRunning) return true
        val ok = renderer.start()
        if (!ok) {
            Log.e(TAG, "could not arm the audio output")
            _events.tryEmit(EngineEvent.OutputFailed)
        }
        return ok
    }

    /** Stop the audio thread and release the device. */
    fun release() {
        isPlaying = false
        clock.stop()
        renderer.stop()
    }

    // ── Commands ───────────────────────────────────────────────────────────

    /**
     * Choose which beat plays. Does NOT restart the clock, so bar phase — and
     * therefore the musical position — survives the switch. That is deliberate
     * and is the property the whole phase-derived step model exists to protect:
     * swapping Forward for Double Time mid-kirtan keeps the downbeat where it
     * was instead of lurching back to the top of the bar.
     */
    fun setBeat(beat: Beat) {
        currentBeat = beat
        sequencer.beat = beat
        sequencer.invalidateGuard()
    }

    fun setBpm(bpm: Int) = clock.setBpm(bpm.toDouble(), renderer.framesRendered)

    fun setVolume(value: Float) = soundPlayer.setVolume(value)

    /** The user's master setting, unaffected by any ducking in force. */
    fun volume(): Float = soundPlayer.volume()

    /**
     * Attenuate for a transient audio-focus loss. Separate from [setVolume] so
     * that ducking for a navigation prompt cannot clobber the user's level — see
     * [SoundPlayer.setMasterDuck].
     */
    fun setMasterDuck(factor: Float) = soundPlayer.setMasterDuck(factor)

    fun setEndVolume(lane: LaneId, value: Float) = soundPlayer.setEndVolume(lane, value)

    fun setEndMuted(lane: LaneId, muted: Boolean) = soundPlayer.setEndMuted(lane, muted)

    fun isEndMuted(lane: LaneId): Boolean = soundPlayer.isEndMuted(lane)

    fun endVolume(lane: LaneId): Float = soundPlayer.endVolume(lane)

    fun setEqBand(lane: LaneId, bandIndex: Int, db: Double) =
        soundPlayer.setEqBand(lane, bandIndex, db)

    fun eqBandDb(lane: LaneId, bandIndex: Int): Double? = soundPlayer.eqBandDb(lane, bandIndex)

    fun hasEq(lane: LaneId): Boolean = soundPlayer.hasEq(lane)

    fun setEndPitch(lane: LaneId, cents: Int) = soundPlayer.setEndPitch(lane, cents)

    /** Begin playing from the top of the bar. */
    fun start() {
        val beat = currentBeat
        if (beat == null) {
            Log.w(TAG, "start() with no beat loaded")
            return
        }
        if (!arm()) return

        // Position 0 so the visual playhead and the audio both begin on the
        // downbeat, matching the web transport's start().
        clock.start(renderer.framesRendered)
        soundPlayer.stopAllVoices()
        sequencer.invalidateGuard()
        isPlaying = true
        _events.tryEmit(EngineEvent.Started)
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        clock.stop()
        // Voices are left to ring out rather than being cut: stopping mid-decay
        // is a click, and a drum that is allowed to finish sounds like a drum
        // that was stopped. The clock stopping is what ends the rhythm.
        _events.tryEmit(EngineEvent.Stopped)
    }

    /**
     * Sound one stroke immediately — the editor's pads, and auditioning a beat
     * from the library. Queued to the audio thread; see [pendingPads].
     */
    fun playStroke(lane: LaneId, open: Boolean) {
        pendingPads.add(StrokeKey(lane, open))
    }

    // ── Position, for the playhead ─────────────────────────────────────────

    /**
     * Bar phase in [0,1). 0 while stopped.
     *
     * Read this once per frame from the UI (Compose's `withFrameNanos`) rather
     * than animating off [EngineEvent.Step]: it is a pure function of the frame
     * position, so it is always exactly in step with the audio and needs no
     * interpolation between step events.
     */
    fun getPhase(): Double = clock.phaseAt(playheadFrame())

    /** The step index in force at the playhead. */
    fun getStep(): Int = clock.stepAt(playheadFrame())

    /**
     * The frame the playhead should be drawn at.
     *
     * Backed off from [AudioRenderer.framesRendered] by the track's buffer
     * depth, because those frames are queued in the device and have not been
     * heard yet. Without this the visual playhead leads the sound by the buffer
     * latency — ~43 ms, which is very visible on a fast double-time beat.
     */
    private fun playheadFrame(): Long {
        val f = renderer.framesRendered - OUTPUT_LATENCY_FRAMES
        return if (f < 0) 0 else f
    }

    /** The queued-block latency, in frames, of the output path. */
    private val OUTPUT_LATENCY_FRAMES: Long = (BLOCK_SIZE * 4).toLong()

    // ── The audio thread's entry point ─────────────────────────────────────

    /**
     * Render one block. Called by [AudioRenderer] on the audio thread.
     *
     * Order matters: triggers are placed FIRST, at their exact offsets within
     * this block, and only then is the block mixed — so a stroke scheduled for
     * frame 137 of 256 sounds at frame 137 and not at the next block boundary.
     */
    override fun render(fromFrame: Long, count: Int, out: FloatArray) {
        val beat = sequencer.beat

        // 1. Pad taps queued from the UI thread.
        while (true) {
            val key = pendingPads.poll() ?: break
            soundPlayer.play(key, 0)
        }

        // 2. Sequenced steps falling inside this block.
        if (beat != null && clock.isRunning) {
            stepScratch.clear()
            sequencer.collectStepEvents(fromFrame, fromFrame + count, stepScratch)

            for (event in stepScratch) {
                val offset = (event.frame - fromFrame).toInt().coerceIn(0, count - 1)
                // Diagnostic: a dropped downbeat shows up in logcat as a gap in
                // this sequence rather than only being heard. Verbose, so silent
                // in normal use.
                Log.v(TAG, "step ${event.step} frame=${event.frame} block=$fromFrame offset=$offset")
                // Iterated inline rather than via a helper that builds a list:
                // this runs ten times a second forever, so it allocates nothing.
                for (lane in LaneId.ORDERED) {
                    val stroke = beat.strokeAt(lane, event.step) ?: continue
                    soundPlayer.play(StrokeKey(lane, stroke == Stroke.OPEN), offset)
                }
                _events.tryEmit(EngineEvent.Step(event.step))
            }
        }

        // 3. Mix: voices → per-end EQ and fader → master.
        soundPlayer.renderBlock(out, count)
    }
}
