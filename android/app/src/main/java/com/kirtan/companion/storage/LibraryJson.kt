package com.kirtan.companion.storage

import com.kirtan.companion.data.MAX_BPM
import com.kirtan.companion.data.MIN_BPM
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlinx.serialization.json.Json
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
 * The stored shape of a beat and a category — the one codec both the on-device
 * store and the cloud `jsonb` column go through.
 *
 * WHY THIS FILE EXISTS SEPARATELY. [LocalBeatsProvider] keeps beats as a JSON
 * array under one DataStore key and [SupabaseBeatsProvider] keeps each beat's
 * body in a `beats.data` jsonb column; those are the same bytes in two
 * envelopes, and the cloud envelope is shared with the WEB app. A beat saved
 * from a browser has to open on a phone and vice versa, so the field names below
 * are the web's, not an invention of this port:
 *
 *     { id, name, note, bpm, steps, beatsPerBar, cellsPerGroup,
 *       groups?, description?, group?, dayan, bayan, kartal? }
 *
 * with each lane an array of `"O"` / `"X"` / `null`, one entry per cell. That is
 * the STORED shape; [ShareCodec]'s compact `"X-OO-XOO"` strings are the SHARED
 * shape — a link has to survive a messenger, a database row does not.
 *
 * `id` is written by the local store and deliberately NOT written into the cloud
 * blob, where the primary-key column already holds it. Duplicating it there is
 * how two sources of truth get out of step; `supabaseProvider.js` strips it for
 * the same reason. See [encodeBody].
 *
 * ── POSTURE ──
 * Like [ShareCodec], this codec NEVER THROWS: a shape it does not recognise
 * decodes to null and the CALLER decides what that means. The two callers decide
 * differently, on purpose — [LocalBeatsProvider] turns a null into a
 * [StorageError] so a corrupt store refuses to be overwritten, while
 * [SupabaseBeatsProvider] turns it into a [StorageError] naming the row. Keeping
 * the policy out of the codec is what lets both be right.
 *
 * ── WHY DECODING IS ALL-OR-NOTHING PER LIST ──
 * Skipping a beat that fails to decode and writing the rest back would be a
 * SILENT PARTIAL DELETE: the user opens the app, one beat is missing, and nothing
 * anywhere says why. Refusing the whole list leaves the bytes where a later
 * release can recover them, and surfaces one honest error instead.
 *
 * ── TOLERANCE ──
 * Unknown fields are ignored, so a row written by a NEWER app still reads here.
 * Numbers are read as doubles and rounded, because every JSON number the web
 * encoder emits is a JS double: a browser-saved `"bpm": 90` and a hypothetical
 * `90.0` are the same beat and neither is corruption.
 */
internal object LibraryJson {

    /**
     * The parser for stored JSON. Lenient only because a hand-edited store is a
     * real thing on a rooted phone; the named-field reads below are what actually
     * keep the data honest — nothing here binds a key it did not ask for.
     */
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_NOTE = "note"
    private const val KEY_BPM = "bpm"
    private const val KEY_STEPS = "steps"
    private const val KEY_BEATS_PER_BAR = "beatsPerBar"
    private const val KEY_CELLS_PER_GROUP = "cellsPerGroup"
    private const val KEY_GROUPS = "groups"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_GROUP = "group"
    private const val KEY_BEAT_IDS = "beatIds"

    // ── Encoding ───────────────────────────────────────────────────────────

    /** One lane's cells as `["O", null, "X", …]`, one entry per cell. */
    private fun encodePattern(cells: List<Stroke?>): JsonArray = buildJsonArray {
        for (cell in cells) {
            if (cell == null) add(JsonNull) else add(cell.code)
        }
    }

    /**
     * The beat's body — everything except its id.
     *
     * Lanes are iterated in [LaneId.ORDERED] and an absent lane is OMITTED rather
     * than written as all-rests, so a beat with no karatala row stays a two-lane
     * beat on the wire. That is the convention both `src/data/beats.js` and the
     * web editor's save follow, and it is what keeps `activeLanes()` honest after
     * a round trip.
     */
    fun encodeBody(beat: Beat): JsonObject = buildJsonObject {
        put(KEY_NAME, beat.name)
        put(KEY_NOTE, beat.note)
        put(KEY_BPM, beat.bpm)
        put(KEY_STEPS, beat.steps)
        put(KEY_BEATS_PER_BAR, beat.beatsPerBar)
        put(KEY_CELLS_PER_GROUP, beat.cellsPerGroup)
        // Omitted, not written as null: the web encoder leaves `groups` off a
        // built-in entirely, and an explicit null would read as "a groups array
        // that happens to be empty" to anything checking for the key's presence.
        beat.groups?.let { groups ->
            put(KEY_GROUPS, buildJsonArray { groups.forEach { add(it) } })
        }
        beat.description?.let { put(KEY_DESCRIPTION, it) }
        beat.group?.let { put(KEY_GROUP, it) }
        for (lane in LaneId.ORDERED) {
            val cells = beat.pattern(lane) ?: continue
            put(lane.wireId, encodePattern(cells))
        }
    }

