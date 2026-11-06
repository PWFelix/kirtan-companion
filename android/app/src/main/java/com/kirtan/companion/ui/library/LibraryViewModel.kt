package com.kirtan.companion.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kirtan.companion.container
import com.kirtan.companion.data.ShippedBeats
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Library
import com.kirtan.companion.storage.LibraryRepository
import com.kirtan.companion.storage.ShippedStatus
import com.kirtan.companion.storage.storageErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The library, as the screens see it.
 *
 * A thin window onto [LibraryRepository] rather than a second copy of its logic.
 * The repository already owns everything that was subtle in `useBeatLibrary.js` —
 * name de-duping, optimistic writes with rollback, minting ids, dropping a deleted
 * beat from every playlist that referenced it — so duplicating any of it here
 * would create two answers to the same question.
 *
 * WHAT THIS CLASS ADDS is only what a ViewModel must: lifecycle-scoped launching
 * of the repository's suspending writes, and the one piece of state that spans
 * screens and so belongs to neither — **which beat is loaded**. On the web that is
 * `beatId` in `App.jsx`, described there as "the one piece of state that spans
 * everything else", and it is the seam between the library and the transport.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.container
    private val repository: LibraryRepository = container.library

    val state: StateFlow<LibraryRepository.State> = repository.state

    /** Every beat: the built-in ones with the user's saved work merged above. */
    val allBeats: StateFlow<List<Beat>> = repository.allBeats

    /**
     * The built-in set alone, live.
     *
     * Collected separately from [allBeats] because a screen that shows only the
     * built-ins — the Beats screen's count, its section headings — must recompose
     * when a served set lands, and reading the count out of [allBeats] minus the
     * custom beats would be arithmetic on two things that change independently.
     */
    val builtInBeats: StateFlow<List<Beat>> = ShippedBeats.effective

    /** Where that set came from, and what the last check for updates said. */
    val shippedStatus: StateFlow<ShippedStatus> = container.shippedStatus

    /** False when this build has no server configured, so there is nothing to check. */
    val canCheckForUpdates: Boolean get() = container.shippedBeats != null

    /** True when the signed-in user may edit the built-in set for everyone. */
    val isMaintainer: StateFlow<Boolean> = container.isMaintainer

    /**
     * The beat currently loaded into the engine.
     *
     * Deliberately NOT derived from the transport's own state: the transport knows
     * what is playing, the library knows what is chosen, and they differ whenever a
     * beat is selected but not yet started. Keeping it here matches the web app,
     * where `App.jsx` owns `beatId` and hands it down to both.
     */
    private val _selectedBeat = kotlinx.coroutines.flow.MutableStateFlow<Beat?>(null)
    val selectedBeat: StateFlow<Beat?> = _selectedBeat

    init {
        // Seed the selection once the library settles, so the app always has
        // something loaded even before the user chooses. Falls back to the first
        // built-in, which is what the web app does on a fresh install.
        viewModelScope.launch {
            allBeats.collect { beats ->
                val current = _selectedBeat.value
                when {
                    current == null -> _selectedBeat.value = beats.firstOrNull()
                    // A beat that vanished (deleted, or filtered out) falls back
                    // to the head of the list.
                    beats.none { it.id == current.id } -> _selectedBeat.value = beats.firstOrNull()
                    // Otherwise REFRESH to the newest object with the same id —
                    // but only when it actually DIFFERS. Without the refresh,
                    // saving an edit left the selection pointing at the STALE
                    // instance: the library list showed the new pattern while
                    // Home's strip and the engine — both fed by the selection —
                    // kept the old one, which read as "my edits didn't hold".
                    //
                    // Without the difference check, a served built-in set landing
                    // mid-playback re-selects an equal beat, which re-loads the
                    // engine, which resets the sequencer's duplicate guard — and
                    // that guard is the only thing stopping a re-grid from
                    // double-hitting a stroke. A refresh that changes nothing must
                    // therefore change nothing.
                    else -> beats.firstOrNull { it.id == current.id }
                        ?.takeIf { it != current }
                        ?.let { _selectedBeat.value = it }
                }
            }
        }
    }

    /** The ordered beats of a category, including the virtual ones. */
    fun categoryBeats(categoryId: String): List<Beat> = repository.categoryBeats(categoryId)

    fun categoryName(categoryId: String): String = repository.categoryName(categoryId)

    fun isCustomBeat(id: String?): Boolean = repository.isCustomBeat(id)

    fun setActiveCategory(categoryId: String) = repository.setActiveCategory(categoryId)

    fun selectBeat(beat: Beat, fromCategory: String? = null) {
        _selectedBeat.value = beat
        // Picking a beat makes its tab the active cycling context — that is what
        // turns a category into a progression Home's ‹ › steps through.
        if (fromCategory != null) repository.setActiveCategory(fromCategory)
    }

    /**
     * Step through the active category, wrapping at the ends.
     *
     * Falls back to every beat when the category holds none, so the chevrons are
     * never dead — a control that silently does nothing reads as a broken app.
     *
     * Deliberately does NOT touch the transport: this ViewModel is data and the
     * engine is the transport's. The engine load for ANY selection change, cycled
     * or picked, is the KirtanApp bridge (selectedBeat → transport.loadBeat),
     * which is where the web's one-function selectBeat lives.
     */
    fun cycleBeat(direction: Int) {
        val categoryId = state.value.activeCategoryId
        val list = repository.categoryBeats(categoryId).ifEmpty { allBeats.value }
        if (list.isEmpty()) return
        val current = _selectedBeat.value
        val index = list.indexOfFirst { it.id == current?.id }
        val next = if (index == -1) 0 else ((index + direction) % list.size + list.size) % list.size
        _selectedBeat.value = list[next]
    }

    fun saveBeat(beat: Beat, onSaved: (Beat?) -> Unit) {
        viewModelScope.launch { onSaved(repository.saveBeat(beat)) }
    }

    fun deleteBeat(id: String) {
        viewModelScope.launch { repository.deleteBeat(id) }
    }

    fun createCategory(name: String, onCreated: (String?) -> Unit) {
        viewModelScope.launch { onCreated(repository.createCategory(name)) }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch { repository.deleteCategory(categoryId) }
    }

    fun toggleBeatInCategory(categoryId: String, beatId: String) {
        viewModelScope.launch { repository.toggleBeatInCategory(categoryId, beatId) }
    }

    fun reorderCategory(categoryId: String, activeId: String, overId: String) {
        viewModelScope.launch { repository.reorderCategory(categoryId, activeId, overId) }
    }

    /**
     * Import a shared payload. The callback reports what landed, including the id
     * of a newly created category, so the caller can navigate there and say "added
     * 3 beats to the list X" rather than silently switching tabs.
     */
    fun importShared(
        payload: com.kirtan.companion.data.ShareCodec.SharePayload?,
        onDone: (LibraryRepository.ImportResult) -> Unit = {},
    ) {
        viewModelScope.launch { onDone(repository.importShared(payload)) }
    }

    fun dismissError() = repository.dismissError()

    fun reload() {
        viewModelScope.launch { repository.reload() }
    }

    /**
     * Ask the server for the built-in set again — the user's button, as distinct
     * from the check every launch already makes on its own.
     *
     * Reports through [shippedStatus] rather than a callback: the answer is a
     * sentence that belongs under the button whether or not the user is still
     * looking at it, and a result that arrives after a navigation should not have
     * to be delivered to a composable that is gone.
     */
    fun checkForBeatUpdates() {
        val client = container.shippedBeats ?: return
        viewModelScope.launch { client.checkForUpdates(manual = true) }
    }

    /**
     * Save an edit of a BUILT-IN beat back onto its own row, for every install.
     *
     * Deliberately not [saveBeat]: that writes to the user's library, and a
     * built-in's id is not in it, so routing an edit there would silently fork a
     * private copy and leave the shipped beat wrong for everybody else. Which of the
     * two a save means is decided by the screen that opened the editor, not here —
     * only it knows whether the maintainer asked to customize or to correct.
     *
     * Reports as `(saved, message)` with exactly one of them null, so a caller can
     * show the sentence without also watching a separate error flow, and the
     * editor's own "stay open on failure" rule gets both outcomes from one call.
     */
    fun saveShippedBeat(beat: Beat, onDone: (Beat?, String?) -> Unit) {
        val client = container.shippedBeats
        if (client == null) {
            onDone(
                null,
                "This build has no server configured, so the built-in beats can't be edited.",
            )
            return
        }
        viewModelScope.launch {
            try {
                client.updateShippedBeat(beat)
                // Hand back the beat as the refetch now holds it, not the draft that
                // was saved: the caller selects the result, and the refetched one is
                // what every other install will get.
                onDone(builtInBeats.value.firstOrNull { it.id == beat.id } ?: beat, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(null, storageErrorMessage(e))
            }
        }
    }

    /** Retire a built-in beat for every install. */
    fun removeShippedBeat(beat: Beat, onDone: (Boolean, String?) -> Unit) {
        val client = container.shippedBeats
        if (client == null) {
            onDone(
                false,
                "This build has no server configured, so the built-in beats can't be edited.",
            )
            return
        }
        viewModelScope.launch {
            try {
                client.removeShippedBeat(beat)
                onDone(true, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(false, storageErrorMessage(e))
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(this[APPLICATION_KEY]!!) }
        }
    }
}
