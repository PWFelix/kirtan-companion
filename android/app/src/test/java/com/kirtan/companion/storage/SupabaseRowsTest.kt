package com.kirtan.companion.storage

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `beats` and `playlists` row ↔ value mapping.
 *
 * Pulled out of [SupabaseBeatsProvider] so it can be pinned down without a network,
 * because this is where the two platforms meet: the same Postgres rows are read by
 * `supabaseProvider.js` in a browser, and a shape disagreement shows up as a library
 * that loads on one device and not the other.
 */
class SupabaseRowsTest {

    private val userId = "11111111-2222-3333-4444-555555555555"

    // ── Writing a row ──────────────────────────────────────────────────────

    @Test
    fun `an insert body stamps the owner and duplicates the name into its column`() {
        val row = SupabaseRows.beatRow(userId, testBeat(id = "ignored", name = "Morning"))

        assertEquals(setOf("user_id", "name", "data"), row.keys)
        assertEquals(userId, row["user_id"]?.stringContent())
        // `name` is promoted to a column purely so the database can sort and search
        // on it; the blob still holds the whole beat.
        assertEquals("Morning", row["name"]?.stringContent())
    }

    @Test
    fun `the blob carries no id, because the column is the id`() {
        val row = SupabaseRows.beatRow(userId, testBeat(id = "row-1", name = "Morning"))
        val data = row["data"]!!.jsonObject

        assertFalse(
            "duplicating the primary key into the blob is how two sources of truth drift",
            data.containsKey("id"),
        )
        assertEquals("Morning", data["name"]?.stringContent())
    }

    @Test
    fun `a beat id array is a json array of strings`() {
        val array = SupabaseRows.beatIdsArray(listOf("te_ta", "abc")).jsonArray

        assertEquals(2, array.size)
        assertEquals(JsonPrimitive("te_ta"), array[0])
        assertEquals(JsonPrimitive("abc"), array[1])
    }

    // ── Reading a row ──────────────────────────────────────────────────────

    @Test
    fun `a row written by this app reads back as the same beat`() {
        val beat = testBeat(id = "row-1", name = "Morning", kartal = "O-O-O---")
        // The database generates the id, so a stored row is the insert body plus it.
        val row = JsonObject(SupabaseRows.beatRow(userId, beat) + ("id" to JsonPrimitive("row-1")))

        assertEquals(beat, SupabaseRows.toBeat(row))
    }

    @Test
    fun `the column id wins over an id inside the blob`() {
        // The web provider does `{ id: row.id, ...row.data }` for the same reason: a
        // blob is data the client wrote, and letting it name its own row would let a
        // stale or hostile id address a different beat.
        val row = buildJsonObject {
            put("id", "column-id")
            put("name", "Morning")
            put(
                "data",
                buildJsonObject {
                    LibraryJson.encodeStored(testBeat(id = "blob-id", name = "Morning"))
                        .forEach { (key, value) -> put(key, value) }
                },
            )
        }

        assertEquals("column-id", SupabaseRows.toBeat(row).id)
    }

    @Test
    fun `a blob this version cannot read is refused, naming the beat`() = runTest {
        val row = buildJsonObject {
            put("id", "row-1")
            put("name", "Damaged")
            put("data", buildJsonObject { put("name", "Damaged") })
        }

        val error = expectStorageError { SupabaseRows.toBeat(row) }

        assertEquals(StorageErrorCode.UNKNOWN, error.code)
        assertTrue(error.message, error.message!!.contains("Damaged"))
    }

    @Test
    fun `a row that is not an object, or has no id, is refused`() = runTest {
        expectStorageError { SupabaseRows.toBeat(JsonPrimitive(7)) }
        expectStorageError { SupabaseRows.toBeat(buildJsonObject { put("name", "no id") }) }
        expectStorageError { SupabaseRows.toCategory(buildJsonObject { put("name", "no id") }) }
    }

    @Test
    fun `a playlist's text array becomes the progression, in order`() {
        val row = buildJsonObject {
            put("id", "cat-1")
            put("name", "Sunday")
            put("beat_ids", SupabaseRows.beatIdsArray(listOf("te_ta", "abc", "forward")))
        }

        val category = SupabaseRows.toCategory(row)

        assertEquals(testCategory(id = "cat-1", name = "Sunday", beatIds = listOf("te_ta", "abc", "forward")), category)
    }

    @Test
    fun `an empty or absent beat_ids is an empty progression, not a failure`() {
        // The order IS the progression, so an empty playlist is a legal thing to have
        // just created — refusing it would break the New-playlist flow.
        val empty = testCategory(id = "cat-1", name = "Sunday", beatIds = emptyList())

        assertEquals(
            empty,
            SupabaseRows.toCategory(buildJsonObject { put("id", "cat-1"); put("name", "Sunday") }),
        )
        assertEquals(
            empty,
            SupabaseRows.toCategory(
                buildJsonObject { put("id", "cat-1"); put("name", "Sunday"); put("beat_ids", SupabaseRows.beatIdsArray(emptyList())) },
            ),
        )
    }
}