    /** The body plus its id — the shape the on-device store keeps. */
    fun encodeStored(beat: Beat): JsonObject = buildJsonObject {
        beat.id?.let { put(KEY_ID, it) }
        encodeBody(beat).forEach { (key, value) -> put(key, value) }
    }

    fun encodeBeats(beats: List<Beat>): String =
        buildJsonArray { beats.forEach { add(encodeStored(it)) } }.toString()

    fun encodeCategory(category: Category): JsonObject = buildJsonObject {
        put(KEY_ID, category.id)
        put(KEY_NAME, category.name)
        put(KEY_BEAT_IDS, buildJsonArray { category.beatIds.forEach { add(it) } })
    }

    fun encodeCategories(categories: List<Category>): String =
        buildJsonArray { categories.forEach { add(encodeCategory(it)) } }.toString()

    // ── Decoding ───────────────────────────────────────────────────────────

    /** The text of a string primitive; null for an absent key, a literal null, or a wrong type. */
    private fun JsonObject.stringField(key: String): String? = when (val element = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.content
        else -> null
    }

    /**
     * A required whole-number field, read through a double and rounded — see the
     * note on tolerance in the header. Null when absent, non-numeric or not
     * finite: a JSON `1e999` parses to infinity and must not become a loop of
     * that many composables.
     */
    private fun JsonObject.intField(key: String): Int? {
        val value = (this[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!value.isFinite()) return null
        return value.roundToInt()
    }

    /**
     * A fractional field, for meters that need one: 7/8 at eighth-note subdivision
     * is 3.5 quarters to the bar, and the web app stores exactly that. Reading it
     * through [intField] would round it to 4 and silently retime every uneven
     * meter a shared or cloud beat carries.
     */
    private fun JsonObject.doubleField(key: String): Double? {
        val value = (this[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!value.isFinite()) return null
        return value
    }

    /**
     * An optional `groups` array. Every entry must be a sane group size and the
     * array a sane length; the SUM is not required to equal `steps`, because a
     * mismatch there mislabels a bar rather than hanging the app, and refusing the
     * whole library over it is the worse failure.
     *
     * @return `first` the groups, or null when the field is absent — the normal case
     *   for a built-in, which predates the groups model; `second` is true exactly
     *   when the value was PRESENT BUT INVALID. One nullable return cannot say both,
     *   and conflating them would reject every shipped beat. [ShareCodec]'s
     *   `unpackPattern` returns the same pair for the same reason.
     */
    private fun JsonObject.groupsField(): Pair<List<Int>?, Boolean> {
        val element = this[KEY_GROUPS]
        if (element == null || element is JsonNull) return Pair(null, false)
        val array = element as? JsonArray ?: return Pair(null, true)
        if (array.isEmpty() || array.size > ShareCodec.MAX_GROUPS) return Pair(null, true)

        val groups = ArrayList<Int>(array.size)
        for (item in array) {
            val value = (item as? JsonPrimitive)?.doubleOrNull ?: return Pair(null, true)
            if (!value.isFinite()) return Pair(null, true)
            val cells = value.roundToInt()
            if (cells < 1 || cells > ShareCodec.MAX_GROUP_CELLS) return Pair(null, true)
            groups.add(cells)
        }
        return Pair(groups, false)
    }

    /**
     * One lane's cells, with the same two-answer contract as [groupsField]: null
     * with `second` false means the beat has no row for that lane, and null with
     * `second` true means the row is there and wrong (a short array, an unknown
     * stroke code).
     *
     * Telling those apart matters for the karatalas. A beat with no cymbal row is
     * perfectly ordinary; a DAMAGED one must not be quietly dropped, because that
     * would delete a stroke the user drew and then persist the deletion.
     */
    private fun JsonObject.patternField(lane: LaneId, steps: Int): Pair<List<Stroke?>?, Boolean> {
        val element = this[lane.wireId]
        if (element == null || element is JsonNull) return Pair(null, false)
        val array = element as? JsonArray ?: return Pair(null, true)
        if (array.size != steps) return Pair(null, true)

        val cells = ArrayList<Stroke?>(steps)
        for (item in array) {
            if (item is JsonNull) {
                cells.add(null)
                continue
            }
            val code = (item as? JsonPrimitive)?.content ?: return Pair(null, true)
            // An unrecognised code is corruption, not a rest: reading it as a rest
            // would silently delete a stroke the user drew.
            cells.add(Stroke.fromCode(code) ?: return Pair(null, true))
        }
        return Pair(cells, false)
    }

    /**
     * A stored beat, or null when the blob is not a beat this app wrote.
     *
     * Both mridanga ends are REQUIRED. A blob without them is not a beat the
     * editor saved, and accepting it would put a patternless row in the library
     * that plays silence while looking fine in the list — the same class of quiet
     * wrongness rule 3 of the contract exists to prevent.
     */
    fun decodeBeat(element: JsonElement?): Beat? {
        val obj = element as? JsonObject ?: return null

        val name = obj.stringField(KEY_NAME) ?: return null
        val note = obj.stringField(KEY_NOTE) ?: return null
        val bpm = obj.intField(KEY_BPM) ?: return null
        val steps = obj.intField(KEY_STEPS) ?: return null
        val beatsPerBar = obj.doubleField(KEY_BEATS_PER_BAR) ?: return null
        val cellsPerGroup = obj.intField(KEY_CELLS_PER_GROUP) ?: return null

        if (bpm !in MIN_BPM..MAX_BPM) return null
        if (steps !in 1..ShareCodec.MAX_STEPS) return null
        if (beatsPerBar < 1 || cellsPerGroup < 1) return null

        val (groups, badGroups) = obj.groupsField()
        if (badGroups) return null

        val lanes = LinkedHashMap<LaneId, List<Stroke?>>()
        for (lane in LaneId.ORDERED) {
            val (cells, rejected) = obj.patternField(lane, steps)
            if (rejected) return null
            if (cells != null) lanes[lane] = cells
        }
        if (!lanes.containsKey(LaneId.DAYAN) || !lanes.containsKey(LaneId.BAYAN)) return null

        return Beat(
            id = obj.stringField(KEY_ID),
            name = name,
            note = note,
            bpm = bpm,
            steps = steps,
            beatsPerBar = beatsPerBar,
            cellsPerGroup = cellsPerGroup,
            groups = groups,
            description = obj.stringField(KEY_DESCRIPTION),
            lanePatterns = lanes,
            group = obj.stringField(KEY_GROUP),
        )
    }

    /** A stored category, or null when the blob is not one. */
    fun decodeCategory(element: JsonElement?): Category? {
        val obj = element as? JsonObject ?: return null
        val id = obj.stringField(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null
        val name = obj.stringField(KEY_NAME) ?: return null

        val beatIds = when (val element = obj[KEY_BEAT_IDS]) {
            // An absent or null list is an empty progression, not corruption: a
            // freshly created category holds nothing yet, and an older row may
            // predate the field.
            null, is JsonNull -> emptyList()
            // STRING primitives only. `beat_ids` is a Postgres text[] and the local
            // store writes strings, so a number here is damage — and coercing it
            // would quietly rename a beat reference to something that matches
            // nothing, emptying part of a progression with no error anywhere.
            is JsonArray -> element.map { item ->
                if (item is JsonPrimitive && item.isString) item.content else return null
            }
            else -> return null
        }

        return Category(id = id, name = name, beatIds = beatIds)
    }

    /**
     * Parse a stored list of beats.
     *
     * @return the beats, or null when the text is not a JSON array of decodable
     *   beats. Null and "an empty library" are different answers and the caller
     *   must not confuse them — the first is a failure to surface, the second is
     *   a perfectly good state.
     */
    fun decodeBeats(raw: String?): List<Beat>? = decodeList(raw, ::decodeBeat)

    fun decodeCategories(raw: String?): List<Category>? = decodeList(raw, ::decodeCategory)

    private fun <T> decodeList(raw: String?, decode: (JsonElement?) -> T?): List<T>? {
        // Absent is an empty library — the ONLY tolerant case in this file.
        if (raw == null) return emptyList()
        return try {
            val array = json.parseToJsonElement(raw) as? JsonArray ?: return null
            val out = ArrayList<T>(array.size)
            for (item in array) {
                // One undecodable row rejects the whole list; see the header.
                out.add(decode(item) ?: return null)
            }
            out
        } catch (e: Exception) {
            // Truncated JSON, or a value that isn't JSON at all: one null, never
            // a crash and never a wipe.
            null
        }
    }
}
