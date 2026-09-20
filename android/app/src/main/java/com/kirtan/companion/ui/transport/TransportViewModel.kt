package com.kirtan.companion.ui.transport

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kirtan.companion.AppContainer
import com.kirtan.companion.container
import com.kirtan.companion.data.DEFAULT_BEAT
import com.kirtan.companion.data.MAX_BPM
import com.kirtan.companion.data.MIN_BPM
import com.kirtan.companion.data.TUNE_MAX_CENTS
import com.kirtan.companion.data.TUNE_MIN_CENTS
import com.kirtan.companion.data.clampEqDb
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.engine.EngineEvent
import com.kirtan.companion.engine.KirtanEngine
import com.kirtan.companion.storage.EndSoundPrefs
import com.kirtan.companion.storage.EqPrefs
import com.kirtan.companion.storage.EqPrefsSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Everything the transport screens render, as one immutable snapshot.
 *
 * The web app keeps these as eight separate `useState` slots inside
 * `useTransport`. Collapsing them into one value is not just tidiness: a mixer
 * change that touches a fader and a mute together is then ONE recomposition
 * rather than two, so the UI can never be observed half-updated.
 */
data class TransportState(
    /** Samples finished decoding. Nothing plays until this is true. */
    val ready: Boolean = false,
    val playing: Boolean = false,
    /** The step most recently fired, or -1 when stopped. */
    val step: Int = -1,
    val bpm: Int = DEFAULT_BEAT.bpm,
    val beatName: String? = null,
    val settings: EqPrefsSnapshot = EqPrefsSnapshot.DEFAULT,
    /** The output device would not open. Sound is impossible until it clears. */
    val outputFailed: Boolean = false,
)

/**
 * The engine and its Compose mirror, as one unit.
 *
 * Ported from `src/hooks/useTransport.js`, which is the single place in the web
 * app that calls `engine.*` — and this is the single place here. That rule is
 * what keeps "the UI talks to the engine only through KirtanEngine" enforceable
 * rather than aspirational. The one sanctioned exception is the beat editor,
 * which takes the transport over entirely while it is open; [engine] is exposed
 * for exactly that, mirroring `BeatEditor.jsx` being handed the engine outright.
 *
 * ── WHAT DID NOT PORT, AND WHY ──
 * The web hook is dominated by `useRef` mirrors of its own state (`bpmRef`,
 * `eqBandsRef`, `eqOpenRef`, `tuneOpenRef`), because a React handler closes over
 * the values from the render it was created in. Rapid taps on ± would otherwise
 * stack from a stale `bpm` and do nothing; a fast EQ drag would otherwise persist
 * a value a later re-render had already superseded.
 *
 * None of that applies here. A ViewModel is not recreated per frame and holds its
 * own authoritative state, so `bpmRef` and `bpm` collapse into one field. The refs
 * were a workaround for a React lifecycle, not a domain requirement, and porting
 * them would mean porting the prevention machinery for a bug this architecture
 * cannot have.
 *
 * What DID port is the clamping discipline: every value is clamped against the
 * shared bounds in `data/` on the way in, so the UI state, the persisted prefs
 * and the engine's DSP can never disagree about what is legal.
 *
 * ── TRANSPORT GOES THROUGH MEDIA3, MIXER DOES NOT ──
 * Play and pause route via [PlaybackConnection] so the notification, lock-screen
 * controls and foreground-service promotion all follow. Tempo, volumes, EQ and
 * tuning go straight to the engine: the notification has no opinion about an EQ
 * band, and routing a slider drag through IPC at 60 Hz would be slow and pointless.
 */
