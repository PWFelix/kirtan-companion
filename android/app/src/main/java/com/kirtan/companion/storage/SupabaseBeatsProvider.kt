package com.kirtan.companion.storage

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.Library
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * The [BeatsProvider] backed by Supabase — the cloud twin of
 * [LocalBeatsProvider], implementing the SAME contract so [LibraryRepository]
 * cannot tell them apart.
 *
 * Ported from `src/storage/supabaseProvider.js`, including its shape decisions:
 *
 *  - IT IS CREATED WITH THE SIGNED-IN USER'S ID, because inserts must stamp
 *    `user_id` for the Row-Level Security check to pass (`auth.uid() = user_id`).
 *    Reads do not need the id — RLS already hides other people's rows — but every
 *    query filters by it anyway, to hit the index and to read the same way it
 *    writes.
 *
 *  - A BEAT'S WHOLE BODY LIVES IN THE `data` JSONB COLUMN; `name` is duplicated
 *    into its own column purely so the database can sort and search on it. A row
 *    becomes an app beat as "the column id, plus the blob" — which is why
 *    [rowToBeat] copies the id over whatever the blob claimed, exactly as the web
 *    provider's `{ id: row.id, ...row.data }` does. Playlists are COLUMNAR
 *    (`name`, `beat_ids`) because the app patches those two independently.
 *
 *  - ERRORS ARE THE POINT. Every failure becomes a [StorageError] with one of the
 *    codes the UI already handles, so a dropped connection or an RLS rejection
 *    surfaces as a sentence a person can read — never a silent loss. The mapping
 *    lives in [restFailure], beside the transport it describes.
 *
 * ── WHAT IS NOT HERE ──
 * The web provider's clock-skew retry is in [SupabaseClient.send], because it is a
 * property of talking to GoTrue at all rather than of these tables; the community
 * library is [CommunityClient], because it is a different surface with different
 * rules (anyone may read it, only the author may write) and is not per-user
 * storage.
 */
class SupabaseBeatsProvider(
    private val client: SupabaseClient,
    private val userId: String,
) : BeatsProvider {

    private val ownerFilter get() = listOf("user_id" to "eq.$userId")

    override suspend fun loadAll(): Library = coroutineScope {
        // Three reads, in parallel — the web provider's Promise.all. They are
        // independent, and on a phone connection the difference between three
        // round trips and one is the whole cost of opening the library.
        val beats = async {
            client.select(
                table = "beats",
                columns = "id, name, data",
                filters = ownerFilter,
                order = "created_at.asc",
                whileDoing = "your beats",
            )
        }
        val playlists = async {
            client.select(
                table = "playlists",
                columns = "id, name, beat_ids",
                filters = ownerFilter,
                order = "created_at.asc",
                whileDoing = "your playlists",
            )
        }
        val profile = async { activeCategoryId() }

        Library(
            beats = rowsOf(beats.await()).map { rowToBeat(it) },
            categories = rowsOf(playlists.await()).map { rowToCategory(it) },
            activeCategoryId = profile.await(),
        )
    }

    /**
     * Which category Home cycles within, or [BUILTIN_CATEGORY].
     *
     * The web provider reads this with `.maybeSingle()`: a brand-new user may have
     * no profile row yet (the `handle_new_user` trigger normally makes one, but it
     * is not there to be relied on before the first write). PostgREST reports "no
     * rows" for a single-object request as `PGRST116`, which [restFailure] maps to
     * NOT_FOUND — so that one code is caught here and read as "no preference yet"
     * rather than being allowed to fail the whole load.
     */
    private suspend fun activeCategoryId(): String = try {
        val row = client.select(
            table = "profiles",
            columns = "active_category_id",
            filters = listOf("id" to "eq.$userId"),
            one = true,
            whileDoing = "your settings",
        ) as? JsonObject
        row?.get("active_category_id")?.stringContent()?.takeIf { it.isNotBlank() }
            ?: BUILTIN_CATEGORY
    } catch (e: StorageError) {
        if (e.code == StorageErrorCode.NOT_FOUND) BUILTIN_CATEGORY else throw e
    }

    override suspend fun createBeat(draft: Beat): Beat {
        val row = client.insert(
            table = "beats",
            body = beatRow(draft),
            one = true,
            whileDoing = "that beat",
        )
        return rowToBeat(row)
    }

    /**
     * ONE INSERT FOR THE WHOLE BATCH — see the note on [BeatsProvider.createBeats].
     * A single Postgres insert returns its rows in input order, so the caller's
     * drafts and the returned beats line up without an id map.
     */
    override suspend fun createBeats(drafts: List<Beat>): List<Beat> {
        if (drafts.isEmpty()) return emptyList()
        val rows = client.insert(
            table = "beats",
            body = buildJsonArray { drafts.forEach { add(beatRow(it)) } },
            whileDoing = "those beats",
        )
        return rowsOf(rows).map { rowToBeat(it) }
    }

    /**
     * The sole caller hands over the complete beat body, so the jsonb blob is
     * REPLACED wholesale rather than merged — a field the editor deleted stays
     * deleted instead of being resurrected by a merge with the old blob.
     */
    override suspend fun updateBeat(id: String, patch: Beat): Beat {
        val row = client.update(
            table = "beats",
            body = buildJsonObject {
                put("name", patch.name)
                put("data", LibraryJson.encodeBody(patch))
            },
            filters = listOf("id" to "eq.$id") + ownerFilter,
            one = true,
            whileDoing = "that beat",
        )
        return rowToBeat(row)
    }

    override suspend fun deleteBeat(id: String) {
        client.delete("beats", listOf("id" to "eq.$id") + ownerFilter, "that change")
    }

    override suspend fun createCategory(draft: CategoryDraft): Category {
        val row = client.insert(
            table = "playlists",
            body = buildJsonObject {
                put("user_id", userId)
                put("name", draft.name)
                put("beat_ids", beatIdsArray(draft.beatIds))
            },
            one = true,
            whileDoing = "that playlist",
        )
        return rowToCategory(row)
    }

    /** A partial patch: only the columns present are touched. See [CategoryPatch]. */
    override suspend fun updateCategory(id: String, patch: CategoryPatch): Category {
        val row = client.update(
            table = "playlists",
            body = buildJsonObject {
                patch.name?.let { put("name", it) }
                patch.beatIds?.let { put("beat_ids", beatIdsArray(it)) }
            },
            filters = listOf("id" to "eq.$id") + ownerFilter,
            one = true,
            whileDoing = "that playlist",
        )
        return rowToCategory(row)
    }

    override suspend fun deleteCategory(id: String) {
        client.delete("playlists", listOf("id" to "eq.$id") + ownerFilter, "that change")
    }

    /**
     * Upsert, so the row is created on first use even if the new-user trigger never
     * ran — a preference should never fail because of a missing profile.
     */
    override suspend fun setActiveCategory(id: String) {
        client.upsert(
            table = "profiles",
            body = buildJsonObject {
                put("id", userId)
                put("active_category_id", id)
            },
            onConflict = "id",
            whileDoing = "that change",
        )
    }

    // ── Rows ───────────────────────────────────────────────────────────────
    // The mapping itself lives in [SupabaseRows], outside this class, because it
    // is the subtle half of the provider — column id over blob, text[] arrays, a
    // payload this build can't read — and none of it needs a client to be tested.

    private fun beatRow(draft: Beat): JsonObject = SupabaseRows.beatRow(userId, draft)

    private fun beatIdsArray(beatIds: List<String>): JsonArray = SupabaseRows.beatIdsArray(beatIds)

    private fun rowToBeat(row: JsonElement?): Beat = SupabaseRows.toBeat(row)

    private fun rowToCategory(row: JsonElement?): Category = SupabaseRows.toCategory(row)

    companion object {
        /**
         * The provider for the signed-in user, or NULL when the cloud is not
         * available — no project configured in this build, or nobody signed in.
         *
         * Null is the signal [LibraryRepository] stays on [LocalBeatsProvider] for,
         * and it is the reason an unconfigured clone runs perfectly well: the app
         * never has to know whether the cloud exists.
         */
        fun create(client: SupabaseClient?, session: AuthSession?): BeatsProvider? {
            val userId = session?.userId ?: return null
            if (client == null) return null
            return SupabaseBeatsProvider(client, userId)
        }
    }
}

