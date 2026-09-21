package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exports a beat as SOURCE, for replacing the built-in set.
 *
 * This exists because [ShareCodec] cannot do this job, and the reason is the
 * opposite of an oversight: the share format is a trust boundary, so it drops
 * `id`, `group` and `description` on purpose — an id from a stranger's link could
 * silently overwrite one of your beats, and a description renders as prose in the
 * info sheet, which is a fine place to phish from. Those same three fields are
 * exactly what a MAINTAINER needs in order to replace a baked-in beat.
 *
 * So the export is deliberately separate, deliberately explicit about what it is
 * doing, and deliberately not reachable from a shared link. It writes out the
 * literals you paste into `data/Beats.kt` (Kotlin) or `src/data/beats.js` (web),
 * preserving every field including the transcription prose.
 *
 * The intended workflow: open a built-in beat in the editor, correct the cells,
 * export, and paste over the entry in both files. THE TWO MUST STAY IN SYNC — a
 * shared link carries no id, so each client resolves built-in ids against its own
 * compiled list, and divergent patterns mean the same link plays a different beat
 * on each platform. Exporting from the app and pasting into both files is how
 * they stay identical.
 */
object BeatSourceExport {

    /** One lane as the compact notation used by the share format and by hand. */
    fun patternNotation(beat: Beat, lane: LaneId): String {
        val cells = beat.pattern(lane) ?: return ""
        return cells.joinToString("") { it?.code ?: ShareCodec.REST_CHAR }
    }

    /**
     * Every field, as JSON.
     *
     * The machine-readable form: it carries what the wire format refuses to, so a
     * script (or the test suite) can diff a baked-in beat against a corrected one.
     */
    fun toJson(beat: Beat): JsonObject = buildJsonObject {
        put("id", beat.id)
        put("name", beat.name)
        put("note", beat.note)
        put("group", beat.group)
        put("bpm", beat.bpm)
        put("steps", beat.steps)
        put("beatsPerBar", beat.beatsPerBar)
        put("cellsPerGroup", beat.cellsPerGroup)
        put("groups", buildJsonArray { groupsFor(beat).forEach { add(it) } })
        put("description", beat.description)
        putJsonObjectLanes(beat)
    }

    /**
     * A `builtIn(…)` call ready to paste into `data/Beats.kt`.
     *
     * Laid out the same way the existing entries are, because the file is read
     * far more often than it is written and its value is that a transcriber can
     * line the pattern up against the book.
     */
    fun toKotlinSource(beat: Beat): String {
        val groups = groupsFor(beat)
        val sb = StringBuilder()
        sb.append("    builtIn(\n")
        sb.append("        id = ${kotlinString(beat.id)}, group = ${kotlinString(beat.group)}, ")
            .append("name = ${kotlinString(beat.name)}, note = ${kotlinString(beat.note)},\n")
        // groups and cpq, NOT steps/beatsPerBar/cellsPerGroup: builtIn derives
        // those three the same way ShareCodec does, so an exported entry cannot
        // contradict the meter it declares. Emitting the derived fields instead
        // would let a paste introduce an inconsistency the compiler accepts.
        sb.append("        bpm = ${beat.bpm}, groups = listOf(${groups.joinToString(", ")}), ")
            .append("cpq = ${cpqFor(beat)},\n")
        beat.description?.let {
            sb.append("        description = ${kotlinString(it)},\n")
        }
        sb.append("        //      ${labelsFromGroups(groups).joinToString(" ")}\n")
        sb.append("        dayan = \"${patternNotation(beat, LaneId.DAYAN)}\",\n")
        sb.append("        bayan = \"${patternNotation(beat, LaneId.BAYAN)}\",\n")
        if (beat.pattern(LaneId.KARTAL) != null) {
            sb.append("        kartal = \"${patternNotation(beat, LaneId.KARTAL)}\",\n")
        } else {
            sb.append("        // no kartal line — the beat has no cymbals\n")
        }
        sb.append("    ),")
        return sb.toString()
    }

    /**
     * A beat object ready to paste into `src/data/beats.js`.
     *
     * Emits the ARRAY form the web file uses rather than the compact string, so
     * the two files read the same way to a human comparing them.
     */
    fun toJsSource(beat: Beat): String {
        val groups = groupsFor(beat)
        val sb = StringBuilder()
        sb.append("  {\n")
        sb.append("    id: ${jsString(beat.id)},\n")
        sb.append("    name: ${jsString(beat.name)},\n")
        sb.append("    note: ${jsString(beat.note)},\n")
        sb.append("    group: ${jsString(beat.group)},\n")
        sb.append("    bpm: ${beat.bpm},\n")
        sb.append("    steps: ${beat.steps},\n")
        sb.append("    beatsPerBar: ${formatDouble(beat.beatsPerBar)},\n")
        sb.append("    cellsPerGroup: ${beat.cellsPerGroup},\n")
        // groups are written too: the web file now carries them, and an entry
        // that omitted them would be re-derived on load, potentially to a
        // different bar than the one authored.
        sb.append("    groups: [${groupsFor(beat).joinToString(", ")}],\n")
        sb.append("    description: ${jsString(beat.description)},\n")
        sb.append("    // ${labelsFromGroups(groups).joinToString(" ")}\n")
        for (lane in LaneId.ORDERED) {
            val cells = beat.pattern(lane) ?: continue
            sb.append("    ${lane.wireId}: [")
            sb.append(cells.joinToString(",") { stroke ->
                if (stroke == null) "null" else "\"${stroke.code}\""
            })
            sb.append("],\n")
        }
        sb.append("  },")
        return sb.toString()
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    /** A Kotlin string literal, or `null` for an absent value. */
    private fun kotlinString(value: String?): String =
        if (value == null) "null" else "\"" + escapeKotlin(value) + "\""

    private fun jsString(value: String?): String =
        if (value == null) "null" else "\"" + escapeKotlin(value) + "\""

    /**
     * Escape the characters that would break out of a quoted literal.
     *
     * The transcribed descriptions contain typographic quotes (“ ”) and
     * apostrophes, which are harmless, but a straight double quote or a backslash
     * in a name would terminate the literal early — and a pasted source file that
     * fails to compile is worse than one that is ugly.
     */
    private fun escapeKotlin(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> append(ch)
            }
        }
    }

    /**
     * `4` rather than `4.0` where the value is whole, so a pasted 4/4 beat reads
     * like every existing entry; `3.5` stays `3.5` where the meter really is
     * fractional. Both files accept either form.
     */
    private fun formatDouble(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObjectLanes(beat: Beat) {
        put("p", buildJsonObject {
            for (lane in LaneId.ORDERED) {
                val notation = patternNotation(beat, lane)
                if (notation.isEmpty()) continue
                put(lane.wireId, notation)
            }
        })
    }
}
