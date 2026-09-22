package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke

/**
 * Pure beat data. No logic, no dependencies.
 *
 * Transcribed from `src/data/beats.js`, which is itself transcribed from
 * Sita-pati das, "The Art and Science of Harinam Sankirtan Yajna".
 *
 * ── THIS IS THE FALLBACK SET, NOT THE CANONICAL ONE ──
 * The built-ins the app shows come from the server's `shipped_beats` table when
 * it can be read, and from this list when it cannot — see [ShippedBeats]. Both
 * files are still generated from the same source (scripts/generateBuiltinBeats.mjs)
 * and must still agree at release time, for two reasons that survive the move
 * server-side: an offline user plays THIS list, and a shared `#b=` link carries no
 * id, so each client resolves built-in ids against the set it happens to be
 * holding. Divergent ids or patterns mean the same link plays a different beat on
 * each platform.
 *
 * Pattern notation is the wire format's own: `"O"` open, `"X"` closed, `"-"`
 * rest. Using it here too means a pattern in this file can be pasted straight
 * into [ShareCodec] and vice versa, and the length check in [builtIn] catches a
 * mistyped cell at class-load time rather than as a silently short loop. The
 * server's `lanes` column uses this same notation, so
 * [com.kirtan.companion.storage.ShippedBeatsClient] and this file parse patterns
 * identically.
 */

private fun pattern(notation: String): List<Stroke?> =
    notation.map { Stroke.fromCode(it.toString()) }

/**
 * One built-in beat, with the invariant every beat must hold checked up front:
 * `steps` must equal each lane's pattern length AND `beatsPerBar ×
 * cellsPerGroup`. A stale `steps` here once made the web strip wrap into extra
 * rows and the sequencer play only half the pattern; failing loudly at load
 * beats that bug silently.
 */
