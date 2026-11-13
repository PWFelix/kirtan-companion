package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * SEQUENCES — Home's running order: beats in turn, each for a number of bars.
 *
 * A sequence names its beats by id rather than holding copies, so editing a beat
 * in the library changes it everywhere it is used. A beat deleted since is
 * skipped when the sequence is played (see [resolve]) but kept in the entry list,
 * so it comes back if the beat does (sync, undo).
 *
 * Separate from lists (progressions) on purpose: a list is a set of beats you
 * move through by hand; a sequence is an arrangement that plays itself.
 */
data class SequenceEntry(val beatId: String, val bars: Int)

data class BeatSequence(
    val id: String,
    val name: String,
    val entries: List<SequenceEntry>,
)

/** Pure edits of an entry list. Every one returns a new list. */
object Sequences {
    const val MIN_BARS = 1
    const val MAX_BARS = 64
    const val DEFAULT_BARS = 4

    fun clampBars(bars: Int) = bars.coerceIn(MIN_BARS, MAX_BARS)

    fun add(entries: List<SequenceEntry>, beatId: String, bars: Int = DEFAULT_BARS) =
        entries + SequenceEntry(beatId, clampBars(bars))

    fun remove(entries: List<SequenceEntry>, index: Int) =
        entries.filterIndexed { i, _ -> i != index }

    fun setBars(entries: List<SequenceEntry>, index: Int, bars: Int) =
        entries.mapIndexed { i, e -> if (i == index) e.copy(bars = clampBars(bars)) else e }

    fun setBeat(entries: List<SequenceEntry>, index: Int, beatId: String) =
        entries.mapIndexed { i, e -> if (i == index) e.copy(beatId = beatId) else e }

    /** Move entry [from] to position [to] (clamped), the others closing up. */
    fun move(entries: List<SequenceEntry>, from: Int, to: Int): List<SequenceEntry> {
        if (from !in entries.indices) return entries
        val list = entries.toMutableList()
        val e = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), e)
        return list
    }

    /**
     * The playable form: each entry's beat looked up, entries whose beat is gone
     * skipped. Paired with the ENTRY index each played step came from, so the
     * playing highlight lands on the right chip even when one was skipped.
     */
    fun resolve(entries: List<SequenceEntry>, lookup: (String) -> Beat?): List<Pair<Int, Beat>> =
        entries.mapIndexedNotNull { i, e -> lookup(e.beatId)?.let { i to it } }

    /** Total bars, for the "12 bars" summary on a saved sequence. */
    fun totalBars(entries: List<SequenceEntry>) = entries.sumOf { it.bars }

    // ── JSON ────────────────────────────────────────────────────────────────

    fun encodeEntries(entries: List<SequenceEntry>): JsonArray = buildJsonArray {
        entries.forEach { e -> add(buildJsonObject { put("beatId", e.beatId); put("bars", e.bars) }) }
    }

    fun decodeEntries(json: Any?): List<SequenceEntry> = (json as? JsonArray).orEmpty().mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        val id = o["beatId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        SequenceEntry(id, clampBars(o["bars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_BARS))
    }

    fun encodeSaved(list: List<BeatSequence>): String = buildJsonArray {
        list.forEach { s ->
            add(buildJsonObject {
                put("id", s.id); put("name", s.name); put("entries", encodeEntries(s.entries))
            })
        }
    }.toString()

    fun decodeSaved(raw: String?): List<BeatSequence> = runCatching {
        Json.parseToJsonElement(raw ?: return emptyList()).jsonArray.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            BeatSequence(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                entries = decodeEntries(o["entries"]),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * What Home is working on: the entries on screen, which saved sequence they came
 * from (null = not saved yet), and the loop switch. Survives a restart.
 */
data class WorkingSequence(
    val entries: List<SequenceEntry> = emptyList(),
    val savedId: String? = null,
    val name: String = "",
    val loop: Boolean = false,
) {
    /** Unsaved changes against the saved copy it came from (or any entries at all). */
    fun dirty(saved: List<BeatSequence>): Boolean {
        val source = saved.firstOrNull { it.id == savedId } ?: return entries.isNotEmpty()
        return source.entries != entries
    }

    fun encode(): String = buildJsonObject {
        put("entries", Sequences.encodeEntries(entries))
        savedId?.let { put("savedId", it) }
        put("name", name)
        put("loop", loop)
    }.toString()

    companion object {
        fun decode(raw: String?): WorkingSequence = runCatching {
            val o = Json.parseToJsonElement(raw ?: return WorkingSequence()).jsonObject
            WorkingSequence(
                entries = Sequences.decodeEntries(o["entries"]),
                savedId = o["savedId"]?.jsonPrimitive?.contentOrNull,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                loop = o["loop"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrDefault(WorkingSequence())
    }
}
