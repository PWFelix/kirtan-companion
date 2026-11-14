package com.kirtan.companion.data

/**
 * Swing: every second cell of a beat played late, so straight 1-and-2-and
 * becomes long-short, long-short.
 *
 * Stored as the share of a PAIR of cells the first one takes: 50 is straight,
 * 67 is the triplet lilt (two thirds, one third). Kept per beat ([com.kirtan
 * .companion.data.model.Beat.swing]) so a beat carries its own feel when shared;
 * the mixer can nudge it live for the beat that is playing.
 *
 * Only beats split into an EVEN number of cells swing — the 2nd, 4th… cell of
 * each. A beat of three cells is already a triplet; delaying one of them would
 * just be wrong, so those play as written. The same rules as web and iOS.
 */
object Swing {
    const val STRAIGHT = 50
    const val MAX = 67

    fun clamp(value: Int): Int = value.coerceIn(STRAIGHT, MAX)

    /**
     * For each cell, true when it is a swung (late) one: an odd position inside
     * a beat with an even number of cells. Built once per beat, off the audio
     * thread's hot path.
     */
    fun offbeats(groups: List<Int>): BooleanArray {
        val out = BooleanArray(groups.sum())
        var at = 0
        for (g in groups) {
            if (g % 2 == 0) for (i in 1 until g step 2) out[at + i] = true
            at += g
        }
        return out
    }

    /**
     * How late a swung cell plays, as a fraction of one cell: a pair of cells
     * lasting 2 gives the first `2 × swing%`, so the second starts
     * `2 × swing% − 1` of a cell late. 0 when straight.
     */
    fun lateFraction(swing: Int): Double = (2 * clamp(swing) / 100.0) - 1.0
}
