package com.kirtan.companion.storage

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything the user has saved, and its persistence — the port of
 * `src/hooks/useBeatLibrary.js`, which is the ONLY consumer of a [BeatsProvider]
 * in the whole app.
 *
 * Two things live here, and keeping them apart is what makes the layer safe:
 *
 *  - CUSTOM BEATS: built or forked in the editor. The compiled-in
 *    [com.kirtan.companion.data.BEATS] are read-only, so every write touches only
 *    this list and a shipped beat can never be corrupted by a save.
 *  - CATEGORIES: ordered lists of beat ids, each one a kirtan PROGRESSION — the
 *    order is the order Home's ‹ › moves through them. Two categories are always
 *    present and are NEVER STORED: [BUILTIN_CATEGORY] and [CUSTOM_CATEGORY].
 *
 * ── BUILT-INS ARE MERGED HERE, NOT IN THE PROVIDER ──
 * Rule 2 of the contract. [allBeats] is built-ins first, then the user's, on every
 * read. The web hook also TAGS each built-in with `readOnly: true` at import so a
 * screen can decide "edit in place or fork?" from the beat alone; that tag is not
 * needed here because [Beat.readOnly] is a derived property of
 * [Beat.isBuiltIn] — the fact is in the type, so it cannot fall out of step with
 * the list it came from.
 *
 * ── WHY SOME WRITES AWAIT AND SOME DON'T ──
 * CREATES await, because the id is minted by the provider and the caller needs the
 * saved beat back in order to select it — its id may not have existed until a
 * moment ago. Everything else is OPTIMISTIC: state changes at once and persistence
 * happens behind it, because a drag-to-reorder that waited for a round trip would
 * feel broken. If an optimistic write fails the state goes back to what it was and
 * the error surfaces, which is the behaviour this whole layer exists to get right.
 * The code this replaced caught every storage failure and carried on, so a beat
 * saved into a full store appeared in the list and vanished on reload.
 *
 * ── SWAPPING THE STORE UNDER A RUNNING APP ──
 * `src/storage/index.js` is a module singleton and says so is enough only until the
 * provider has to change WHILE THE APP IS RUNNING — signed out → device, signed in
 * → cloud. That moment is here: [attachProvider] replaces the provider and reloads,
 * and every call site goes through this class so nothing else in the app notices.
 * A generation counter drops a stale load that finishes after a newer one started,
 * which is the race that otherwise signs a user back into the library they just
 * left.
 *
 * @param scope the coroutine scope the initial load, [allBeats] and the
 *   fire-and-forget preference write run in. The composition root's scope, so the
 *   repository lives as long as the process.
 * @param builtIns the compiled-in beats. A parameter, not a constant, so a test can
 *   hand over two beats and reason about de-duping without the whole shipped
 *   library in the way — the same reason the web hook takes its provider as one.
 */
