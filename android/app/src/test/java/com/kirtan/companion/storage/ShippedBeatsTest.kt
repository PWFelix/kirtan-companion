package com.kirtan.companion.storage

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.DEFAULT_BEAT
import com.kirtan.companion.data.ShareCodec
import com.kirtan.companion.data.ShippedBeats
import com.kirtan.companion.data.model.LaneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's built-in beat set as the app reads it — and as it writes one.
 *
 * The first test is the one that matters, and it is the reason the row format was
 * chosen to be derivable rather than stored: a row written by [shippedRowFor] and
 * read back by [shippedBeatsOf] must produce a beat EQUAL to the compiled one it
 * came from. That closes the loop between the two platforms without a network,
 * because both implement exactly these two functions against the same table, and a
 * `steps` column either side could have got wrong does not exist to disagree about.
 *
 * The live table was checked against the compiled set out of band (all nine rows,
 * field for field and cell for cell, over the anon REST endpoint). What is NOT
 * pinned here is the live CONTENT, deliberately: the whole point of serving the
 * beats is that a maintainer can change them, and a test holding a snapshot of the
 * table would fail the first time anyone used the feature.
 *
 * The rest is the trust boundary. A served set replaces what every install plays,
 * so the rules for accepting one are strict and ALL OR NOTHING — see
 * [ShippedBeatsClient]'s header for why a partial set is the worse failure.
 */
class ShippedBeatsTest {

    // ── The row format round-trips ─────────────────────────────────────────

    @Test
    fun `every compiled beat survives a trip through the row format unchanged`() {
        val rows = BEATS.mapIndexed { ordinal, beat ->
            shippedRowFor(
                beat.id!!, ordinal, beat.group!!, beat,
                sourcePublishedId = null, description = beat.description,
            )
        }

        assertEquals(BEATS, shippedBeatsOf(JsonArray(rows)))
    }

    @Test
    fun `an absent cymbal lane stays absent and a present one is kept`() {
        // The convention the strip and the editor both read: "no cymbals" is an
        // ABSENT key, not a lane of rests. Materialising it would render an empty
        // row and offer the user a lane they never drew.
        val twoLane = shippedRowFor("two", 0, "Straight", testBeat(id = "two", name = "Two"), null, null)
        val derived = shippedBeatsOf(one(twoLane))!!.single()
        assertNull(derived.pattern(LaneId.KARTAL))
        assertEquals(listOf(LaneId.DAYAN, LaneId.BAYAN), derived.activeLanes())

        val threeLane = shippedRowFor(
            "three",
            0,
            "Straight",
            testBeat(id = "three", name = "Three", kartal = "O-O-O-O-"),
            null,
            null,
        )
        assertEquals(
            pattern("O-O-O-O-"),
            shippedBeatsOf(one(threeLane))!!.single().pattern(LaneId.KARTAL),
        )
    }

    @Test
    fun `rows are ordered by ordinal, not by the order they arrived in`() {
        val rows = BEATS.take(3).mapIndexed { ordinal, beat ->
            shippedRowFor(beat.id!!, ordinal, beat.group!!, beat, null, beat.description)
        }

        val shuffled = JsonArray(listOf(rows[2], rows[0], rows[1]))

        assertEquals(
            BEATS.take(3).map { it.id },
            shippedBeatsOf(shuffled)!!.map { it.id },
        )
    }

    @Test
    fun `a whole number sent as a double still parses`() {
        // A row written by a JS client carries JS numbers, and `bpm: 90` and
        // `bpm: 90.0` are the same beat. Refusing the second would reject every
        // beat the web app promotes.
        val asInt = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)
        val asDouble = asInt.with("bpm", JsonPrimitive(90.0))

