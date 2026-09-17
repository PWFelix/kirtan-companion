package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.kirtan.companion.data.EQ_BAND_COUNT
import com.kirtan.companion.data.EQ_MAX_DB
import com.kirtan.companion.data.EQ_MIN_DB
import com.kirtan.companion.data.TUNE_MAX_CENTS
import com.kirtan.companion.data.TUNE_MIN_CENTS
import com.kirtan.companion.data.model.LaneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The mixer's never-throw path.
 *
 * Two contracts are under test and they pull in opposite directions, which is the
 * point of the file:
 *
 *  - NOTHING HERE MAY THROW. A corrupt or absent blob arrives at the flat defaults,
 *    because a mixer that fails to mount is worse than a mixer that forgot your EQ.
 *    `src/storage/eqPrefs.js` says the same thing, and adds the reason it is allowed
 *    to differ from the beat provider: a lost EQ setting is re-set in seconds, a lost
 *    beat is not.
 *  - NOTHING OUT OF RANGE MAY GET THROUGH. The load-time sanitise exists so a
 *    hand-edited or stale value cannot reach the DSP via mount hydration — the
 *    transport hands these numbers straight to a filter chain. The bounds asserted
 *    below are read from `data/Eq.kt` and `data/Tuning.kt`, so this test fails if the
 *    store and the engine ever stop agreeing about how far a gain may swing.
 */
class EqPrefsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopStores() {
        scopes.forEach { it.cancel() }
    }

    private fun newStore(): DataStore<Preferences> {
        val file = File(folder.root, "prefs-${scopes.size}.preferences_pb")
        scopes += CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scopes.last(),
            produceFile = { file },
        )
    }

    private fun prefs(store: DataStore<Preferences>) = EqPrefs(store, SilentStorageLog)

    // ── The defaults ───────────────────────────────────────────────────────

    @Test
    fun `the defaults are flat, centred, closed and unmuted`() {
        val defaults = EqPrefsSnapshot.DEFAULT

        assertEquals(List(EQ_BAND_COUNT) { 0.0 }, defaults.dayan.bands)
        assertEquals(List(EQ_BAND_COUNT) { 0.0 }, defaults.bayan.bands)
        assertEquals(0, defaults.dayan.tuneCents)
        assertEquals(0, defaults.bayan.tuneCents)
        assertFalse(defaults.dayan.eqPanelOpen)
        assertFalse(defaults.dayan.tunePanelOpen)
        assertTrue(defaults.mutedEnds.isEmpty())
        assertEquals(1f, defaults.volume(LaneId.KARTAL), 0f)
    }

    @Test
    fun `kartal has faders but no equaliser`() {
        // The mixer's third lane has no EQ and no tuning, so it gets no entry — and
        // the snapshot names the two ends as FIELDS, which makes "do not add one"
        // structural rather than a comment someone has to remember.
        val defaults = EqPrefsSnapshot.DEFAULT

        assertNull(defaults.end(LaneId.KARTAL))
        assertEquals(defaults.dayan, defaults.end(LaneId.DAYAN))
        assertEquals(defaults.bayan, defaults.end(LaneId.BAYAN))
    }

    // ── Sanitising on load ─────────────────────────────────────────────────

    @Test
    fun `out-of-range bands are clamped into the shared dB range`() {
        val snapshot = EqPrefsCodec.decode(
            """{"dayan":{"bands":[99,-99,3.5,"2",-1000]},"bayan":{"bands":[12,-12]}}""",
        )

        // 99 and -99 clamp to the shared bounds; the numeric STRING "2" is coerced,
        // because the web's bandOf accepts one and a value the browser wrote must
        // read the same here.
        assertEquals(listOf(EQ_MAX_DB, EQ_MIN_DB, 3.5, 2.0, EQ_MIN_DB), snapshot.dayan.bands)
        // A short array is padded to the band count, so the engine's five-filter
        // chain always gets five values.
        assertEquals(
            listOf(12.0, -12.0) + List(EQ_BAND_COUNT - 2) { 0.0 },
            snapshot.bayan.bands,
        )
    }

    @Test
    fun `a long bands array is truncated to the shared band count`() {
        val snapshot = EqPrefsCodec.decode("""{"dayan":{"bands":[1,2,3,4,5,6,7,8,9]}}""")

        assertEquals(EQ_BAND_COUNT, snapshot.dayan.bands.size)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), snapshot.dayan.bands)
    }

    @Test
    fun `a non-numeric or infinite band flattens to zero before clamping`() {
        val snapshot = EqPrefsCodec.decode("""{"dayan":{"bands":["loud",null,{},1e999,3]}}""")

        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 3.0), snapshot.dayan.bands)
    }

    @Test
    fun `tuning is clamped into the shared cents range`() {
        val snapshot = EqPrefsCodec.decode(
            """{"dayan":{"tune":5000},"bayan":{"tune":-5000}}""",
        )

        assertEquals(TUNE_MAX_CENTS, snapshot.dayan.tuneCents)
        assertEquals(TUNE_MIN_CENTS, snapshot.bayan.tuneCents)
    }

    @Test
    fun `a fractional tuning offset rounds rather than truncating`() {
        assertEquals(13, EqPrefsCodec.decode("""{"dayan":{"tune":12.6}}""").dayan.tuneCents)
    }

    @Test
    fun `volumes are clamped to the fader range`() {
        val snapshot = EqPrefsCodec.decode(
            """{"volume":4.5,"endVolumes":{"dayan":-1,"bayan":2,"kartal":0.5,"melody":0.5}}""",
        )

        assertEquals(1f, snapshot.masterVolume, 0f)
        assertEquals(0f, snapshot.volume(LaneId.DAYAN), 0f)
        assertEquals(1f, snapshot.volume(LaneId.BAYAN), 0f)
        assertEquals(0.5f, snapshot.volume(LaneId.KARTAL), 0f)
        // A lane this build has no channel for cannot be introduced by a stored key.
        assertEquals(3, snapshot.endVolumes.size)
    }

    @Test
    fun `a non-finite volume falls back rather than becoming silent`() {
        val snapshot = EqPrefsCodec.decode("""{"volume":1e999}""")

        assertEquals(EqPrefsSnapshot.DEFAULT_MASTER_VOLUME, snapshot.masterVolume, 0f)
    }

    @Test
    fun `the interface scale survives a round trip`() {
        for (preset in EqPrefsSnapshot.UI_SCALE_PRESETS) {
            val snapshot = EqPrefsSnapshot.DEFAULT.copy(uiScale = preset)
            assertEquals(
                "preset $preset did not survive encode/decode",
                preset,
                EqPrefsCodec.decode(EqPrefsCodec.encode(snapshot)).uiScale,
                0f,
            )
        }
    }

    @Test
    fun `an out-of-range interface scale is clamped, not honoured`() {
        // A scale of 0 renders the app as a point and 50 as a single cell, so a
        // hand-edited or foreign store must not be able to hand either to the
        // composition. Clamped on read, like every other numeric here.
        assertEquals(
            EqPrefsSnapshot.UI_SCALE_MIN,
            EqPrefsCodec.decode("""{"uiScale":0.001}""").uiScale,
            0f,
        )
        assertEquals(
            EqPrefsSnapshot.UI_SCALE_MAX,
            EqPrefsCodec.decode("""{"uiScale":50}""").uiScale,
            0f,
        )
    }

    @Test
    fun `a missing or malformed interface scale is unscaled`() {
        // 1.0 is the safe default: an app that ignores an absent setting is
        // correct, whereas one that guessed would resize itself differently on
        // every install.
        assertEquals(1.0f, EqPrefsCodec.decode("""{}""").uiScale, 0f)
        assertEquals(1.0f, EqPrefsCodec.decode("""{"uiScale":"big"}""").uiScale, 0f)
        assertEquals(1.0f, EqPrefsCodec.decode("""{"uiScale":null}""").uiScale, 0f)
        assertEquals(1.0f, EqPrefsCodec.decode("""{"uiScale":1e999}""").uiScale, 0f)
    }

    @Test
    fun `every preset is inside the clamp range`() {
        // Guards the two constants against drifting apart: if a preset were ever
        // added outside MIN..MAX, decode would silently clamp it and the chip in
        // Settings would show a value the app is not actually using.
        for (preset in EqPrefsSnapshot.UI_SCALE_PRESETS) {
            assertTrue(
                "preset $preset is outside the clamp range",
                preset in EqPrefsSnapshot.UI_SCALE_MIN..EqPrefsSnapshot.UI_SCALE_MAX,
            )
        }
        assertTrue(EqPrefsSnapshot.UI_SCALE_PRESETS.contains(1.0f))
    }

    @Test
    fun `panel state is only true for a literal true`() {
        val snapshot = EqPrefsCodec.decode("""{"dayan":{"open":"yes","tuneOpen":true}}""")

        assertFalse(snapshot.dayan.eqPanelOpen)
        assertTrue(snapshot.dayan.tunePanelOpen)
    }

    @Test
    fun `an unrecognised mute is dropped`() {
        val snapshot = EqPrefsCodec.decode("""{"mutedEnds":["bayan","melody",7,null]}""")

        assertEquals(setOf(LaneId.BAYAN), snapshot.mutedEnds)
    }

    @Test
    fun `a half-written end still yields a complete valid one`() {
        val snapshot = EqPrefsCodec.decode("""{"dayan":{"bands":[3]}}""")

        assertEquals(listOf(3.0, 0.0, 0.0, 0.0, 0.0), snapshot.dayan.bands)
        assertEquals(0, snapshot.dayan.tuneCents)
        assertFalse(snapshot.dayan.eqPanelOpen)
        // The other end was absent entirely and is still complete.
        assertEquals(EndSoundPrefs.DEFAULT, snapshot.bayan)
    }

    // ── Never throwing ─────────────────────────────────────────────────────

    @Test
    fun `anything wrong at all arrives at the defaults`() {
        val broken = listOf(
            null,
            "",
            "not json",
            "{\"volume\": ",          // truncated mid-value
            "[1,2,3]",                // the right JSON, the wrong shape
            "\"a string\"",
            "42",
            "{\"dayan\": 7}",         // an end that is not an object
            "{\"dayan\": {\"bands\": \"loud\"}}",
        )

        for (raw in broken) {
            assertEquals("for stored value $raw", EqPrefsSnapshot.DEFAULT, EqPrefsCodec.decode(raw))
        }
    }

    @Test
    fun `a store that cannot be read yields the defaults`() = runTest {
        val store = newStore()
        store.updateData { prefs -> prefs.toMutablePreferences().also { it[Keys.MIXER_PREFS] = "{{{" } }

        assertEquals(EqPrefsSnapshot.DEFAULT, prefs(store).load())
    }

    @Test
    fun `a store with nothing in it yet yields the defaults`() = runTest {
        assertEquals(EqPrefsSnapshot.DEFAULT, prefs(newStore()).load())
    }

    // ── Round trip ─────────────────────────────────────────────────────────

    @Test
    fun `a snapshot survives the store unchanged`() = runTest {
        val store = newStore()
        val snapshot = EqPrefsSnapshot(
            masterVolume = 0.75f,
            endVolumes = mapOf(LaneId.DAYAN to 0.9f, LaneId.BAYAN to 0.4f, LaneId.KARTAL to 1f),
            mutedEnds = setOf(LaneId.BAYAN),
            tempoLocked = true,
            showBols = true,
            showStepLabels = false,
            // A non-default scale, so this test also proves the value survives a
            // real store round trip and not just the codec.
            uiScale = 1.15f,
            dayan = EndSoundPrefs(listOf(-3.0, 1.5, 0.0, 6.0, -12.0), 25, true, false),
            bayan = EndSoundPrefs(listOf(2.0, 0.0, -1.0, 0.0, 4.5), -600, false, true),
        )

        prefs(store).save(snapshot)
        val loaded = prefs(store).load()

        assertEquals(snapshot, loaded)
        assertEquals(listOf(-3.0, 1.5, 0.0, 6.0, -12.0), loaded.dayan.bands)
        assertTrue(loaded.isMuted(LaneId.BAYAN))
        assertFalse(loaded.isMuted(LaneId.DAYAN))
    }

    @Test
    fun `the stored blob uses the web's per-end field names`() {
        // `bands`, `tune`, `open`, `tuneOpen` are `src/storage/eqPrefs.js`'s keys, so
        // a settings file carried across from the browser still means the same thing.
        val blob = Json.parseToJsonElement(EqPrefsCodec.encode(EqPrefsSnapshot.DEFAULT)).jsonObject

        assertEquals(
            setOf(
                "volume", "endVolumes", "mutedEnds", "tempoLocked",
                "showBols", "showStepLabels", "dayan", "bayan",
                // This port's own key: the web app has never persisted an
                // interface scale, so there is no cross-platform name to honour.
                "uiScale",
            ),
            blob.keys,
        )
        assertEquals(setOf("bands", "tune", "open", "tuneOpen"), blob["dayan"]!!.jsonObject.keys)
        // Kartal is the mixer's third lane and has no equaliser, so it gets no entry.
        assertFalse("kartal must not gain an EQ entry", blob.containsKey("kartal"))
    }

    @Test
    fun `the band gains are handed to the engine as an array`() {
        val end = EndSoundPrefs(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0, false, false)

        assertTrue(end.bandsArray() contentEquals doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
    }
}
