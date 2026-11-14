package com.kirtan.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequencesTest {
    private val abc = listOf(SequenceEntry("a", 4), SequenceEntry("b", 2), SequenceEntry("c", 8))

    @Test fun addDefaultsToFourBarsAndClamps() {
        assertEquals(SequenceEntry("x", 4), Sequences.add(emptyList(), "x").single())
        assertEquals(64, Sequences.add(emptyList(), "x", 500).single().bars)
        assertEquals(1, Sequences.setBars(abc, 1, 0)[1].bars)
    }

    @Test fun moveAndRemove() {
        assertEquals(listOf("b", "c", "a"), Sequences.move(abc, 0, 2).map { it.beatId })
        assertEquals(listOf("c", "a", "b"), Sequences.move(abc, 2, -5).map { it.beatId })
        assertEquals(abc, Sequences.move(abc, 9, 0))
        assertEquals(listOf("a", "c"), Sequences.remove(abc, 1).map { it.beatId })
        assertEquals("z", Sequences.setBeat(abc, 2, "z")[2].beatId)
    }

    @Test fun resolveSkipsMissingBeatsButKeepsEntryIndexes() {
        val beat = DEFAULT_BEAT
        val played = Sequences.resolve(abc) { if (it == "b") null else beat }
        assertEquals(listOf(0, 2), played.map { it.first })
        assertEquals(14, Sequences.totalBars(abc))
    }

    @Test fun savedRoundTrips() {
        val saved = listOf(BeatSequence("1", "Evening", abc), BeatSequence("2", "", emptyList()))
        assertEquals(saved, Sequences.decodeSaved(Sequences.encodeSaved(saved)))
        assertEquals(emptyList<BeatSequence>(), Sequences.decodeSaved("not json"))
    }

    @Test fun workingRoundTripsAndKnowsWhenItIsDirty() {
        val w = WorkingSequence(abc, "1", "Evening", loop = true)
        assertEquals(w, WorkingSequence.decode(w.encode()))
        assertEquals(WorkingSequence(), WorkingSequence.decode(null))
        val saved = listOf(BeatSequence("1", "Evening", abc))
        assertFalse(w.dirty(saved))
        assertTrue(w.copy(entries = Sequences.setBars(abc, 0, 5)).dirty(saved))
        assertTrue(WorkingSequence(abc).dirty(saved)) // never saved
        assertFalse(WorkingSequence().dirty(saved))   // empty, nothing to lose
    }
}