        assertEquals(
            shippedBeatsOf(one(asInt))!!.single(),
            shippedBeatsOf(one(asDouble))!!.single(),
        )
    }

    // ── The trust boundary ─────────────────────────────────────────────────

    @Test
    fun `one unusable row rejects the whole set`() {
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)
        assertNotNull("the fixture itself must be valid", shippedBeatsOf(one(good)))

        // A set, not a row, is the unit of acceptance: the Beats screen takes its
        // section headings from the set's order and playlists address it by id, so
        // a set with one beat quietly missing is worse than no set at all.
        assertNull(shippedBeatsOf(one(good, good)))                       // one id twice
        assertNull(shippedBeatsOf(JsonArray(emptyList())))                // nothing served
        assertNull(shippedBeatsOf(null))                                  // no body at all
        assertNull(shippedBeatsOf(Json.parseToJsonElement("""{"error":"rate limited"}""")))

        assertNull(shippedBeatsOf(one(good.without("id"))))
        assertNull(shippedBeatsOf(one(good.with("id", JsonPrimitive("")))))
        assertNull(shippedBeatsOf(one(good.without("heading"))))
        assertNull(shippedBeatsOf(one(good.without("name"))))
        assertNull(shippedBeatsOf(one(good.without("ordinal"))))
        assertNull(shippedBeatsOf(one(good.without("bpm"))))
        assertNull(shippedBeatsOf(one(good.with("bpm", JsonPrimitive(999)))))
        assertNull(shippedBeatsOf(one(good.with("bpm", JsonPrimitive(0)))))
        assertNull(shippedBeatsOf(one(good.with("cpq", JsonPrimitive(0)))))
        assertNull(shippedBeatsOf(one(good.with("cpq", JsonPrimitive(ShareCodec.MAX_CPQ + 1)))))
        assertNull(shippedBeatsOf(one(good.with("groups", JsonArray(emptyList())))))
        assertNull(shippedBeatsOf(one(good.without("lanes"))))
        assertNull(shippedBeatsOf(one(good.with("lanes", JsonPrimitive("X-OO")))))
        // A description that is not text is a row something else wrote; guessing at
        // it would put a number where the info sheet renders a paragraph.
        assertNull(shippedBeatsOf(one(good.with("description", JsonPrimitive(7)))))
    }

    @Test
    fun `the share codec's bounds are the built-in bounds`() {
        // Not new numbers: a promoted beat arrives through the share format, so
        // "expressible as a share code" and "acceptable as a built-in" are the same
        // set by construction.
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)

        // More numbered beats than the codec allows.
        val tooMany = buildJsonArray { repeat(ShareCodec.MAX_GROUPS + 1) { add(2) } }
        assertNull(shippedBeatsOf(one(good.with("groups", tooMany))))

        // One group wider than the codec allows.
        val tooWide = buildJsonArray {
            add(ShareCodec.MAX_GROUP_CELLS + 1)
            add(2)
            add(2)
            add(2)
        }
        assertNull(shippedBeatsOf(one(good.with("groups", tooWide))))

        // Legal groups that sum past the cell bound. Checked before the lanes, so
        // the pattern lengths here are irrelevant to the refusal.
        val tooLong = buildJsonArray { repeat(6) { add(ShareCodec.MAX_GROUP_CELLS) } }
        assertEquals(72, tooLong.sumOf { it.jsonPrimitive.int })
        assertTrue(72 > ShareCodec.MAX_STEPS)
        assertNull(shippedBeatsOf(one(good.with("groups", tooLong))))

        // The bounds are INCLUSIVE, and the ceiling is reachable by a real beat:
        // 32 numbered beats of 2 cells is exactly MAX_STEPS. A bound nobody can
        // legally reach would be indistinguishable from an off-by-one.
        val atLimit = testBeat(
            id = "at_limit",
            name = "At the limit",
            group = "Straight",
            steps = ShareCodec.MAX_STEPS,
            beatsPerBar = 32.0,
            cellsPerGroup = 2,
            groups = List(ShareCodec.MAX_GROUPS) { 2 },
            dayan = "O".repeat(ShareCodec.MAX_STEPS),
            bayan = "X".repeat(ShareCodec.MAX_STEPS),
        )
        val accepted = shippedBeatsOf(
            one(shippedRowFor("at_limit", 0, "Straight", atLimit, null, atLimit.description)),
        )
        // `group` comes from the row's HEADING and `note` from the row's group
        // count, so the fixture has to carry the same ones — a beat with no section
        // is a beat the Beats screen's heading loop never renders.
        assertEquals(listOf(atLimit.copy(group = "Straight", note = "32 beats")), accepted)
    }

    @Test
    fun `a number where a string belongs is refused`() {
        // The web client requires an actual string for these four, so accepting a
        // number's TEXT here — which the library's lenient `stringContent` does,
        // rightly, for a uuid a database might render bare — would leave one
        // platform serving a row the other rejects. Two platforms disagreeing
        // about a row are two platforms showing different built-in sets, and a
        // shared link resolves built-in ids against whichever one you are holding.
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)

        for (column in listOf("id", "heading", "name", "note")) {
            assertNull(
                "$column: a number must not be read as text",
                shippedBeatsOf(one(good.with(column, JsonPrimitive(7)))),
            )
            assertNull(
                "$column: an object must not be read as text",
                shippedBeatsOf(one(good.with(column, Json.parseToJsonElement("{\"a\":1}")))),
            )
        }
        // A blank id or name is a beat with nothing to address it and nothing to
        // read in the list. An empty NOTE is fine: the column defaults to ''.
        assertNull(shippedBeatsOf(one(good.with("id", JsonPrimitive("   ")))))
        assertNull(shippedBeatsOf(one(good.with("name", JsonPrimitive("")))))
        assertNotNull(shippedBeatsOf(one(good.with("note", JsonPrimitive("")))))
    }

    @Test
    fun `a fractional number is refused rather than rounded`() {
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)

        // Rounding 4.4 cells to 4 is a guess about somebody's meter, and the web
        // refuses it — so guessing here is how the two platforms come to serve
        // different sets from one table.
        assertNull(shippedBeatsOf(one(good.with("bpm", JsonPrimitive(90.5)))))
        assertNull(shippedBeatsOf(one(good.with("cpq", JsonPrimitive(2.5)))))
        assertNull(
            shippedBeatsOf(
                one(good.with("groups", buildJsonArray { add(2.5); add(2); add(2); add(2) })),
            ),
        )
        assertNull(shippedBeatsOf(one(good.with("ordinal", JsonPrimitive(0.5)))))

        // A whole double is NOT a fraction: `90.0` is what a JS writer produces,
        // and in JS `Number.isInteger(90.0)` is true, so the web accepts it too.
        assertNotNull(shippedBeatsOf(one(good.with("ordinal", JsonPrimitive(0.0)))))
    }

    @Test
    fun `a row no client could serve cannot be promoted`() {
        // The promote path runs the row it built back through the read path
        // before writing it, and this is the case that guard exists for: a blank
        // name slugs to "beat" happily, but no client will serve the row, and
        // since the set is all-or-nothing, writing it would take every user's
        // built-in list back to the compiled fallback.
        val blank = shippedRowFor("beat", 0, "Community", testBeat(name = "   "), null, null)
        assertNull(shippedBeatsOf(one(blank)))

        // A real beat's row always passes — the guard must not refuse the
        // ordinary case.
        val real = shippedRowFor("dadra", 0, "Community", testBeat(name = "Dadra"), null, null)
        assertNotNull(shippedBeatsOf(one(real)))
    }

    @Test
    fun `a lane of the wrong length or an unknown stroke is refused`() {
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)

        // A short pattern reads as trailing rests and silently truncates the loop —
        // the failure data/Beats.kt checks at class-load time for the compiled set.
        assertNull(shippedBeatsOf(one(withLane(good, "dayan", "X-OOX-O"))))
        assertNull(shippedBeatsOf(one(withLane(good, "dayan", "X-OOX-OO-"))))
        assertNull(shippedBeatsOf(one(withLane(good, "kartal", "O-"))))

        // An unknown character is refused rather than read as a rest: a rest is a
        // claim that nothing is struck there, so a typo would become a beat that
        // plays wrong while looking exactly right.
        assertNull(shippedBeatsOf(one(withLane(good, "bayan", "O--X-OOQ"))))
        assertNull(shippedBeatsOf(one(withLane(good, "dayan", "X-OOX-OO".lowercase()))))

        // Both mridanga ends are a beat's identity.
        assertNull(shippedBeatsOf(one(good.with("lanes", lanesOf(good).without("dayan")))))
        assertNull(shippedBeatsOf(one(good.with("lanes", lanesOf(good).without("bayan")))))
    }

    @Test
    fun `an unknown lane key is ignored rather than becoming a lane`() {
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)
        val withMelody = withLane(good, "melody", "OOOOOOOO")

        val derived = shippedBeatsOf(one(withMelody))!!.single()

        // Lanes are read by an allowlist of ids, so a column a future version adds
        // cannot become an instrument this build has no samples for.
        assertEquals(listOf(LaneId.DAYAN, LaneId.BAYAN), derived.activeLanes())
        assertTrue(derived.lanePatterns.keys.all { it in LaneId.ORDERED })
    }

    @Test
    fun `a served beat this build never compiled is still read-only`() {
        // The case that makes serving the set safe: a beat promoted after this APK
        // was built has an id that appears nowhere in it, and `isBuiltIn` is what
        // stops the editor offering to overwrite it.
        val before = ShippedBeats.effective.value
        try {
            val promoted = testBeat(
                id = "promoted_after_this_build",
                name = "Dadra",
                group = "Community",
            )
            assertFalse("the fixture must not already be a built-in", promoted.isBuiltIn)

            ShippedBeats.install(listOf(promoted) + before)

            assertTrue(promoted.isBuiltIn)
            assertTrue(promoted.readOnly)
            assertEquals("Community", ShippedBeats.groups.first())
            assertEquals(promoted, DEFAULT_BEAT)
        } finally {
            // The registry is process-wide, and other test classes assert against
            // the compiled set. Restoring is not tidiness, it is isolation.
            ShippedBeats.install(before)
        }

        assertFalse(testBeat(id = "promoted_after_this_build").isBuiltIn)
        assertEquals(before, ShippedBeats.effective.value)
    }

    @Test
    fun `an empty set is refused rather than adopted`() {
        val before = ShippedBeats.effective.value

        ShippedBeats.install(emptyList())

        // An empty built-in set would leave the app with no default beat and an
        // empty Built-in section: "the server had nothing" must read as "keep what
        // we have".
        assertEquals(before, ShippedBeats.effective.value)
        assertEquals(BEATS, ShippedBeats.compiled)
    }

    // ── The cache's revision ───────────────────────────────────────────────

    @Test
    fun `the revision is the newest updated_at in the set`() {
        val good = shippedRowFor("dadra", 0, "Straight", testBeat(id = "dadra", name = "Dadra"), null, null)
        val rows = buildJsonArray {
            add(good.with("updated_at", JsonPrimitive("2026-01-01T00:00:00+00:00")))
            add(good.with("updated_at", JsonPrimitive("2026-09-21T23:15:17.447734+00:00")))
            add(good.with("updated_at", JsonPrimitive("2026-03-05T12:00:00+00:00")))
        }

        assertEquals("2026-09-21T23:15:17.447734+00:00", shippedRevision(rows))
        // Compared as TEXT, which is sound only because PostgREST renders
        // timestamptz in one fixed ISO-8601 form: lexicographic order is
        // chronological order for that form and nothing else.
        assertEquals("", shippedRevision(buildJsonArray { }))
        assertEquals("", shippedRevision(buildJsonArray { add(good) }))
    }

    // ── Promoting ──────────────────────────────────────────────────────────

    @Test
    fun `a promoted row carries the meter and not the fields derived from it`() {
        val beat = testBeat(name = "Dadra", description = "A galloping feel")
        val row = shippedRowFor("dadra", 3, "Community", beat, "published-id", beat.description)

        assertEquals(
            setOf(
                "id", "ordinal", "heading", "name", "note", "bpm", "groups", "cpq",
                "lanes", "description", "source_published_id",
            ),
            row.keys,
        )
        assertEquals(3, row["ordinal"]!!.jsonPrimitive.int)
        // Derived from the group count, NOT taken from the beat: the draft says
        // "Custom", which is meaningless in a built-in list and would go stale the
        // moment a maintainer changed the meter.
        assertEquals("4 beats", row["note"]!!.jsonPrimitive.content)
        assertEquals("Community", row["heading"]!!.jsonPrimitive.content)
        assertEquals(listOf(2, 2, 2, 2), row["groups"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(2, row["cpq"]!!.jsonPrimitive.int)
        assertEquals("A galloping feel", row["description"]!!.jsonPrimitive.content)
        assertEquals("published-id", row["source_published_id"]!!.jsonPrimitive.content)

        // And it reads back as the same beat, in the section it was promoted into
        // and with the note the row derives rather than the one the draft carried.
        assertEquals(
            beat.copy(id = "dadra", group = "Community", note = "4 beats"),
            shippedBeatsOf(one(row))!!.single(),
        )
    }

    @Test
    fun `a beat with no prose writes an explicit null rather than omitting the column`() {
        // `resolution=merge-duplicates` only touches the columns present, so an
        // omitted `description` would leave the PREVIOUS row's prose attached to a
        // beat that no longer has any. A promote replaces a row wholesale.
        val row = shippedRowFor("dadra", 0, "Community", testBeat(name = "Dadra"), null, null)

        assertTrue(row.containsKey("description"))
        assertEquals(JsonNull, row["description"])
        assertTrue(row.containsKey("source_published_id"))
        assertEquals(JsonNull, row["source_published_id"])

        // A null is still "no prose" on the way back in, not a damaged row.
        assertNull(shippedBeatsOf(one(row))!!.single().description)
    }

    @Test
    fun `a promoted beat's id is a stable readable slug`() {
        assertEquals("dadra_taal", shippedIdFor("Dadra Taal", emptySet()))
        assertEquals("double_time_2", shippedIdFor("Double Time 2", emptySet()))
        // Punctuation collapses to ONE underscore, including the non-ASCII sort:
        // an id per character would be unreadable in the dashboard, which is where
        // a maintainer goes to fix a row.
        assertEquals("lofa_taal_damodarastakam", shippedIdFor("lofa taal — Damodarastakam!", emptySet()))
        assertEquals("beat", shippedIdFor("!!!", emptySet()))
        assertEquals("beat", shippedIdFor("", emptySet()))
    }

    @Test
    fun `a slug that is already shipping is suffixed rather than reused`() {
        // The write is an upsert, so reusing an id would silently REPLACE that beat
        // for every user — a deletion nobody asked for, and invisible in the app
        // that did it.
        assertEquals("dadra", shippedIdFor("Dadra", emptySet()))
        assertEquals("dadra_2", shippedIdFor("Dadra", setOf("dadra")))
        assertEquals("dadra_3", shippedIdFor("Dadra", setOf("dadra", "dadra_2")))
    }

    @Test
    fun `a long name is truncated to a legal id`() {
        val id = shippedIdFor("ab ".repeat(30), emptySet())

        assertTrue("id \"$id\" is ${id.length} characters", id.length <= 48)
        // Truncation can land on a separator, and an id ending in one is a slug
        // nobody meant to write.
        assertFalse("id \"$id\" ends in a separator", id.endsWith("_"))
        assertEquals("ab_ab", shippedIdFor("ab ab", emptySet()))
    }

    @Test
    fun `the note counts the groups, whatever the beat claims`() {
        val beat = testBeat(id = "dadra", name = "Dadra", note = "Custom")
        assertEquals(
            "4 beats",
            shippedRowFor("dadra", 0, "Straight", beat, null, null)["note"]!!.jsonPrimitive.content,
        )

        // The case that makes deriving worth it: a maintainer widens the bar, and
        // a preserved note would keep saying "4 beats" over five groups — in the
        // list row, where everybody can see it disagree with the pattern.
        val wider = testBeat(
            id = "dadra",
            name = "Dadra",
            note = "Custom",
            steps = 10,
            beatsPerBar = 5.0,
            groups = listOf(2, 2, 2, 2, 2),
            dayan = "O".repeat(10),
            bayan = "X".repeat(10),
        )
        assertEquals(
            "5 beats",
            shippedRowFor("dadra", 0, "Straight", wider, null, null)["note"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an edit keeps the row's id, section, prose and provenance`() {
        // What the editor hands back after a maintainer corrects a built-in: a
        // renamed beat with the draft's own hardcoded note and no prose, because
        // EditorDraft.toBeat stamps `note = "Custom"` and `description = null` on
        // everything it produces. Those fields have to come from the ROW.
        val edited = testBeat(id = "keherva_medium_speed", name = "Keherva, corrected")
        val row = shippedRowFor(
            id = "keherva_medium_speed",
            ordinal = 8,
            heading = "Straight",
            beat = edited,
            sourcePublishedId = "published-id",
            description = "Prose the editor has no field for",
        )

        assertEquals("keherva_medium_speed", row["id"]!!.jsonPrimitive.content)
        assertEquals(8, row["ordinal"]!!.jsonPrimitive.int)
        assertEquals("Straight", row["heading"]!!.jsonPrimitive.content)
        assertEquals("Prose the editor has no field for", row["description"]!!.jsonPrimitive.content)
        assertEquals("published-id", row["source_published_id"]!!.jsonPrimitive.content)
        assertEquals("Keherva, corrected", row["name"]!!.jsonPrimitive.content)

        // THE POINT OF THE TEST: renaming does not re-slugify. A new id would
        // orphan every playlist that referenced the old one and leave the old row
        // behind, and nothing anywhere could tell the two apart.
        assertNotEquals(
            shippedIdFor(edited.name, emptySet()),
            row["id"]!!.jsonPrimitive.content,
        )

        // And it reads back as the edited beat wearing the row's section and prose.
        assertEquals(
            edited.copy(
                group = "Straight",
                note = "4 beats",
                description = "Prose the editor has no field for",
            ),
            shippedBeatsOf(one(row))!!.single(),
        )
    }

    // ── Status ─────────────────────────────────────────────────────────────

    @Test
    fun `the status starts as the compiled fallback with nothing checked yet`() {
        val status = ShippedStatus()

        assertEquals(ShippedSource.COMPILED, status.source)
        assertEquals(BEATS.size, status.count)
        assertNull(status.checkedAt)
        assertFalse(status.checking)
        // A launch must never announce itself: only a check the USER asked for sets
        // a notice, or "already up to date" appears on every cold start and trains
        // the user to ignore the line that matters.
        assertNull(status.notice)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun one(vararg rows: JsonObject): JsonArray = JsonArray(rows.toList())

    private fun lanesOf(row: JsonObject): JsonObject = row["lanes"]!!.jsonObject

    private fun withLane(row: JsonObject, lane: String, notation: String): JsonObject =
        row.with("lanes", lanesOf(row).with(lane, JsonPrimitive(notation)))

    /** A row with one column replaced, or removed when [value] is null. */
    private fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
        JsonObject(
            toMutableMap().apply {
                if (value == null) remove(key) else put(key, value)
            },
        )

    private fun JsonObject.without(key: String): JsonObject = with(key, null)
}
