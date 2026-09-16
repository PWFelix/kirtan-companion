package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Packing a beat (or a whole category) into a share code, and — the half that
 * matters — unpacking one safely.
 *
 * Ported from `src/data/shareCodec.js`, which is the app's FIRST UNTRUSTED
 * INPUT. Everything else comes from the user's own hands; this comes from a
 * stranger and ends up written to device storage, where it persists across
 * sessions. So this file is a TRUST BOUNDARY: the only place in the app that
 * touches a payload it did not create, and everything downstream receives either
 * a clean, freshly-constructed object or null.
 *
 * The rules that keep it a boundary all have teeth, and all are preserved:
 *
 *  1. NEVER COPY AN OBJECT WHOLESALE. The JS version warns that `{...incoming}`
 *     would carry a `__proto__` key straight into app state. The Kotlin
 *     equivalent is a serializer that binds unknown fields, so this decoder
 *     reads NAMED fields out of a [JsonObject] and constructs new values.
 *     Patterns are read by iterating [LaneId.ORDERED], not the payload's keys —
 *     an allowlist, not a denylist.
 *  2. NEVER TRUST AN ID. There is no id in the format at all. Saving is an
 *     upsert by id, so an incoming id could silently replace one of the user's
 *     beats or shadow a built-in. Ids are minted locally on import; the decoded
 *     beat's id is always null.
 *  3. NEVER THROW. A payload arriving by deep link that threw would crash the
 *     app on every relaunch, because the payload lives in the intent.
 *     [decodeShare] returns null on anything unexpected. The CALLER must also
 *     consume the link once and clear it — see the note on [SharePayload.Invalid].
 *  4. HARD BOUNDS ON EVERY NUMBER. `steps: 1e9` would mean a composable per
 *     cell and freeze the UI. Nothing is unbounded.
 *  5. NO PROSE. `description` and `group` are dropped entirely rather than
 *     validated — a description renders as a paragraph in the info sheet, which
 *     is a fine place to phish from. Editor-made beats never have one anyway.
 *
 * ── THE RULE FOR WHOEVER EXTENDS THIS FORMAT ──
 * The schema must stay a PURE DATA ALLOWLIST and must never gain a URL-valued or
 * code-valued field. PROJECT_PLAN §7 has "record or upload custom sample sounds"
 * on the roadmap; the day a beat can reference a sound, a share link carrying a
 * sound URL becomes an arbitrary remote fetch performed by every recipient. If
 * shared beats ever need custom sounds, the audio must be a bundled/known id,
 * never a URL from the payload.
 *
 * ── WIRE FORMAT ──
 * Must stay byte-compatible with the web app: links already shared from the
 * browser have to open here and vice versa. Deliberately compact, because a link
 * has to survive a messenger:
 *
 *   one beat    { v:1, t:"b", n, m, g, q, p }
 *   a category  { v:1, t:"c", n, b:[ {n,m,g,q,p}, ... ] }
 *
 * where n = name, m = bpm, g = groups, q = cells per quarter, and p = patterns
 * keyed by LANE ID, each a string with "-" for a rest ("X-OO-XOO"). Strings
 * rather than JSON arrays is most of the saving: ~250 bytes down to ~110. Keying
 * p by lane id means a commented-out lane needs no format change to start
 * sharing.
 *
 * steps, beatsPerBar, cellsPerGroup and note are all DERIVED on import, not
 * carried — four fewer fields anyone can lie about. The derivation mirrors the
 * editor's save exactly, so a beat round-trips byte-identically.
 *
 * ── WHAT LIVES ELSEWHERE ──
 * The web version also builds and reads the URL (`location.origin + '#b=' +
 * code`). That is deliberately NOT here: a codec that reaches for the platform's
 * location object cannot be unit-tested and cannot be reused. This file deals
 * only in code strings; the deep-link reading and link building live in the
 * platform layer, which is also where the "consume once, then clear" rule is
 * enforced.
 */
object ShareCodec {

    const val VERSION = 1
    const val REST_CHAR = "-"

    // Bounds. Generous enough that no real beat hits them, tight enough that a
    // hostile payload can't allocate anything interesting.
    const val MAX_CODE_LEN = 8000   // ~a category of ten beats, with headroom
    const val MAX_NAME_LEN = 40
    const val MAX_GROUPS = 32       // numbered beats in one pattern
    const val MAX_GROUP_CELLS = 12  // cells in one numbered beat
    const val MAX_STEPS = 64        // total cells — the DoS bound that matters
    const val MAX_CPQ = 12
    const val MAX_CAT_BEATS = 50

    private const val DEFAULT_NAME_BEAT = "Shared Beat"
    private const val DEFAULT_NAME_CATEGORY = "Shared List"
    private const val DEFAULT_BPM = 90

