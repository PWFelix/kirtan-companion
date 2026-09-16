package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kirtan.companion.data.EQ_BAND_COUNT
import com.kirtan.companion.data.TUNE_MAX_CENTS
import com.kirtan.companion.data.TUNE_MIN_CENTS
import com.kirtan.companion.data.clampEqDb
import com.kirtan.companion.data.model.LaneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Persistence for the mixer and the notation display — the deliberately-NOT-a-
 * provider path.
 *
 * Ported from `src/storage/eqPrefs.js`, extended on the Android side with the
 * transport and display settings the web app keeps only in React state (master
 * volume, per-end faders and mutes, the tempo lock, and the Learn screen's
 * bols/step-label toggles). They live here rather than in a second file because
 * they are the same kind of thing: a small blob of cosmetic preference with no
 * rows, no ids and no migration.
 *
 * ── WHY THIS IS NOT A PROVIDER METHOD ──
 * [BeatsProvider] is shaped like the database the beat library becomes: rows,
 * read-modify-write lists, and errors that surface. Mixer settings are none of
 * those things, so forcing them through the provider would mean inventing an id
 * and a table for something that is one object per install — and would put a
 * network round trip in front of a slider drag the moment the cloud provider
 * became active. The web app's answer is a separate file with its own key and its
 * own posture; this is the same answer.
 *
 * ── THE POSTURE IS "NEVER THROW" ──
 * The asymmetry with [BeatsProvider] is intentional and must survive any edit
 * here. Reading fails whenever the store is unavailable, and decoding fails
 * whenever something unexpected lands under the key — a future schema change, a
 * backup restore from an older build, `adb shell` curiosity. A corrupt mixer
 * setting must degrade to FLAT, not brick the transport on launch, so every
 * failure path below arrives at [EqPrefsSnapshot.DEFAULT]: all bands 0 dB, tuning
 * centred, panels closed, faders up, nothing muted. By the same reasoning a failed
 * SAVE is only a warning — the sliders keep working for this session, the device
 * just won't remember. This is deliberately not the beat provider's "errors are
 * the point" stance: a lost EQ setting is re-set in seconds, a lost beat is not.
 *
 * ── THE SHAPE CARRIES DAYAN AND BAYAN ONLY ──
 * Kartal is the mixer's third lane and has faders and a mute, but no equaliser and
 * no tuning, so it gets no [EndSoundPrefs] — and [EqPrefsSnapshot] names the two
 * ends as FIELDS rather than holding a map, which makes "do not add a kartal EQ"
 * structural instead of a comment someone has to remember. Volumes and mutes are
 * per-[LaneId] and do cover all three.
 *
 * ── SANITISE ON LOAD ──
 * Every band is clamped to [com.kirtan.companion.data.EQ_MIN_DB]…[com.kirtan.companion.data.EQ_MAX_DB]
 * via [clampEqDb] and every tuning offset to [TUNE_MIN_CENTS]…[TUNE_MAX_CENTS],
 * reading the SAME bounds the engine, the mixer's sliders and the transport's
 * clamp use. The web version does this for one reason and it applies here
 * unchanged: a hand-edited or stale value must not be able to push the DSP out of
 * range through mount hydration, because the first thing the transport does with
 * these numbers is hand them to a filter chain.
 */
