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
import com.kirtan.companion.storage.CommunityClient
import com.kirtan.companion.storage.PublishedItem
import com.kirtan.companion.storage.storageErrorMessage
import kotlinx.coroutines.CancellationException
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

    /**
     * The signed-in user's own stars per item id, so their row of stars shows
     * what THEY chose while the card's number shows the community's average.
     * Empty when signed out, which is what hides the tappable stars.
     */
    private val _myRatings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val myRatings: StateFlow<Map<String, Int>> = _myRatings.asStateFlow()

    private val _scope = MutableStateFlow(CommunityClient.BrowseScope.EVERYTHING)
    val scope: StateFlow<CommunityClient.BrowseScope> = _scope.asStateFlow()

    fun setScope(scope: CommunityClient.BrowseScope) {
        if (_scope.value == scope) return
        _scope.value = scope
    }

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
     * True when the signed-in user may change the built-in beat set for everyone.
     *
     * Resolved once per SESSION rather than per browse, because the flag only
     * decides whether a button exists and the search field re-queries on every
     * pause in typing — hanging it off [refresh] would turn a keystroke into a
     * second round trip. Fails closed: the database is what actually authorises the
     * write, so a wrong `false` here costs a maintainer one retry and a wrong
     * `true` would be a button that always fails.
     */
    private val _isMaintainer = MutableStateFlow(false)
    val isMaintainer: StateFlow<Boolean> = _isMaintainer.asStateFlow()

    init {
        viewModelScope.launch {
            session.collect { current ->
                _isMaintainer.value =
                    current != null && container.shippedBeats?.isMaintainer() == true
            }
        }
    }

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

    private var lastQuery = ""

    fun refresh(query: String = "") {
        lastQuery = query
        val client = community ?: return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val loaded = client.browse(query, _scope.value)
                _items.value = loaded
                // One extra query for the whole page, only when signed in: the
                // stars need to show the user's own choice, not the average.
                val me = session.value?.userId
                _myRatings.value = if (me == null) {
                    emptyMap()
                } else {
                    client.myRatings(me, loaded.map { it.id })
                }
            } catch (e: Exception) {
                _error.value = storageErrorMessage(e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Rate an item, or clear the rating by passing the star already chosen.
     *
     * Optimistic: the stars move immediately and the list reloads afterwards,
     * because the average lives on the parent row and only the server knows the
     * new total. A failed write restores the previous state via the reload.
     */
    fun rate(item: PublishedItem, stars: Int, onDone: (String?) -> Unit) {
        val client = community ?: return
        val me = session.value
        if (me == null) {
            onDone("Sign in to rate community beats.")
            return
        }
        val clearing = _myRatings.value[item.id] == stars
        viewModelScope.launch {
            try {
                client.rate(item.id, me.userId, if (clearing) null else stars)
                _myRatings.value = if (clearing) {
                    _myRatings.value - item.id
                } else {
                    _myRatings.value + (item.id to stars)
                }
                refresh(lastQuery)
                onDone(null)
            } catch (e: Exception) {
                onDone(storageErrorMessage(e))
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

    /**
     * The beat a card would promote, or null when it cannot be promoted.
     *
     * Only a single BEAT can become a built-in: a published progression is several
     * beats and one row, and splitting it would silently invent a set nobody
     * chose. Null also covers a snapshot this build cannot decode, which the card
     * already handles by not offering Add.
     */
    fun promotable(item: PublishedItem): Beat? =
        (community?.toImportPayload(item) as? ShareCodec.SharePayload.BeatPayload)?.beat

    /**
     * Make a community beat one of the built-ins, for every user of every install.
     *
     * The write goes to `shipped_beats`, whose insert policy is `is_maintainer()`,
     * so the server has the last word; [isMaintainer] only decides whether the
     * button is on screen. The refetch inside means the maintainer sees the beat
     * under Built in immediately rather than after a restart — the one part of this
     * feature worth confirming with your own eyes before telling anyone it shipped.
     *
     * @param heading the section the beat lands in, asked for because every
     *   promoted beat arriving in one bucket is a set nobody can browse.
     */
    fun promote(item: PublishedItem, heading: String, onDone: (String?) -> Unit) {
        val client = container.shippedBeats ?: return
        val beat = promotable(item)
        if (beat == null) {
            onDone("Only a single beat can become a built-in — not a list.")
            return
        }
        viewModelScope.launch {
            try {
                client.promote(item, beat, heading.trim().ifEmpty { DEFAULT_HEADING })
                onDone("“${beat.name}” is now one of the built-in beats.")
            } catch (e: CancellationException) {
                throw e
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
        /**
         * The section a promoted beat lands in when its maintainer leaves the
         * field blank. A real heading rather than an empty one: `heading` is
         * `not null` in the table, and a beat with no section is a beat the Beats
         * screen's heading loop never renders.
         */
        internal const val DEFAULT_HEADING = "Community"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CommunityViewModel(this[APPLICATION_KEY]!!) }
        }
    }
}
