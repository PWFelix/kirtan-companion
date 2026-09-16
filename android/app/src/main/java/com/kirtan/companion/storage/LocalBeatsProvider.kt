package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * The [BeatsProvider] backed by this device's DataStore.
 *
 * Ported from `src/storage/localStorageProvider.js`, and the only place in the
 * app that writes the user's beats to disk. That web file's rule carries over
 * verbatim: NOTHING OUTSIDE `storage/` MAY TOUCH THE STORE. There it meant no
 * `localStorage.getItem` outside `src/storage/`; here it means no read of
 * [Keys.BEATS] or [Keys.CATEGORIES] outside this package. Keeping the store
 * behind one class is the layer's entire job, and every screen that reaches past
 * it becomes one more place to keep in sync.
 *
 * Two things worth knowing about the implementation:
 *
 *  - EVERY WRITE IS A READ-MODIFY-WRITE OF A WHOLE LIST, because neither
 *    localStorage nor a preferences DataStore has a notion of a row. That is fine
 *    here (a library is tens of beats, and there is exactly one process writing)
 *    and it is deliberately hidden behind per-entity methods: the interface is
 *    shaped for the database this becomes, not for the key-value store it is.
 *    DataStore's `updateData` does make the read-modify-write ATOMIC, which the
 *    web version's `getItem`/`setItem` pair does not — two coroutines creating
 *    beats at once cannot lose one here.
 *
 *  - THE ERRORS ARE THE POINT. Writing to disk fails in ways that really happen:
 *    the device is out of space, the store file sits on unavailable storage, or
 *    the process is killed mid-write. The web code this layer replaced caught all
 *    of them and carried on, which is why a failed save used to look identical to
 *    a good one. Every failure below becomes a [StorageError] carrying a sentence
 *    a person can read.
 *
 * ── A CORRUPT STORE IS REFUSED, NOT WIPED ──
 * This is where the port deliberately departs from the web. `localStorageProvider`
 * falls back to `[]` when a value won't parse ("losing it is better than refusing
 * to start"), which is survivable there because the browser holds the only copy
 * and the user is looking at it. Here the NEXT write would persist that empty list
 * over the real one, turning a truncated blob into a deleted library. So a value
 * that won't decode throws instead: the read fails, the write never happens, the
 * bytes stay on disk, and [LibraryRepository] tells the user their beats could not
 * be read rather than showing them an empty shelf.
 *
 * The one wipe this class cannot prevent is DataStore's own: if the
 * `.preferences_pb` file is unreadable at the protobuf level, the library replaces
 * it with empty preferences before this code ever sees it. That case is
 * unrecoverable by definition — there are no bytes left to refuse on behalf of —
 * which is exactly why the JSON-level guard above is the one that matters.
 *
 * ── NO MIGRATION STEP ──
 * `src/storage/migrate.js` moves a v1 browser store into the v2 layout, rewriting
 * every id into a UUID and — the part that must not be got wrong — repointing every
 * category's `beatIds` through the same map so a progression survives. There is
 * nothing to migrate here: the first install of this app writes v2 directly, and
 * [Keys.SCHEMA_VERSION] is stamped so that the NEXT shape change has a guard to
 * hang off. If a migration is ever added, it belongs in this class, behind that
 * version check, and it must rewrite category references or progressions will
 * silently empty themselves.
 */