private fun builtIn(
    id: String,
    group: String,
    name: String,
    note: String,
    bpm: Int,
    groups: List<Int>,
    cpq: Int,
    dayan: String,
    bayan: String,
    kartal: String? = null,
    description: String? = null,
): Beat {
    // steps / beatsPerBar / cellsPerGroup are DERIVED, not declared, and derived
    // exactly as ShareCodec.decodeBeat derives them. That is the point: a built-in
    // then round-trips through the share format to an identical beat, which a
    // test asserts, so the two derivations cannot drift apart.
    //
    // The old `steps == beatsPerBar × cellsPerGroup` check is GONE because it is
    // not a general truth: it holds only when cells-per-group equals cells-per-
    // quarter. A compound meter breaks it — `matan` is 16 groups of 3 at eighth
    // subdivision, so 48 cells over 24 quarters, and 24 × 3 ≠ 48. The invariant
    // that does hold, and is checked below, is steps == sum(groups).
    val steps = sumGroups(groups)
    require(steps > 0) { "beat \"$id\": groups sum to zero cells" }
    require(cpq >= 1) { "beat \"$id\": cells-per-quarter must be at least 1" }

    val uniform = groups.all { it == groups[0] }
    val cellsPerGroup = if (uniform) groups[0] else cpq
    val beatsPerBar = steps.toDouble() / cpq

    val lanes = linkedMapOf(
        LaneId.DAYAN to pattern(dayan),
        LaneId.BAYAN to pattern(bayan),
    )
    // A beat without a kartal line simply has no cymbal row — the map key is
    // absent, which is what `activeLanes()` and the strip's lane filter read.
    if (kartal != null) lanes[LaneId.KARTAL] = pattern(kartal)

    lanes.forEach { (lane, cells) ->
        require(cells.size == steps) {
            "beat \"$id\": lane ${lane.wireId} has ${cells.size} cells, expected $steps"
        }
    }

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

val BEATS: List<Beat> = listOf(
    builtIn(
        id = "double_time_2", group = "Sixteenths", name = "Double Time 2", note = "4 beats",
        bpm = 140, groups = listOf(4, 4, 4, 4), cpq = 4,
        dayan = "X-OOX-OOX-OOX-OO",
        bayan = "O--X-OO-O--X-OO-",
        kartal = "O-XXO-XXO-XXO-XX",
    ),
    builtIn(
        id = "daspahir_taal", group = "Sixteenths", name = "daspahir taal", note = "8 beats",
        bpm = 90, groups = listOf(4, 4, 4, 4, 4, 4, 4, 4), cpq = 4,
        dayan = "----X-O-X-XXXXO-----O-O---O---O-",
        bayan = "O---------------X--X-OO-O-O-O-O-",
    ),
    builtIn(
        id = "tehai", group = "Sixteenths", name = "tehai", note = "4 beats",
        bpm = 90, groups = listOf(4, 4, 4, 4), cpq = 4,
        dayan = "-O-OO--O-OO--O-O",
        bayan = "X-X-O-X-X-O-X-X-",
    ),
    builtIn(
        id = "pick_up", group = "Sixteenths", name = "pick up", note = "4 beats",
        bpm = 90, groups = listOf(4, 4, 4, 4), cpq = 4,
        dayan = "O-OO-O-OO-OO-O-O",
        bayan = "-X-X--X--X-X--X-",
    ),
    builtIn(
        id = "bhajani_taal", group = "Sixteenths", name = "bhajani taal", note = "4 beats",
        bpm = 90, groups = listOf(4, 4, 4, 4), cpq = 4,
        dayan = "--O---O---O---O-",
        bayan = "OO-O-O--XX-X-X-O",
    ),
    builtIn(
        id = "matan", group = "Straight", name = "matan", note = "16 beats",
        bpm = 157, groups = listOf(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3), cpq = 2,
        dayan = "OOOOOOOOOOOOOOOOOOOOOOO-OOOOOOOOOOOOOOOOOOOOOOO-",
        bayan = "OOOOO-OOOOO-OOOOO-OO-O--XXXXX-XXXXX-XXXXX-XX-X--",
    ),
    builtIn(
        id = "lofa_taal_two_beat_damodarastakam", group = "Straight", name = "lofa taal two beat damodarastakam", note = "8 beats",
        bpm = 90, groups = listOf(3, 3, 3, 3, 3, 3, 3, 3), cpq = 2,
        dayan = "O-XXXXO-XXXXO-XXXXO---OO",
        bayan = "O-O-O-O-O-O---------XX--",
    ),
    builtIn(
        id = "iskcon_smasher", group = "Sixteenths", name = "Iskcon smasher", note = "4 beats",
        bpm = 90, groups = listOf(4, 4, 4, 4), cpq = 4,
        dayan = "O--O--O-O--O--O-",
        bayan = "O---O-O---O-O-O-",
    ),
    builtIn(
        id = "keherva_medium_speed", group = "Straight", name = "keherva medium speed", note = "4 beats",
        bpm = 90, groups = listOf(2, 2, 2, 2), cpq = 2,
        dayan = "O-XOO-XO",
        bayan = "OO-X-OO-",
    ),
)

/**
 * The distinct `group` headings of the COMPILED set, in declaration order.
 *
 * The set the app actually shows is [ShippedBeats.effective], and the Beats
 * screen derives ITS headings from the list it was handed rather than from this
 * constant — a beat promoted after this APK was built carries a heading that is
 * not in here. What remains true, and what a test pins, is that the fallback set
 * has exactly these two sections in this order.
 */
val BUILT_IN_GROUPS: List<String> = BEATS.mapNotNull { it.group }.distinct()

/**
 * The default beat the app loads before the user chooses one.
 *
 * Via [ShippedBeats] rather than `BEATS.first()` so the default follows a
 * remotely updated set: a maintainer who reorders `ordinal` is reordering what
 * every installed app opens on, which is the point of serving the list.
 */
val DEFAULT_BEAT: Beat get() = ShippedBeats.default
