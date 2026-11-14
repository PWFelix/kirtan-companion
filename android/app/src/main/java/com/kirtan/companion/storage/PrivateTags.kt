package com.kirtan.companion.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add

/**
 * The user's PRIVATE tags: beat id → tag ids, on this device.
 *
 * Kept beside the library rather than in the beat, because private tags must
 * work on beats the user can't edit (built-ins, community beats) and must never
 * travel with a published beat. The author's own tags live on [com.kirtan.companion.data.model.Beat.tags].
 */
class PrivateTags(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _tags = MutableStateFlow(read())
    val tags: StateFlow<Map<String, List<String>>> = _tags.asStateFlow()

    fun of(beatId: String?): List<String> = beatId?.let { _tags.value[it] }.orEmpty()

    fun set(beatId: String, tags: List<String>) {
        val next = if (tags.isEmpty()) _tags.value - beatId else _tags.value + (beatId to tags.distinct())
        _tags.value = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    private fun read(): Map<String, List<String>> = try {
        val obj = Json.parseToJsonElement(prefs.getString(KEY, null) ?: "{}") as? JsonObject
        obj?.mapNotNull { (id, list) ->
            val ids = (list as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            if (ids.isEmpty()) null else id to ids
        }?.toMap().orEmpty()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun encode(map: Map<String, List<String>>): String = buildJsonObject {
        map.forEach { (id, ids) -> put(id, buildJsonArray { ids.forEach { add(it) } }) }
    }.toString()

    private companion object {
        const val PREFS = "kirtan_private_tags"
        const val KEY = "tags"
    }
}
