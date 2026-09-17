package com.kirtan.companion.data

import com.kirtan.companion.data.model.LaneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maintainer export.
 *
 * The point of these tests is that the export must be PASTABLE: a snippet that
 * does not compile is worse than no snippet, because it looks authoritative and
 * the mistake surfaces as a build failure hours later in a different file. So
 * escaping, literal shape and field completeness are all asserted here.
 *
 * The strongest check is the first one: exporting a built-in beat must reproduce
 * the literals that beat is defined by. If those diverge, the export is lying
 * about the data it claims to describe.
 */
class BeatSourceExportTest {

    private val teTa = BEATS.first { it.id == "te_ta" }

    @Test
    fun `exporting a built-in reproduces the notations it is defined by`() {
        assertEquals("XOXOXOXO", BeatSourceExport.patternNotation(teTa, LaneId.DAYAN))
        assertEquals("O--X-OO-", BeatSourceExport.patternNotation(teTa, LaneId.BAYAN))
        assertEquals("O-O-O---", BeatSourceExport.patternNotation(teTa, LaneId.KARTAL))
    }

    @Test
    fun `the exported pattern notation agrees with the share codec's`() {
        // Two independent encoders of the same grid. If they drift, a beat pasted
        // out of one path would not mean the same thing through the other.
        for (beat in BEATS) {
            val decoded = ShareCodec.decodeShare(ShareCodec.encodeBeat(beat))
            assertTrue(decoded is ShareCodec.SharePayload.BeatPayload)
            val round = (decoded as ShareCodec.SharePayload.BeatPayload).beat

            for (lane in LaneId.ORDERED) {
                val exported = BeatSourceExport.patternNotation(beat, lane)
                val viaCodec = round.pattern(lane)?.joinToString("") { it?.code ?: ShareCodec.REST_CHAR }
                assertEquals(
                    "${beat.id}/${lane.wireId}: export and share codec disagree",
                    viaCodec,
                    exported,
                )
            }
        }
    }

    @Test
    fun `the kotlin export carries every field the share format strips`() {
        val source = BeatSourceExport.toKotlinSource(teTa)

        // These three are exactly what ShareCodec refuses to carry, and exactly
        // what replacing a baked-in entry needs.
        assertTrue(source.contains("id = \"te_ta\""))
        assertTrue(source.contains("group = \"Foundations\""))
        assertTrue(source.contains("description = "))
        assertTrue(source.contains("name = \"Te Ta\""))
        assertTrue(source.contains("note = \"Foundational\""))
        assertTrue(source.contains("bpm = 80"))
        assertTrue(source.contains("steps = 8"))
        assertTrue(source.contains("beatsPerBar = 4"))
        assertTrue(source.contains("cellsPerGroup = 2"))
        assertTrue(source.contains("dayan = \"XOXOXOXO\""))
        assertTrue(source.contains("bayan = \"O--X-OO-\""))
        assertTrue(source.contains("kartal = \"O-O-O---\""))
        assertTrue(source.trimEnd().endsWith("),"))
    }

    @Test
    fun `a whole bar length is exported without a decimal point`() {
        // `beatsPerBar = 4.0` in a file where every existing entry says `4.0`-free
        // integers would be noise; `3.5` must stay fractional where the meter
        // really is.
        assertTrue(BeatSourceExport.toKotlinSource(teTa).contains("beatsPerBar = 4,"))

        val sevenEight = teTa.copy(
            id = null, steps = 7, beatsPerBar = 3.5, cellsPerGroup = 2,
            groups = listOf(2, 2, 3),
            lanePatterns = teTa.lanePatterns.mapValues { (_, cells) -> cells.take(7) + listOf(null) },
        )
        val source = BeatSourceExport.toKotlinSource(sevenEight)
        assertTrue("fractional meter lost its fraction: $source", source.contains("beatsPerBar = 3.5"))
        // And the group labels come out as the uneven signature's own.
        assertTrue(source.contains("1 · 2 · 3 · ·"))
    }

