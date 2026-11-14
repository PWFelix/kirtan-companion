package com.kirtan.companion.storage

import com.kirtan.companion.data.model.Beat
import kotlinx.serialization.json.JsonObject

/**
 * How a community beat in the library relates to the original it follows.
 *
 * A community beat ([Beat.isCommunity]) is read-only, so ANY difference from the
 * original means the author changed it: that is all "update available" needs, no
 * timestamps. Editing makes a copy in Your beats ([Beat.forkedFrom]), and that copy
 * can be shared only once it is musically different from the original — renaming
 * or retagging someone else's beat is not a new beat.
 *
 * Pure: tested without a network. [CommunityLibrary] feeds it.
 */
object CommunityTracking {

    sealed interface Status {
        /** Not checked yet (offline, signed out of a backend, still loading). */
        data object Unknown : Status
        data object Current : Status
        data class UpdateAvailable(val upstream: Beat) : Status
        /** The author unpublished it (or it left the list it came in). */
        data object Gone : Status
    }

    /**
     * What the update check compares: everything the author controls except the
     * name, which the import may have suffixed ("Steady 2 (2)") to keep library
     * names unique.
     */
    fun contentPrint(beat: Beat): JsonObject =
        LibraryJson.encodeBody(beat.copy(id = null, name = "", group = null, sourceItemId = null, sourceIndex = null, forkedFrom = null))

    /** The music alone: what makes a copy a different beat rather than a relabel. */
    fun musicalPrint(beat: Beat): JsonObject =
        contentPrint(beat.copy(note = "", description = null, tags = emptyList()))

    /** The original of [beat] inside its item's beats ([items]: item id → beats). */
    fun upstreamOf(beat: Beat, items: Map<String, List<Beat>>): Beat? {
        val list = items[beat.sourceItemId ?: return null] ?: return null
        val index = beat.sourceIndex
        return if (index == null) list.singleOrNull() else list.getOrNull(index)
    }

    /**
     * @param items the originals, or null when they couldn't be loaded. An item
     *   missing from a loaded map is GONE (the fetch asked for it by id).
     */
    fun status(beat: Beat, items: Map<String, List<Beat>>?): Status {
        if (!beat.isCommunity) return Status.Current
        if (items == null) return Status.Unknown
        val up = upstreamOf(beat, items) ?: return Status.Gone
        return if (contentPrint(up) == contentPrint(beat)) Status.Current else Status.UpdateAvailable(up)
    }

    /** [beat] brought up to [upstream]: the author's content, the library's identity and name. */
    fun applyUpdate(beat: Beat, upstream: Beat): Beat = upstream.copy(
        id = beat.id,
        name = beat.name,
        group = null,
        sourceItemId = beat.sourceItemId,
        sourceIndex = beat.sourceIndex,
        forkedFrom = null,
    )

    /**
     * True when an edited copy is still just the original (so it must not be
     * shared). [items] holds the originals; unknown originals don't block.
     */
    fun isUnchangedCopy(beat: Beat, items: Map<String, List<Beat>>?): Boolean {
        val from = beat.forkedFrom ?: return false
        val originals = items?.get(from) ?: return false
        val print = musicalPrint(beat)
        return originals.any { musicalPrint(it) == print }
    }

    // ── One-time migration of imports made before community beats were read-only ──

    sealed interface Migration {
        /** Untouched: stays a community beat, at [sourceIndex] in its list. */
        data class Keep(val sourceIndex: Int?) : Migration
        /** Edited in place back then: becomes the user's own copy. */
        data object Fork : Migration
        /** The original is gone: nothing to compare with, so leave it as it is. */
        data object Undecided : Migration
    }

    /**
     * Before this, imports were editable, so an old import that no longer matches
     * its original was edited by the user (originals could not change until
     * "Share updated version", released just before this). Matching is on the
     * music alone, ignoring the name the import may have suffixed.
     */
    fun migrate(beat: Beat, originals: List<Beat>?): Migration {
        if (originals.isNullOrEmpty()) return Migration.Undecided
        val print = musicalPrint(beat)
        val index = originals.indexOfFirst { musicalPrint(it) == print }
        return when {
            index < 0 -> Migration.Fork
            originals.size == 1 -> Migration.Keep(null)
            else -> Migration.Keep(index)
        }
    }
}
