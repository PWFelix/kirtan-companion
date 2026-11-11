package com.kirtan.companion.storage

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.ui.community.ShareIndex
import com.kirtan.companion.ui.community.ShareStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Community beats follow their originals, can't be re-shared, and an edited copy
 * shares only once its music differs. See CommunityTracking.
 */
class CommunityTrackingTest {
    /** The original, as read back from the server: no id, no local fields. */
    private val original = BEATS.first().copy(id = null, group = null, name = "Their Beat", tags = listOf("keherwa"))

    /** The same beat in the library, renamed on import to keep names unique. */
    private val added = original.copy(id = "local-1", name = "Their Beat (2)", sourceItemId = "item-9")

    private val items = mapOf("item-9" to listOf(original))

    /** The beat with one more stroke: a musically different beat. */
    private fun edited(beat: com.kirtan.companion.data.model.Beat) = beat.copy(
        lanePatterns = beat.lanePatterns + (LaneId.DAYAN to beat.pattern(LaneId.DAYAN)!!.mapIndexed { i, s ->
            if (i == 1) (if (s == Stroke.OPEN) Stroke.CLOSED else Stroke.OPEN) else s
        }),
    )

    @Test
    fun unknownUntilLoaded() {
        assertEquals(CommunityTracking.Status.Unknown, CommunityTracking.status(added, null))
    }

    @Test
    fun matchingOriginalIsCurrentDespiteTheImportRename() {
        assertEquals(CommunityTracking.Status.Current, CommunityTracking.status(added, items))
    }

    @Test
    fun authorChangeIsAnUpdateAndApplyingItKeepsIdentity() {
        val newer = original.copy(bpm = original.bpm + 6, tags = listOf("keherwa", "kirtan"))
        val status = CommunityTracking.status(added, mapOf("item-9" to listOf(newer)))
        assertTrue(status is CommunityTracking.Status.UpdateAvailable)
        val updated = CommunityTracking.applyUpdate(added, (status as CommunityTracking.Status.UpdateAvailable).upstream)
        assertEquals("local-1", updated.id)
        assertEquals("Their Beat (2)", updated.name)
        assertEquals("item-9", updated.sourceItemId)
        assertEquals(newer.bpm, updated.bpm)
        assertEquals(listOf("keherwa", "kirtan"), updated.tags)
        assertEquals(CommunityTracking.Status.Current, CommunityTracking.status(updated, mapOf("item-9" to listOf(newer))))
    }

    @Test
    fun missingFromALoadedMapIsGone() {
        assertEquals(CommunityTracking.Status.Gone, CommunityTracking.status(added, emptyMap()))
    }

    @Test
    fun listBeatsFollowTheirPosition() {
        val second = BEATS[1].copy(id = null, group = null)
        val fromList = second.copy(id = "local-2", sourceItemId = "list-1", sourceIndex = 1)
        val list = mapOf("list-1" to listOf(original, second))
        assertEquals(CommunityTracking.Status.Current, CommunityTracking.status(fromList, list))
        assertEquals(CommunityTracking.Status.Gone, CommunityTracking.status(fromList, mapOf("list-1" to listOf(original))))
    }

    @Test
    fun communityBeatsAreReadOnlyAndNeverShared() {
        assertTrue(added.readOnly)
        assertEquals(ShareStatus.NotYours, ShareIndex(originals = items).status(added))
        assertFalse(ShareStatus.NotYours.canShare)
    }

    @Test
    fun aCopyIsShareableOnlyOnceTheMusicDiffers() {
        val copy = added.copy(id = "local-3", sourceItemId = null, forkedFrom = "item-9")
        // Renamed and retagged is still their beat.
        val relabelled = copy.copy(name = "Mine Now", tags = listOf("bhajan"))
        assertEquals(ShareStatus.UnchangedCopy, ShareIndex(originals = items).status(relabelled))
        assertEquals(ShareStatus.New, ShareIndex(originals = items).status(edited(copy)))
        // Originals not loaded: don't block.
        assertEquals(ShareStatus.New, ShareIndex().status(relabelled))
    }

    @Test
    fun migrationKeepsUntouchedImportsAndForksEditedOnes() {
        val legacy = added.copy(sourceIndex = null)
        assertEquals(CommunityTracking.Migration.Keep(null), CommunityTracking.migrate(legacy, listOf(original)))
        assertEquals(CommunityTracking.Migration.Fork, CommunityTracking.migrate(edited(legacy), listOf(original)))
        assertEquals(CommunityTracking.Migration.Undecided, CommunityTracking.migrate(legacy, null))
        val second = BEATS[1].copy(id = null, group = null)
        val legacyFromList = second.copy(id = "local-4", sourceItemId = "list-1")
        assertEquals(CommunityTracking.Migration.Keep(1), CommunityTracking.migrate(legacyFromList, listOf(original, second)))
    }

    @Test
    fun newFieldsRoundTripAndArePublishedWithoutLocalOnes() {
        val beat = added.copy(sourceIndex = 2, forkedFrom = "item-1", tags = listOf("keherwa", "future-tag"))
        val back = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))!!
        assertEquals(beat.tags, back.tags)
        assertEquals(2, back.sourceIndex)
        assertEquals("item-1", back.forkedFrom)
        // Untagged beats keep their old body exactly.
        assertNull(LibraryJson.encodeBody(original.copy(tags = emptyList()))["tags"])
    }

    @Test
    fun tagFilterIsAJsonContainmentTest() {
        assertEquals(emptyList<Pair<String, String>>(), tagFilter(null))
        assertEquals(listOf("payload->beat->tags" to "cs.[\"dadra\"]"), tagFilter("dadra"))
    }
}
