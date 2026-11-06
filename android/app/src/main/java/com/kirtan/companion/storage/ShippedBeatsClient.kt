package com.kirtan.companion.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.kirtan.companion.data.BeatSourceExport
import com.kirtan.companion.data.MIN_BPM
import com.kirtan.companion.data.MAX_BPM
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.ShippedBeats
import com.kirtan.companion.data.cpqFor
import com.kirtan.companion.data.groupsFor
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.data.sumGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * The built-in beat set as served by the database, and the cache that stands in
 * for it.
 *
 * WHY THIS EXISTS. The beats used to be reachable only by shipping a new APK, so
 * correcting one transcription meant a rebuild, a reinstall, and every player who
 * had already installed the app staying wrong. `shipped_beats` is now canonical:
 * the two people who maintain the set edit rows, and every installed app picks
 * the change up on its next launch. [com.kirtan.companion.data.BEATS] — compiled
 * in, and identical to the seed — is what plays when there is no network, and
 * what a fresh install plays for the second between launch and the first response.
 *
 * ── READS NEED NO IDENTITY ──
 * `requireSession = false`, exactly like [CommunityClient.browse]: the select
 * policy is `using (true)`, and the built-in beats are the one thing a signed-out
 * user must still get. Failing this read with "sign in and try again" would be a
 * lie, and would leave the app with no default beat.
 *
 * ── VALIDATION IS ALL OR NOTHING ──
 * One unusable row rejects the WHOLE set, and a rejected set falls back — to the
 * cache, then to the compiled list. Skipping the bad row and keeping the rest is
 * the tempting alternative and the wrong one: a built-in set is a contract other
 * state depends on, because playlists store built-in ids and the Beats screen
 * takes its section headings from the set's order. A set with one beat silently
 * missing leaves a playlist pointing at nothing and no explanation anywhere. This
 * is the same rule [LibraryJson] applies to a damaged library and
 * [ShareCodec] to a shared playlist.
 *
 * The bounds are the SHARE CODEC'S, not new ones, and that is not a coincidence
 * worth tidying: a promoted beat always arrives through the share format, so
 * "expressible as a share code" and "acceptable as a built-in" are the same set
 * by construction. `matan` is 48 cells against a 64-cell bound, so the ceiling
 * has headroom without being loose.
 *
 * ── THE CACHE HOLDS ROWS, NOT BEATS ──
 * Written verbatim as fetched and validated again on the way back in, so a cache
 * left by an older build with different derivation rules cannot smuggle a beat
 * past the checks — and a change to those rules needs no cache-version bump.
 */