/**
 * The row ↔ value mapping for the `beats` and `playlists` tables.
 *
 * Split out from [SupabaseBeatsProvider] so it can be tested without a network, a
 * session or a client: this is where a shape mismatch between the two platforms
 * would show up, and it is the part worth pinning down.
 */
internal object SupabaseRows {

    /**
     * The insert body for one beat.
     *
     * `name` is duplicated into its own column purely so the database can sort and
     * search on it; the whole body rides in `data`. The blob carries NO id — the
     * primary-key column is the id, and duplicating it is how two sources of truth
     * drift apart.
     */
    fun beatRow(userId: String, draft: Beat): JsonObject = buildJsonObject {
        put("user_id", userId)
        put("name", draft.name)
        put("data", LibraryJson.encodeBody(draft))
    }

    /** A Postgres `text[]` is a JSON array on the wire. */
    fun beatIdsArray(beatIds: List<String>): JsonArray =
        buildJsonArray { beatIds.forEach { add(it) } }

    /**
     * A `beats` row as an app beat: `{ id: row.id, ...row.data }` — the COLUMN id
     * over the blob, everything else from the blob. The web provider does exactly
     * this, and the order matters: a blob carrying a stale or hostile id must not
     * be able to name a different row.
     *
     * A blob that will not decode is a [StorageError], not a skipped row — see the
     * all-or-nothing note in [LibraryJson]'s header. It names the beat so the user
     * has something to look for.
     */
    fun toBeat(row: JsonElement?): Beat {
        val obj = row as? JsonObject ?: throw unreadable("one of your beats")
        val id = obj["id"]?.stringContent() ?: throw unreadable("one of your beats")
        val name = obj["name"]?.stringContent().orEmpty()
        val beat = LibraryJson.decodeBeat(obj["data"]) ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            "One of your saved beats (\"$name\") is in a format this version of the app can't read.",
        )
        return beat.copy(id = id)
    }

    /** A `playlists` row as an app category. */
    fun toCategory(row: JsonElement?): Category {
        val obj = row as? JsonObject ?: throw unreadable("one of your playlists")
        val id = obj["id"]?.stringContent() ?: throw unreadable("one of your playlists")
        val beatIds = when (val element = obj["beat_ids"]) {
            // An empty progression is a legal playlist, and a NULL column (which
            // the schema's default makes impossible but a hand-run migration might)
            // reads as one rather than failing the whole load.
            null, is JsonNull -> emptyList()
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> throw unreadable("one of your playlists")
        }
        return Category(
            id = id,
            name = obj["name"]?.stringContent().orEmpty(),
            beatIds = beatIds,
        )
    }

    private fun unreadable(what: String) = StorageError(
        StorageErrorCode.UNKNOWN,
        "The server sent $what this version of the app can't read.",
    )
}

/**
 * The rows of a PostgREST collection response.
 *
 * A `null` body is not an error — a 204 carries no content — and reading it as
 * "no rows" keeps an empty library and an empty response indistinguishable, which
 * is what they both mean.
 */
private fun rowsOf(body: JsonElement?): List<JsonElement> = (body as? JsonArray) ?: emptyList()
