package com.kirtan.companion.storage

import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * The PUBLIC side of the backend: the community library the Browse screen shows.
 *
 * Ported from `src/storage/communityClient.js`, and separate from [BeatsProvider]
 * for the same reason it is there: this is a different surface with different
 * rules — ANYONE MAY READ IT (the `published_beats` select policy is
 * `using (true)`), only the author may write — so it is not per-user storage and
 * does not belong behind the provider seam.
 *
 * ── A PUBLISHED ITEM IS A SNAPSHOT ──
 * Publishing copies the whole shareable body into `published_beats.payload`, so
 * editing your private beat afterwards never rewrites what the community already
 * downloaded. That is why this client takes and returns whole beats rather than ids.
 *
 * ── WHY IT HANDS BACK A [ShareCodec.SharePayload] ──
 * [toImportPayload] converts a row into exactly the type a scanned share link
 * decodes to, so copying a community beat into the library is ONE CODE PATH:
 * [LibraryRepository.importShared] mints fresh ids, de-dupes the name and writes
 * the beats, and cannot tell whether they arrived by deep link or from the Browse
 * screen. That is not a coincidence worth losing — the two routes used to be
 * separate on the web and drifted.
 *
 * The table's word is "playlist" and the app's is "category"; [PublishedKind] is
 * the one place the two names meet.
 */