class ShippedBeatsClient(
    private val client: SupabaseClient,
    private val store: DataStore<Preferences>,
    private val log: StorageLog = AndroidStorageLog,
) {

    private companion object {
        const val TABLE = "shipped_beats"
        const val MAINTAINERS = "maintainers"
        // The columns both platforms agreed on, named rather than `*`: this is
        // the row shape the derivation reads, and spelling it out keeps a new
        // column from entering the cached blob before either half knows what it
        // means. The web client selects the same list, so the two caches hold
        // the same bytes for the same table state.
        const val COLUMNS =
            "id, ordinal, heading, name, note, bpm, groups, cpq, lanes, " +
                "description, source_published_id, updated_at"
    }

    private val _status = MutableStateFlow(ShippedStatus())

    /** Where the set on screen came from, and what the last check said. */
    val status: StateFlow<ShippedStatus> = _status.asStateFlow()

    /**
     * Adopt the cached rows, if they still validate.
     *
     * Runs BEFORE the first network answer so a returning user sees the set they
     * last had rather than a set that briefly disagrees with their playlists. A
     * missing or damaged cache is not an error and says nothing: the compiled
     * fallback is already on screen.
     */
    suspend fun loadCached() {
        val cached = try {
            store.data.first()[Keys.SHIPPED_ROWS]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[shipped] the cached beat set could not be read", e)
            null
        }
        val beats = shippedBeatsOf(cached?.parseJsonOrNull()) ?: return
        ShippedBeats.install(beats)
        _status.update {
            it.copy(source = ShippedSource.CACHED, count = beats.size)
        }
    }

    /**
     * Fetch the server's set and adopt it.
     *
     * Never throws: this runs at launch, behind the splash, and a failed lookup
     * must leave the app playing whatever it already had. [manual] only decides
     * whether the user gets a sentence about the result — an automatic check that
     * found nothing new should be silent, while one the user asked for has to say
     * something even when the answer is "nothing changed".
     */
    suspend fun checkForUpdates(manual: Boolean) {
        _status.update { it.copy(checking = true, notice = null) }
        val previous = ShippedBeats.effective.value
        val fetched = try {
            refetch()
        } catch (e: CancellationException) {
            _status.update { it.copy(checking = false) }
            throw e
        } catch (e: Exception) {
            log.warn("[shipped] the server's beat set could not be read", e)
            _status.update {
                it.copy(
                    checking = false,
                    checkedAt = now(),
                    notice = if (manual) storageErrorMessage(e) else null,
                )
            }
            return
        }

        val beats = ShippedBeats.effective.value
        val notice = when {
            !manual -> null
            // The server answered and the answer was unusable. Distinct from a
            // failed request, and distinct from "up to date": a maintainer who has
            // just promoted a beat needs to know it did not arrive, and both of
            // those other lines would tell them everything is fine.
            fetched == null ->
                "The server sent a beat set this app couldn't use, so nothing changed."
            // Compared as beats, not as revisions: two different `updated_at`
            // values can describe the same nine beats (a re-run seed, a promoted
            // beat that was then reverted), and "updated" would be a lie.
            beats == previous -> "Already up to date · ${beats.size} beats"
            else -> "Built-in beats updated · ${beats.size} beats"
        }
        _status.update {
            it.copy(checking = false, checkedAt = now(), count = beats.size, notice = notice)
        }
    }

    /**
     * Pull the rows, validate them, install them and cache them.
     *
     * @return the adopted set, or null when the server's answer was unusable —
     *   in which case whatever was already installed stays, and nothing is cached
     *   over it. Caching a rejected set would make the damage survive a restart.
     */
    private suspend fun refetch(): List<Beat>? {
        val body = client.select(
            table = TABLE,
            columns = COLUMNS,
            order = "ordinal.asc",
            requireSession = false,
            whileDoing = "loading the built-in beats",
        )
        val rows = body as? JsonArray ?: return null
        val beats = shippedBeatsOf(rows) ?: run {
            log.warn("[shipped] the server's beat set was rejected: ${rows.size} rows", null)
            return null
        }
        ShippedBeats.install(beats)
        _status.update { it.copy(source = ShippedSource.SERVER, count = beats.size) }

        // Only rewrite the blob when the set actually moved. Every launch checks,
        // and a write per launch for a set that changes a few times a year is a
        // disk write and a fsync for nothing.
        val revision = shippedRevision(rows)
        val cached = try {
            store.data.first()[Keys.SHIPPED_REVISION]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (revision != cached) writeCache(rows, revision)
        return beats
    }

    /**
     * True when the signed-in user may change the built-in set.
     *
     * Read from `maintainers`, whose ONLY select policy is "you are already on
     * it" — so a non-maintainer gets an empty list rather than a refusal, and
     * there is no way to enumerate who may promote. The table has no write policy
     * at all, which is the part that matters: neither maintainer can add the
     * other, and no bug in this app can promote itself. Only the service role can.
     *
     * Fails CLOSED to false. This gate decides whether to show a button; the
     * database decides whether the write succeeds, so a wrong `true` here would
     * be a button that always fails and a wrong `false` is a maintainer who has
     * to try again.
     */
    suspend fun isMaintainer(): Boolean = try {
        val rows = client.select(
            table = MAINTAINERS,
            columns = "user_id",
            limit = 1,
            whileDoing = "checking whether you may edit the built-in beats",
        )
        (rows as? JsonArray)?.isNotEmpty() == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("[shipped] the maintainer check failed", e)
        false
    }

    /**
     * Make a community beat one of the built-ins, for every user of every install.
     *
     * Takes the PUBLISHED ITEM as well as the beat decoded from it, because the
     * beat is what gets stored and the item is where its provenance comes from:
     * `source_published_id` is what lets someone later answer "which shared beat
     * did this ship from?" without reading the row's patterns and guessing.
     *
     * An upsert on `id`, so promoting a corrected version of a beat already in the
     * set replaces it in place — the id is stable and playlists keep working,
     * which is the whole reason the table's key is a slug rather than a uuid.
     *
     * @param heading the section the beat lands in. Asked for rather than assumed:
     *   every promoted beat arriving in one bucket is a set nobody can browse, and
     *   fixing it afterwards means SQL.
     * @throws StorageError when the write is refused — RLS says the caller is not a
     *   maintainer, or the row broke a check constraint.
     */
    suspend fun promote(item: PublishedItem, beat: Beat, heading: String) {
        // The id AND the ordinal are chosen against a fresh read of the table,
        // not against the set on screen. The write is an upsert on the primary
        // key, so an id picked from a stale list can collide and silently
        // OVERWRITE a beat that ships to everybody: a community beat called
        // "Double Time 2" slugs to the compiled beat's own id, and the
        // maintainer would be told it worked. The installed set is stale
        // exactly when the launch check failed — which is when nobody is
        // looking at it. It also covers the other maintainer promoting
        // something since this device last asked.
        val current = currentRows()
        val row = checkedRow(
            shippedRowFor(
                id = shippedIdFor(beat.name, current.ids),
                ordinal = current.nextOrdinal,
                heading = heading,
                beat = beat,
                sourcePublishedId = item.id,
                description = beat.description,
            ),
            "That beat can't become a built-in one — its name or its pattern is " +
                "outside what the built-in set allows.",
        )

        client.upsert(TABLE, row, onConflict = "id", whileDoing = "making that a built-in beat")
        // Silent: the caller has its own "promoted" message, and a set that now
        // holds one more beat is not news the user needs twice.
        refetch()
    }

    /**
     * Write a corrected beat back onto the row it came from.
     *
     * The other half of serving the set. Promote ADDS a beat; this fixes one that
     * is already shipping, which is the common case — a wrong cell in a pattern
     * everybody is playing. Before this existed the only route was SQL, and the
     * route before that was a rebuild and a reinstall for every phone.
     *
     * ── THE ID IS NEVER RE-SLUGIFIED ──
     * Not even when the maintainer renames the beat, and that is the one rule here
     * that must not be relaxed: playlists store built-in ids, and nothing anywhere
     * can tell a stale reference from a live one, so a new slug would orphan every
     * progression that used this beat while leaving the old row behind. A rename
     * changes `name` and nothing else.
     *
     * ── WHY IT READS THE ROW BACK FIRST ──
     * `ordinal`, `heading`, `description` and `source_published_id` are not on
     * [Beat] in a form the editor round-trips, so without a read they would be
     * written as nulls and the beat would silently lose its section, its prose and
     * its provenance.
     *
     * ── AND WHY THE WRITE IS AN UPDATE, NOT AN UPSERT ──
     * The read above does NOT make this safe on its own: a beat retired by the
     * other maintainer between the read and the write would come straight back if
     * the write inserted on conflict, and the person doing it would be told the
     * save worked. A filtered UPDATE cannot resurrect a row, and because it asks
     * for the representation back, "matched nothing" is detectable — an UPDATE that
     * changes no rows is a 200 with an empty body, not an error, so without reading
     * the response this would report success and change nothing.
     *
     * @throws StorageError when there is no row to update, when the edit is not
     *   representable, or when RLS says the caller is not a maintainer.
     */
    suspend fun updateShippedBeat(edited: Beat) {
        val id = edited.id ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            "That beat has no id, so there is no built-in row to update.",
        )
        val meta = currentRow(id) ?: throw notInTheSet()
        val row = checkedRow(
            shippedRowFor(
                id = id,
                ordinal = meta.ordinal,
                heading = meta.heading,
                beat = edited,
                sourcePublishedId = meta.sourcePublishedId,
                description = meta.description,
            ),
            "That edit can't be saved — its name or its pattern is outside what " +
                "the built-in set allows.",
        )
        val written = client.update(
            TABLE,
            row,
            filters = listOf("id" to "eq.$id"),
            whileDoing = "save that for everyone",
        )
        if ((written as? JsonArray).isNullOrEmpty()) throw notInTheSet()

        // Silent, as after a promote: the caller has its own confirmation, and the
        // point of the refetch is that the maintainer sees their own correction
        // immediately — a mistake is then visible to the one person who can fix it.
        refetch()
    }

    /**
     * Retire one built-in beat for every user of every install.
     *
     * The third shipped-set write: promote adds, update corrects, and this removes.
     * A retired beat disappears from the Beats screen on the next launch, but its
     * id remains addressable by playlists — a progression that used it keeps
     * working, pointing at a row that no longer appears in any list. That is the
     * intended behaviour: removing a beat should never orphan a playlist someone
     * built.
     *
     * THE ROW IS READ FRESH first, so a beat retired by the other maintainer while
     * this editor was open reports "not found" rather than silently succeeding and
     * changing nothing. The write itself is a DELETE filtered on the primary key,
     * not an upsert or update — there is no row to write back, only one to remove.
     *
     * @throws StorageError when there is no row to delete, or when RLS says the
     *   caller is not a maintainer.
     */
    suspend fun removeShippedBeat(beat: Beat) {
        val id = beat.id ?: throw StorageError(
            StorageErrorCode.UNKNOWN,
            "That beat has no id, so there is no built-in row to remove.",
        )
        val meta = currentRow(id) ?: throw notInTheSet()

        // A DELETE that matches nothing is a 200 with an empty body, not an error.
        // Reading the response back makes "matched nothing" detectable — the same
        // reason updateShippedBeat reads its response back before declaring success.
        val deleted = client.delete(
            TABLE,
            filters = listOf("id" to "eq.$id"),
            whileDoing = "remove that from the built-in beats",
        )
        if ((deleted as? JsonArray).isNullOrEmpty()) throw notInTheSet()

        // Silent, as after a promote: the caller has its own confirmation. The
        // refetch is what makes the maintainer see their own retirement immediately,
        // which matters because a retired beat is gone for everyone — a mistake is
        // then visible to the one person who can fix it.
        refetch()
    }

    /**
     * The row is gone. One sentence for both the pre-read and the empty write,
     * because from the maintainer's side they are the same fact and the same
     * remedy, and the web client words it identically.
     */
    private fun notInTheSet() = StorageError(
        StorageErrorCode.NOT_FOUND,
        "That beat isn't in the built-in set any more, so there was nothing to update.",
    )

    /**
     * One row's carried-over fields, or null when there is no such row.
     *
     * Signed out is fine: this is a read of a world-readable table, and the write
     * that follows is what RLS gates.
     */
    private suspend fun currentRow(id: String): ShippedRowMeta? {
        val body = client.select(
            table = TABLE,
            columns = "ordinal, heading, description, source_published_id",
            // Unquoted, which is safe because an id is `[a-z0-9_]` by construction
            // — see [shippedIdFor] — so it cannot hold a character PostgREST would
            // read as an operator or a separator.
            filters = listOf("id" to "eq.$id"),
            limit = 1,
            requireSession = false,
            whileDoing = "loading the built-in beats",
        )
        val row = (body as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return ShippedRowMeta(
            ordinal = row["ordinal"].intContent() ?: return null,
            heading = row["heading"].stringField() ?: return null,
            description = row["description"].stringField(),
            sourcePublishedId = row["source_published_id"].stringField(),
        )
    }

    /**
     * Refuse to write a row the READ path would reject.
     *
     * The set is all-or-nothing, so ONE row a client cannot parse takes every
     * user's built-in list back to the compiled fallback — a correction that
     * breaks the app for everybody is the worst available outcome of an edit.
     * Checking the row against the same function that reads the table turns that
     * into a sentence on the maintainer's screen, and catches what a column
     * constraint would otherwise report as an opaque database error.
     */
    private fun checkedRow(row: JsonObject, refusal: String): JsonObject {
        if (shippedBeatsOf(JsonArray(listOf(row))) == null) {
            throw StorageError(StorageErrorCode.UNKNOWN, refusal)
        }
        return row
    }

    /** The table as it is NOW: every id, and the ordinal that appends to it. */
    private data class CurrentRows(val ids: Set<String>, val nextOrdinal: Int)

    private suspend fun currentRows(): CurrentRows {
        val body = client.select(
            table = TABLE,
            columns = "id, ordinal",
            requireSession = false,
            whileDoing = "loading the built-in beats",
        )
        val rows = (body as? JsonArray).orEmpty()
        // Ids are collected whether or not the rest of the row would parse: an
        // id this build cannot read is still taken, and reusing it would still
        // overwrite whatever is behind it.
        val ids = rows.mapNotNull { (it as? JsonObject)?.get("id")?.stringField() }
            .filter { it.isNotBlank() }
            .toSet()
        val highest = rows.mapNotNull { (it as? JsonObject)?.get("ordinal")?.intContent() }
            .maxOrNull()
        return CurrentRows(ids, (highest ?: -1) + 1)
    }

    /**
     * Persist the rows exactly as they arrived.
     *
     * Best-effort and the one write here that swallows a failure: the beats are
     * already installed and on screen, and a cache that did not take only means
     * the next cold start shows the compiled set for a moment longer.
     */
    private suspend fun writeCache(rows: JsonArray, revision: String) {
        try {
            store.edit { prefs ->
                prefs[Keys.SHIPPED_ROWS] = rows.toString()
                prefs[Keys.SHIPPED_REVISION] = revision
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[shipped] the beat set was not cached", e)
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}

/** Where the built-in set currently on screen came from. */
enum class ShippedSource {
    /** No usable server set and no usable cache: the compiled fallback. */
    COMPILED,

    /** Rows cached by an earlier launch, adopted before the network answered. */
    CACHED,

    /** Adopted from a response this process fetched. */
    SERVER,
}

/**
 * What the app knows about the built-in set, for the Beats screen's one line
 * about it.
 *
 * [notice] is set only by a check the USER asked for. An automatic one that found
 * nothing new must stay silent: a launch is not a conversation, and a banner
 * saying "already up to date" on every cold start trains the user to ignore it.
 */
data class ShippedStatus(
    val source: ShippedSource = ShippedSource.COMPILED,
    val count: Int = ShippedBeats.compiled.size,
    /** True while a check is in flight, so the row can say so instead of lying. */
    val checking: Boolean = false,
    /** Epoch millis of the last check that finished, or null before the first. */
    val checkedAt: Long? = null,
    val notice: String? = null,
)

// ── Pure row parsing ───────────────────────────────────────────────────────
// Top-level and internal for the reason `browseFilters` is: these are the rules
// that decide what the app will play, and they are testable against a literal
// with no DataStore, no network and no Supabase project in the way.

/** One fetched row: its beat, plus the `ordinal` that only sorting cares about. */
private data class ShippedRow(val ordinal: Int, val beat: Beat)

/**
 * A whole server set as beats, or null when ANY part of it is unusable.
 *
 * Accepts the [JsonElement] a response or a cache read yields, so "the server
 * sent an object because it was an error body" and "the cache holds a truncated
 * string" are both just a rejected set rather than a crash.
 *
 * Rows are sorted by `ordinal` here rather than trusted to arrive sorted: the
 * fetch asks for the order, but the cache is a string on disk and a future caller
 * may not ask. Order is load-bearing — it is the order of the Beats screen's
 * sections and of `ordinal` 0 being the app's default beat.
 */
internal fun shippedBeatsOf(rows: JsonElement?): List<Beat>? {
    val array = rows as? JsonArray ?: return null
    if (array.isEmpty()) return null

    val parsed = ArrayList<ShippedRow>(array.size)
    val ids = HashSet<String>()
    for (element in array) {
        val row = shippedRowOf(element) ?: return null
        val id = row.beat.id ?: return null
        // Two rows with one id would mean one of them silently disappears from the
        // list while both remain addressable by playlists — a duplicate primary key
        // cannot happen in the table, but a hand-built cache can hold one.
        if (!ids.add(id)) return null
        parsed.add(row)
    }
    return parsed.sortedBy { it.ordinal }.map { it.beat }
}

/**
 * The newest `updated_at` across a set of rows, as the cache's revision.
 *
 * Compared as TEXT, which is safe here and is the reason no parsing is needed:
 * PostgREST renders `timestamptz` in one fixed ISO-8601 form at a fixed offset,
 * so lexicographic order is chronological order. Empty when no row carries one.
 */
internal fun shippedRevision(rows: JsonArray): String =
    rows.mapNotNull { (it as? JsonObject)?.get("updated_at")?.stringField() }
        .maxOrNull()
        .orEmpty()

/**
 * One row as a beat, or null if it is not a usable one.
 *
 * Every refusal here is a whole-set refusal (see [shippedBeatsOf]), so the bar is
 * "would this make the app play something wrong or hang", not "is this row tidy".
 * Fields the app does not read — `source_published_id` — are not validated at all.
 */
private fun shippedRowOf(element: JsonElement?): ShippedRow? {
    val row = element as? JsonObject ?: return null

    // String primitives ONLY, and id/name non-BLANK. [stringContent] would also
    // accept a number's text, which is the right tolerance for a uuid column
    // read back from a database that might render one bare — and the wrong one
    // here, because the web client requires an actual string. Two platforms that
    // disagree about a row are two platforms showing different built-in sets,
    // and a shared link resolves built-in ids against whichever set the
    // recipient is holding.
    val id = row["id"].stringField()?.takeIf { it.isNotBlank() } ?: return null
    val heading = row["heading"].stringField() ?: return null
    val name = row["name"].stringField()?.takeIf { it.isNotBlank() } ?: return null
    val ordinal = row["ordinal"].intContent() ?: return null

    // Present and a string, but blank is fine: see the note on the field above.
    val note = row["note"].stringField() ?: return null

    val bpm = row["bpm"].intContent() ?: return null
    if (bpm !in MIN_BPM..MAX_BPM) return null
    val cpq = row["cpq"].intContent() ?: return null
    if (cpq !in 1..ShareCodec.MAX_CPQ) return null

    val groups = validShippedGroups(row["groups"]) ?: return null
    val steps = sumGroups(groups)
    if (steps !in 1..ShareCodec.MAX_STEPS) return null

    val lanes = row["lanes"] as? JsonObject ?: return null
    val patterns = LinkedHashMap<LaneId, List<Stroke?>>()
    for (lane in LaneId.ORDERED) {
        // Read by lane id, so an unknown key in `lanes` can never become a lane:
        // the same allowlist that makes ShareCodec's `p` object safe. A lane with
        // no key is ABSENT — not all rests — which is how a two-lane beat says it
        // has no cymbals, and what `activeLanes()` and the strip's filter read.
        val raw = lanes[lane.wireId] ?: continue
        val primitive = raw as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        patterns[lane] = cellsOf(primitive.content, steps) ?: return null
    }
    // Every PRIMARY lane is required, asked of the model rather than spelled
    // out as dayan+bayan: the mridanga's two ends are a beat's identity, the
    // sequencer indexes them unguarded, and `melody` is already sitting
    // commented out in [LaneId]. When it isn't, this rule follows the flag the
    // rest of the app uses instead of needing a second edit here.
    if (LaneId.ORDERED.any { it.primary && patterns[it] == null }) return null

    val description = when (val value = row["description"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else return null
        else -> return null
    }

    // steps / beatsPerBar / cellsPerGroup are DERIVED, exactly as `builtIn` in
    // data/Beats.kt and ShareCodec.decodeBeat derive them, so a served beat and a
    // compiled one with the same row are the same object — which a test asserts
    // against the live table rather than against my arithmetic.
    val uniform = groups.all { it == groups[0] }
    return ShippedRow(
        ordinal = ordinal,
        beat = Beat(
            id = id,
            name = name,
            // A row's note is `not null default ''` in the table, so an empty one
            // is legitimate; the list rows that print it cope with an empty string.
            note = note,
            bpm = bpm,
            steps = steps,
            beatsPerBar = steps.toDouble() / cpq,
            cellsPerGroup = if (uniform) groups[0] else cpq,
            groups = groups,
            description = description,
            lanePatterns = patterns,
            group = heading,
        ),
    )
}

/**
 * The `groups` column, or null when it cannot be a meter.
 *
 * The bounds are [ShareCodec]'s. Entries are read through a double and rounded
 * for the same reason [LibraryJson.intField] does it: a row written by a JS
 * client is a double on the wire, and refusing `4.0` would reject every beat the
 * web app promotes.
 */
private fun validShippedGroups(element: JsonElement?): List<Int>? {
    val array = element as? JsonArray ?: return null
    if (array.isEmpty() || array.size > ShareCodec.MAX_GROUPS) return null
    val groups = ArrayList<Int>(array.size)
    for (item in array) {
        val cells = item.intContent() ?: return null
        if (cells !in 1..ShareCodec.MAX_GROUP_CELLS) return null
        groups.add(cells)
    }
    return groups
}

/**
 * Compact lane notation as cells, or null when it is not [steps] of `O`/`X`/`-`.
 *
 * The LENGTH check is the one that matters. A short pattern reads as trailing
 * rests and silently truncates the loop — the failure `data/Beats.kt` checks at
 * class-load time for the compiled set, and the reason a served row gets the same
 * treatment. An unknown character is refused rather than read as a rest: a rest
 * is a claim that nothing is struck there, and guessing would turn a typo into a
 * beat that plays wrong but looks right.
 */
private fun cellsOf(notation: String, steps: Int): List<Stroke?>? {
    if (notation.length != steps) return null
    val cells = ArrayList<Stroke?>(steps)
    for (ch in notation) {
        val cell = Stroke.fromCode(ch.toString())
        // `fromCode` answers null for the rest character AND for anything it does
        // not know, and only the first of those is a rest. Telling them apart is
        // the whole check: reading a typo as a rest would produce a beat that
        // plays wrong while looking exactly right on screen.
        if (cell == null && ch.toString() != ShareCodec.REST_CHAR) return null
        cells.add(cell)
    }
    return cells
}

/**
 * A whole-number JSON field, or null when it isn't one.
 *
 * Read through a double so a `90.0` is accepted — that is what a JS writer
 * produces, and in JS `Number.isInteger(90.0)` is true, so the web client
 * accepts it too. A value with an actual FRACTION is refused rather than
 * rounded: rounding `4.4` cells to 4 would be a guess about a meter, and the
 * web refuses it, so guessing here is how the two platforms end up serving
 * different sets from one table.
 */
private fun JsonElement?.intContent(): Int? {
    val value = (this as? JsonPrimitive)?.doubleOrNull ?: return null
    if (!value.isFinite()) return null
    val whole = value.roundToInt()
    if (whole.toDouble() != value) return null
    return whole
}

/** A JSON string primitive's text, or null for a number, object, array or null. */
private fun JsonElement?.stringField(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// ── Pure row writing ───────────────────────────────────────────────────────

/**
 * A beat's slug for the `shipped_beats` primary key.
 *
 * Derived from the name rather than minted as a uuid because the id is STABLE BY
 * REQUIREMENT: playlists store built-in ids, so an id that changed when a beat was
 * re-promoted would orphan every progression referencing it. A readable slug also
 * makes a row recognisable in the dashboard, which is where a maintainer fixes one.
 *
 * [taken] is the set of ids already shipping. A collision is suffixed rather than
 * allowed, because the write is an upsert: promoting "Dadra" over an existing
 * `dadra` would silently REPLACE that beat for every user, which is a deletion
 * nobody asked for.
 */
internal fun shippedIdFor(name: String, taken: Set<String>): String {
    val slug = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(MAX_ID_LEN_FOR_SLUG)
        .trimEnd('_')
        .ifEmpty { "beat" }
    if (slug !in taken) return slug
    var suffix = 2
    while ("${slug}_$suffix" in taken) suffix++
    return "${slug}_$suffix"
}

/** Kept beside the slug rule so a change to one is a change to both. */
private const val MAX_ID_LEN_FOR_SLUG = 48

/**
 * The row a promote writes.
 *
 * Lanes go out in the same compact notation they come in, and a lane the beat does
 * not have is OMITTED rather than sent as rests — an all-rest `kartal` key would
 * come back as a cymbal lane that never strikes, which the strip renders as an
 * empty row and the editor as a lane the user must have wanted.
 *
 * `groups` and `cpq` are sent and `steps`/`beatsPerBar`/`cellsPerGroup` are not:
 * those three are derived on every client from those two, so a row cannot declare
 * a meter that contradicts itself. That is the same reason
 * [BeatSourceExport.toKotlinSource] emits groups rather than the derived fields.
 *
 * `note` is DERIVED here rather than taken from the beat, and [description] is a
 * parameter rather than a read of the beat, for one shared reason: neither
 * survives a trip through the editor. [com.kirtan.companion.ui.editor.EditorDraft.toBeat]
 * stamps `note = "Custom"` and `description = null` on everything it produces,
 * because a private beat is new and has no prose — so an edit written straight
 * from the draft would replace "4 beats" with "Custom" and erase a row's prose.
 * Deriving the note also means it cannot go stale: a maintainer who changes the
 * meter gets a note that counts the new groups, where a preserved one would say
 * "4 beats" over a five-group bar, in the list row where everyone can see it.
 * `scripts/generateBuiltinBeats.mjs` mints the same string, so the table and the
 * compiled fallback agree.
 *
 * The two NULLABLE columns are written explicitly rather than omitted when empty,
 * which is the opposite of what [LibraryJson] does and for an upsert-specific
 * reason: `resolution=merge-duplicates` only touches the columns present in the
 * body, so an omitted `description` would leave the PREVIOUS row's prose attached
 * to a beat that no longer has any. A promote replaces a row wholesale.
 */
internal fun shippedRowFor(
    id: String,
    ordinal: Int,
    heading: String,
    beat: Beat,
    sourcePublishedId: String?,
    description: String?,
): JsonObject = buildJsonObject {
    put("id", id)
    put("ordinal", ordinal)
    put("heading", heading)
    put("name", beat.name)
    put("note", "${groupsFor(beat).size} beats")
    put("bpm", beat.bpm)
    put("groups", buildJsonArray { groupsFor(beat).forEach { add(it) } })
    put("cpq", cpqFor(beat))
    put(
        "lanes",
        buildJsonObject {
            for (lane in LaneId.ORDERED) {
                val notation = BeatSourceExport.patternNotation(beat, lane)
                if (notation.isNotEmpty()) put(lane.wireId, notation)
            }
        },
    )
    put("description", description)
    put("source_published_id", sourcePublishedId)
}

/**
 * The parts of a row that an EDIT must carry over, read fresh from the table.
 *
 * None of these four is on [Beat] in a form an editor could round-trip: `ordinal`
 * and `source_published_id` are not beat fields at all, and `heading` and
 * `description` are exactly what [com.kirtan.companion.ui.editor.EditorDraft]
 * drops. Reading them back rather than trusting the beat in memory is what makes
 * "edit this beat" unable to silently un-file it from its section or strip its
 * provenance.
 */
internal data class ShippedRowMeta(
    val ordinal: Int,
    val heading: String,
    val description: String?,
    val sourcePublishedId: String?,
)