class LocalBeatsProvider(
    private val store: DataStore<Preferences>,
    private val log: StorageLog = AndroidStorageLog,
) : BeatsProvider {

    override suspend fun loadAll(): Library {
        val prefs = read()
        return Library(
            beats = beatsIn(prefs),
            categories = categoriesIn(prefs),
            activeCategoryId = prefs[Keys.ACTIVE_CATEGORY]?.takeIf { it.isNotBlank() }
                ?: BUILTIN_CATEGORY,
        )
    }

    override suspend fun createBeat(draft: Beat): Beat {
        // The id is minted OUTSIDE the write: DataStore may run the transform
        // again after an IOException, and minting inside would mean two ids for
        // one beat with no way to know which was stored.
        val beat = draft.copy(id = newId())
        mutate("that beat") { prefs, out ->
            out[Keys.BEATS] = LibraryJson.encodeBeats(beatsIn(prefs) + beat)
        }
        return beat
    }

    // One write for the whole batch — see the note on BeatsProvider.createBeats.
    override suspend fun createBeats(drafts: List<Beat>): List<Beat> {
        val beats = drafts.map { it.copy(id = newId()) }
        mutate("those beats") { prefs, out ->
            out[Keys.BEATS] = LibraryJson.encodeBeats(beatsIn(prefs) + beats)
        }
        return beats
    }

    override suspend fun updateBeat(id: String, patch: Beat): Beat {
        val beat = patch.copy(id = id)
        mutate("that beat") { prefs, out ->
            val beats = beatsIn(prefs)
            val index = beats.indexOfFirst { it.id == id }
            if (index == -1) throw notFound("That beat no longer exists.")
            out[Keys.BEATS] =
                LibraryJson.encodeBeats(beats.toMutableList().also { it[index] = beat })
        }
        return beat
    }

    override suspend fun deleteBeat(id: String) {
        mutate("that change") { prefs, out ->
            out[Keys.BEATS] = LibraryJson.encodeBeats(beatsIn(prefs).filterNot { it.id == id })
        }
    }

    override suspend fun createCategory(draft: CategoryDraft): Category {
        val category = Category(id = newId(), name = draft.name, beatIds = draft.beatIds)
        mutate("that list") { prefs, out ->
            out[Keys.CATEGORIES] = LibraryJson.encodeCategories(categoriesIn(prefs) + category)
        }
        return category
    }

    override suspend fun updateCategory(id: String, patch: CategoryPatch): Category =
        mutate("that list") { prefs, out ->
            val categories = categoriesIn(prefs)
            val index = categories.indexOfFirst { it.id == id }
            if (index == -1) throw notFound("That list no longer exists.")
            val current = categories[index]
            // A null field in the patch means "leave it alone" — see
            // CategoryPatch for why the two halves are patched separately.
            val next = current.copy(
                name = patch.name ?: current.name,
                beatIds = patch.beatIds ?: current.beatIds,
            )
            out[Keys.CATEGORIES] =
                LibraryJson.encodeCategories(categories.toMutableList().also { it[index] = next })
            next
        }

    override suspend fun deleteCategory(id: String) {
        mutate("that change") { prefs, out ->
            out[Keys.CATEGORIES] =
                LibraryJson.encodeCategories(categoriesIn(prefs).filterNot { it.id == id })
        }
    }

    override suspend fun setActiveCategory(id: String) {
        mutate("that change") { _, out -> out[Keys.ACTIVE_CATEGORY] = id }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    private suspend fun read(): Preferences = try {
        store.data.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw classify(e, "your beats")
    } catch (e: Exception) {
        throw unknown(e, "your beats")
    }

    /**
     * The stored beats, or a [StorageError] explaining why they could not be read.
     *
     * Called inside the write transform as well as on load, which is what makes a
     * corrupt store abort the write that would have overwritten it.
     */
    private fun beatsIn(prefs: Preferences): List<Beat> =
        LibraryJson.decodeBeats(prefs[Keys.BEATS]) ?: throw corrupt(Keys.BEATS.name)

    private fun categoriesIn(prefs: Preferences): List<Category> =
        LibraryJson.decodeCategories(prefs[Keys.CATEGORIES]) ?: throw corrupt(Keys.CATEGORIES.name)

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * Carries a value out of DataStore's write transform, which can only return
     * [Preferences]. `updateData` either writes or throws, so the transform always
     * runs before it returns normally.
     */
    private class Out<T> {
        var value: T? = null
    }

    /**
     * One atomic read-modify-write.
     *
     * [apply] reads the committed [Preferences], writes the new values into the
     * mutable copy, and returns whatever the caller needs back. It runs inside
     * DataStore's transaction, so it must not suspend and must not have side
     * effects beyond those two arguments — DataStore may run it a second time if
     * the write itself hit an [IOException], and everything here is written to be
     * idempotent under that.
     */
    private suspend fun <T> mutate(
        whileDoing: String,
        apply: (read: Preferences, write: MutablePreferences) -> T,
    ): T {
        val out = Out<T>()
        try {
            store.updateData { prefs ->
                prefs.toMutablePreferences().also { next ->
                    out.value = apply(prefs, next)
                    next[Keys.SCHEMA_VERSION] = SCHEMA_VERSION
                }
            }
            return checkNotNull(out.value) { "the store's write transform did not run" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageError) {
            throw e
        } catch (e: IOException) {
            throw classify(e, whileDoing)
        } catch (e: Exception) {
            throw unknown(e, whileDoing)
        }
    }

    // ── Errors ─────────────────────────────────────────────────────────────

    /**
     * Turn a disk failure into the code that describes it.
     *
     * The web version checks four spellings of "the store is full" because
     * browsers disagree about how to report it. Android reports it as an
     * [IOException] carrying the errno text, so that is what is matched here;
     * anything else is storage the app could not use, which is the same failure
     * the web calls "blocked".
     */
    private fun classify(error: IOException, whileDoing: String): StorageError {
        val text = error.message.orEmpty()
        val full = "ENOSPC" in text || "No space left" in text
        return if (full) {
            StorageError(
                StorageErrorCode.QUOTA,
                "There's no room left on this device, so that couldn't be saved. Freeing up some storage will fix it.",
                error,
            )
        } else {
            StorageError(
                StorageErrorCode.UNAVAILABLE,
                "This device wouldn't let the app write its storage, so $whileDoing couldn't be saved.",
                error,
            )
        }
    }

    private fun corrupt(key: String): StorageError = StorageError(
        StorageErrorCode.UNAVAILABLE,
        "Your saved beats couldn't be read — the file holding them is damaged. Nothing has been deleted; try again, and if it keeps happening the library may need to be reset.",
    ).also { log.warn("[storage] $key was unreadable and has NOT been overwritten", it) }

    private fun notFound(message: String): StorageError =
        StorageError(StorageErrorCode.NOT_FOUND, message)

    private fun unknown(cause: Throwable?, whileDoing: String): StorageError = StorageError(
        StorageErrorCode.UNKNOWN,
        "Something went wrong saving $whileDoing. It may not have been kept.",
        cause,
    ).also { if (cause != null) log.warn("[storage] write failed while saving $whileDoing", cause) }
}
