package com.kirtan.companion.ui

import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * BeatText — the words a built-in beat carries, in the reader's language.
 *
 * A beat's `note` and `group` are data, not interface text: they arrive from
 * Beats.kt, from the server's built-in set, or from whatever a user typed. So
 * they can't be resource ids. Instead the known English values — the vocabulary
 * the built-in sets actually use, on this app and on the web — are looked up
 * here, and anything unrecognised (a user's own note, a group a maintainer
 * invented yesterday) is shown exactly as written. Translating someone's own
 * words would be wrong; leaving the shipped vocabulary in English would be
 * half a translation.
 */
object BeatText {
    private val COUNT = Regex("""^(\d+) beats?$""")

    private val NOTES = mapOf(
        "Custom" to R.string.note_custom,
        "Foundational" to R.string.note_foundational,
        "Everyday" to R.string.note_everyday,
        "Variation" to R.string.note_variation,
        "Lively" to R.string.note_lively,
        "Build up" to R.string.note_build_up,
        "Gentle" to R.string.note_gentle,
        "Fast" to R.string.note_fast,
        "Swing" to R.string.note_swing,
    )

    private val GROUPS = mapOf(
        "Sixteenths" to R.string.group_sixteenths,
        "Straight" to R.string.group_straight,
        "Foundations" to R.string.group_foundations,
        "Everyday" to R.string.group_everyday,
        "Building up" to R.string.group_building_up,
        "Gentle" to R.string.group_gentle,
        "Swing" to R.string.group_swing,
    )

    fun note(note: String): String {
        NOTES[note]?.let { return Strings.get(it) }
        val count = COUNT.matchEntire(note)?.groupValues?.get(1)?.toIntOrNull()
        return if (count != null) Strings.plural(R.plurals.note_beat_count, count, count) else note
    }

    fun group(group: String): String = GROUPS[group]?.let { Strings.get(it) } ?: group
}
