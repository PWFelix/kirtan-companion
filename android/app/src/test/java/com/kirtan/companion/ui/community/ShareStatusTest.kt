package com.kirtan.companion.ui.community

import com.kirtan.companion.data.BEATS
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Share says for a beat: Share, Already shared, or Share updated version. */
class ShareStatusTest {
    private val mine = BEATS.first().copy(id = "local-1", name = "My Beat", sourceItemId = null)
    /** How it reads back from the server: no id, nothing local. */
    private val published = mine.copy(id = null)

    @Test
    fun notPublishedIsNew() {
        assertEquals(ShareStatus.New, ShareIndex().status(mine))
    }

    @Test
    fun linkedAndUnchangedIsShared() {
        val index = ShareIndex(mine = listOf("item-9" to published), links = mapOf("local-1" to "item-9"))
        assertEquals(ShareStatus.Shared, index.status(mine))
    }

    @Test
    fun linkedAndEditedSinceIsChangedAndNamesTheItem() {
        val index = ShareIndex(mine = listOf("item-9" to published), links = mapOf("local-1" to "item-9"))
        assertEquals(ShareStatus.Changed("item-9"), index.status(mine.copy(bpm = mine.bpm + 4)))
        assertEquals(ShareStatus.Changed("item-9"), index.status(mine.copy(name = "Renamed")))
    }

    @Test
    fun unlinkedButIdenticalContentIsShared() {
        // Shared from another device, or before links were recorded.
        assertEquals(ShareStatus.Shared, ShareIndex(mine = listOf("item-9" to published)).status(mine))
    }

    @Test
    fun linkToAnUnpublishedItemFallsBackToNew() {
        val index = ShareIndex(mine = emptyList(), links = mapOf("local-1" to "item-9"))
        assertEquals(ShareStatus.New, index.status(mine))
    }

    @Test
    fun anotherBeatsShareDoesNotCount() {
        val other = BEATS.last().copy(id = null)
        assertEquals(ShareStatus.New, ShareIndex(mine = listOf("item-3" to other)).status(mine))
    }

    @Test
    fun aServerRoundTripStillReadsAsShared() {
        // What the server echoes is decoded, so any normalisation in decodeBeat
        // would make every shared beat look edited. Every built-in, plus one
        // with the editor's custom groups, must survive encode → JSON → decode.
        val samples = BEATS.map { it.copy(id = "l-" + it.name) } + mine.copy(id = "g", groups = listOf(3, 3, 2))
        for (beat in samples) {
            val wire = kotlinx.serialization.json.Json.parseToJsonElement(ShareIndex.fingerprint(beat).toString())
            val back = com.kirtan.companion.storage.LibraryJson.decodeBeat(wire)!!.copy(id = null)
            assertEquals(beat.name, ShareStatus.Shared, ShareIndex(mine = listOf("x" to back)).status(beat))
        }
    }
}
