package com.kirtan.companion.ui.community

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.storage.LibraryJson
import kotlinx.serialization.json.JsonObject

/**
 * Whether one of the user's beats is already in the community library, so Share
 * can say "Already shared" (disabled) or "Share updated version" instead of
 * quietly publishing a duplicate.
 */
sealed interface ShareStatus {
    /** Not shared yet (or unpublished since): Share publishes a new item. */
    data object New : ShareStatus

    /** The community copy matches the beat exactly: nothing to do. */
    data object Shared : ShareStatus

    /** Shared before, edited since: Share updates [itemId] in place. */
    data class Changed(val itemId: String) : ShareStatus

    /** Added from the community: someone else's beat, never re-shared. */
    data object NotYours : ShareStatus

    /**
     * A copy of a community beat that is musically still the original (renamed
     * or retagged at most). Sharable once the music actually changes.
     */
    data object UnchangedCopy : ShareStatus

    /** True when Share may publish or update. */
    val canShare: Boolean get() = this is New || this is Changed
}

/**
 * What the status is worked out from: the user's own published beats (decoded)
 * and this device's record of which beat was published as which item.
 *
 * The record is per device because published snapshots carry no beat id (they
 * are stripped on upload, by design). A beat with no record still reads as
 * Shared when one of the user's items has exactly its content — which covers
 * shares made before the record existed, or from another device. Only "edited
 * since" needs the record, since a changed beat no longer matches anything.
 */
data class ShareIndex(
    /** (item id, the beat it holds) for every BEAT the user has published. */
    val mine: List<Pair<String, Beat>> = emptyList(),
    /** Local beat id → the community item it was published as. */
    val links: Map<String, String> = emptyMap(),
    /** The originals of the library's community beats and copies, when loaded. */
    val originals: Map<String, List<Beat>>? = null,
) {
    fun status(beat: Beat): ShareStatus {
        if (beat.isCommunity) return ShareStatus.NotYours
        if (com.kirtan.companion.storage.CommunityTracking.isUnchangedCopy(beat, originals)) return ShareStatus.UnchangedCopy
        val print = fingerprint(beat)
        val linkedId = beat.id?.let { links[it] }
        val linked = linkedId?.let { id -> mine.firstOrNull { it.first == id } }
        if (linked != null) {
            return if (fingerprint(linked.second) == print) ShareStatus.Shared else ShareStatus.Changed(linked.first)
        }
        return if (mine.any { fingerprint(it.second) == print }) ShareStatus.Shared else ShareStatus.New
    }

    companion object {
        /**
         * The beat as it would be published: wire shape, ids stripped. Compared
         * through the encoder rather than with `Beat.equals` so fields the wire
         * doesn't carry (built-in flags, ids) can't make a match look like a change.
         */
        fun fingerprint(beat: Beat): JsonObject =
            LibraryJson.encodeBody(beat.copy(id = null, sourceItemId = null, sourceIndex = null, forkedFrom = null))
    }
}
