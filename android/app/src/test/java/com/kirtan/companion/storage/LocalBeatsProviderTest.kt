package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The on-device provider, against a REAL DataStore over a real file.
 *
 * Not a fake: the two behaviours that matter most here are properties of the store
 * itself, and a fake would have to be written to agree with whatever this test
 * assumed. The first is that a write is an atomic read-modify-write, so the value a
 * provider read a moment ago is the value it overwrites. The second — the one the
 * whole layer exists for — is that a store holding something undecodable REFUSES the
 * next write instead of replacing the user's library with an empty one.
 */
class LocalBeatsProviderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopStores() {
        // DataStore keeps an actor alive on the scope it was given; without this the
        // threads outlive the test and the JVM waits for them at the end of the run.
        scopes.forEach { it.cancel() }
    }

    /**
     * A store over a file that does not exist yet, which is what a first launch sees.
     * The corruption handler is the one the production delegate installs, so a
     * damaged FILE behaves here as it does on a device.
     */
    private fun newStore(): DataStore<Preferences> {
        val file = File(folder.root, "store-${scopes.size}.preferences_pb")
        scopes += CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scopes.last(),
            produceFile = { file },
        )
    }

    /**
     * A provider over [store]. Calling this twice is the "throw the provider away"
     * case: nothing is cached in memory, so the second one has to read what the
     * first one wrote.
     */
    private fun provider(store: DataStore<Preferences>) = LocalBeatsProvider(store, SilentStorageLog)

    /** Put a raw value under a key, bypassing the provider — what corruption looks like. */
    private suspend fun seed(store: DataStore<Preferences>, key: Preferences.Key<String>, value: String) {
        store.updateData { prefs -> prefs.toMutablePreferences().also { it[key] = value } }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    @Test
    fun `a first launch loads an empty library on the built-in category`() = runTest {
        val library = provider(newStore()).loadAll()

        assertTrue(library.beats.isEmpty())
        assertTrue(library.categories.isEmpty())
        assertEquals(BUILTIN_CATEGORY, library.activeCategoryId)
    }

    @Test
    fun `a saved beat is there for a provider that never saw the create`() = runTest {
        val store = newStore()
        val beat = provider(store).createBeat(testBeat(name = "Morning"))

        val reloaded = provider(store).loadAll()

        assertEquals(listOf(beat), reloaded.beats)
        assertEquals(beat.id, reloaded.beats.single().id)
    }

    @Test
    fun `categories and the active category survive a reload`() = runTest {
        val store = newStore()
        val first = provider(store)
        val category = first.createCategory(CategoryDraft("Sunday", listOf("te_ta")))
        first.setActiveCategory(category.id)

        val reloaded = provider(store).loadAll()

        assertEquals(listOf(category), reloaded.categories)
        assertEquals(category.id, reloaded.activeCategoryId)
    }

    @Test
    fun `the schema version is stamped on the first write`() = runTest {
        val store = newStore()
        provider(store).createBeat(testBeat())

        assertEquals(SCHEMA_VERSION, store.data.first()[Keys.SCHEMA_VERSION])
    }

    // ── Writing ────────────────────────────────────────────────────────────

    @Test
    fun `a batch is written in one go and comes back in order`() = runTest {
        val provider = provider(newStore())
        val drafts = listOf(testBeat(name = "A"), testBeat(name = "B"), testBeat(name = "C"))

        val created = provider.createBeats(drafts)

        assertEquals(listOf("A", "B", "C"), created.map { it.name })
        assertEquals("every draft got its own id", 3, created.mapNotNull { it.id }.distinct().size)
        assertEquals(created, provider.loadAll().beats)
    }

    @Test
    fun `creating a beat keeps the ones already there`() = runTest {
        val provider = provider(newStore())
        val first = provider.createBeat(testBeat(name = "First"))
        val second = provider.createBeat(testBeat(name = "Second"))

        assertEquals(listOf(first, second), provider.loadAll().beats)
    }

    @Test
    fun `updating a beat replaces it in place`() = runTest {
        val provider = provider(newStore())
        val saved = provider.createBeat(testBeat(name = "Mine", bpm = 90))

        val updated = provider.updateBeat(saved.id!!, saved.copy(bpm = 120, name = "Renamed"))
        val stored = provider.loadAll().beats

        assertEquals(120, updated.bpm)
        assertEquals("Renamed", updated.name)
        assertEquals(saved.id, updated.id)
        assertEquals(listOf(updated), stored)
    }

    @Test
    fun `updating a beat that is not there is notFound`() = runTest {
        val provider = provider(newStore())

        val error = expectStorageError { provider.updateBeat("no-such-id", testBeat()) }

        assertEquals(StorageErrorCode.NOT_FOUND, error.code)
        assertEquals("That beat no longer exists.", error.message)
    }

    @Test
    fun `updating a category that is not there is notFound`() = runTest {
        val provider = provider(newStore())

        val error = expectStorageError { provider.updateCategory("no-such-id", CategoryPatch(name = "x")) }

        assertEquals(StorageErrorCode.NOT_FOUND, error.code)
        assertEquals("That list no longer exists.", error.message)
    }

    @Test
    fun `a partial category patch leaves the other half alone`() = runTest {
        val provider = provider(newStore())
        val category = provider.createCategory(CategoryDraft("Sunday", listOf("a")))

        val renamed = provider.updateCategory(category.id, CategoryPatch(name = "Sunday (2)"))
        assertEquals("a name patch must not touch the order", listOf("a"), renamed.beatIds)

        val reordered = provider.updateCategory(category.id, CategoryPatch(beatIds = listOf("b", "a")))
        assertEquals("an order patch must not touch the name", "Sunday (2)", reordered.name)
        assertEquals(listOf("b", "a"), reordered.beatIds)
    }

    @Test
    fun `deleting something that is already gone is not an error`() = runTest {
        val provider = provider(newStore())

        provider.deleteBeat("never-existed")
        provider.deleteCategory("never-existed")

        assertTrue(provider.loadAll().beats.isEmpty())
    }

    // ── A corrupt store ────────────────────────────────────────────────────

    @Test
    fun `a corrupt beats blob is refused rather than read as an empty library`() = runTest {
        val store = newStore()
        seed(store, Keys.BEATS, "{\"beats\": [truncated")

        val error = expectStorageError { provider(store).loadAll() }

        assertEquals(StorageErrorCode.UNAVAILABLE, error.code)
        assertTrue(
            "the message has to be a sentence a person can read: ${error.message}",
            error.message!!.contains("couldn't be read"),
        )
    }

    @Test
    fun `a corrupt store is not overwritten by the next write`() = runTest {
        val store = newStore()
        val garbage = "[{\"name\":\"half a beat\""
        seed(store, Keys.BEATS, garbage)
        val provider = provider(store)

        // The write must fail — this is the whole reason the read refuses. The web
        // provider falls back to [] here, and the next save then persists that empty
        // list over the real one: a truncated blob becomes a deleted library.
        val error = expectStorageError { provider.createBeat(testBeat(name = "New")) }

        // The CORRUPT error, not the generic "something went wrong" one: it is thrown
        // from inside DataStore's write transform, so this also pins down that
        // DataStore rethrows a transform failure unwrapped rather than laundering it
        // into its own exception type.
        assertEquals(StorageErrorCode.UNAVAILABLE, error.code)
        assertTrue(error.message, error.message!!.contains("couldn't be read"))

        assertEquals("the damaged bytes must still be on disk", garbage, store.data.first()[Keys.BEATS])
    }

    @Test
    fun `a well-formed beat missing a mridanga end is refused`() = runTest {
        val store = newStore()
        seed(
            store,
            Keys.BEATS,
            """[{"id":"x","name":"No bayan","note":"Custom","bpm":90,"steps":8,"beatsPerBar":4,"cellsPerGroup":2,"dayan":["X","O","X","O","X","O","X","O"]}]""",
        )

        expectStorageError { provider(store).loadAll() }
    }

    @Test
    fun `a beat with an unknown stroke code is refused`() = runTest {
        val store = newStore()
        seed(
            store,
            Keys.BEATS,
            """[{"id":"x","name":"Q","note":"Custom","bpm":90,"steps":2,"beatsPerBar":2,"cellsPerGroup":1,"dayan":["Q",null],"bayan":[null,"O"]}]""",
        )

        // Reading "Q" as a rest would silently delete a stroke the user drew.
        expectStorageError { provider(store).loadAll() }
    }

    @Test
    fun `one bad beat rejects the whole list rather than dropping it`() = runTest {
        val store = newStore()
        val good = LibraryJson.encodeStored(testBeat(id = "good", name = "Good"))
        seed(store, Keys.BEATS, "[$good, 42]")

        // Skipping the bad row and writing the rest back would be a silent partial
        // delete: one beat missing, nothing anywhere saying why.
        expectStorageError { provider(store).loadAll() }
    }

    @Test
    fun `a corrupt categories blob is refused too`() = runTest {
        val store = newStore()
        seed(store, Keys.CATEGORIES, "not json at all")

        assertEquals(StorageErrorCode.UNAVAILABLE, expectStorageError { provider(store).loadAll() }.code)
    }

    @Test
    fun `an empty library is not the same as an unreadable one`() = runTest {
        val store = newStore()
        seed(store, Keys.BEATS, "[]")
        seed(store, Keys.CATEGORIES, "[]")

        val library = provider(store).loadAll()

        assertTrue(library.beats.isEmpty())
        assertTrue(library.categories.isEmpty())
    }
}
