package com.kirtan.companion.storage

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.Library

/**
 * An in-memory [BeatsProvider].
 *
 * `useBeatLibrary.js` takes its provider as a parameter "purely so a test can pass
 * a fake; nothing in the app supplies it", and [LibraryRepository] keeps that seam
 * for the same reason. This is the other half of it.
 *
 * It holds real lists rather than mocking an interface, because the behaviours
 * worth testing are the ones that come out of a store: an id minted on create, a
 * patch against a missing row refusing, a batch written in one go. A mock would
 * have all of that stubbed back in by hand, which is the same code with none of the
 * confidence.
 *
 * Failures are injected per method (`failCreateBeat`, `failDeleteBeat`, …) rather
 * than by a global switch, because the interesting question is always WHICH write
 * the store refused — a rollback test that fails the wrong call passes for the
 * wrong reason.
 */
internal class FakeBeatsProvider(
    beats: List<Beat> = emptyList(),
    categories: List<Category> = emptyList(),
    activeCategoryId: String? = null,
) : BeatsProvider {

    /** What is stored, as the next [loadAll] will report it. */
    var beats: List<Beat> = beats
        private set
    var categories: List<Category> = categories
        private set
    var activeCategoryId: String? = activeCategoryId
        private set

    // ── Failure injection ──────────────────────────────────────────────────

    var failLoadAll: StorageError? = null
    var failCreateBeat: StorageError? = null
    var failCreateBeats: StorageError? = null
    var failUpdateBeat: StorageError? = null
    var failDeleteBeat: StorageError? = null
    var failCreateCategory: StorageError? = null
    var failUpdateCategory: StorageError? = null
    var failDeleteCategory: StorageError? = null
    var failSetActiveCategory: StorageError? = null

    // ── Call counts ────────────────────────────────────────────────────────
    // The contract's "createBeats is plural on purpose" rule is only enforceable
    // by counting: a repository that loops over createBeat is indistinguishable
    // from one that batches by looking at the resulting state.

    var loadAllCalls = 0
        private set
    var createBeatCalls = 0
        private set
    var createBeatsCalls = 0
        private set
    var updateBeatCalls = 0
        private set
    var updateCategoryCalls = 0
        private set

    override suspend fun loadAll(): Library {
        loadAllCalls++
        failLoadAll?.let { throw it }
        return Library(beats, categories, activeCategoryId ?: BUILTIN_CATEGORY)
    }

    override suspend fun createBeat(draft: Beat): Beat {
        createBeatCalls++
        failCreateBeat?.let { throw it }
        val beat = draft.copy(id = newId())
        beats = beats + beat
        return beat
    }

    override suspend fun createBeats(drafts: List<Beat>): List<Beat> {
        createBeatsCalls++
        failCreateBeats?.let { throw it }
        val created = drafts.map { it.copy(id = newId()) }
        // One write for the whole batch, exactly as both real providers do it.
        beats = beats + created
        return created
    }

    override suspend fun updateBeat(id: String, patch: Beat): Beat {
        updateBeatCalls++
        failUpdateBeat?.let { throw it }
        val index = beats.indexOfFirst { it.id == id }
        if (index == -1) {
            throw StorageError(StorageErrorCode.NOT_FOUND, "That beat no longer exists.")
        }
        val beat = patch.copy(id = id)
        beats = beats.toMutableList().also { it[index] = beat }
        return beat
    }

    override suspend fun deleteBeat(id: String) {
        failDeleteBeat?.let { throw it }
        beats = beats.filterNot { it.id == id }
    }

    override suspend fun createCategory(draft: CategoryDraft): Category {
        failCreateCategory?.let { throw it }
        val category = Category(newId(), draft.name, draft.beatIds)
        categories = categories + category
        return category
    }

    override suspend fun updateCategory(id: String, patch: CategoryPatch): Category {
        updateCategoryCalls++
        failUpdateCategory?.let { throw it }
        val index = categories.indexOfFirst { it.id == id }
        if (index == -1) {
            throw StorageError(StorageErrorCode.NOT_FOUND, "That list no longer exists.")
        }
        val current = categories[index]
        val next = current.copy(
            name = patch.name ?: current.name,
            beatIds = patch.beatIds ?: current.beatIds,
        )
        categories = categories.toMutableList().also { it[index] = next }
        return next
    }

    override suspend fun deleteCategory(id: String) {
        failDeleteCategory?.let { throw it }
        categories = categories.filterNot { it.id == id }
    }

    override suspend fun setActiveCategory(id: String) {
        failSetActiveCategory?.let { throw it }
        activeCategoryId = id
    }
}
