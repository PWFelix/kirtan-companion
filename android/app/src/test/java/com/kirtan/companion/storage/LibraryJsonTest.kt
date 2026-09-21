package com.kirtan.companion.storage

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.BeatSourceExport
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.LaneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored shape of a beat — the bytes that have to survive a save, a reload, and
 * (for the cloud store) a trip through a database the WEB APP also writes to.
 *
 * The round trip over every built-in beat is the one that matters most: it is the
 * only thing standing between a persistence change and a library that quietly loses a
 * stroke, a tempo or a description. It is asserted as WHOLE-BEAT EQUALITY rather than
 * field by field, so a field added to [Beat] without being added to the codec fails
 * here immediately instead of being silently dropped from every save.
 */
class LibraryJsonTest {

    // ── Round tripping ─────────────────────────────────────────────────────

    @Test
    fun `every built-in beat round-trips losslessly`() {
        for (beat in BEATS) {
            val decoded = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))

            assertEquals("${beat.id}: the stored form must decode to the same beat", beat, decoded)
            // Belt and braces on the two fields a silent default would hide: a
            // description that renders as a paragraph, and the group heading the
            // Beats screen derives its sections from.
            assertEquals("${beat.id}: description", beat.description, decoded?.description)
            assertEquals("${beat.id}: group", beat.group, decoded?.group)
        }
    }

    @Test
    fun `a round trip keeps every cell of every lane`() {
        // Whole-beat equality would also pass if BOTH sides lost a cell, so the
        // patterns are checked against the source data directly.
        for (beat in BEATS) {
            val decoded = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))!!
            for (lane in LaneId.ORDERED) {
                assertEquals("${beat.id}/${lane.wireId}: cells", beat.pattern(lane), decoded.pattern(lane))
            }
            assertEquals("${beat.id}: lanes present", beat.activeLanes(), decoded.activeLanes())
        }
    }

    @Test
    fun `a beat with no kartal row stays a two-lane beat`() {
        val beat = testBeat(id = "two-lane", kartal = null)

        val decoded = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))!!

        assertEquals(beat, decoded)
        assertEquals(listOf(LaneId.DAYAN, LaneId.BAYAN), decoded.activeLanes())
        assertNull("an absent lane is absent, not all-rests", decoded.pattern(LaneId.KARTAL))
    }

    @Test
    fun `a beat with a kartal row keeps it`() {
        val beat = testBeat(id = "three-lane", kartal = "O-O-O---")

        val decoded = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))!!

        assertEquals(pattern("O-O-O---"), decoded.pattern(LaneId.KARTAL))
    }

    @Test
    fun `an uneven meter survives`() {
        // 7/8 as [2,2,3]: the groups array is the only thing that can express it, so
        // losing or flattening it silently retimes the beat.
        val beat = testBeat(
            id = "seven",
            steps = 7,
            beatsPerBar = 7.0,
            cellsPerGroup = 1,
            groups = listOf(2, 2, 3),
            dayan = "X-OOX-O",
            bayan = "O--X-OO",
        )

        assertEquals(beat, LibraryJson.decodeBeat(LibraryJson.encodeStored(beat)))
    }

    @Test
    fun `groups round-trip, and a beat without them stores none`() {
        // The shipped set is generated from a share payload, which always carries
        // groups, so they must survive the store exactly.
        val shipped = BEATS.first()
        assertNotNull(shipped.groups)
        assertEquals(
            shipped.groups,
            LibraryJson.decodeBeat(LibraryJson.encodeStored(shipped))?.groups,
        )

        // A beat authored before the groups model carries none, and the store must
        // not invent them: writing `[]` or reconstructing uniform groups would make
        // a round trip lie about which model the beat was authored in.
        val legacy = shipped.copy(id = null, groups = null)
        assertFalse(LibraryJson.encodeStored(legacy).containsKey("groups"))
        assertNull(LibraryJson.decodeBeat(LibraryJson.encodeStored(legacy))?.groups)
    }

    @Test
    fun `a draft's body round-trips without an id`() {
        val draft = testBeat(id = null, name = "Unsaved")

        val encoded = LibraryJson.encodeBody(draft)
        assertFalse("the body must not carry an id", encoded.containsKey("id"))

        val decoded = LibraryJson.decodeBeat(encoded)!!
        assertNull(decoded.id)
        assertEquals(draft, decoded)
    }

    @Test
    fun `categories round-trip`() {
        val category = testCategory(id = "cat-1", name = "Sunday feast", beatIds = listOf("te_ta", "abc"))

        assertEquals(
            listOf(category),
            LibraryJson.decodeCategories(LibraryJson.encodeCategories(listOf(category))),
        )
    }

    // ── Cross-platform shape ───────────────────────────────────────────────

    @Test
    fun `the stored shape uses the web's field names and lane arrays`() {
        val shipped = BEATS.first()
        val encoded = LibraryJson.encodeStored(shipped)

        assertEquals(
            setOf(
                "id", "name", "note", "bpm", "steps", "beatsPerBar", "cellsPerGroup",
                // `groups` is present because the shipped set carries its meter
                // explicitly; a beat authored before the groups model omits it.
                // `description` is absent here for the same reason: the shipped
                // beats have no prose, and a null field is omitted rather than
                // written as null.
                "groups", "group", "dayan", "bayan", "kartal",
            ),
            encoded.keys,
        )
        // Lanes are arrays of "O" / "X" / null, one entry per cell — NOT the compact
        // "X-OO-XOO" strings ShareCodec uses. A link has to survive a messenger; a
        // database row does not, and this is the shape the web app writes.
        val bayan = encoded["bayan"]!!.jsonArray
        assertEquals(shipped.steps, bayan.size)
        assertEquals(JsonPrimitive("O"), bayan[0])
        assertEquals(JsonNull, bayan[1])
        assertEquals(JsonPrimitive("X"), bayan[3])
    }

    @Test
    fun `a beat saved by the web app decodes to the same beat`() {
        // Built field by field in the web's own shape (see src/data/beats.js), with
        // the description taken from the compiled beat so the assertion is about the
        // SHAPE rather than about a paragraph transcribed twice by hand.
        val shipped = BEATS.first()
        val webBlob = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", shipped.id)
                    put("name", shipped.name)
                    put("note", shipped.note)
                    put("bpm", shipped.bpm)
                    put("steps", shipped.steps)
                    put("beatsPerBar", shipped.beatsPerBar)
                    put("cellsPerGroup", shipped.cellsPerGroup)
                    // Derived from the shipped beat rather than hardcoded, so
                    // regenerating the built-in set does not silently invalidate
                    // this test — it is about the SHAPE, not the patterns.
                    shipped.groups?.let { groups ->
                        put("groups", buildJsonArray { groups.forEach { add(it) } })
                    }
                    shipped.description?.let { put("description", it) }
                    shipped.group?.let { put("group", it) }
                    for (lane in LaneId.ORDERED) {
                        val notation = BeatSourceExport.patternNotation(shipped, lane)
                        if (notation.isNotEmpty()) put(lane.wireId, laneArray(notation))
                    }
                },
            )
        }

        assertEquals(listOf(shipped), LibraryJson.decodeBeats(webBlob.toString()))
    }

    private fun laneArray(notation: String) = buildJsonArray {
        for (cell in notation) {
            if (cell == '-') add(JsonNull) else add(JsonPrimitive(cell.toString()))
        }
    }

    @Test
    fun `a whole number written as a double still decodes`() {
        // Every JSON number the web encoder emits is a JS double, so a browser-saved
        // `"bpm": 90` and a `90.0` are the same beat and neither is corruption.
        val decoded = decode(beatJson(bpm = "90.0", steps = "8.0", beatsPerBar = "4.0", cellsPerGroup = "2.0"))

        assertEquals(testBeat(id = "x", name = "N", groups = null), decoded)
    }

    @Test
    fun `a field this build has never heard of is ignored`() {
        // Forward compatibility: a row written by a NEWER app must still read here,
        // or upgrading one platform breaks the other's library.
        val decoded = decode(beatJson(extra = """"futureField":{"anything":true},"melody":["O","O"]"""))

        assertEquals(testBeat(id = "x", name = "N", groups = null), decoded)
    }

    // ── Refusing what is not a beat ────────────────────────────────────────

    @Test
    fun `an absent value is an empty library, not a failure`() {
        // The ONE tolerant case in the codec: no key at all means "nothing saved
        // yet", which is a perfectly good state and must not be reported as damage.
        assertEquals(0, LibraryJson.decodeBeats(null)?.size)
        assertEquals(0, LibraryJson.decodeCategories(null)?.size)
        assertEquals(0, LibraryJson.decodeBeats("[]")?.size)
    }

    @Test
    fun `truncated json is refused rather than half-decoded`() {
        val truncated = LibraryJson.encodeBeats(BEATS.take(3)).dropLast(20)

        assertNull("a truncated store must not decode to the beats that fit", LibraryJson.decodeBeats(truncated))
    }

    @Test
    fun `one bad row rejects the whole list`() {
        // Skipping the bad row and writing the rest back would be a silent partial
        // delete — one beat missing and nothing anywhere saying why.
        val good = LibraryJson.encodeStored(testBeat(id = "good"))

        assertNull(LibraryJson.decodeBeats("[$good, 42]"))
        assertNull(LibraryJson.decodeBeats("[$good, {}]"))
        assertNull(LibraryJson.decodeBeats("""{"not":"a list"}"""))
        assertNull(LibraryJson.decodeBeats("not json at all"))
    }

    @Test
    fun `a beat with impossible numbers is refused`() {
        // Steps of a billion would mean a composable per cell and a frozen UI — the
        // same bound ShareCodec enforces on a payload from a stranger.
        assertNull(decode(beatJson(steps = "1000000000")))
        assertNull(decode(beatJson(steps = "0")))
        assertNull(decode(beatJson(bpm = "100000")))
        assertNull(decode(beatJson(bpm = "1e999")))
        assertNull(decode(beatJson(beatsPerBar = "0")))
    }

    @Test
    fun `a lane of the wrong length is refused`() {
        // A short array would read as trailing rests and silently truncate the
        // pattern — the failure mode data/Beats.kt checks at class-load time. The
        // default lanes hold 8 cells, so claiming 4 steps cannot be right.
        assertNull(decode(beatJson(steps = "4", beatsPerBar = "2", cellsPerGroup = "2")))
    }

    @Test
    fun `a damaged karatala row is refused rather than quietly dropped`() {
        // An ABSENT kartal row is normal — a two-lane beat has none — but a damaged
        // one must not be read as absent: that would delete the cymbals the user drew
        // and then persist the deletion on the next save.
        assertNull(decode(beatJson(kartal = """["O","Q"]""")))
        assertNull(decode(beatJson(kartal = """["O"]""")))
        // The compact share-code form is a STRING; the stored form is an array. A
        // string here means something wrote the wrong shape into the row.
        assertNull(decode(beatJson(kartal = "\"O-\"")))

        assertEquals(
            pattern("O-O-O-O-"),
            decode(beatJson(kartal = laneJson("O-O-O-O-")))?.pattern(LaneId.KARTAL),
        )

        // A beat with no kartal row at all is fine — that is how every two-lane beat
        // is stored — and reads as an absent lane, not as rests.
        val twoLane = decode(beatJson())
        assertNotNull(twoLane)
        assertNull(twoLane!!.pattern(LaneId.KARTAL))
        assertEquals(listOf(LaneId.DAYAN, LaneId.BAYAN), twoLane.activeLanes())
    }

    @Test
    fun `a beat missing a required field is refused`() {
        assertNull(LibraryJson.decodeBeat(buildJsonObject { put("id", "x"); put("name", "N") }))
        assertNull(decode(beatJson(note = null)))
        assertNull(decode(beatJson(bpm = null)))
        assertNull(decode(beatJson(dayan = null)))
        assertNull(LibraryJson.decodeBeat(JsonNull))
        assertNull(LibraryJson.decodeBeat(buildJsonArray { }))
    }

    @Test
    fun `a groups array with an impossible entry is refused`() {
        assertNull(decode(beatJson(groups = "[2,0,2,2]")))
        assertNull(decode(beatJson(groups = "[2,99]")))
        assertNull(decode(beatJson(groups = "[]")))
        // A sane one is kept exactly as written.
        assertEquals(listOf(2, 2), decode(beatJson(groups = "[2,2]"))?.groups)
    }

    @Test
    fun `a category with no id is refused and one with no beats is not`() {
        assertNull(decodeCategory("""{"name":"No id","beatIds":[]}"""))
        assertNull(decodeCategory("""{"id":"","name":"Blank id"}"""))

        // An empty progression is a perfectly good playlist — it is what "new
        // category" creates — and an absent list means the same thing.
        val empty = testCategory(id = "c", name = "Empty", beatIds = emptyList())
        assertEquals(empty, decodeCategory("""{"id":"c","name":"Empty"}"""))
        assertEquals(empty, decodeCategory("""{"id":"c","name":"Empty","beatIds":[]}"""))
    }

    @Test
    fun `a category holding a non-string beat id is refused`() {
        // A built-in id like "te_ta" is a legal entry, so this cannot be a number
        // that should have been coerced: it is corruption, and guessing would
        // repoint the progression at something the user never chose.
        assertNull(decodeCategory("""{"id":"c","name":"N","beatIds":["a",7]}"""))
        assertNotNull(decodeCategory("""{"id":"c","name":"N","beatIds":["te_ta"]}"""))
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** One beat blob, wrapped in a list and decoded through the same path a store uses. */
    private fun decode(blob: String): Beat? = LibraryJson.decodeBeats("[$blob]")?.singleOrNull()

    private fun decodeCategory(blob: String): Category? =
        LibraryJson.decodeCategories("[$blob]")?.singleOrNull()

    /**
     * A stored-beat literal. Numbers are passed as TEXT so a test can write the ones
     * that are not legal values — `1e999`, `0`, `1000000000` — and a null field is
     * omitted entirely, which is how a missing key looks on the wire.
     */
    private fun beatJson(
        id: String = "x",
        name: String = "N",
        note: String? = "Custom",
        bpm: String? = "90",
        steps: String = "8",
        beatsPerBar: String = "4",
        cellsPerGroup: String = "2",
        groups: String? = null,
        dayan: String? = laneJson("X-OOX-OO"),
        bayan: String? = laneJson("O--X-OO-"),
        kartal: String? = null,
        extra: String? = null,
    ): String = buildString {
        val fields = LinkedHashMap<String, String>()
        fields["id"] = """"$id""""
        fields["name"] = """"$name""""
        note?.let { fields["note"] = """"$it"""" }
        bpm?.let { fields["bpm"] = it }
        fields["steps"] = steps
        fields["beatsPerBar"] = beatsPerBar
        fields["cellsPerGroup"] = cellsPerGroup
        groups?.let { fields["groups"] = it }
        dayan?.let { fields["dayan"] = it }
        bayan?.let { fields["bayan"] = it }
        kartal?.let { fields["kartal"] = it }
        append("{")
        append(fields.entries.joinToString(",") { (key, value) -> """"$key":$value""" })
        if (extra != null) append(",$extra")
        append("}")
    }

    /**
     * A lane as the STORED array form of the `"X-OO-XOO"` notation.
     *
     * The defaults above are chosen to match [testBeat]'s, so a test that only wants
     * to vary one field can compare against `testBeat(id = "x", name = "N")` and know
     * the rest of the blob is the fixture it thinks it is.
     */
    private fun laneJson(notation: String): String =
        buildString {
            append('[')
            notation.forEachIndexed { index, cell ->
                if (index > 0) append(',')
                append(if (cell == '-') "null" else "\"$cell\"")
            }
            append(']')
        }
}
