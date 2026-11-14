package com.kirtan.companion.ui.editor

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.storage.LibraryJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The ONE beat being edited, held above the editor screen.
 *
 * The editor used to own its draft, and closing it was how a draft was
 * discarded — so walking to another tab and back threw the work away. Now the
 * app holds this session and the screen just shows it: leave, come back, and the
 * draft (cursor included) is where you left it. One session, not one per beat:
 * starting a different edit over unsaved changes asks first (see KirtanApp).
 *
 * [seed] is what the draft started from — the beat being edited, or null for a
 * new one — and is what "dirty" is measured against and what Revert returns to.
 */
@Stable
internal class EditorSession(
    val seed: Beat?,
    val forEveryone: Boolean,
    var returnTo: com.kirtan.companion.ui.nav.Tab?,
    draft: EditorDraft = EditorDraft.from(seed),
    tags: List<String> = seed?.tags.orEmpty(),
    notes: String = seed?.description.orEmpty(),
) {
    var draft by mutableStateOf(draft)
    var tags by mutableStateOf(tags)
    /** The rhythm's notes (saved as the beat's description); see RhythmNotes.kt. */
    var notes by mutableStateOf(notes)

    /**
     * True when the draft's CONTENT differs from [seed] — not "was touched", so
     * undoing back to the original clears it.
     */
    val dirty: Boolean get() = isDirty(seed, draft, tags, notes)

    /** The same edit, back at its starting point: Revert, or Start over for a new beat. */
    fun fresh() = EditorSession(seed, forEveryone, returnTo)

    companion object {
        fun isDirty(seed: Beat?, draft: EditorDraft, tags: List<String>, notes: String = seed?.description.orEmpty()): Boolean =
            !sameContent(draft, EditorDraft.from(seed)) || tags != seed?.tags.orEmpty() ||
                notes.trim() != seed?.description.orEmpty().trim()

        /** What a save would write, compared field by field; cursor and undo don't count. */
        fun sameContent(a: EditorDraft, b: EditorDraft): Boolean =
            a.groups == b.groups && a.cpq == b.cpq && a.bpm == b.bpm && a.swing == b.swing &&
                a.name.trim() == b.name.trim() &&
                LaneId.ORDERED.all { lane -> a.pattern(lane) == b.pattern(lane) }
    }
}

/**
 * The session on disk, so it survives the app being killed — which on a phone
 * happens behind your back. Kept only while dirty; a clean session is nothing
 * to lose. SharedPreferences, like SessionPrefs: read synchronously at startup.
 */
internal class EditorDraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("kirtan_editor", Context.MODE_PRIVATE)

    fun load(): EditorSession? = decode(prefs.getString(KEY, null))

    fun save(session: EditorSession?) {
        if (session == null || !session.dirty) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, encode(session)).apply()
    }

    companion object {
        private const val KEY = "draft"

        fun encode(s: EditorSession): String = buildJsonObject {
            s.seed?.let { put("seed", LibraryJson.encodeStored(it)) }
            put("forEveryone", s.forEveryone)
            // The name raw: toBeat substitutes a default for an empty one.
            put("name", s.draft.name)
            put("draft", LibraryJson.encodeStored(s.draft.toBeat(null).copy(name = s.draft.name.ifEmpty { "-" })))
            put("cursor", s.draft.cursor)
            put("tags", buildJsonArray { s.tags.forEach { add(JsonPrimitive(it)) } })
            put("notes", s.notes)
        }.toString()

        fun decode(raw: String?): EditorSession? = runCatching {
            val o = Json.parseToJsonElement(raw ?: return null).jsonObject
            val seed = (o["seed"] as? JsonObject)?.let { LibraryJson.decodeBeat(it) }
            val beat = LibraryJson.decodeBeat(o["draft"]) ?: return null
            val base = EditorDraft.from(beat)
            val cursor = o["cursor"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(0, base.steps - 1) ?: 0
            val draft = base.copy(name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), cursor = cursor)
            val tags = o["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val notes = o["notes"]?.jsonPrimitive?.contentOrNull ?: seed?.description.orEmpty()
            EditorSession(seed, o["forEveryone"]?.jsonPrimitive?.boolean == true, null, draft, tags, notes)
        }.getOrNull()
    }
}