    /** Control and format characters: invisible, and used for override tricks. */
    private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}]")
    private val CODE_CHARS = Regex("^[A-Za-z0-9_-]+$")

    private val json = Json {
        // Lenient parsing, strict validation: we accept odd JSON but never
        // trust a field we did not ask for by name.
        isLenient = true
        ignoreUnknownKeys = true
    }

    // ── Result types ───────────────────────────────────────────────────────

    /** What a decoded share turned out to be. */
    sealed interface SharePayload {
        /** One beat, with `id == null` — the library mints one on import. */
        data class BeatPayload(val beat: Beat) : SharePayload

        data class CategoryPayload(val name: String, val beats: List<Beat>) : SharePayload

        /**
         * A `#b=` link was present but unusable.
         *
         * Distinct from "no link at all" so the UI can say "that link didn't
         * work" instead of silently doing nothing. The caller MUST consume the
         * inbound link exactly once and then clear it, so a hostile payload gets
         * one chance and cannot re-trigger on every relaunch.
         */
        data object Invalid : SharePayload
    }

    // ── base64url ──────────────────────────────────────────────────────────
    // Plain base64 is not URL-safe: "+" becomes a space and "=" terminates
    // awkwardly. UTF-8 first, so a name in Devanagari survives the trip — the
    // JS version goes through TextEncoder for exactly this reason.

    private fun toBase64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    private fun fromBase64Url(code: String): String {
        val bytes = Base64.getUrlDecoder().decode(code)
        return String(bytes, StandardCharsets.UTF_8)
    }

    // ── Encoding (trusted side — these beats are already in the library) ────

    /**
     * One lane's pattern as a fixed-length string: exactly [steps] characters
     * whatever the array holds, matching how the strip renders and the sequencer
     * plays — extra data ignored, missing data rests.
     */
    private fun packPattern(beat: Beat, lane: LaneId, steps: Int): String? {
        val cells = beat.pattern(lane) ?: return null
        val sb = StringBuilder(steps)
        for (i in 0 until steps) {
            val stroke = cells.getOrNull(i)
            sb.append(stroke?.code ?: REST_CHAR)
        }
        return sb.toString()
    }

    private fun packBeat(beat: Beat): JsonObject {
        val groups = groupsFor(beat)
        val steps = sumGroups(groups)
        return buildJsonObject {
            put("n", beat.name)
            put("m", beat.bpm)
            put("g", buildJsonArray { groups.forEach { add(it) } })
            put("q", cpqFor(beat))
            putJsonObject("p") {
                for (lane in LaneId.ORDERED) {
                    val packed = packPattern(beat, lane, steps) ?: continue
                    put(lane.wireId, packed)
                }
            }
        }
    }

    fun encodeBeat(beat: Beat): String {
        val payload = buildJsonObject {
            put("v", VERSION)
            put("t", "b")
            // Key order matches the JS encoder field-for-field, so the same beat
            // produces the same code on both platforms and links are diffable.
            packBeat(beat).forEach { (k, v) -> put(k, v) }
        }
        return toBase64Url(payload.toString())
    }

    fun encodeCategory(name: String, beats: List<Beat>): String {
        val payload = buildJsonObject {
            put("v", VERSION)
            put("t", "c")
            put("n", name)
            put("b", buildJsonArray { beats.forEach { add(packBeat(it)) } })
        }
        return toBase64Url(payload.toString())
    }

    // ── Decoding (untrusted side — assume every field is hostile) ──────────

    /**
     * Strip control/format characters, trim, cap.
     *
     * Not ASCII-only: real names may be Devanagari. The cap is about layout and
     * storage; the strip is about invisible direction-override and zero-width
     * tricks in a name that will sit beside the word "Built in". Compose escapes
     * text as thoroughly as React does, so the strip is about deception rather
     * than injection — but a name that renders right-to-left over its neighbour
     * is still a lie.
     */
    private fun cleanName(value: JsonElement?, fallback: String): String {
        val raw = (value as? JsonPrimitive)?.contentOrNull() ?: return fallback
        val cleaned = INVISIBLE.replace(raw, "").trim().take(MAX_NAME_LEN)
        return cleaned.ifEmpty { fallback }
    }

    /** The text of a JSON primitive, or null for a literal `null`. */
    private fun JsonPrimitive.contentOrNull(): String? =
        if (this is JsonNull) null else this.content

    private fun validGroups(element: JsonElement?): List<Int>? {
        val array = element as? JsonArray ?: return null
        if (array.isEmpty() || array.size > MAX_GROUPS) return null

        val groups = ArrayList<Int>(array.size)
        for (item in array) {
            // An integer, and nothing but: a float that happens to be whole
            // ("3.0") is rejected, because the web encoder never emits one and
            // accepting it widens the format for no reason.
            val primitive = item as? JsonPrimitive ?: return null
            val n = primitive.intOrNull ?: return null
            if (n < 1 || n > MAX_GROUP_CELLS) return null
            groups.add(n)
        }

        val steps = sumGroups(groups)
        if (steps < 1 || steps > MAX_STEPS) return null
        return groups
    }

    /**
     * One lane's pattern.
     *
     * @return `first` the cells, or null if the value was present but invalid;
     *   `second` is true exactly in that rejected case. Absent (or literal null)
     *   yields all-rests and is NOT a rejection — the web encoder omits a lane a
     *   beat doesn't use, so an absent key is normal.
     */
    private fun unpackPattern(
        patterns: JsonObject,
        lane: LaneId,
        steps: Int,
    ): Pair<List<Stroke?>?, Boolean> {
        val element = patterns[lane.wireId]
        // Absent lane → all rests. Every lane gets an array even when the
        // payload omits it, because the sequencer indexes patterns unguarded.
        if (element == null || element is JsonNull) return Pair(List(steps) { null }, false)

        val primitive = element as? JsonPrimitive ?: return Pair(null, true)
        val text = primitive.contentOrNull() ?: return Pair(null, true)
        if (text.length != steps) return Pair(null, true)

        val cells = ArrayList<Stroke?>(steps)
        for (ch in text) {
            cells.add(if (ch.toString() == REST_CHAR) null else Stroke.fromCode(ch.toString()))
        }
        return Pair(cells, false)
    }

    /**
     * One beat, as a DRAFT: everything a beat needs except an id, which is
     * minted locally on import. Returns null if anything is off.
     */
    private fun decodeBeat(raw: JsonElement?): Beat? {
        val obj = raw as? JsonObject ?: return null

        val groups = validGroups(obj["g"]) ?: return null

        val q = (obj["q"] as? JsonPrimitive)?.intOrNull ?: return null
        if (q < 1 || q > MAX_CPQ) return null

        val patterns = obj["p"] as? JsonObject ?: return null

        val steps = sumGroups(groups)
        val lanes = LinkedHashMap<LaneId, List<Stroke?>>()
        for (lane in LaneId.ORDERED) {
            val (cells, rejected) = unpackPattern(patterns, lane, steps)
            if (rejected || cells == null) return null
            lanes[lane] = cells
        }

        val bpmRaw = (obj["m"] as? JsonPrimitive)?.doubleOrNull
        val bpm = if (bpmRaw != null && bpmRaw.isFinite()) {
            bpmRaw.roundToInt().coerceIn(MIN_BPM, MAX_BPM)
        } else {
            DEFAULT_BPM
        }

        // Derived exactly as the editor's save derives them, so a shared beat
        // and its original are the same object apart from the id.
        val uniform = groups.all { it == groups[0] }

        return Beat(
            id = null,               // NEVER trusted from a payload; see rule 2
            name = cleanName(obj["n"], DEFAULT_NAME_BEAT),
            note = "Custom",
            bpm = bpm,
            steps = steps,
            beatsPerBar = steps / q,
            cellsPerGroup = if (uniform) groups[0] else q,
            groups = groups,
            description = null,      // dropped, not validated; see rule 5
            lanePatterns = lanes,
            group = null,            // dropped for the same reason
        )
    }

    /**
     * The one entry point for untrusted data.
     *
     * Never throws — that is the contract, not an implementation detail.
     */
    fun decodeShare(code: String?): SharePayload? {
        return try {
            if (code == null) return null
            // Cheap checks first, so a 20 MB paste never reaches the base64
            // decoder or the JSON parser.
            if (code.isEmpty() || code.length > MAX_CODE_LEN) return null
            if (!CODE_CHARS.matches(code)) return null

            val raw = json.parseToJsonElement(fromBase64Url(code)) as? JsonObject ?: return null

            // Exact version match: a future v2 fails cleanly rather than
            // half-parsing into something subtly wrong.
            val version = (raw["v"] as? JsonPrimitive)?.intOrNull
            if (version != VERSION) return null

            when ((raw["t"] as? JsonPrimitive)?.contentOrNull()) {
                "b" -> decodeBeat(raw)?.let { SharePayload.BeatPayload(it) }

                "c" -> {
                    val list = raw["b"] as? JsonArray ?: return null
                    if (list.isEmpty() || list.size > MAX_CAT_BEATS) return null
                    val beats = ArrayList<Beat>(list.size)
                    for (entry in list) {
                        // One bad beat rejects the whole list: a half-imported
                        // playlist is worse than a refused one, because the user
                        // cannot tell which beats are missing.
                        val beat = decodeBeat(entry) ?: return null
                        beats.add(beat)
                    }
                    SharePayload.CategoryPayload(
                        name = cleanName(raw["n"], DEFAULT_NAME_CATEGORY),
                        beats = beats,
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            // Malformed base64, malformed JSON, anything at all — one friendly
            // null. A hostile link must not be able to choose our exception.
            null
        }
    }

    /**
     * A pasted value may be a whole URL or a bare code; take the code either way.
     * Uses the LAST occurrence, so a link whose own query contains `#b=` still
     * yields the intended payload.
     */
    fun codeFromInput(text: String?): String {
        if (text == null) return ""
        val trimmed = text.trim()
        val at = trimmed.lastIndexOf("#b=")
        return if (at == -1) trimmed else trimmed.substring(at + 3)
    }
}
