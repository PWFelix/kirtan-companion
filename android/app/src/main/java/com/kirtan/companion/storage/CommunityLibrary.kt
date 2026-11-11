package com.kirtan.companion.storage

import android.content.Context
import com.kirtan.companion.data.model.Beat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the library's community beats in step with their originals.
 *
 * Whenever the set of community items the library refers to changes (launch,
 * an import, an edit that makes a copy), it fetches those originals once. From
 * them [CommunityTracking] answers "update available" / "no longer shared" and
 * whether an edited copy is still just the original. It also runs, once per
 * beat, the migration of imports made back when they were editable.
 *
 * Offline or without a backend everything reads as Unknown and nothing changes.
 */
class CommunityLibrary(
    context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val community: CommunityClient?,
) {
    private val log: StorageLog = AndroidStorageLog
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _originals = MutableStateFlow<Map<String, List<Beat>>?>(null)

    /** Item id → its current beats; null until loaded (or when it can't be). */
    val originals: StateFlow<Map<String, List<Beat>>?> = _originals.asStateFlow()

    init {
        if (community != null) {
            scope.launch {
                library.state
                    .filter { !it.loading }
                    .map { st -> st.customBeats.mapNotNull { it.sourceItemId ?: it.forkedFrom }.toSet() }
                    .distinctUntilChanged()
                    .collect { refresh(it) }
            }
        }
    }

    fun status(beat: Beat): CommunityTracking.Status = CommunityTracking.status(beat, _originals.value)

    fun isUnchangedCopy(beat: Beat): Boolean = CommunityTracking.isUnchangedCopy(beat, _originals.value)

    /** Bring a community beat up to its original. False when there is nothing to apply or the write failed. */
    suspend fun update(beat: Beat): Boolean {
        val upstream = (status(beat) as? CommunityTracking.Status.UpdateAvailable)?.upstream ?: return false
        return library.replaceBeat(CommunityTracking.applyUpdate(beat, upstream))
    }

    /** Re-check now (pull to refresh, opening the Community list). */
    fun refreshNow() {
        if (community == null) return
        scope.launch {
            refresh(library.state.value.customBeats.mapNotNull { it.sourceItemId ?: it.forkedFrom }.toSet())
        }
    }

    private suspend fun refresh(ids: Set<String>) = mutex.withLock {
        val client = community ?: return@withLock
        if (ids.isEmpty()) {
            _originals.value = emptyMap()
            return@withLock
        }
        val loaded = try {
            client.originals(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("[community] couldn't load originals", e)
            return@withLock
        }
        _originals.value = loaded
        migrate(loaded)
    }

    private suspend fun migrate(loaded: Map<String, List<Beat>>) {
        val checked = prefs.getStringSet(KEY_CHECKED, emptySet()).orEmpty().toMutableSet()
        val before = checked.size
        for (beat in library.state.value.customBeats) {
            val id = beat.id ?: continue
            val source = beat.sourceItemId ?: continue
            if (id in checked) continue
            val done = when (val m = CommunityTracking.migrate(beat, loaded[source])) {
                is CommunityTracking.Migration.Keep ->
                    m.sourceIndex == beat.sourceIndex || library.replaceBeat(beat.copy(sourceIndex = m.sourceIndex))
                CommunityTracking.Migration.Fork ->
                    library.replaceBeat(beat.copy(sourceItemId = null, sourceIndex = null, forkedFrom = source))
                CommunityTracking.Migration.Undecided -> false
            }
            if (done) checked += id
        }
        if (checked.size != before) prefs.edit().putStringSet(KEY_CHECKED, checked).apply()
    }

    private companion object {
        const val PREFS = "kirtan_community_library"
        const val KEY_CHECKED = "checked_beat_ids"
    }
}