    @Test
    fun `the js export uses the array form the web file is written in`() {
        val source = BeatSourceExport.toJsSource(teTa)

        assertTrue(source.contains("id: \"te_ta\","))
        assertTrue(source.contains("group: \"Foundations\","))
        assertTrue(source.contains("dayan: [\"X\",\"O\",\"X\",\"O\",\"X\",\"O\",\"X\",\"O\"],"))
        assertTrue(source.contains("bayan: [\"O\",null,null,\"X\",null,\"O\",\"O\",null],"))
        // beats.js has no `groups` field on built-ins — they predate the model.
        assertFalse(source.contains("groups:"))
    }

    @Test
    fun `a beat with no cymbals exports no kartal line`() {
        val noCymbals = teTa.copy(
            lanePatterns = teTa.lanePatterns - LaneId.KARTAL,
        )
        val kotlin = BeatSourceExport.toKotlinSource(noCymbals)
        assertTrue(kotlin.contains("// no kartal line"))
        assertFalse(kotlin.contains("kartal ="))
        assertFalse(BeatSourceExport.toJsSource(noCymbals).contains("kartal:"))
        assertFalse(BeatSourceExport.toJson(noCymbals)["p"].toString().contains("kartal"))
    }

    @Test
    fun `the json export is machine-readable and keeps the stripped fields`() {
        val json = BeatSourceExport.toJson(teTa)

        assertEquals("te_ta", (json["id"] as JsonPrimitive).content)
        assertEquals("Foundations", (json["group"] as JsonPrimitive).content)
        assertNotNull(json["description"])
        assertEquals(8, (json["steps"] as JsonPrimitive).content.toInt())
        assertEquals(4.0, (json["beatsPerBar"] as JsonPrimitive).content.toDouble(), 0.0)

        val patterns = json["p"] as JsonObject
        assertEquals("XOXOXOXO", (patterns["dayan"] as JsonPrimitive).content)
    }

    @Test
    fun `hostile characters in a name or description cannot break the literal`() {
        // A straight double quote would terminate the string early, and a `$`
        // would start a Kotlin template — either turns a pasted snippet into a
        // compile error in a file the user did not write.
        val nasty = teTa.copy(
            id = null,
            name = "Quote \" and backslash \\ and dollar \$ end",
            description = "Line one\nLine two\tTabbed",
        )

        val kotlin = BeatSourceExport.toKotlinSource(nasty)
        assertTrue(kotlin.contains("\\\""))
        assertTrue(kotlin.contains("\\\\"))
        assertTrue(kotlin.contains("\\\$"))
        assertTrue(kotlin.contains("\\n"))
        // No raw newline may survive inside the quoted literal.
        val descriptionLine = kotlin.lines().first { it.contains("description = ") }
        assertFalse("a raw newline escaped into the literal", descriptionLine.contains("\n"))

        val js = BeatSourceExport.toJsSource(nasty)
        assertTrue(js.contains("\\\""))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun `an absent optional field exports as null, not as an empty string`() {
        val minimal = teTa.copy(id = null, group = null, description = null)

        val json = BeatSourceExport.toJson(minimal)
        assertTrue(json["id"].toString() == "null")
        assertTrue(json["group"].toString() == "null")
        assertTrue(json["description"].toString() == "null")

        val kotlin = BeatSourceExport.toKotlinSource(minimal)
        assertTrue(kotlin.contains("id = null"))
        assertTrue(kotlin.contains("group = null"))
        assertFalse(kotlin.contains("description ="))

        val js = BeatSourceExport.toJsSource(minimal)
        assertTrue(js.contains("id: null"))
        assertTrue(js.contains("description: null"))
    }

    @Test
    fun `a custom beat from the editor exports cleanly`() {
        // The realistic path: author in the editor, export, paste over a built-in.
        var draft = com.kirtan.companion.ui.editor.EditorDraft.from(null)
        val ta = com.kirtan.companion.ui.editor.padSetFor(
            com.kirtan.companion.ui.editor.EditLane.BOTH
        ).first { it.key == "ta" }
        repeat(4) { draft = draft.tapPad(ta) }

        val beat = draft.toBeat(null)
        val source = BeatSourceExport.toKotlinSource(beat)

        assertTrue(source.contains("id = null"))
        assertTrue(source.contains("name = \"Custom Beat\""))
        assertTrue(source.contains("group = null"))
        assertTrue(source.contains("dayan = \"OOOO----\""))
        assertNull(beat.id)
    }
}
