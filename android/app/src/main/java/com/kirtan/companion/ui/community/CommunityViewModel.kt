package com.kirtan.companion.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kirtan.companion.KirtanApplication
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.storage.AuthSession
import com.kirtan.companion.storage.PublishedItem
import com.kirtan.companion.storage.storageErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The community library: browse what everyone has published, copy it into your
 * library, and manage what you published yourself.
 *
 * Built on [com.kirtan.companion.storage.CommunityClient], which already existed
 * and was tested; this is only the surface it was missing on Android.
 *
 * Two rules from the client shape this screen:
 *  - Browsing needs NO identity (the select policy is `using (true)`), so a
 *    signed-out user can browse and even copy into device storage.
 *  - Copying goes through [com.kirtan.companion.storage.LibraryRepository.importShared],
 *    the SAME path a scanned share link uses, so a community beat and a linked
 *    beat cannot drift apart in how they are minted and de-duplicated. The copy
 *    counter bumps afterwards, best-effort, because the beat is already safely in
 *    the library by then and a count that didn't move is not worth an error.
 */
class CommunityViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as KirtanApplication).container
    private val community = container.community

    private val _items = MutableStateFlow<List<PublishedItem>>(emptyList())
    val items: StateFlow<List<PublishedItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Null when the build has no cloud configured; the screen says so plainly. */
    val session: StateFlow<AuthSession?> =
        container.supabase?.session ?: MutableStateFlow(null)

    /** False when this build has no cloud at all. */
    val available: Boolean get() = community != null

    /**
     * The beats a published snapshot holds, for the card's mini strip.
     *
     * Null when the snapshot will not decode — the card then shows its name and
     * author without a strip rather than pretending, and Add explains itself.
     */
    fun preview(item: PublishedItem): List<Beat>? =
        when (val payload = community?.toImportPayload(item)) {
            is ShareCodec.SharePayload.BeatPayload -> listOf(payload.beat)
            is ShareCodec.SharePayload.CategoryPayload -> payload.beats
            else -> null
        }

    fun refresh(query: String = "") {
        val client = community ?: return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _items.value = client.browse(query)
            } catch (e: Exception) {
                _error.value = storageErrorMessage(e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Copy a published item into the library, then bump its counter.
     *
     * The import decides the outcome message (new list, or added to Your beats);
     * the counter is fire-and-forget for the reason in the class comment.
     */
    fun add(item: PublishedItem, onDone: (String?) -> Unit) {
        val client = community ?: return
        val payload = client.toImportPayload(item)
        if (payload == null) {
            onDone("That item couldn't be read — it may have been published by a newer version of the app.")
            return
        }
        viewModelScope.launch {
            val result = container.library.importShared(payload)
            val message = when {
                result.beats.isEmpty() -> "Nothing was added."
                result.categoryId != null ->
                    "Added ${result.beats.size} beats as the list “${item.name}”."
                else -> "Added “${item.name}” to Your beats."
            }
            onDone(message)
            launch { client.incrementCopies(item.id) }
        }
    }

    fun unpublish(item: PublishedItem, onDone: (String?) -> Unit) {
        val client = community ?: return
        viewModelScope.launch {
            try {
                client.unpublish(item.id)
                _items.value = _items.value.filterNot { it.id == item.id }
                onDone("Removed “${item.name}” from the community library.")
            } catch (e: Exception) {
                onDone(storageErrorMessage(e))
            }
        }
    }

    /** Publish one of your beats. Requires a session; the caller gates the button. */
    fun publishBeat(beat: Beat, onDone: (String?) -> Unit) {
        publish(ShareCodec.SharePayload.BeatPayload(beat), beat.name, onDone)
    }

    /** Publish a whole progression as one snapshot. */
    fun publishCategory(name: String, beats: List<Beat>, onDone: (String?) -> Unit) {
        publish(ShareCodec.SharePayload.CategoryPayload(name, beats), name, onDone)
    }

    private fun publish(payload: ShareCodec.SharePayload, name: String, onDone: (String?) -> Unit) {
        val client = community ?: return
        val current = session.value
        if (current == null) {
            onDone("Sign in to publish to the community library.")
            return
        }
        viewModelScope.launch {
            try {
                client.publish(payload, current.userId, current.displayName)
                refresh()
                onDone("Published “$name” to the community library.")
            } catch (e: Exception) {
                onDone(storageErrorMessage(e))
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CommunityViewModel(this[APPLICATION_KEY]!!) }
        }
    }
}