class TransportViewModel(
    app: Application,
    private val prefs: EqPrefs,
) : AndroidViewModel(app) {

    private val container: AppContainer = app.container

    /** See the class header: exposed for the beat editor only. */
    val engine: KirtanEngine get() = container.engine

    val connection = PlaybackConnection(app)

    private val _state = MutableStateFlow(TransportState())
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /**
     * The bar phase is deliberately NOT in [state].
     *
     * It changes 60 times a second while playing, and putting it in a StateFlow
     * would invalidate every collector on every frame — the whole screen would
     * recompose to move one vertical line. The strip reads it directly from the
     * engine inside `withFrameNanos`, which recomposes only the playhead. This is
     * the Compose equivalent of the web strip's rAF loop writing to `style.left`
     * without going through React state.
     */
    fun getPhase(): Double = engine.getPhase()

    /** Authoritative tempo, mutated synchronously so rapid ± taps stack. */
    private var bpm: Int = _state.value.bpm
    private var tempoLocked: Boolean = false

    /**
     * The last beat whose suggested tempo [loadBeat] adopted.
     *
     * Re-loading the SAME beat must not re-adopt: the selection bridge in
     * KirtanApp re-fires whenever its LaunchedEffect is re-launched (a rotation
     * re-runs it), and an unconditional changeBpm would clobber a tempo the user
     * nudged in the meantime. Different beat, same locked tempo, fresh process
     * (this field resets with the ViewModel, as does the engine) all behave.
     */
    private var loadedBeatId: String? = null
    private var settings: EqPrefsSnapshot = EqPrefsSnapshot.DEFAULT

    private var prefsSaveJob: Job? = null
    private val tapTimes = ArrayList<Long>(4)

    init {
        // ORDER MATTERS HERE, and getting it wrong is a silent dead app.
        //
        // The engine's event flow has no replay, so a Ready emitted before this
        // ViewModel's collector subscribes is DROPPED — and Ready is the only thing
        // that flips `ready`, which gates the Play button. Kicking off the load
        // before subscribing is exactly that ordering. So: subscribe first, then
        // load.
        observeEngine()

        // Belt-and-braces for the case where the load finished before THIS
        // ViewModel existed at all (the engine is process-scoped; a second
        // ViewModel after a rotation would otherwise wait forever for a Ready
        // that already fired). Seeding from `isReady` covers it.
        if (engine.isReady) {
            _state.value = _state.value.copy(ready = true)
            applyPrefsToEngine()
        }

        container.ensureSoundsLoaded()
        loadPrefs()
    }

    private fun observeEngine() {
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    // Hydrating from the Ready handler rather than right after the
                    // load resolves is load-bearing: tuning walks a pool of players
                    // that is empty until decoding finishes, so applying it any
                    // earlier is silently dropped.
                    EngineEvent.Ready -> {
                        _state.value = _state.value.copy(ready = true)
                        applyPrefsToEngine()
                    }

                    EngineEvent.Started -> _state.value = _state.value.copy(playing = true)

                    EngineEvent.Stopped -> _state.value = _state.value.copy(
                        playing = false,
                        step = -1,
                    )

                    is EngineEvent.Step -> _state.value = _state.value.copy(step = event.step)

                    EngineEvent.OutputFailed -> _state.value =
                        _state.value.copy(outputFailed = true)
                }
            }
        }

        // The controller owns "should we be playing"; the engine owns "are we".
        // Mirroring the controller is what keeps the UI honest when playback stops
        // from the notification or a headset button rather than from us.
        viewModelScope.launch {
            connection.playWhenReady.collect { playing ->
                _state.value = _state.value.copy(playing = playing)
            }
        }
    }

    private fun loadPrefs() {
        viewModelScope.launch {
            // EqPrefs.load() never throws and already sanitises against the shared
            // bounds, so there is nothing to recover from here.
            val loaded = prefs.load()
            settings = loaded
            tempoLocked = loaded.tempoLocked
            _state.value = _state.value.copy(settings = loaded)
            // Applied immediately in case samples decoded first, and again from
            // the Ready handler if they did not. Two-phase hydration, as in the
            // web hook, for the reason given above.
            applyPrefsToEngine()
        }
    }

    /** Push every persisted setting into the engine's DSP. */
    private fun applyPrefsToEngine() {
        if (!engine.isReady) return
        engine.setVolume(settings.masterVolume)
        for (lane in LaneId.ORDERED) {
            engine.setEndVolume(lane, settings.volume(lane))
            engine.setEndMuted(lane, settings.isMuted(lane))
        }
        for (lane in LaneId.ORDERED) {
            val end = settings.end(lane) ?: continue
            end.bands.forEachIndexed { i, db -> engine.setEqBand(lane, i, db) }
            engine.setEndPitch(lane, end.tuneCents)
        }
    }

    // ── Tempo ──────────────────────────────────────────────────────────────

    fun changeBpm(value: Int) {
        bpm = value.coerceIn(MIN_BPM, MAX_BPM)
        engine.setBpm(bpm)
        _state.value = _state.value.copy(bpm = bpm)
    }

    fun nudgeBpm(delta: Int) = changeBpm(bpm + delta)

    fun commitBpm(value: Int) = changeBpm(value.coerceIn(MIN_BPM, MAX_BPM))

    /**
     * "Keep this speed while I change beats mid-kirtan." A locked tempo makes
     * [loadBeat] leave the BPM alone instead of adopting the beat's suggestion.
     */
    fun setTempoLocked(locked: Boolean) {
        tempoLocked = locked
        publish(settings.copy(tempoLocked = locked))
    }

    /**
     * Tap tempo: average the gaps between the last few taps.
     *
     * A gap over two seconds means the user started a new count rather than
     * continuing one, so the history resets instead of averaging a pause into the
     * tempo — which would otherwise report something near zero BPM.
     */
    fun tapTempo() {
        val now = System.currentTimeMillis()
        tapTimes.add(now)
        if (tapTimes.size > 4) tapTimes.removeAt(0)
        if (tapTimes.size < 2) return

        val gaps = (1 until tapTimes.size).map { tapTimes[it] - tapTimes[it - 1] }
        if (gaps.last() > TAP_RESET_MS) {
            tapTimes.clear()
            tapTimes.add(now)
            return
        }
        val avgGap = gaps.average()
        if (avgGap > 0) changeBpm((60_000.0 / avgGap).roundToInt())
    }

    // ── Mixer ──────────────────────────────────────────────────────────────

    fun changeVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        publish(settings.copy(masterVolume = v))
        engine.setVolume(v)
    }

    fun changeEndVolume(lane: LaneId, value: Float) {
        val v = value.coerceIn(0f, 1f)
        publish(settings.copy(endVolumes = settings.endVolumes + (lane to v)))
        engine.setEndVolume(lane, v)
    }

    fun toggleMute(lane: LaneId) {
        val muted = !settings.isMuted(lane)
        val next = if (muted) settings.mutedEnds + lane else settings.mutedEnds - lane
        publish(settings.copy(mutedEnds = next))
        engine.setEndMuted(lane, muted)
    }

    fun changeEqBand(lane: LaneId, bandIndex: Int, db: Double) {
        // Clamped HERE rather than only in the engine, so the state, the persisted
        // value and the DSP cannot disagree about what is legal.
        val gain = clampEqDb(if (db.isFinite()) db else 0.0)
        val updated = updateEnd(lane) { end ->
            val bands = end.bands.toMutableList()
            if (bandIndex in bands.indices) bands[bandIndex] = gain
            end.copy(bands = bands)
        }
        if (updated) engine.setEqBand(lane, bandIndex, gain)
    }

    fun changeTune(lane: LaneId, cents: Int) {
        val clamped = cents.coerceIn(TUNE_MIN_CENTS, TUNE_MAX_CENTS)
        if (updateEnd(lane) { it.copy(tuneCents = clamped) }) {
            engine.setEndPitch(lane, clamped)
        }
    }

    fun toggleEqPanel(lane: LaneId) {
        updateEnd(lane) { it.copy(eqPanelOpen = !it.eqPanelOpen) }
    }

    fun toggleTunePanel(lane: LaneId) {
        updateEnd(lane) { it.copy(tunePanelOpen = !it.tunePanelOpen) }
    }

    fun setShowBols(show: Boolean) = publish(settings.copy(showBols = show))

    fun setShowStepLabels(show: Boolean) = publish(settings.copy(showStepLabels = show))

    /**
     * The whole-interface scale. No engine involvement: this is purely
     * presentational, applied by [com.kirtan.companion.ui.KirtanApp] providing a
     * scaled [androidx.compose.ui.unit.Density] at the composition root.
     */
    fun setUiScale(scale: Float) = publish(
        settings.copy(
            uiScale = scale.coerceIn(
                com.kirtan.companion.storage.EqPrefsSnapshot.UI_SCALE_MIN,
                com.kirtan.companion.storage.EqPrefsSnapshot.UI_SCALE_MAX,
            )
        )
    )

    /**
     * Apply [transform] to one end's settings and republish.
     *
     * Returns false for a lane that has no EQ or tuning — the karatalas — so a
     * caller cannot accidentally invent settings for an instrument the engine
     * deliberately does not shape.
     */
    private fun updateEnd(lane: LaneId, transform: (EndSoundPrefs) -> EndSoundPrefs): Boolean {
        val end = settings.end(lane) ?: return false
        val next = transform(end)
        val snapshot = when (lane) {
            LaneId.DAYAN -> settings.copy(dayan = next)
            LaneId.BAYAN -> settings.copy(bayan = next)
            else -> return false
        }
        publish(snapshot)
        return true
    }

    /** Publish a new snapshot and schedule its persistence. */
    private fun publish(next: EqPrefsSnapshot) {
        settings = next
        _state.value = _state.value.copy(settings = next)
        schedulePrefsSave()
    }

    /**
     * Persistence is DEBOUNCED while the DSP is not.
     *
     * A slider drag fires dozens of changes a second; the engine must hear every
     * one (that is what makes the drag feel connected to the sound), but writing
     * to disk on each would queue I/O behind the gesture. So the state and the DSP
     * update immediately and only the write coalesces, last one winning.
     *
     * The save reads `settings` when it FIRES rather than capturing a snapshot
     * when it is scheduled, so a fast drag always persists the final position.
     */
    private fun schedulePrefsSave() {
        prefsSaveJob?.cancel()
        prefsSaveJob = viewModelScope.launch {
            delay(EqPrefs.SAVE_DEBOUNCE_MS)
            prefs.save(settings)
        }
    }

    // ── Beat selection and transport ───────────────────────────────────────

    /**
     * Point the engine at a beat WITHOUT starting it, adopting the beat's own
     * suggested tempo unless the user has locked it.
     */
    fun loadBeat(beat: Beat) {
        engine.setBeat(beat)
        container.currentBeatName.value = beat.name
        _state.value = _state.value.copy(beatName = beat.name)
        if (!tempoLocked && beat.id != loadedBeatId) changeBpm(beat.bpm)
        loadedBeatId = beat.id
    }

    /**
     * Start [beat]. A no-op while samples are still decoding, and a no-op while
     * ALREADY playing — the same rule the web's `play()` states: the selection
     * that preceded this tap loadBeat'd the beat, which switched the loop live
     * with bar phase preserved. Calling engine.start() on top of that would
     * re-anchor the clock to the bar top and kill the still-ringing voices: a
     * lurch, not a switch.
     */
    fun play(beat: Beat) {
        if (!_state.value.ready) return
        if (_state.value.playing) return
        engine.setBeat(beat)
        engine.setBpm(bpm)
        container.currentBeatName.value = beat.name
        _state.value = _state.value.copy(beatName = beat.name)
        connection.setPlayWhenReady(true)
    }

    fun togglePlay(beat: Beat) {
        if (!_state.value.ready) return
        if (_state.value.playing) connection.setPlayWhenReady(false) else play(beat)
    }

    fun stop() = connection.setPlayWhenReady(false)

    /**
     * The splash's Begin tap. On the web this exists to satisfy the browser's "no
     * sound before a user gesture" rule; Android has no such rule, but the tap is
     * still the right moment to bind the controller and open the output device,
     * because that is when the user has said they want sound.
     */
    suspend fun begin() {
        connection.connect()
        engine.arm()
    }

    override fun onCleared() {
        // Flush a pending debounced save so the last drag position isn't lost.
        // On the PROCESS scope, not viewModelScope: viewModelScope is cancelled
        // before onCleared returns, so a launch there would silently never run
        // and drop the final position on exactly the exit path this flush exists
        // to cover.
        prefsSaveJob?.cancel()
        container.scope.launch { prefs.save(settings) }

        // The engine and its audio device belong to the service, not to this
        // ViewModel, so they are deliberately left running: rotating the phone
        // must not stop a kirtan.
        connection.release()
        super.onCleared()
    }

    companion object {
        /** A gap longer than this starts a new tap-tempo count. */
        const val TAP_RESET_MS = 2000L

        val BPM_RANGE = MIN_BPM..MAX_BPM

        /**
         * [prefs] is injected rather than constructed here so a test can pass a
         * fake — the same seam `useBeatLibrary(provider)` has on the web, and for
         * the same reason.
         */
        fun factory(prefs: EqPrefs): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TransportViewModel(this[APPLICATION_KEY]!!, prefs)
            }
        }
    }
}
