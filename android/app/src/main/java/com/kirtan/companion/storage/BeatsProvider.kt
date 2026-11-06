package com.kirtan.companion.storage

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.Library
import java.util.UUID

/**
 * THE CONTRACT between the app and wherever the user's saved work lives.
 *
 * Ported from `src/storage/BeatsProvider.js`. That file holds no storage code at
 * all — it is the shape every provider must implement, the error type they all
 * throw, and the id minter they all share — and neither does this one.
 * [LocalBeatsProvider] is the on-device implementation;
 * [SupabaseBeatsProvider] is the cloud twin; [LibraryRepository] is the only
 * consumer that is allowed to know which is which.
 *
 * THREE RULES, and the reason each exists:
 *
 *  1. EVERY METHOD SUSPENDS, even though the on-device store is local.
 *     This is the whole point of the layer. A remote provider can be dropped in
 *     without touching a single call site, because every caller already suspends.
 *     If any method here were synchronous, swapping the store would ripple into
 *     every screen that reads a beat.
 *
 *  2. A PROVIDER OWNS ONLY WHAT THE USER MADE. The beats compiled into
 *     [com.kirtan.companion.data.BEATS] are identical for every user and needed
 *     without waiting on I/O, so they are merged in one level up, in
 *     [LibraryRepository]. Putting them here would mean writing that merge again
 *     inside every future provider, and would leave [deleteBeat] having to
 *     refuse rows the provider never stored.
 *
 *  3. FAILURES THROW [StorageError]. They do not return `false` and they are not
 *     swallowed. The web code this layer replaced wrapped every write in
 *     `catch {}`, so a full or blocked store looked EXACTLY like a successful
 *     save: the beat appeared in the list and was gone on the next reload. Every
 *     method here throws instead, and [LibraryRepository] turns that into
 *     something the user can see and — for optimistic writes — into a rollback.
 *
 * ── THE INTERFACE ──
 *
 *     loadAll()                  → Library(beats, categories, activeCategoryId)
 *     createBeat(draft)          → Beat        (mints and returns the id)
 *     createBeats(drafts)        → List<Beat>  (ONE write, see below)
 *     updateBeat(id, patch)      → Beat
 *     deleteBeat(id)             → Unit
 *     createCategory(draft)      → Category    (mints and returns the id)
 *     updateCategory(id, patch)  → Category
 *     deleteCategory(id)         → Unit
 *     setActiveCategory(id)      → Unit
 *
 * [createBeats] is plural ON PURPOSE. Importing a shared category writes several
 * beats at once, and the loop-of-single-creates version of that has already been
 * a bug in the web app once: each call read stale state and dropped beats. One
 * call, one write — and a single batch insert when the provider is a network
 * call, which is the other half of why the shape is worth keeping.
 *
 * ── DRAFTS AND PATCHES ──
 * A beat's draft is just a [Beat] with `id == null`; the model already documents
 * that absence as "an unsaved editor draft", and the provider mints the real id.
 * A beat patch is a whole [Beat], because the sole caller (`saveBeat`) always
 * hands over the complete body — the cloud provider then replaces its jsonb blob
 * wholesale rather than merging, which is what keeps an editor that DELETED a
 * field from resurrecting it on the next save.
 *
 * Categories need two small types because [Category.id] is non-nullable: a
 * draft cannot be expressed as "a Category with no id" the way a beat's can.
 *
 * ── VIRTUAL CATEGORIES ──
 * [BUILTIN_CATEGORY] and [CUSTOM_CATEGORY] are pseudo-categories that were never
 * stored as rows. They may appear as an `activeCategoryId` — and did, in the web
 * app's v1 layout — so a provider must pass them through untouched rather than
 * treating them as a missing row.
 */
interface BeatsProvider {

    /** Everything the user has saved. Never includes a built-in beat. */
    suspend fun loadAll(): Library

    /** Save one new beat and return it with its minted id. `draft.id` is ignored. */
    suspend fun createBeat(draft: Beat): Beat

    /**
     * Save several new beats in ONE write and return them in the order given.
     * See the note on [createBeats] being plural in the header.
     */
    suspend fun createBeats(drafts: List<Beat>): List<Beat>

    /**
     * Overwrite the beat with [id] using the complete body in [patch], and return
     * what was stored. Throws [StorageError] with [StorageErrorCode.NOT_FOUND]
     * when there is no such beat — a patch against nothing must not look like a
     * successful save.
     */
    suspend fun updateBeat(id: String, patch: Beat): Beat

    /** Remove a beat. Removing one that is already gone is not an error. */
    suspend fun deleteBeat(id: String)

    /** Save one new category and return it with its minted id. */
    suspend fun createCategory(draft: CategoryDraft): Category

    /**
     * Apply the fields [patch] carries and return the stored category. Throws
     * with [StorageErrorCode.NOT_FOUND] when there is no such category.
     */
    suspend fun updateCategory(id: String, patch: CategoryPatch): Category

    /** Remove a category. Removing one that is already gone is not an error. */
    suspend fun deleteCategory(id: String)

    /** Remember which category Home cycles within. Accepts a virtual id. */
    suspend fun setActiveCategory(id: String)
}

/** The "all shipped beats" pseudo-category. Never stored as a row. */
internal const val BUILTIN_CATEGORY = "builtin"

/** The "everything you have made" pseudo-category. Never stored as a row. */
internal const val CUSTOM_CATEGORY = "custom"

/** What a new category needs: a name, and the progression it starts holding. */
data class CategoryDraft(
    val name: String,
    val beatIds: List<String> = emptyList(),
)

/**
 * A PARTIAL category write: a null field is left alone.
 *
 * Partial because the two things a caller may want to change are independent —
 * reordering a progression patches `beatIds` and must not touch the name, and a
 * rename must not touch the order. A whole-[Category] patch would make each caller
 * restate the half it did not mean to change, and the cloud provider would then
 * write both columns every time.
 *
 * Today's callers all patch [beatIds]; [name] exists because the ported contract
 * has it (`supabaseProvider.js` writes only the columns present in the patch) and
 * because a rename is the obvious next thing a playlist screen grows.
 */
data class CategoryPatch(
    val name: String? = null,
    val beatIds: List<String>? = null,
)

/**
 * Mint an id.
 *
 * The web version needs a fallback ladder: `crypto.randomUUID` only exists in a
 * SECURE CONTEXT (https or localhost), so opening the dev server from a phone on
 * the same wifi — `http://192.168.x.x:5173`, exactly how that app gets tested —
 * left it undefined and saving a beat threw on the one device it was designed
 * for. There is no equivalent hole here: [UUID.randomUUID] is backed by
 * `SecureRandom` at every API level this app supports, so there is no insecure
 * context to fall back from and no second code path to keep in step.
 *
 * The format is the same v4 UUID either way, which is what lets the cloud
 * `uuid` primary-key columns accept an id minted on either platform.
 */
internal fun newId(): String = UUID.randomUUID().toString()
