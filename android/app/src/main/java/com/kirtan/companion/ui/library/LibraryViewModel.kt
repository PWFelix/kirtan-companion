package com.kirtan.companion.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kirtan.companion.container
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Library
import com.kirtan.companion.storage.LibraryRepository
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

    private val repository: LibraryRepository = app.container.library

    val state: StateFlow<LibraryRepository.State> = repository.state

    /** Every beat: the compiled-in ones with the user's saved work merged above. */
    val allBeats: StateFlow<List<Beat>> = repository.allBeats

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
                if (current == null || beats.none { it.id == current.id }) {
                    _selectedBeat.value = beats.firstOrNull()
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

    fun importShared(payload: com.kirtan.companion.data.ShareCodec.SharePayload?) {
        viewModelScope.launch { repository.importShared(payload) }
    }

    fun dismissError() = repository.dismissError()

    fun reload() {
        viewModelScope.launch { repository.reload() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(this[APPLICATION_KEY]!!) }
        }
    }
}