class EqPrefs(
    private val store: DataStore<Preferences>,
    private val log: StorageLog = AndroidStorageLog,
) {

    /** Serialises overlapping saves; see [save]. */
    private val writeMutex = Mutex()

    /**
     * The settings as one immutable snapshot — read ONCE at launch and hydrated
     * into the transport from there.
     *
     * Reading once is the web hook's rule (`useTransport` calls `loadEqPrefs` in a
     * lazy initialiser and every slice derives from that single snapshot): two
     * reads double the disk access and can disagree if the store changed between
     * them.
     *
     * Never throws; see the class header.
     */
    suspend fun load(): EqPrefsSnapshot = try {
        EqPrefsCodec.decode(store.data.first()[Keys.MIXER_PREFS])
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("[storage] mixer settings could not be read; using the defaults", e)
        EqPrefsSnapshot.DEFAULT
    }

    /**
     * Write the WHOLE snapshot.
     *
     * Whole-object rather than per-field for the reason the web hook gives: the
     * handlers persist one object built from the current state, and a per-field
     * write computed from a stale snapshot resurrects the value the user changed a
     * moment ago. Failures are logged, never thrown — a slider change must not be
     * able to take the mixer down with it.
     *
     * DEBOUNCE-FRIENDLY, not debounced. A slider drag fires many changes a second
     * and a disk write per event janks it, so the caller coalesces; the number to
     * coalesce on is [SAVE_DEBOUNCE_MS], kept here rather than in the transport so
     * the two cannot drift. The [Mutex] is what makes an un-debounced caller merely
     * wasteful rather than wrong: overlapping saves serialise instead of
     * interleaving two half-written blobs.
     */
    suspend fun save(snapshot: EqPrefsSnapshot) {
        try {
            writeMutex.withLock {
                store.updateData { prefs ->
                    prefs.toMutablePreferences().also { next ->
                        next[Keys.MIXER_PREFS] = EqPrefsCodec.encode(snapshot)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[storage] mixer settings could not be saved", e)
        }
    }

    companion object {
        /**
         * The idle period a caller should coalesce writes over. The web transport's
         * `EQ_SAVE_DEBOUNCE_MS`, unchanged: React state and the DSP still update
         * immediately, only the write waits.
         */
        const val SAVE_DEBOUNCE_MS = 250L
    }
}

/** One mridanga end's sound-shaping settings, and its two panels' disclosure state. */
data class EndSoundPrefs(
    /** [EQ_BAND_COUNT] gains in dB, in [com.kirtan.companion.data.EQ_BANDS] order. */
    val bands: List<Double>,
    /** The tuning offset in cents, within [TUNE_MIN_CENTS]…[TUNE_MAX_CENTS]. */
    val tuneCents: Int,
    /** Whether the EQ panel is open. Cosmetic, but worth remembering. */
    val eqPanelOpen: Boolean,
    /** Whether the tuning panel is open. */
    val tunePanelOpen: Boolean,
) {
    companion object {
        /** Flat, centred, closed. */
        val DEFAULT = EndSoundPrefs(
            bands = List(EQ_BAND_COUNT) { 0.0 },
            tuneCents = 0,
            eqPanelOpen = false,
            tunePanelOpen = false,
        )
    }

    /** The band gains as the array the engine's `setEqBands` takes. */
    fun bandsArray(): DoubleArray = bands.toDoubleArray()
}

/**
 * Everything the mixer and the notation display remember.
 *
 * Immutable, and the unit both [EqPrefs.load] returns and [EqPrefs.save] takes —
 * `copy` one field and save the result, rather than mutating something shared.
 */
data class EqPrefsSnapshot(
    /** Master volume, 0…1. */
    val masterVolume: Float,
    /** Per-lane faders, 0…1. A lane absent from the map reads as 1. */
    val endVolumes: Map<LaneId, Float>,
    /** Lanes currently muted. Kept apart from [endVolumes] so unmuting restores the fader. */
    val mutedEnds: Set<LaneId>,
    /** Whether the tempo controls are locked against an accidental drag. */
    val tempoLocked: Boolean,
    /**
     * Whether the strip prints the bol syllable ("Ta", "Ge"…) under each mark.
     * The web app's `BeatStrip.showBols` Settings toggle.
     */
    val showBols: Boolean,
    /**
     * Whether the strip prints the guided step labels ("1 · 2 · …") above it —
     * [com.kirtan.companion.data.generateGuidedLabels]. On by default: the
     * numbered downbeats are what a learner reads the cycle by.
     */
    val showStepLabels: Boolean,
    /**
     * Interface scale: a multiplier applied to EVERY dp and sp in the app.
     *
     * Not a font size — a whole-interface scale. It is implemented by providing a
     * scaled [androidx.compose.ui.unit.Density] at the composition root, which is
     * the one place every dp→px conversion in Compose passes through, so a single
     * value resizes the strip, the transport, the sheets and the nav together and
     * they stay in proportion. That is what makes "smaller on a small phone,
     * bigger on a tablet" one setting rather than a per-screen compromise.
     *
     * Persisted because it is exactly the kind of choice a user makes once per
     * device and expects to survive a reinstall of their settings.
     */
    val uiScale: Float,
    val dayan: EndSoundPrefs,
    val bayan: EndSoundPrefs,
) {

    /** A lane's EQ/tuning settings, or null for a lane that has none (kartal). */
    fun end(lane: LaneId): EndSoundPrefs? = when (lane) {
        LaneId.DAYAN -> dayan
        LaneId.BAYAN -> bayan
        else -> null
    }

    /** A lane's fader, defaulting to unity — the engine's own default. */
    fun volume(lane: LaneId): Float = endVolumes[lane] ?: 1f

    fun isMuted(lane: LaneId): Boolean = lane in mutedEnds

    companion object {
        /** The web transport's initial master volume. */
        const val DEFAULT_MASTER_VOLUME = 0.9f

        /**
         * Bounds for [uiScale]. Wide enough to matter at both ends — 0.85 buys a
         * whole extra strip row on a small phone, 1.3 makes a tablet readable from
         * a metre away — and narrow enough that no preset can push a layout into
         * a degenerate state.
         */
        const val UI_SCALE_MIN = 0.75f
        const val UI_SCALE_MAX = 1.5f

        /**
         * The choices Settings offers. Discrete rather than a slider on purpose:
         * a scale is a per-device decision made once, presets are sanity-checkable
         * (each has been looked at on a small phone and a large screen), and a
         * slider would invite intermediate values nobody has ever seen laid out.
         */
        val UI_SCALE_PRESETS: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f)

        /** The state a fresh install, and every failure path, arrives at. */
        val DEFAULT = EqPrefsSnapshot(
            masterVolume = DEFAULT_MASTER_VOLUME,
            endVolumes = emptyMap(),
            mutedEnds = emptySet(),
            tempoLocked = false,
            showBols = false,
            showStepLabels = true,
            uiScale = 1.0f,
            dayan = EndSoundPrefs.DEFAULT,
            bayan = EndSoundPrefs.DEFAULT,
        )
    }
}

/**
 * The snapshot's JSON, and the sanitiser that reads it back.
 *
 * Pure and never-throwing, so the "a poisoned value cannot reach the DSP" rule can
 * be tested without a disk, a Context or a DataStore.
 *
 * The per-end field names — `bands`, `tune`, `open`, `tuneOpen` — are the web
 * `eqPrefs.js` blob's, so a settings file carried across from the browser still
 * means the same thing. The rest of the keys are this port's, because the web app
 * has never persisted them.
 */
internal object EqPrefsCodec {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private const val KEY_VOLUME = "volume"
    private const val KEY_END_VOLUMES = "endVolumes"
    private const val KEY_MUTED_ENDS = "mutedEnds"
    private const val KEY_TEMPO_LOCKED = "tempoLocked"
    private const val KEY_SHOW_BOLS = "showBols"
    private const val KEY_SHOW_STEP_LABELS = "showStepLabels"
    private const val KEY_UI_SCALE = "uiScale"
    private const val KEY_BANDS = "bands"
    private const val KEY_TUNE = "tune"
    private const val KEY_OPEN = "open"
    private const val KEY_TUNE_OPEN = "tuneOpen"

    /** The web blob's per-end keys, so a settings file carried across still reads. */
    private const val KEY_DAYAN = "dayan"
    private const val KEY_BAYAN = "bayan"

    fun encode(snapshot: EqPrefsSnapshot): String = buildJsonObject {
        put(KEY_VOLUME, snapshot.masterVolume)
        put(KEY_END_VOLUMES, buildJsonObject {
            for ((lane, volume) in snapshot.endVolumes) put(lane.wireId, volume)
        })
        put(KEY_MUTED_ENDS, buildJsonArray {
            for (lane in LaneId.ORDERED) if (lane in snapshot.mutedEnds) add(lane.wireId)
        })
        put(KEY_TEMPO_LOCKED, snapshot.tempoLocked)
        put(KEY_SHOW_BOLS, snapshot.showBols)
        put(KEY_SHOW_STEP_LABELS, snapshot.showStepLabels)
        put(KEY_UI_SCALE, snapshot.uiScale)
        put(KEY_DAYAN, encodeEnd(snapshot.dayan))
        put(KEY_BAYAN, encodeEnd(snapshot.bayan))
    }.toString()

    private fun encodeEnd(end: EndSoundPrefs): JsonObject = buildJsonObject {
        put(KEY_BANDS, buildJsonArray { end.bands.forEach { add(it) } })
        put(KEY_TUNE, end.tuneCents)
        put(KEY_OPEN, end.eqPanelOpen)
        put(KEY_TUNE_OPEN, end.tunePanelOpen)
    }

    /**
     * The snapshot held in [raw], or [EqPrefsSnapshot.DEFAULT] for anything at all
     * that is wrong with it — absent, not JSON, not an object, a field of the wrong
     * type. Per-FIELD fallback rather than all-or-nothing: a half-written blob still
     * yields a complete, valid snapshot, with the damaged fields at their defaults.
     */
    fun decode(raw: String?): EqPrefsSnapshot {
        if (raw == null) return EqPrefsSnapshot.DEFAULT
        val parsed = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return EqPrefsSnapshot.DEFAULT
        } catch (e: Exception) {
            return EqPrefsSnapshot.DEFAULT
        }
        return EqPrefsSnapshot(
            masterVolume = volumeOf(parsed[KEY_VOLUME], EqPrefsSnapshot.DEFAULT_MASTER_VOLUME),
            endVolumes = endVolumesOf(parsed[KEY_END_VOLUMES]),
            mutedEnds = mutedEndsOf(parsed[KEY_MUTED_ENDS]),
            tempoLocked = boolOf(parsed[KEY_TEMPO_LOCKED], false),
            showBols = boolOf(parsed[KEY_SHOW_BOLS], false),
            showStepLabels = boolOf(parsed[KEY_SHOW_STEP_LABELS], true),
            uiScale = scaleOf(parsed[KEY_UI_SCALE]),
            dayan = endOf(parsed[KEY_DAYAN]),
            bayan = endOf(parsed[KEY_BAYAN]),
        )
    }

    /**
     * Rebuild one end's settings from whatever the store held. Every wrong shape —
     * missing, null, wrong type, a short or long bands array — falls back per field,
     * so even a half-corrupt blob yields a complete, valid end.
     */
    private fun endOf(element: JsonElement?): EndSoundPrefs {
        val obj = element as? JsonObject ?: return EndSoundPrefs.DEFAULT
        return EndSoundPrefs(
            bands = bandsOf(obj[KEY_BANDS]),
            tuneCents = tuneOf(obj[KEY_TUNE]),
            eqPanelOpen = boolOf(obj[KEY_OPEN], false),
            tunePanelOpen = boolOf(obj[KEY_TUNE_OPEN], false),
        )
    }

    /**
     * Exactly [EQ_BAND_COUNT] gains, each coerced to a finite number and then
     * clamped into the shared dB range. A short array pads with 0 dB and a long one
     * is truncated, so the engine's five-filter chain always gets five values — the
     * band count comes from the shared EQ spec, which is what stops the engine, the
     * mixer and this store drifting apart.
     */
    private fun bandsOf(element: JsonElement?): List<Double> {
        val array = element as? JsonArray
        return List(EQ_BAND_COUNT) { index ->
            val raw = (array?.getOrNull(index) as? JsonPrimitive)?.doubleOrNull
            // Non-finite flattens to 0 before clamping, matching the web's bandOf.
            clampEqDb(if (raw != null && raw.isFinite()) raw else 0.0)
        }
    }

    /**
     * The tuning offset coerced into cents in the shared range — the same
     * coerce-then-clamp contract as [bandsOf], so a poisoned value cannot reach the
     * engine through mount hydration.
     */
    private fun tuneOf(element: JsonElement?): Int {
        val raw = (element as? JsonPrimitive)?.doubleOrNull
        val cents = if (raw != null && raw.isFinite()) raw.roundToInt() else 0
        return cents.coerceIn(TUNE_MIN_CENTS, TUNE_MAX_CENTS)
    }

    private fun volumeOf(element: JsonElement?, default: Float): Float {
        val raw = (element as? JsonPrimitive)?.doubleOrNull ?: return default
        if (!raw.isFinite()) return default
        return raw.toFloat().coerceIn(0f, 1f)
    }

    private fun endVolumesOf(element: JsonElement?): Map<LaneId, Float> {
        val obj = element as? JsonObject ?: return emptyMap()
        val volumes = LinkedHashMap<LaneId, Float>()
        // Iterated by the KNOWN lanes rather than by the payload's keys, so an
        // invented key cannot introduce a lane the engine has no channel for.
        for (lane in LaneId.ORDERED) {
            val raw = (obj[lane.wireId] as? JsonPrimitive)?.doubleOrNull ?: continue
            if (raw.isFinite()) volumes[lane] = raw.toFloat().coerceIn(0f, 1f)
        }
        return volumes
    }

    private fun mutedEndsOf(element: JsonElement?): Set<LaneId> {
        val array = element as? JsonArray ?: return emptySet()
        val muted = LinkedHashSet<LaneId>()
        for (item in array) {
            val wireId = (item as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: continue
            LaneId.fromWire(wireId)?.let { muted.add(it) }
        }
        return muted
    }

    private fun boolOf(element: JsonElement?, default: Boolean): Boolean =
        (element as? JsonPrimitive)?.booleanOrNull ?: default

    /**
     * The interface scale, clamped into [EqPrefsSnapshot.UI_SCALE_MIN]…MAX.
     *
     * Clamped on READ, like every other numeric here: a hand-edited store or a
     * future client with wider bounds must not be able to hand the composition a
     * scale of 0 or 50, which would render the app as a point or a single cell.
     * Non-finite or wrong-typed falls back to 1.0 — unscaled, the safe default.
     */
    private fun scaleOf(element: JsonElement?): Float {
        val raw = (element as? JsonPrimitive)?.doubleOrNull ?: return 1.0f
        if (!raw.isFinite()) return 1.0f
        return raw.toFloat()
            .coerceIn(EqPrefsSnapshot.UI_SCALE_MIN, EqPrefsSnapshot.UI_SCALE_MAX)
    }
}
