package com.kirtan.companion.storage

import com.kirtan.companion.data.ShareCodec
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
        const val RATINGS = "published_ratings"
        const val COLUMNS =
            "id, author_id, author_name, kind, name, payload, copies, " +
                "rating_sum, rating_count, created_at"
        const val ANONYMOUS_AUTHOR = "A devotee"
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
    enum class BrowseScope { EVERYTHING, NAMES, AUTHORS }

    /**
     * The community list, newest first.
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
    ): List<PublishedItem> {
        val rows = client.select(
            table = TABLE,
            columns = COLUMNS,
            filters = browseFilters(query, scope),
            order = "created_at.desc",
            limit = limit,
            requireSession = false,
            whileDoing = "load the community library",
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
                body = buildJsonObject {
                    put("beat", LibraryJson.encodeBody(payload.beat.copy(id = null)))
                }
            }
            is ShareCodec.SharePayload.CategoryPayload -> {
                kind = PublishedKind.PLAYLIST
                name = payload.name
                body = buildJsonObject {
                    put("name", payload.name)
                    put("beats", buildJsonArray {
                        payload.beats.forEach { add(LibraryJson.encodeBody(it.copy(id = null))) }
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
        val created = client.insert(TABLE, row, one = true, whileDoing = "publish that")
        return itemOf(created) ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            "The server didn't return the beat you just published.",
        )
    }

    /** Everything the signed-in user has published, so they can unpublish it. */
    suspend fun myPublished(authorId: String): List<PublishedItem> {
        val rows = client.select(
            table = TABLE,
            columns = COLUMNS,
            filters = listOf("author_id" to "eq.$authorId"),
            order = "created_at.desc",
            whileDoing = "load your shared beats",
        )
        return rowsOf(rows).mapNotNull { itemOf(it) }
    }

    /** Remove a published item. RLS lets only its author through. */
    suspend fun unpublish(id: String) {
        client.delete(TABLE, listOf("id" to "eq.$id"), "unpublish that")
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
                whileDoing = "count that copy",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[community] copy count not bumped for $id", e)
        }
    }

    /**
     * Set or clear the signed-in user's rating on an item.
     *
     * Upsert on `(item_id, user_id)`: one rating per person per item, changed by
     * tapping a different star. Null CLEARS — tapping your own star again is the
     * natural "take it back", and the unique constraint makes clearing a delete
     * rather than a zero, which would otherwise drag the average down forever.
     *
     * The counters on the parent row are maintained by a database trigger, so
     * nothing here has to remember to update them — and two people rating at
     * once cannot lose a count to a read-modify-write race.
     */
    suspend fun rate(itemId: String, userId: String, stars: Int?) {
        if (stars == null) {
            client.delete(
                RATINGS,
                listOf("item_id" to "eq.$itemId", "user_id" to "eq.$userId"),
                "remove that rating",
            )
            return
        }
        require(stars in 1..5) { "a rating is 1 to 5 stars, got $stars" }
        client.upsert(
            table = RATINGS,
            body = buildJsonObject {
                put("item_id", itemId)
                put("user_id", userId)
                put("stars", stars)
            },
            onConflict = "item_id,user_id",
            whileDoing = "save that rating",
        )
    }

    /**
     * The signed-in user's own stars per item, so their stars show what they
     * chose rather than the community's average. One query for the whole page.
     */
    suspend fun myRatings(userId: String, itemIds: List<String>): Map<String, Int> {
        if (itemIds.isEmpty()) return emptyMap()
        val rows = client.select(
            table = RATINGS,
            columns = "item_id,stars",
            filters = listOf(
                "user_id" to "eq.$userId",
                "item_id" to "in.(${itemIds.joinToString(",")})",
            ),
            whileDoing = "load your ratings",
        )
        return rowsOf(rows).mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj["item_id"]?.stringContent() ?: return@mapNotNull null
            val stars = (obj["stars"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: return@mapNotNull null
            id to stars
        }.toMap()
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
            ratingSum = (obj["rating_sum"] as? JsonPrimitive)?.longOrNull ?: 0L,
            ratingCount = (obj["rating_count"] as? JsonPrimitive)?.longOrNull ?: 0L,
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
    /** Sum of all stars, maintained by a database trigger. */
    val ratingSum: Long = 0,
    /** How many people rated it; zero means "nobody yet", not "zero stars". */
    val ratingCount: Long = 0,
    /** Epoch millis, or null when the server's timestamp could not be read. */
    val createdAt: Long?,
) {
    /** The community's average, or null when nobody has rated it yet. */
    val averageStars: Double?
        get() = if (ratingCount > 0) ratingSum.toDouble() / ratingCount else null
}

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
