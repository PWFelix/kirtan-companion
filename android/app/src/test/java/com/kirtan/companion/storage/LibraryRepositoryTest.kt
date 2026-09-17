package com.kirtan.companion.storage

import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer above the providers — `useBeatLibrary.js` as a class.
 *
 * The tests here are the ones the web app learned the hard way, and each is named
 * after the bug it stops rather than the function it exercises:
 *
 *  - DE-DUPING, including the "editing a beat without renaming it must not suffix
 *    it a little further on every save" case, which is why the name is computed
 *    against every name in the library EXCEPT the beat's own.
 *  - OPTIMISTIC ROLLBACK. The code this layer replaced caught every storage
 *    failure and carried on, so a beat saved into a full store appeared in the list
 *    and vanished on reload. A write that throws must leave the state as it was and
 *    say why.
 *  - ONE WRITE PER BATCH. Importing a shared playlist through a loop of single
 *    creates dropped beats, because each call read stale state.
 *  - DELETING A BEAT CLEANS UP AFTER ITSELF in every progression that referenced it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {

    /** Stand-ins for the compiled-in beats: two, with names worth colliding with. */
    private val shipped = listOf(
        testBeat(id = "te_ta", name = "Te Ta", note = "Foundational", group = "Foundations"),
        testBeat(id = "forward", name = "Forward"),
    )

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelRepositories() {
        scopes.forEach { it.cancel() }
    }

    /**
     * A repository on its own scope over this test's scheduler.
     *
     * Deliberately NOT `TestScope.backgroundScope`: `advanceUntilIdle` stops as soon
     * as only BACKGROUND work is left, so a repository launched there never loads and
     * every assertion below would run against an empty library — quietly, because an
     * empty library is also a legal state. A plain scope on the same scheduler is
     * foreground work, which is what these tests need to drive.
     */
    private fun TestScope.repository(
        provider: BeatsProvider,
        builtIns: List<Beat> = shipped,
    ): LibraryRepository {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return LibraryRepository(scope, provider, builtIns, SilentStorageLog)
    }

    // ── uniqueName ─────────────────────────────────────────────────────────

    @Test
    fun `a taken name is suffixed until it is free`() {
        val taken = mutableSetOf("My Beat")
        assertEquals("My Beat (2)", uniqueName("My Beat", taken))
        // The set was mutated by the first call, so the second has to step over
        // "(2)" as well — which is what keeps a batch unique against ITSELF.
        assertEquals("My Beat (3)", uniqueName("My Beat", taken))
    }

    @Test
    fun `a free name is left alone`() {
        assertEquals("My Beat", uniqueName("My Beat", mutableSetOf("Other")))
    }

    @Test
    fun `a suffix collision is stepped over rather than accepted`() {
        val taken = mutableSetOf("My Beat", "My Beat (2)")
        assertEquals("My Beat (3)", uniqueName("My Beat", taken))
    }

    @Test
    fun `editing a beat without renaming it does not suffix it further every save`() {
        runTest {
            val fake = FakeBeatsProvider()
            val repo = repository(fake)
            advanceUntilIdle()

            val first = repo.saveBeat(testBeat(name = "My Beat"))
            assertEquals("My Beat", first?.name)

            // Two more saves, both keeping the name. The trap: the beat's own name
            // is in the library by now, so de-duping against "every name" would
            // give "My Beat (2)" and then "My Beat (2) (2)".
            val second = repo.saveBeat(first!!.copy(bpm = 100))
            assertEquals("My Beat", second?.name)

            val third = repo.saveBeat(second!!.copy(bpm = 110))
            assertEquals("My Beat", third?.name)

            assertEquals(1, repo.state.value.customBeats.size)
            assertEquals(110, repo.state.value.customBeats.single().bpm)
        }
    }

    @Test
    fun `a custom beat cannot shadow a built-in name`() {
        runTest {
            val repo = repository(FakeBeatsProvider())
            advanceUntilIdle()
            assertEquals("Te Ta (2)", repo.saveBeat(testBeat(name = "Te Ta"))?.name)
        }
    }

    @Test
    fun `an imported batch stays unique against itself`() {
        runTest {
            val repo = repository(FakeBeatsProvider())
            advanceUntilIdle()

            val payload = ShareCodec.SharePayload.CategoryPayload(
                name = "Sunday kirtan",
                beats = listOf(
                    testBeat(name = "My Beat"),
                    testBeat(name = "My Beat"),
                    testBeat(name = "My Beat"),
                ),
            )
            val result = repo.importShared(payload)

            assertEquals(
                listOf("My Beat", "My Beat (2)", "My Beat (3)"),
                result.beats.map { it.name },
            )
        }
    }

    // ── Optimistic writes and rollback ─────────────────────────────────────

    @Test
    fun `a refused delete puts the beat back and says why`() {
        runTest {
            val fake = FakeBeatsProvider()
            val repo = repository(fake)
            advanceUntilIdle()
            val saved = repo.saveBeat(testBeat(name = "My Beat"))!!

            fake.failDeleteBeat = StorageError(
                StorageErrorCode.QUOTA,
                "There's no room left on this device.",
            )
            repo.deleteBeat(saved.id!!)

            assertEquals(
                "the beat must still be there",
                listOf(saved.id),
                repo.state.value.customBeats.map { it.id },
            )
            assertEquals("There's no room left on this device.", repo.state.value.error)
        }
    }

    @Test
    fun `a refused create leaves nothing behind`() {
        runTest {
            val fake = FakeBeatsProvider()
            fake.failCreateBeat = StorageError(
                StorageErrorCode.UNAVAILABLE,
                "This device wouldn't let the app write its storage.",
            )
            val repo = repository(fake)
            advanceUntilIdle()

            assertNull(repo.saveBeat(testBeat(name = "My Beat")))
            assertTrue(repo.state.value.customBeats.isEmpty())
            assertNotNull(repo.state.value.error)
        }
    }

    @Test
    fun `a refused reorder leaves the progression as it was`() {
        runTest {
            val a = testBeat(id = "a", name = "A")
            val b = testBeat(id = "b", name = "B")
            val fake = FakeBeatsProvider(
                beats = listOf(a, b),
                categories = listOf(testCategory(id = "c", beatIds = listOf("a", "b"))),
            )
            val repo = repository(fake)
            advanceUntilIdle()

            // The drag is painted first — that is the whole point of optimistic —
            // so the order is already the new one before the write is refused.
            fake.failUpdateCategory = StorageError(StorageErrorCode.NETWORK, "Couldn't reach the server.")
            repo.reorderCategory("c", "a", "b")

            assertEquals(listOf("a", "b"), repo.state.value.categories.single().beatIds)
            assertEquals("Couldn't reach the server.", repo.state.value.error)
        }
    }

    @Test
    fun `a reorder that succeeds keeps the new order`() {
        runTest {
            val fake = FakeBeatsProvider(
                beats = listOf(testBeat(id = "a"), testBeat(id = "b"), testBeat(id = "c")),
                categories = listOf(testCategory(id = "cat", beatIds = listOf("a", "b", "c"))),
            )
            val repo = repository(fake)
            advanceUntilIdle()

            repo.reorderCategory("cat", "a", "c")

            assertEquals(listOf("b", "c", "a"), repo.state.value.categories.single().beatIds)
            assertEquals(listOf("b", "c", "a"), fake.categories.single().beatIds)
        }
    }

    @Test
    fun `the active category is not rolled back when its write fails`() {
        runTest {
            val fake = FakeBeatsProvider()
            fake.failSetActiveCategory = StorageError(StorageErrorCode.NETWORK, "Couldn't reach the server.")
            val repo = repository(fake)
            advanceUntilIdle()

            repo.setActiveCategory(CUSTOM_CATEGORY)
            advanceUntilIdle()

            // A preference, not the user's work: snapping the tab back under them
            // is worse than the wrong tab coming back next launch.
            assertEquals(CUSTOM_CATEGORY, repo.state.value.activeCategoryId)
            assertNull("a failed preference write is not worth an error banner", repo.state.value.error)
        }
    }

    // ── Deleting a beat ────────────────────────────────────────────────────

    @Test
    fun `deleting a beat drops it from every progression that referenced it`() {
        runTest {
            val keep = testBeat(id = "keep", name = "Keep")
            val gone = testBeat(id = "gone", name = "Gone")
            val fake = FakeBeatsProvider(
                beats = listOf(keep, gone),
                categories = listOf(
                    testCategory(id = "one", beatIds = listOf("gone", "keep")),
                    testCategory(id = "two", beatIds = listOf("keep", "gone")),
                    testCategory(id = "three", beatIds = listOf("keep")),
                ),
            )
            val repo = repository(fake)
            advanceUntilIdle()

            repo.deleteBeat("gone")

            val stored = repo.state.value.categories.associate { it.id to it.beatIds }
            assertEquals(listOf("keep"), stored["one"])
            assertEquals(listOf("keep"), stored["two"])
            assertEquals("an unaffected progression is not rewritten", listOf("keep"), stored["three"])

            // And the store agrees — a category left pointing at a dead beat id is
            // the failure this exists to prevent.
            assertEquals(listOf("keep"), fake.categories.first { it.id == "one" }.beatIds)
            assertTrue(fake.beats.none { it.id == "gone" })
        }
    }

    @Test
    fun `deleting the category you are standing in falls back to the built-ins`() {
        runTest {
            val fake = FakeBeatsProvider(categories = listOf(testCategory(id = "cat")))
            val repo = repository(fake)
            advanceUntilIdle()
            repo.setActiveCategory("cat")

            repo.deleteCategory("cat")
            advanceUntilIdle()

            assertEquals(BUILTIN_CATEGORY, repo.state.value.activeCategoryId)
            assertTrue(repo.state.value.categories.isEmpty())
        }
    }

    // ── The merge and the virtual categories ───────────────────────────────

    @Test
    fun `built-ins are merged above whatever the provider returns`() {
        runTest {
            val mine = testBeat(id = "mine", name = "Mine")
            val repo = repository(FakeBeatsProvider(beats = listOf(mine)))
            advanceUntilIdle()

            assertEquals(shipped + mine, repo.allBeats.value)
            assertTrue("a shipped beat is not a custom one", repo.isCustomBeat("te_ta").not())
            assertTrue(repo.isCustomBeat("mine"))
        }
    }

    @Test
    fun `the two virtual categories are answered without being stored`() {
        runTest {
            val mine = testBeat(id = "mine", name = "Mine")
            val fake = FakeBeatsProvider(beats = listOf(mine))
            val repo = repository(fake)
            advanceUntilIdle()

            assertEquals(shipped, repo.categoryBeats(BUILTIN_CATEGORY))
            assertEquals(listOf(mine), repo.categoryBeats(CUSTOM_CATEGORY))
            assertEquals("Built in", repo.categoryName(BUILTIN_CATEGORY))
            assertEquals("Your beats", repo.categoryName(CUSTOM_CATEGORY))
            assertTrue("a virtual category must never be written", fake.categories.isEmpty())
        }
    }

    @Test
    fun `a progression drops ids that name no beat`() {
        runTest {
            val repo = repository(
                FakeBeatsProvider(
                    beats = listOf(testBeat(id = "mine", name = "Mine")),
                    categories = listOf(
                        testCategory(id = "cat", beatIds = listOf("mine", "deleted-long-ago", "te_ta")),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                listOf("mine", "te_ta"),
                repo.categoryBeats("cat").map { it.id },
            )
        }
    }

    @Test
    fun `an unknown category falls back to every beat`() {
        runTest {
            val repo = repository(FakeBeatsProvider(beats = listOf(testBeat(id = "mine"))))
            advanceUntilIdle()
            assertEquals(shipped.size + 1, repo.categoryBeats("no-such-category").size)
            assertEquals("Beats", repo.categoryName("no-such-category"))
        }
    }

    // ── Loading, failing to load, and swapping stores ──────────────────────

    @Test
    fun `a failed load leaves the app playable and says so`() {
        runTest {
            val fake = FakeBeatsProvider()
            fake.failLoadAll = StorageError(
                StorageErrorCode.UNAVAILABLE,
                "Your saved beats couldn't be read.",
            )
            val repo = repository(fake)
            advanceUntilIdle()

            val state = repo.state.value
            assertFalse("a failed load still settles; the splash gates on this", state.loading)
            assertEquals("Your saved beats couldn't be read.", state.error)
            // The built-ins are compiled in, so the drum still plays.
            assertEquals(shipped, repo.allBeats.value)
            assertEquals(shipped, repo.categoryBeats(BUILTIN_CATEGORY))
        }
    }

    @Test
    fun `swapping the provider reloads from the new store`() {
        runTest {
            val onDevice = testBeat(id = "device", name = "On device")
            val inCloud = testBeat(id = "cloud", name = "In the cloud")
            val local = FakeBeatsProvider(beats = listOf(onDevice))
            val cloud = FakeBeatsProvider(beats = listOf(inCloud))

            val repo = repository(local)
            advanceUntilIdle()
            assertEquals(listOf("device"), repo.state.value.customBeats.map { it.id })

            // This is the sign-in moment: the same repository, a different store.
            repo.attachProvider(cloud)
            advanceUntilIdle()

            assertEquals(listOf("cloud"), repo.state.value.customBeats.map { it.id })
            assertEquals(shipped + inCloud, repo.allBeats.value)

            // And writes go to the new store, not the old one.
            repo.saveBeat(testBeat(name = "After sign-in"))
            assertEquals(1, cloud.createBeatCalls)
            assertEquals(0, local.createBeatCalls + local.createBeatsCalls)
        }
    }

    @Test
    fun `a swap that fails to load does not leave the previous account's beats on screen`() {
        runTest {
            val signedIn = FakeBeatsProvider(beats = listOf(testBeat(id = "theirs", name = "Theirs")))
            val broken = FakeBeatsProvider()
            broken.failLoadAll = StorageError(StorageErrorCode.NETWORK, "Couldn't reach the server.")

            val repo = repository(signedIn)
            advanceUntilIdle()
            assertEquals(listOf("theirs"), repo.state.value.customBeats.map { it.id })

            repo.attachProvider(broken)
            advanceUntilIdle()

            // An empty shelf that says "couldn't be read" is honest; a full one
            // belonging to the account we just left is not.
            assertTrue(repo.state.value.customBeats.isEmpty())
            assertEquals("Couldn't reach the server.", repo.state.value.error)
            assertEquals(shipped, repo.allBeats.value)
        }
    }

    @Test
    fun `a stale load cannot overwrite a newer one`() {
        runTest {
            val first = FakeBeatsProvider(beats = listOf(testBeat(id = "first")))
            val second = FakeBeatsProvider(beats = listOf(testBeat(id = "second")))
            val repo = repository(first)
            advanceUntilIdle()

            repo.attachProvider(second)
            repo.attachProvider(first)
            advanceUntilIdle()

            assertEquals(
                "the last store attached is the one on screen",
                listOf("first"),
                repo.state.value.customBeats.map { it.id },
            )
        }
    }

    @Test
    fun `the stored active category is honoured`() {
        runTest {
            val repo = repository(
                FakeBeatsProvider(
                    categories = listOf(testCategory(id = "cat", name = "Sunday")),
                    activeCategoryId = "cat",
                ),
            )
            advanceUntilIdle()
            assertEquals("cat", repo.state.value.activeCategoryId)
            assertEquals("Sunday", repo.categoryName("cat"))
        }
    }

    // ── Importing ──────────────────────────────────────────────────────────

    @Test
    fun `an import is ONE batch write, not a loop of creates`() {
        runTest {
            val fake = FakeBeatsProvider()
            val repo = repository(fake)
            advanceUntilIdle()

            repo.importShared(
                ShareCodec.SharePayload.CategoryPayload(
                    name = "Sunday",
                    beats = listOf(testBeat(name = "A"), testBeat(name = "B"), testBeat(name = "C")),
                ),
            )

            assertEquals("the loop-of-creates version of this dropped beats", 1, fake.createBeatsCalls)
            assertEquals(0, fake.createBeatCalls)
            assertEquals(3, fake.beats.size)
        }
    }

    @Test
    fun `an imported beat never keeps the id it arrived with`() {
        runTest {
            val repo = repository(FakeBeatsProvider())
            advanceUntilIdle()

            // A stranger's link naming one of the user's own beats — or a built-in —
            // is exactly what ShareCodec's "never trust an id" rule is about.
            val result = repo.importShared(
                ShareCodec.SharePayload.BeatPayload(testBeat(id = "te_ta", name = "Stolen")),
            )

            val imported = result.beats.single()
            assertNotEquals("te_ta", imported.id)
            assertNotNull(imported.id)
            assertTrue("the built-in is still there, untouched", repo.state.value.customBeats.none { it.id == "te_ta" })
        }
    }

    @Test
    fun `a second category with the same name is suffixed`() {
        runTest {
            val fake = FakeBeatsProvider(categories = listOf(testCategory(id = "old", name = "Sunday")))
            val repo = repository(fake)
            advanceUntilIdle()

            // createCategory resolves with the id so the caller can land the user in
            // the new list; the NAME is what the de-duping applies to.
            val id = repo.createCategory("Sunday")

            assertNotNull(id)
            assertEquals("Sunday (2)", repo.state.value.categories.first { it.id == id }.name)
            assertEquals("Sunday (2)", fake.categories.first { it.id == id }.name)
            assertEquals("the existing category is untouched", "Sunday", fake.categories.first { it.id == "old" }.name)
        }
    }

    @Test
    fun `importing a playlist whose beats land but whose list does not keeps the beats`() {
        runTest {
            val fake = FakeBeatsProvider()
            fake.failCreateCategory = StorageError(StorageErrorCode.NETWORK, "Couldn't reach the server.")
            val repo = repository(fake)
            advanceUntilIdle()

            val result = repo.importShared(
                ShareCodec.SharePayload.CategoryPayload(
                    name = "Sunday",
                    beats = listOf(testBeat(name = "A")),
                ),
            )

            // The beats are genuinely saved; only the grouping failed. Unwinding a
            // successful write would lose the user's import over a cosmetic failure.
            assertEquals(1, result.beats.size)
            assertNull(result.categoryId)
            assertEquals(1, repo.state.value.customBeats.size)
            assertEquals("Couldn't reach the server.", repo.state.value.error)
        }
    }

    @Test
    fun `toggling a beat in and out of a progression`() {
        runTest {
            val fake = FakeBeatsProvider(
                beats = listOf(testBeat(id = "a")),
                categories = listOf(testCategory(id = "cat", beatIds = emptyList())),
            )
            val repo = repository(fake)
            advanceUntilIdle()

            repo.toggleBeatInCategory("cat", "a")
            assertEquals(listOf("a"), repo.state.value.categories.single().beatIds)

            repo.toggleBeatInCategory("cat", "a")
            assertTrue(repo.state.value.categories.single().beatIds.isEmpty())
        }
    }

    // ── First-sign-in migration ────────────────────────────────────────────

    @Test
    fun `a migrated progression is repointed at the new ids and keeps built-in ones`() {
        runTest {
            val repo = repository(FakeBeatsProvider())
            advanceUntilIdle()

            val result = repo.importLibrary(
                Library(
                    beats = listOf(testBeat(id = "old-1", name = "First"), testBeat(id = "old-2", name = "Second")),
                    categories = listOf(
                        // A real progression mixes a shipped id with local ones.
                        testCategory(id = "old-cat", name = "Sunday", beatIds = listOf("old-2", "te_ta", "old-1")),
                    ),
                    activeCategoryId = null,
                ),
            )

            assertEquals(2, result.beats)
            assertEquals(1, result.categories)
            assertFalse(result.failed)

            val moved = repo.state.value.customBeats.associateBy { it.name }
            val first = moved.getValue("First").id
            val second = moved.getValue("Second").id
            assertNotEquals("ids are minted by the new store", "old-1", first)

            assertEquals(
                "the order is the progression, and te_ta is the same everywhere",
                listOf(second, "te_ta", first),
                repo.state.value.categories.single().beatIds,
            )
        }
    }

    @Test
    fun `migrating an empty device library does nothing`() {
        runTest {
            val fake = FakeBeatsProvider()
            val repo = repository(fake)
            advanceUntilIdle()

            val result = repo.importLibrary(Library.EMPTY)

            assertEquals(0, result.beats)
            assertEquals(0, result.categories)
            assertEquals(0, fake.createBeatsCalls)
        }
    }

    @Test
    fun `an empty local library is reported as having nothing to offer`() {
        assertTrue(Library.EMPTY.hasContent().not())
        assertTrue(Library(listOf(testBeat(id = "a")), emptyList(), null).hasContent())
        assertTrue(Library(emptyList(), listOf(testCategory()), null).hasContent())
    }
}