class LibraryRepository(
    private val scope: CoroutineScope,
    initialProvider: BeatsProvider,
    private val builtIns: List<Beat> = BEATS,
    private val log: StorageLog = AndroidStorageLog,
) {

    /**
     * The library as one immutable snapshot.
     *
     * One object rather than five flows because these values are meaningless apart
     * from each other: a beat list without the categories that reference it, or an
     * active category without the list it was chosen from, is a state the UI cannot
     * render correctly. The web hook's eight `useState`s are the same fact spread
     * out, and the reason it needs refs to keep them coherent is exactly the reason
     * a single snapshot does not.
     */
    data class State(
        /** True until the first load settles. A failed load settles too. */
        val loading: Boolean = true,
        /** A sentence for the user, or null. See [storageErrorMessage]. */
        val error: String? = null,
        /** What the user has made. Never contains a built-in beat. */
        val customBeats: List<Beat> = emptyList(),
        val categories: List<Category> = emptyList(),
        /** May be [BUILTIN_CATEGORY] or [CUSTOM_CATEGORY], which are not rows. */
        val activeCategoryId: String = BUILTIN_CATEGORY,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Built-ins then the user's beats, in that order — the list every screen reads.
     * Derived once here so no screen can get the order or the merge wrong.
     */
    val allBeats: StateFlow<List<Beat>> = _state
        .map { builtIns + it.customBeats }
        .stateIn(scope, SharingStarted.Eagerly, builtIns)

    private val provider = MutableStateFlow(initialProvider)

    /**
     * Serialises the optimistic writes.
     *
     * Without it, two overlapping writes would each roll back to a snapshot the
     * other had already moved on from, and the loser's rollback would erase the
     * winner's change. The React version gets away without a lock because its state
     * updates are queued on one thread; here they are not.
     */
    private val writeMutex = Mutex()

    /** Bumped on every load so a stale one can recognise itself and stop. */
    private val generation = AtomicInteger()

    init {
        // Reads suspend now, so the library starts empty and fills in — the same
        // shape as the web hook's effect. The splash gates on [State.loading], so
        // the empty first frame is never shown.
        scope.launch { reload() }
    }

    // ── Loading and provider swapping ──────────────────────────────────────

    /**
     * Point the library at a different store and reload from it.
     *
     * Called on sign-in (with [SupabaseBeatsProvider]) and on sign-out (back to
     * [LocalBeatsProvider]). Does not block: the reload is launched, and the state is
     * reset to "loading, nothing known yet" at once.
     *
     * The reset is the point. Keeping the previous store's beats on screen while the
     * new one loads would show account A's library to whoever just signed in as
     * account B — and if the new load then FAILED, [reload] would leave those beats
     * in place with only an error banner to say they aren't really there. An empty
     * shelf that says "couldn't be read" is honest; a full one that belongs to
     * someone else is not.
     */
    fun attachProvider(next: BeatsProvider) {
        provider.value = next
        _state.value = State()
        scope.launch { reload() }
    }

    /**
     * (Re)read everything from the current provider.
     *
     * A FAILED LOAD IS NOT FATAL: the built-in beats are compiled in, so the app
     * still plays. The user just cannot see their own work, and needs telling that
     * rather than being shown an empty library. A failure leaves whatever was already
     * loaded in place — which is why [attachProvider] clears it up front when the
     * store itself changes, and why a plain retry after a dropped connection does not
     * blank the screen.
     */
    suspend fun reload() {
        val started = generation.incrementAndGet()
        _state.update { it.copy(loading = true, error = null) }
        try {
            val library = provider.value.loadAll()
            if (started != generation.get()) return
            _state.update {
                it.copy(
                    loading = false,
                    customBeats = library.beats,
                    categories = library.categories,
                    activeCategoryId = library.activeCategoryId?.takeIf { id -> id.isNotBlank() }
                        ?: BUILTIN_CATEGORY,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (started != generation.get()) return
            log.warn("[library] load failed", e)
            _state.update { it.copy(loading = false, error = storageErrorMessage(e)) }
        }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /** True when [id] is one of the user's beats rather than a shipped one. */
    fun isCustomBeat(id: String?): Boolean =
        id != null && _state.value.customBeats.any { it.id == id }

    /**
     * The ordered beats of a category: `"builtin"`, `"custom"`, or a stored id.
     *
     * The two virtual categories are answered here and are NOT rows anywhere —
     * [BUILTIN_CATEGORY] is every shipped beat and [CUSTOM_CATEGORY] is everything
     * the user made. An id that names no category falls back to [allBeats], which
     * is what the web hook does; it keeps a stale active-category preference from
     * leaving Home with nothing to play.
     *
     * A progression drops ids that name no beat, so a category can never hand the
     * transport a hole — which is also why [deleteBeat] has to clean the references
     * up rather than relying on this filter to hide them.
     */
    fun categoryBeats(categoryId: String): List<Beat> {
        val current = _state.value
        when (categoryId) {
            BUILTIN_CATEGORY -> return builtIns
            CUSTOM_CATEGORY -> return current.customBeats
        }
        val everything = builtIns + current.customBeats
        val category = current.categories.firstOrNull { it.id == categoryId } ?: return everything
        return category.beatIds.mapNotNull { id -> everything.firstOrNull { it.id == id } }
    }

    /** The heading for a category, including the two virtual ones. */
    fun categoryName(categoryId: String): String = when (categoryId) {
        BUILTIN_CATEGORY -> "Built in"
        CUSTOM_CATEGORY -> "Your beats"
        else -> _state.value.categories.firstOrNull { it.id == categoryId }?.name ?: "Beats"
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * Save from the editor.
     *
     * A beat with no id is new (or a fork of a built-in) and gets one minted; a
     * beat with an id already in the library is edited in place. An id we do NOT
     * recognise is treated as new rather than as an error — the alternative is
     * refusing to save the user's work over a bookkeeping mismatch.
     *
     * @return the saved beat, so the caller can select it, or null if it wasn't
     *   kept. Null is not a silent failure: [State.error] is set.
     */
    suspend fun saveBeat(newBeat: Beat): Beat? = writeMutex.withLock {
        val id = newBeat.id
        val current = _state.value

        // Every name in the library EXCEPT this beat's own — otherwise editing a
        // beat without renaming it would suffix it a little further on every save
        // ("My Beat (2)", then "My Beat (2) (2)"). Built-ins are included, so a
        // custom beat cannot shadow "Te Ta" either.
        val taken = LinkedHashSet(
            (builtIns + current.customBeats).filter { it.id != id }.map { it.name },
        )
        val fields = newBeat.copy(id = null, name = uniqueName(newBeat.name, taken))

        try {
            val editing = id != null && current.customBeats.any { it.id == id }
            val saved = if (id != null && editing) {
                provider.value.updateBeat(id, fields)
            } else {
                provider.value.createBeat(fields)
            }
            _state.update {
                it.copy(
                    customBeats = if (editing) {
                        it.customBeats.map { beat -> if (beat.id == id) saved else beat }
                    } else {
                        it.customBeats + saved
                    },
                    error = null,
                )
            }
            saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("[library] save failed", e)
            _state.update { it.copy(error = storageErrorMessage(e)) }
            null
        }
    }

    /**
     * Delete a beat, and drop it from every progression that referenced it.
     *
     * Two writes, not one: the beat list and each affected category are separate
     * rows as far as the provider is concerned. If the second fails the beat is
     * still gone and the categories are put back as they were — a progression
     * pointing at a missing beat, which [categoryBeats] filters out, is a smaller
     * wrong than a beat that refuses to die.
     */
    suspend fun deleteBeat(id: String) {
        writeMutex.withLock {
            val current = _state.value
            val affected = current.categories.filter { id in it.beatIds }
            val deleted = optimisticBeats(
                prev = current.customBeats,
                next = current.customBeats.filterNot { it.id == id },
                persist = { provider.value.deleteBeat(id) },
            )
            if (!deleted || affected.isEmpty()) return@withLock

            val next = current.categories.map { category ->
                if (id in category.beatIds) {
                    category.copy(beatIds = category.beatIds.filterNot { it == id })
                } else {
                    category
                }
            }
            optimisticCategories(prev = current.categories, next = next) {
                // One write per affected category, concurrently — the web hook's
                // Promise.all over the same list.
                coroutineScope {
                    affected.map { category ->
                        async {
                            provider.value.updateCategory(
                                category.id,
                                CategoryPatch(beatIds = category.beatIds.filterNot { it == id }),
                            )
                        }
                    }.awaitAll()
                }
            }
        }
    }

    /**
     * Add a decoded share payload — or a community copy, which arrives as the same
     * type — to the library. The ONLY way beats from outside this device get in.
     *
     * Two things it must do that a loop of [saveBeat] calls could not:
     *
     *  - MINT FRESH IDS. [ShareCodec] carries no id at all, precisely so a
     *    stranger's link cannot name one of the user's beats and quietly replace
     *    it. The provider mints them on create, which is the only place in the app
     *    ids come from.
     *  - WRITE ONCE PER LIST. Importing a category touches both beats and
     *    categories; calling [BeatsProvider.createBeat] in a loop would read stale
     *    state and drop beats, which is the bug [BeatsProvider.createBeats] exists
     *    to prevent.
     */
    suspend fun importShared(payload: ShareCodec.SharePayload?): ImportResult =
        writeMutex.withLock {
            val incoming: List<Beat> = when (payload) {
                is ShareCodec.SharePayload.BeatPayload -> listOf(payload.beat)
                is ShareCodec.SharePayload.CategoryPayload -> payload.beats
                // Nothing to import: a link that failed to decode, or none at all.
                else -> return@withLock ImportResult(emptyList(), null)
            }
            if (incoming.isEmpty()) return@withLock ImportResult(emptyList(), null)

            // Re-importing your own link shouldn't produce two rows with one name.
            val takenBeatNames = LinkedHashSet(
                (builtIns + _state.value.customBeats).map { it.name },
            )
            val drafts = incoming.map { beat ->
                beat.copy(id = null, name = uniqueName(beat.name, takenBeatNames))
            }

            val created = try {
                provider.value.createBeats(drafts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("[library] import failed", e)
                _state.update { it.copy(error = storageErrorMessage(e)) }
                return@withLock ImportResult(emptyList(), null)
            }
            _state.update { it.copy(customBeats = it.customBeats + created, error = null) }

            if (payload !is ShareCodec.SharePayload.CategoryPayload) {
                return@withLock ImportResult(created, null)
            }

            try {
                val takenCategoryNames = LinkedHashSet(_state.value.categories.map { it.name })
                val name = uniqueName(payload.name, takenCategoryNames)
                // A provider returns what it stored, and what it stored has an id;
                // mapNotNull only keeps the type honest about Beat.id being nullable.
                val beatIds = created.mapNotNull { it.id }
                val category = provider.value.createCategory(CategoryDraft(name, beatIds))
                _state.update { it.copy(categories = it.categories + category) }
                ImportResult(created, category.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The beats are genuinely saved; only the grouping failed. Keeping
                // them and saying so beats unwinding a successful write — they land
                // in "Your beats", which is where a single shared beat goes anyway.
                log.warn("[library] imported beats, but the list wasn't created", e)
                _state.update { it.copy(error = storageErrorMessage(e)) }
                ImportResult(created, null)
            }
        }

    /**
     * Bulk-move a whole local library into the CURRENT store — the first-sign-in
     * "bring your on-device beats to your account" step (`importLibrary` in the web
     * hook, fed by `migrateToCloud.js`).
     *
     * It is [importShared] writ large, and for the same reason: uploading mints
     * FRESH ids, so any playlist that referenced a local beat by its old id has to
     * be repointed to the new one. Built-in ids pass straight through — a playlist
     * can hold `"te_ta"` alongside a custom uuid, and built-ins are the same
     * everywhere. Names are de-duped against what the account already holds.
     *
     * @param local the device library, read from [LocalBeatsProvider] regardless of
     *   which provider is active. That is the whole point of `migrateToCloud.js`:
     *   the guest library has to be readable after the app has already switched to
     *   the cloud.
     */
    suspend fun importLibrary(local: Library): MigrationResult = writeMutex.withLock {
        if (local.beats.isEmpty() && local.categories.isEmpty()) {
            return@withLock MigrationResult(0, 0)
        }

        // Keep the old ids parallel to the drafts, so the returned rows — same
        // order — give us the old→new map the playlists below need. `map`, not
        // `mapNotNull`: dropping one null would shift every later index and repoint
        // the wrong playlists, which is a worse bug than the one it looks like it
        // avoids.
        val takenBeatNames = LinkedHashSet((builtIns + _state.value.customBeats).map { it.name })
        val oldIds: List<String?> = local.beats.map { it.id }
        val drafts = local.beats.map { beat ->
            beat.copy(id = null, name = uniqueName(beat.name, takenBeatNames))
        }

        val created = try {
            provider.value.createBeats(drafts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("[library] migration: beats failed", e)
            _state.update { it.copy(error = storageErrorMessage(e)) }
            return@withLock MigrationResult(0, 0, failed = true)
        }
        if (created.isNotEmpty()) {
            _state.update { it.copy(customBeats = it.customBeats + created) }
        }

        val idMap = HashMap<String, String>()
        created.forEachIndexed { index, beat ->
            val newId = beat.id ?: return@forEachIndexed
            oldIds.getOrNull(index)?.let { idMap[it] = newId }
        }

        // Playlists, with their beat ids repointed. Anything not in the map is a
        // built-in id and is left exactly as it was.
        val takenCategoryNames = LinkedHashSet(_state.value.categories.map { it.name })
        var madeCategories = 0
        try {
            for (category in local.categories) {
                val beatIds = category.beatIds.map { idMap[it] ?: it }
                val saved = provider.value.createCategory(
                    CategoryDraft(uniqueName(category.name, takenCategoryNames), beatIds),
                )
                _state.update { it.copy(categories = it.categories + saved) }
                madeCategories++
            }
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The beats made it; only some grouping didn't. Say so, keep the rest.
            log.warn("[library] migration: a playlist failed", e)
            _state.update { it.copy(error = storageErrorMessage(e)) }
        }
        MigrationResult(created.size, madeCategories)
    }

    /**
     * Create a category and return its id, so the caller can land the user in it.
     *
     * Same de-duping every beat gets: a second "Sunday kirtan" becomes
     * "Sunday kirtan (2)" rather than a twin the user cannot tell apart — a row
     * shows the name and nothing else.
     */
    suspend fun createCategory(name: String): String? = writeMutex.withLock {
        try {
            val taken = LinkedHashSet(_state.value.categories.map { it.name })
            val category = provider.value.createCategory(
                CategoryDraft(uniqueName(name, taken), emptyList()),
            )
            _state.update { it.copy(categories = it.categories + category, error = null) }
            category.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("[library] category not created", e)
            _state.update { it.copy(error = storageErrorMessage(e)) }
            null
        }
    }

    suspend fun deleteCategory(categoryId: String) {
        writeMutex.withLock {
            val current = _state.value
            val deleted = optimisticCategories(
                prev = current.categories,
                next = current.categories.filterNot { it.id == categoryId },
                persist = { provider.value.deleteCategory(categoryId) },
            )
            // Deleting the category you were standing in leaves Home with nothing to
            // cycle, so the built-ins take over. Only on success: a refused delete
            // must not move the user either.
            if (deleted && _state.value.activeCategoryId == categoryId) {
                setActiveCategory(BUILTIN_CATEGORY)
            }
        }
    }

    /** Add a beat to a progression, or remove it if it is already in there. */
    suspend fun toggleBeatInCategory(categoryId: String, beatId: String) {
        writeMutex.withLock {
            val category = _state.value.categories.firstOrNull { it.id == categoryId } ?: return@withLock
            val beatIds = if (beatId in category.beatIds) {
                category.beatIds.filterNot { it == beatId }
            } else {
                category.beatIds + beatId
            }
            patchCategory(categoryId, beatIds)
        }
    }

    /**
     * Reorder a progression after a drag.
     *
     * The order IS the progression, so this is the one write where the list's
     * contents are unchanged and only their sequence matters. A no-op when the two
     * ids are the same or either is missing, which is what a dropped drag looks
     * like.
     */
    suspend fun reorderCategory(categoryId: String, activeId: String, overId: String) {
        if (activeId == overId) return
        writeMutex.withLock {
            val category = _state.value.categories.firstOrNull { it.id == categoryId } ?: return@withLock
            val from = category.beatIds.indexOf(activeId)
            val to = category.beatIds.indexOf(overId)
            if (from == -1 || to == -1) return@withLock
            patchCategory(categoryId, arrayMove(category.beatIds, from, to))
        }
    }

    /**
     * Remember which category Home cycles within.
     *
     * NOT rolled back on failure, unlike everything else here: this is a preference,
     * not the user's work. Snapping the tab back under them would be a worse outcome
     * than the wrong tab being restored next launch — which is why it also does not
     * suspend, and why the write is launched rather than awaited.
     */
    fun setActiveCategory(categoryId: String) {
        _state.update { it.copy(activeCategoryId = categoryId) }
        scope.launch {
            try {
                provider.value.setActiveCategory(categoryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("[library] active category not saved", e)
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun patchCategory(categoryId: String, beatIds: List<String>) {
        val current = _state.value
        optimisticCategories(
            prev = current.categories,
            next = current.categories.map {
                if (it.id == categoryId) it.copy(beatIds = beatIds) else it
            },
            persist = { provider.value.updateCategory(categoryId, CategoryPatch(beatIds = beatIds)) },
        )
    }

    /**
     * Paint [next] immediately, persist behind it, and put [prev] back if the store
     * refuses. Returns whether it stuck.
     *
     * The rollback restores ONLY the beat list (and sets the error), never the whole
     * snapshot: an unrelated change that landed in between — the active category, a
     * rename — survives the revert instead of being rewound with it.
     */
    private suspend fun optimisticBeats(
        prev: List<Beat>,
        next: List<Beat>,
        persist: suspend () -> Unit,
    ): Boolean {
        _state.update { it.copy(customBeats = next) }
        return try {
            persist()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("[library] write failed", e)
            _state.update { it.copy(customBeats = prev, error = storageErrorMessage(e)) }
            false
        }
    }

    /** [optimisticBeats] for the category list. */
    private suspend fun optimisticCategories(
        prev: List<Category>,
        next: List<Category>,
        persist: suspend () -> Unit,
    ): Boolean {
        _state.update { it.copy(categories = next) }
        return try {
            persist()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("[library] write failed", e)
            _state.update { it.copy(categories = prev, error = storageErrorMessage(e)) }
            false
        }
    }

    /** What [importShared] landed, so the caller can select and navigate to it. */
    data class ImportResult(val beats: List<Beat>, val categoryId: String?)

    /**
     * What [importLibrary] moved, for a prompt that says "moved 4 beats and 2
     * playlists". [failed] is true only when the BEATS failed — a playlist that
     * didn't make it is reported through [State.error] with the beats kept.
     */
    data class MigrationResult(val beats: Int, val categories: Int, val failed: Boolean = false)
}

/**
 * A name nobody else in the library is using: "My Beat" → "My Beat (2)".
 *
 * NAMING IS THE LIBRARY'S JOB, not the editor's, because this is the only place
 * that knows what is already taken. It used to live inside the web app's import
 * path — so re-importing your own share link could not produce two identical rows —
 * while beats made in the editor got no such treatment, and two beats with one name
 * are genuinely indistinguishable in the list. Every way in now goes through here:
 * the editor, a fork of a built-in, and an import.
 *
 * MUTATES [taken], which is what lets a batch import stay unique against ITSELF and
 * not just against what was already saved. That is not an accident to be tidied
 * away: without it, importing a playlist of three beats all called "My Beat" would
 * produce three beats all called "My Beat (2)".
 */
internal fun uniqueName(wanted: String, taken: MutableSet<String>): String {
    var name = wanted
    var suffix = 2
    while (name in taken) {
        name = "$wanted ($suffix)"
        suffix++
    }
    taken.add(name)
    return name
}

/** dnd-kit's `arrayMove`: lift the element at [from] and put it at [to]. */
internal fun <T> arrayMove(list: List<T>, from: Int, to: Int): List<T> =
    list.toMutableList().apply { add(to, removeAt(from)) }

/**
 * True when there is anything on this device worth offering to a freshly signed-in
 * account — `hasLocalContent` in `src/storage/migrateToCloud.js`.
 */
internal fun Library.hasContent(): Boolean = beats.isNotEmpty() || categories.isNotEmpty()
