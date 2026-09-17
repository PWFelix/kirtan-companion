package com.kirtan.companion.storage

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.Category
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke

/**
 * Beats and categories for the storage tests.
 *
 * Patterns are written in the same `"O"`/`"X"`/`"-"` notation `data/Beats.kt` uses,
 * because a fixture nobody can read is a fixture nobody checks — and the whole point
 * of the round-trip tests is that a specific cell survives.
 *
 * Kept in one place so the three test classes below cannot drift into three
 * different ideas of what a legal beat is.
 */
internal fun testBeat(
    id: String? = null,
    name: String = "My Beat",
    bpm: Int = 90,
    steps: Int = 8,
    beatsPerBar: Double = 4.0,
    cellsPerGroup: Int = 2,
    groups: List<Int>? = List(beatsPerBar.toInt()) { cellsPerGroup },
    description: String? = null,
    group: String? = null,
    note: String = "Custom",
    dayan: String = "X-OOX-OO",
    bayan: String = "O--X-OO-",
    kartal: String? = null,
): Beat {
    val lanes = linkedMapOf(
        LaneId.DAYAN to pattern(dayan),
        LaneId.BAYAN to pattern(bayan),
    )
    if (kartal != null) lanes[LaneId.KARTAL] = pattern(kartal)
    return Beat(
        id = id,
        name = name,
        note = note,
        bpm = bpm,
        steps = steps,
        beatsPerBar = beatsPerBar,
        cellsPerGroup = cellsPerGroup,
        groups = groups,
        description = description,
        lanePatterns = lanes,
        group = group,
    )
}

internal fun pattern(notation: String): List<Stroke?> =
    notation.map { Stroke.fromCode(it.toString()) }

internal fun testCategory(
    id: String = "cat",
    name: String = "Sunday kirtan",
    beatIds: List<String> = emptyList(),
): Category = Category(id, name, beatIds)

/**
 * Run [block], requiring it to throw a [StorageError], and return that error so the
 * test can assert on its code and its message.
 *
 * A helper rather than JUnit's `assertThrows`, because the whole contract under test
 * is "failures throw [StorageError]" and an assertion that also checks the CODE is
 * the difference between "it failed" and "it failed the way the UI can handle".
 */
internal suspend fun expectStorageError(block: suspend () -> Unit): StorageError = try {
    block()
    throw AssertionError("expected a StorageError, and nothing was thrown")
} catch (e: StorageError) {
    e
}
