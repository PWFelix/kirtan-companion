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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kirtan.companion.R
import com.kirtan.companion.Strings

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

    /** Item ids the signed-in user has upvoted; empty when signed out. */
    private val _myVotes = MutableStateFlow<Set<String>>(emptySet())
    val myVotes: StateFlow<Set<String>> = _myVotes.asStateFlow()

    /** Browse order; Top by default. */
    private val _sort = MutableStateFlow(CommunityClient.BrowseSort.TOP)
    val sort: StateFlow<CommunityClient.BrowseSort> = _sort.asStateFlow()

    fun setSort(sort: CommunityClient.BrowseSort) {
        _sort.value = sort
    }

    private val _scope = MutableStateFlow(CommunityClient.BrowseScope.EVERYTHING)
    val scope: StateFlow<CommunityClient.BrowseScope> = _scope.asStateFlow()

    fun setScope(scope: CommunityClient.BrowseScope) {
        if (_scope.value == scope) return
        _scope.value = scope
    }

    /** Show only beats with this tag, or null for everything. */
    private val _tag = MutableStateFlow<String?>(null)
    val tag: StateFlow<String?> = _tag.asStateFlow()

    fun setTag(tag: String?) {
        _tag.value = tag
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
     * Ids of the community items the library already holds a copy of.
     *
     * Read from the library itself rather than remembered at add time, so
     * deleting your copy brings the Add button back, and a copy made on another
     * device (it syncs with the beat) counts too (issue #11).
     */
    val inLibrary: StateFlow<Set<String>> = container.library.state
        .map { state -> state.customBeats.mapNotNullTo(HashSet()) { it.sourceItemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * True when the signed-in user may change the built-in beat set for everyone.
     *
     * Read from the container rather than probed here: the Beats screen gates its
     * own maintainer control on the same fact, and one probe per session in one
     * place is cheaper and cannot disagree with itself.
     */
    val isMaintainer: StateFlow<Boolean> = container.isMaintainer

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

    // ── Share status ───────────────────────────────────────────────────────
    // What Share says for each of the user's beats; see [ShareIndex].
    private val links = app.getSharedPreferences(LINKS_PREFS, android.content.Context.MODE_PRIVATE)
    private val _shareIndex = MutableStateFlow(
        ShareIndex(links = links.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()),
    )
    val shareIndex: StateFlow<ShareIndex> = _shareIndex.asStateFlow()

    init {
        viewModelScope.launch { session.collect { reloadMine() } }
        viewModelScope.launch {
            container.communityLibrary.originals.collect { originals ->
                _shareIndex.value = _shareIndex.value.copy(originals = originals)
            }
        }
    }

    /** Re-read the user's published beats; empty when signed out or offline. */
    private suspend fun reloadMine() {
        val client = community
        val me = session.value?.userId
        val mine = if (client == null || me == null) emptyList() else try {
            client.myPublished(me).mapNotNull { item ->
                (client.toImportPayload(item) as? ShareCodec.SharePayload.BeatPayload)?.let { item.id to it.beat }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unknown is safest as "not shared": Share stays usable, and a
            // duplicate is the worst case, as before this existed.
            emptyList()
        }
        _shareIndex.value = _shareIndex.value.copy(mine = mine)
    }

    private fun link(beatId: String?, itemId: String) {
        if (beatId == null) return
        links.edit().putString(beatId, itemId).apply()
        _shareIndex.value = _shareIndex.value.copy(links = _shareIndex.value.links + (beatId to itemId))
    }

    private var lastQuery = ""

    fun refresh(query: String = "") {
        lastQuery = query
        val client = community ?: return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val loaded = client.browse(query, _scope.value, tag = _tag.value, sort = _sort.value)
                _items.value = loaded
                // One extra query for the page, only when signed in, so the
                // arrows show your own votes. A failure just leaves them empty.
                val me = session.value?.userId
                _myVotes.value = if (me == null) {
                    emptySet()
                } else try {
                    client.myVotes(me, loaded.map { it.id })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptySet()
                }
            } catch (e: Exception) {
                _error.value = storageErrorMessage(e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Upvote an item, or take your vote back.
     *
     * Optimistic: the arrow and count move at once and go back if the write
     * fails. The list isn't reloaded, so nothing re-sorts under your finger.
     */
    fun toggleVote(item: PublishedItem, onDone: (String?) -> Unit) {
        val client = community ?: return
        val me = session.value
        if (me == null) {
            onDone(Strings.get(R.string.community_sign_in_to_vote))
            return
        }
        val on = item.id !in _myVotes.value
        fun apply(yes: Boolean) {
            _myVotes.value = if (yes) _myVotes.value + item.id else _myVotes.value - item.id
            _items.value = _items.value.map {
                if (it.id == item.id && it.voteCount != null) {
                    it.copy(voteCount = maxOf(0L, it.voteCount + if (yes) 1 else -1))
                } else it
            }
        }
        apply(on)
        viewModelScope.launch {
            try {
                client.vote(item.id, me.userId, on)
                onDone(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apply(!on)
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
        // The card hides Add once a copy exists; this guards a double tap that
        // lands before the library state catches up.
        if (item.id in inLibrary.value) {
            onDone(Strings.get(R.string.community_already_have, item.name))
            return
        }
        val payload = client.toImportPayload(item)
        if (payload == null) {
            onDone(Strings.get(R.string.community_unreadable))
            return
        }
        viewModelScope.launch {
            val result = container.library.importShared(payload, sourceItemId = item.id)
            val message = when {
                result.beats.isEmpty() -> Strings.get(R.string.community_nothing_added)
                result.categoryId != null ->
                    Strings.plural(R.plurals.community_added_list, result.beats.size, result.beats.size, item.name)
                else -> Strings.get(R.string.community_added_custom, item.name)
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
                onDone(Strings.get(R.string.community_removed, item.name))
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
            onDone(Strings.get(R.string.promote_not_list))
            return
        }
        viewModelScope.launch {
            try {
                client.promote(item, beat, heading.trim().ifEmpty { DEFAULT_HEADING })
                onDone(Strings.get(R.string.promote_done, beat.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(storageErrorMessage(e))
            }
        }
    }

    /** Publish one of your beats. Requires a session; the caller gates the button. */
    fun publishBeat(beat: Beat, onDone: (String?) -> Unit) {
        val client = community ?: return
        val current = session.value
        if (current == null) {
            onDone(Strings.get(R.string.community_sign_in_to_publish))
            return
        }
        when (val status = _shareIndex.value.status(beat)) {
            ShareStatus.Shared -> onDone(Strings.get(R.string.share_already_shared))
            ShareStatus.NotYours -> onDone(Strings.get(R.string.share_not_yours))
            ShareStatus.UnchangedCopy -> onDone(Strings.get(R.string.share_unchanged_copy))
            ShareStatus.New -> viewModelScope.launch {
                try {
                    val item = client.publish(ShareCodec.SharePayload.BeatPayload(beat), current.userId, current.displayName)
                    link(beat.id, item.id)
                    reloadMine()
                    refresh(lastQuery)
                    onDone(Strings.get(R.string.community_published, beat.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onDone(storageErrorMessage(e))
                }
            }
            is ShareStatus.Changed -> viewModelScope.launch {
                try {
                    client.updatePublishedBeat(status.itemId, beat)
                    reloadMine()
                    refresh(lastQuery)
                    onDone(Strings.get(R.string.share_updated, beat.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onDone(storageErrorMessage(e))
                }
            }
        }
    }

    /** Publish a whole progression as one snapshot. */
    fun publishCategory(name: String, beats: List<Beat>, onDone: (String?) -> Unit) {
        publish(ShareCodec.SharePayload.CategoryPayload(name, beats), name, onDone)
    }

    private fun publish(payload: ShareCodec.SharePayload, name: String, onDone: (String?) -> Unit) {
        val client = community ?: return
        val current = session.value
        if (current == null) {
            onDone(Strings.get(R.string.community_sign_in_to_publish))
            return
        }
        viewModelScope.launch {
            try {
                client.publish(payload, current.userId, current.displayName)
                refresh()
                onDone(Strings.get(R.string.community_published, name))
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

        /** Beat id → published item id, for "Share updated version". */
        private const val LINKS_PREFS = "community_share_links"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CommunityViewModel(this[APPLICATION_KEY]!!) }
        }
    }
}