class CommunityClient(
    private val client: SupabaseClient,
    private val log: StorageLog = AndroidStorageLog,
) {

    private companion object {
        const val TABLE = "published_beats"
        const val VOTES = "published_votes"
        const val COLUMNS =
            "id, author_id, author_name, kind, name, payload, copies, created_at"
        const val ANONYMOUS_AUTHOR = "A devotee"
        const val ORIGINALS_BATCH = 50
        const val DEFAULT_LIMIT = 40
    }

    /**
     * What a community search matches against.
     *
     * Author search is a first-class scope rather than always matching both,
     * because "everything" makes a common surname drown the library: searching
     * for a beat called "Dadra" should not return every beat by someone whose
     * name contains it.
     */
    enum class BrowseScope(val wireId: String) { EVERYTHING("everything"), NAMES("names"), AUTHORS("authors") }

    /**
     * How Browse orders the list. TOP is votes decayed by age — see
     * `browse_published` in supabase/schema.sql — so a beat that's good now can
     * climb past one liked last year.
     */
    enum class BrowseSort(val wireId: String) { TOP("top"), NEW("new"), COPIES("copies") }

    /**
     * The community list, in [sort] order.
     *
     * Reads need no identity — RLS already allows the world — so this is the one
     * call that does not require a session. A signed-out user can browse; copying
     * something into their library is what needs an account (or lands on device
     * storage, which needs nothing).
     *
     * @param query matched per [scope]. EVERYTHING uses a PostgREST `or=(…)`,
     *   which is one round trip rather than two queries unioned client-side.
     */
    suspend fun browse(
        query: String = "",
        scope: BrowseScope = BrowseScope.EVERYTHING,
        limit: Int = DEFAULT_LIMIT,
        tag: String? = null,
        sort: BrowseSort = BrowseSort.TOP,
    ): List<PublishedItem> {
        // One server-side function does search, tag and sort (TOP needs now(),
        // which no plain PostgREST order can express).
        try {
            val rows = client.rpc(
                function = "browse_published",
                args = buildJsonObject {
                    put("q", query.trim().ifEmpty { null })
                    put("tag", tag)
                    put("sort", sort.wireId)
                    put("lim", limit)
                    put("scope", scope.wireId)
                },
                whileDoing = Strings.get(R.string.doing_load_community),
                requireSession = false,
            )
            return rowsOf(rows).mapNotNull { itemOf(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageError) {
            // Offline or signed-out problems are real: say so. Anything else most
            // likely means the database hasn't had the votes schema run yet, so
            // fall back to the plain query (no TOP, no counts) rather than fail.
            if (e.code == StorageErrorCode.NETWORK || e.code == StorageErrorCode.UNAVAILABLE) throw e
            log.warn("[community] browse_published unavailable, falling back", e)
        }
        val rows = client.select(
            table = TABLE,
            columns = COLUMNS,
            filters = browseFilters(query, scope) + tagFilter(tag),
            order = if (sort == BrowseSort.COPIES) "copies.desc" else "created_at.desc",
            limit = limit,
            requireSession = false,
            whileDoing = Strings.get(R.string.doing_load_community),
        )
        return rowsOf(rows).mapNotNull { itemOf(it) }
    }

    /**
     * Publish a beat or a playlist.
     *
     * Takes the same [ShareCodec.SharePayload] the share sheet builds, because
     * "share by link" and "share to the library" are the same act with different
     * transports. The ids in it are STRIPPED before upload: a published snapshot
     * must not hand a recipient an id that names one of the author's private beats,
     * which is rule 2 of `ShareCodec`'s trust boundary and applies just as much to
     * a row read back from a database as to one read off a link.
     *
     * @param authorName the display name to credit; the web app's "A devotee"
     *   stands in when the user has not set one.
     */
    suspend fun publish(
        payload: ShareCodec.SharePayload,
        authorId: String,
        authorName: String?,
    ): PublishedItem {
        val kind: PublishedKind
        val name: String
        val body: JsonObject
        when (payload) {
            is ShareCodec.SharePayload.BeatPayload -> {
                kind = PublishedKind.BEAT
                name = payload.beat.name
                // sourceItemId is cleared: publishing a copy makes a NEW item,
                // and the old item's id would make other people's cards wrong.
                body = buildJsonObject {
                    put("beat", LibraryJson.encodeBody(publishable(payload.beat)))
                }
            }
            is ShareCodec.SharePayload.CategoryPayload -> {
                kind = PublishedKind.PLAYLIST
                name = payload.name
                body = buildJsonObject {
                    put("name", payload.name)
                    put("beats", buildJsonArray {
                        payload.beats.forEach { add(LibraryJson.encodeBody(publishable(it))) }
                    })
                }
            }
            // Not a user-facing failure: `Invalid` is what a link that failed to
            // decode returns, and there is nothing to publish in it.
            ShareCodec.SharePayload.Invalid ->
                error("an invalid share payload cannot be published")
        }

        val row = buildJsonObject {
            put("author_id", authorId)
            put("author_name", authorName?.takeIf { it.isNotBlank() } ?: ANONYMOUS_AUTHOR)
            put("kind", kind.wireId)
            put("name", name)
            put("payload", body)
        }
        val created = client.insert(TABLE, row, one = true, whileDoing = Strings.get(R.string.doing_publish))
        return itemOf(created) ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            Strings.get(R.string.err_publish_no_echo),
        )
    }

    /**
     * The beat as it goes to the community: no local id, and none of the fields
     * that only mean something in the author's own library (where it came from,
     * what it was copied from). Tags stay — they are the author's.
     */
    fun publishable(beat: Beat): Beat =
        beat.copy(id = null, sourceItemId = null, sourceIndex = null, forkedFrom = null)

    /**
     * Replace the snapshot of a beat the user already published ("Share updated
     * version"), keeping the item's id, copies and ratings. RLS lets only its
     * author through. Ids are stripped exactly as [publish] strips them.
     */
    suspend fun updatePublishedBeat(id: String, beat: Beat): PublishedItem {
        val body = buildJsonObject {
            put("name", beat.name)
            put("payload", buildJsonObject {
                put("beat", LibraryJson.encodeBody(publishable(beat)))
            })
        }
        val updated = client.update(TABLE, body, listOf("id" to "eq.$id"), one = true, whileDoing = Strings.get(R.string.doing_publish))
        return itemOf(updated) ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            Strings.get(R.string.err_publish_no_echo),
        )
    }

    /** Everything the signed-in user has published, so they can unpublish it. */
    suspend fun myPublished(authorId: String): List<PublishedItem> {
        val rows = client.select(
            table = TABLE,
            columns = COLUMNS,
            filters = listOf("author_id" to "eq.$authorId"),
            order = "created_at.desc",
            whileDoing = Strings.get(R.string.doing_load_shared),
        )
        return rowsOf(rows).mapNotNull { itemOf(it) }
    }

    /**
     * The current originals of the community beats in the library: item id → its
     * beats (one for a beat, the list's beats for a playlist). An id absent from
     * the result was unpublished. Ids go in batches to keep URLs short.
     */
    suspend fun originals(itemIds: Collection<String>): Map<String, List<Beat>> {
        val out = LinkedHashMap<String, List<Beat>>()
        for (batch in itemIds.distinct().chunked(ORIGINALS_BATCH)) {
            val rows = client.select(
                table = TABLE,
                columns = COLUMNS,
                filters = listOf("id" to "in.(${batch.joinToString(",")})"),
                requireSession = false,
                whileDoing = Strings.get(R.string.doing_load_community),
            )
            for (item in rowsOf(rows).mapNotNull { itemOf(it) }) {
                out[item.id] = when (val p = toImportPayload(item)) {
                    is ShareCodec.SharePayload.BeatPayload -> listOf(p.beat)
                    is ShareCodec.SharePayload.CategoryPayload -> p.beats
                    else -> continue
                }
            }
        }
        return out
    }

    /** Remove a published item. RLS lets only its author through. */
    suspend fun unpublish(id: String) {
        client.delete(TABLE, listOf("id" to "eq.$id"), Strings.get(R.string.doing_unpublish))
    }

    /**
     * Bump the copy counter when someone adds a community item to their library.
     *
     * BEST-EFFORT, and the one method here that swallows a failure: the beat is
     * already in the user's library by the time this runs, and a count that didn't
     * move is not worth telling them their import failed over. The web client wraps
     * this in a `try` with a `console.warn` for exactly that reason.
     */
    suspend fun incrementCopies(id: String) {
        try {
            client.rpc(
                function = "increment_published_copies",
                args = buildJsonObject { put("pub_id", id) },
                whileDoing = Strings.get(R.string.doing_count_copy),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[community] copy count not bumped for $id", e)
        }
    }

    /**
     * Upvote an item, or take the vote back.
     *
     * The primary key `(item_id, user_id)` is the one-vote rule, so voting twice
     * is a no-op upsert and taking it back is a delete. `vote_count` on the item
     * follows by trigger: two people voting at once can't lose a count.
     */
    suspend fun vote(itemId: String, userId: String, on: Boolean) {
        if (!on) {
            client.delete(
                VOTES,
                listOf("item_id" to "eq.$itemId", "user_id" to "eq.$userId"),
                Strings.get(R.string.doing_save_vote),
            )
            return
        }
        client.upsert(
            table = VOTES,
            body = buildJsonObject {
                put("item_id", itemId)
                put("user_id", userId)
            },
            onConflict = "item_id,user_id",
            whileDoing = Strings.get(R.string.doing_save_vote),
        )
    }

    /** Which of [itemIds] the signed-in user has upvoted. One query for the page. */
    suspend fun myVotes(userId: String, itemIds: List<String>): Set<String> {
        if (itemIds.isEmpty()) return emptySet()
        val rows = client.select(
            table = VOTES,
            columns = "item_id",
            filters = listOf(
                "user_id" to "eq.$userId",
                "item_id" to "in.(${itemIds.joinToString(",")})",
            ),
            whileDoing = Strings.get(R.string.doing_load_community),
        )
        return rowsOf(rows).mapNotNull { (it as? JsonObject)?.get("item_id")?.stringContent() }.toSet()
    }

    /**
     * A published row as the payload [LibraryRepository.importShared] consumes.
     *
     * Null when the row's snapshot will not decode — an item published by a future
     * app version, or a damaged payload. Refusing one card in the list is right;
     * importing a half-decoded beat into the user's library is not.
     *
     * Ids are stripped on the way out, so a recipient's library can never be
     * addressed by a stranger's row even if the snapshot carried an id.
     */
    fun toImportPayload(item: PublishedItem): ShareCodec.SharePayload? {
        return when (item.kind) {
            PublishedKind.BEAT -> {
                val beat = LibraryJson.decodeBeat(item.payload["beat"]) ?: return null
                ShareCodec.SharePayload.BeatPayload(beat.copy(id = null))
            }
            PublishedKind.PLAYLIST -> {
                // One undecodable beat rejects the whole playlist, for the reason
                // ShareCodec gives: a half-imported progression is worse than a
                // refused one, because the user cannot tell which beats are missing.
                val beats = (item.payload["beats"] as? JsonArray)?.map { element ->
                    (LibraryJson.decodeBeat(element) ?: return null).copy(id = null)
                }
                if (beats.isNullOrEmpty()) return null
                val name = item.payload["name"]?.stringContent()?.takeIf { it.isNotBlank() }
                    ?: item.name
                ShareCodec.SharePayload.CategoryPayload(name, beats)
            }
        }
    }

    /** One row as a [PublishedItem], or null when it is not shaped like one. */
    private fun itemOf(row: JsonElement?): PublishedItem? {
        val obj = row as? JsonObject ?: return null
        val id = obj["id"]?.stringContent() ?: return null
        val kind = PublishedKind.fromWire(obj["kind"]?.stringContent()) ?: return null
        return PublishedItem(
            id = id,
            authorId = obj["author_id"]?.stringContent(),
            authorName = obj["author_name"]?.stringContent(),
            kind = kind,
            name = obj["name"]?.stringContent().orEmpty(),
            payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            copies = (obj["copies"] as? JsonPrimitive)?.longOrNull ?: 0L,
            voteCount = (obj["vote_count"] as? JsonPrimitive)?.longOrNull,
            createdAt = parseTimestamp(obj["created_at"]?.stringContent()),
        )
    }
}

/** Whether a published snapshot holds one beat or a whole progression. */
enum class PublishedKind(val wireId: String) {
    BEAT("beat"),
    PLAYLIST("playlist"),
    ;

    companion object {
        fun fromWire(wireId: String?): PublishedKind? =
            entries.firstOrNull { it.wireId == wireId }
    }
}

/**
 * One row of the community library.
 *
 * Not `data.model.PublishedBeat`, which carries a single `beat` and so cannot
 * represent a published PLAYLIST, nor a copy count — both of which the Browse
 * screen needs. This is the row as the table holds it; [CommunityClient.toImportPayload]
 * is what turns it into beats.
 */
data class PublishedItem(
    val id: String,
    val authorId: String?,
    val authorName: String?,
    val kind: PublishedKind,
    val name: String,
    /** The snapshot: `{ beat }` or `{ name, beats }`, in [LibraryJson]'s shape. */
    val payload: JsonObject,
    val copies: Long,
    /** Upvotes, kept by a database trigger. Null before the votes schema exists. */
    val voteCount: Long? = null,
    /** Epoch millis, or null when the server's timestamp could not be read. */
    val createdAt: Long?,
)

/**
 * A Postgres `timestamptz` as epoch millis.
 *
 * PostgREST sends an offset (`+00:00`) rather than a `Z`, which `Instant.parse`
 * refuses — `OffsetDateTime` takes both. Null rather than a throw, because a
 * missing timestamp changes how a card sorts and nothing else.
 */
internal fun parseTimestamp(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

private fun rowsOf(body: JsonElement?): List<JsonElement> = (body as? JsonArray) ?: emptyList()

/**
 * The PostgREST filters for a community search.
 *
 * Top-level and pure so the author-search wiring is testable without a network
 * or a client: EVERYTHING is a single `or=(…)` rather than two queries unioned
 * client-side, and an empty query means "no filters" rather than a filter that
 * matches nothing.
 */
/**
 * Only beats carrying [tag] — a JSON containment test on the snapshot's tags.
 * Playlists have no single tag set, so a tag filter shows beats only.
 */
internal fun tagFilter(tag: String?): List<Pair<String, String>> =
    if (tag.isNullOrBlank()) emptyList() else listOf("payload->beat->tags" to "cs.[\"$tag\"]")

internal fun browseFilters(
    query: String,
    scope: CommunityClient.BrowseScope,
): List<Pair<String, String>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val like = "ilike.%$trimmed%"
    return when (scope) {
        CommunityClient.BrowseScope.NAMES -> listOf("name" to like)
        CommunityClient.BrowseScope.AUTHORS -> listOf("author_name" to like)
        CommunityClient.BrowseScope.EVERYTHING -> listOf("or" to "(name.$like,author_name.$like)")
    }
}
