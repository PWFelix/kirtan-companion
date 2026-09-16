package com.kirtan.companion.data.model

/**
 * The value types every layer of the app speaks in.
 *
 * Ported from `src/data/lanes.js`, `src/data/strokes.js` and the beat shape
 * documented in `src/storage/BeatsProvider.js`. The web app models a stroke as
 * the string `"O"` / `"X"` or `null`; here a rest is a Kotlin `null` and the
 * two strokes are an enum, so an impossible stroke cannot be constructed.
 */

/** One instrument row: an instrument, or one end of the mridanga. */
enum class LaneId(
    val wireId: String,
    val label: String,
    /**
     * Primary lanes are a beat's rhythmic identity (the mridanga). MINI strips
     * — beat lists, quick-pick — render primary lanes only: at 8px marks four
     * stacked lanes are noise. Mirrors the `primary` flag in lanes.js.
     */
    val primary: Boolean,
) {
    DAYAN("dayan", "Dayan", true),
    BAYAN("bayan", "Bayan", true),

    /**
     * Karatalas — the congregation's timekeeper. NOT primary: they colour a
     * beat, they don't define its rhythmic identity.
     */
    KARTAL("kartal", "Kartal", false),

    // ── Ready for when this instrument is added ──
    // MELODY("melody", "Melody", false),
    ;

    companion object {
        /** Lanes in render order, top to bottom. */
        val ORDERED: List<LaneId> = entries.toList()

        fun fromWire(id: String): LaneId? = entries.firstOrNull { it.wireId == id }
    }
}

/**
 * A struck cell. The two values are the only strokes the app knows; a rest is
 * represented by `null` in a pattern, not by a third enum constant, so that
 * `List<Stroke?>` reads exactly like the JS `["O", null, "X"]` arrays.
 */
enum class Stroke(val code: String) {
    OPEN("O"),
    CLOSED("X"),
    ;

    companion object {
        fun fromCode(code: String?): Stroke? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One playable pattern.
 *
 * BPM is the quarter-note PULSE (the 1-2-3-4 you'd clap along to) and
 * [beatsPerBar] is how many of those pulses make one bar, so the transport can
 * derive the per-cell interval from `steps / beatsPerBar`:
 *
 *     8 in 4 → 2 per beat → eighth notes
 *    12 in 4 → 3 per beat → eighth-triplets (dadra "galloping" feel)
 *    16 in 4 → 4 per beat → sixteenth notes
 *
 * Keeping both explicit means a future 3/4 or 6/8 beat needs no engine change.
 *
 * [cellsPerGroup] is the "musical unit" the editor grows and shrinks by, and
 * holds the invariant `steps = beatsPerBar × cellsPerGroup` so each cell's
 * DURATION stays fixed no matter how many cells there are — adding cells makes
 * the loop longer in real time without changing the tempo (subdivision-locked,
 * not bar-locked).
 *
 * [groups] is the uneven-meter generalisation of [cellsPerGroup]: one entry per
 * NUMBERED beat, its value the cells it spans, so 7/8 is `[2,2,3]`. Built-in
 * beats predate it and carry `null`; see [com.kirtan.companion.data.groupsFor].
 *
 * @param id `null` for an unsaved editor draft — that absence is what makes
 *   saving a built-in beat a FORK rather than an edit.
 */
data class Beat(
    val id: String?,
    val name: String,
    val note: String,
    val bpm: Int,
    val steps: Int,
    /**
     * How many quarter-note pulses make one bar.
     *
     * A [Double] because uneven meters are fractional: 7/8 at eighth-note
     * subdivision is 3.5 quarters to the bar. The web app stores exactly this, and
     * the beat editor's seven-eight preset produces it, so an Int here would
     * silently retime every uneven meter the editor can author. Timing itself
     * never divides by this — [com.kirtan.companion.engine.MusicalClock] turns
     * `PPQ × beatsPerBar` into whole ticks — it is the declaration of the meter,
     * not the arithmetic of the loop.
     */
    val beatsPerBar: Double,
    val cellsPerGroup: Int,
    val groups: List<Int>?,
    val description: String?,
    val lanePatterns: Map<LaneId, List<Stroke?>>,
    /**
     * The built-in beats' section heading — "Everyday", "Building up", and so
     * on. The Beats screen derives its headings from the DISTINCT groups in
     * [com.kirtan.companion.data.BEATS] order and filters by this, so it is
     * display metadata, not a category: `"builtin"` is the category, and group
     * only sub-divides it visually. Null for editor-made beats.
     */
    val group: String? = null,
) {
    /** True when this beat shipped with the app rather than being user-made. */
    val isBuiltIn: Boolean get() = id != null && com.kirtan.companion.data.BUILT_IN_ID_SET.contains(id)

    /**
     * True when the user may not overwrite it. Built-in beats are read-only;
     * editing one forks it (see BeatEditor's open path in App).
     */
    val readOnly: Boolean get() = isBuiltIn

    /** A lane's pattern, or null when the beat has no row for that lane. */
    fun pattern(lane: LaneId): List<Stroke?>? = lanePatterns[lane]

    /**
     * The stroke at [index] on [lane]. Absent lanes and short arrays both read
     * as a rest, which is what lets a beat omit the karatalas entirely.
     */
    fun strokeAt(lane: LaneId, index: Int): Stroke? =
        lanePatterns[lane]?.getOrNull(index)

    /** Lanes this beat actually uses, in render order. */
    fun activeLanes(): List<LaneId> = LaneId.ORDERED.filter { lanePatterns.containsKey(it) }
}

/**
 * An ordered set of beats — a kirtan progression. Home's ‹ › cycles through
 * the ACTIVE category, which is what makes a playlist more than a folder.
 *
 * `beatIds` may mix a built-in id (`"te_ta"`) with a custom beat's uuid,
 * which is why the matching `playlists.beat_ids` column is `text[]` and not a
 * foreign key.
 */
data class Category(
    val id: String,
    val name: String,
    val beatIds: List<String>,
)

/** Everything a provider owns: the user's saved work, and nothing else. */
data class Library(
    val beats: List<Beat>,
    val categories: List<Category>,
    val activeCategoryId: String?,
) {
    companion object {
        val EMPTY = Library(emptyList(), emptyList(), null)
    }
}

/** A beat published to the community library. */
data class PublishedBeat(
    val id: String,
    val authorId: String?,
    val authorName: String?,
    val beat: Beat,
    val createdAt: Long?,
)
