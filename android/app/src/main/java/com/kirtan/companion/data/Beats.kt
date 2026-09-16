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
 * THE TWO LISTS MUST STAY IN SYNC. A beat added or retimed on the web has to be
 * mirrored here, because a shared `#b=` link carries no id: the recipient's
 * library resolves built-in ids against ITS OWN compiled list. Divergent ids or
 * patterns mean the same link plays a different beat on each platform.
 *
 * Pattern notation is the wire format's own: `"O"` open, `"X"` closed, `"-"`
 * rest. Using it here too means a pattern in this file can be pasted straight
 * into [ShareCodec] and vice versa, and the length check in [builtIn] catches a
 * mistyped cell at class-load time rather than as a silently short loop.
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
    steps: Int,
    beatsPerBar: Int,
    cellsPerGroup: Int,
    description: String,
    dayan: String,
    bayan: String,
    kartal: String? = null,
): Beat {
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
    require(beatsPerBar * cellsPerGroup == steps) {
        "beat \"$id\": beatsPerBar($beatsPerBar) × cellsPerGroup($cellsPerGroup) != steps($steps)"
    }

    return Beat(
        id = id,
        name = name,
        note = note,
        bpm = bpm,
        steps = steps,
        beatsPerBar = beatsPerBar,
        cellsPerGroup = cellsPerGroup,
        groups = null, // built-ins predate the groups model; see groupsFor()
        description = description,
        lanePatterns = lanes,
        group = group,
    )
}

val BEATS: List<Beat> = listOf(
    builtIn(
        id = "te_ta", group = "Foundations", name = "Te Ta", note = "Foundational",
        bpm = 80, steps = 8, beatsPerBar = 4, cellsPerGroup = 2,
        description = "The first pattern every player drills: “te ta te ta” on the small head over the standard off-beat, moving the kirtan along in a bopping fashion. The ringing open “ta” is the heart of it — the closed “te” is just a touch that stops the ring, not a slap. A steady, roomy beat for learning and for kirtans that should bounce gently rather than drive.",
        //      1  +  2  +  3  +  4  +
        dayan = "XOXOXOXO",
        bayan = "O--X-OO-",
        kartal = "O-O-O---",
    ),
    builtIn(
        id = "forward", group = "Everyday", name = "Forward", note = "Everyday",
        bpm = 90, steps = 8, beatsPerBar = 4, cellsPerGroup = 2,
        description = "The everyday “Forwards” beat — “te tata, te tata”, with the double open strike pushing each phrase ahead. A reliable default for congregational chanting at a walking tempo, and the same pattern becomes the fast double-time beat when the kirtan takes off.",
        //      1  +  2  +  3  +  4  +
        dayan = "X-OOX-OO",
        bayan = "O--X-OO-",
        kartal = "O-O-O---",
    ),
    builtIn(
        id = "backward", group = "Everyday", name = "Backward", note = "Variation",
        bpm = 90, steps = 8, beatsPerBar = 4, cellsPerGroup = 2,
        description = "The reverse of Forward — “ta te tata” — a phrasing common in North Indian tabla playing. Swap it in against Forward to keep a long kirtan fresh without changing the feel; at double speed it becomes the top end of a fired-up Vrindavan-mellows style beat.",
        //      1  +  2  +  3  +  4  +
        dayan = "OOOOXO--",
        bayan = "O-X-O-O-",
        kartal = "O-O-O---",
    ),
    builtIn(
        id = "funky_swing", group = "Everyday", name = "Funky Swing", note = "Lively",
        bpm = 95, steps = 8, beatsPerBar = 4, cellsPerGroup = 2,
        description = "From a Vrindavan-mellows beat “that has a really funky swing to it” — the pair of closed strokes after each open one gives the bounce. Good for long stretches of chanting the same melody, such as when the microphone is being passed around the kirtan.",
        //      1  +  2  +  3  +  4  +
        dayan = "OXXOXX--",
        bayan = "O-X-O-O-",
        kartal = "O-O-O---",
    ),
    builtIn(
        id = "da_ge_te_te", group = "Building up", name = "Da Ge Te Te", note = "Build up",
        bpm = 110, steps = 8, beatsPerBar = 4, cellsPerGroup = 2,
        description = "“Da ge te te take dhena” — a beat from Bablu das, used when you want to ramp the kirtan up. The extra open bass at the top of the bar builds momentum: start with Forward, and move to this as the energy climbs toward the fast section.",
        //      1  +  2  +  3  +  4  +
        dayan = "XOXOXOXO",
        bayan = "OOX-O-O-",
        kartal = "O-O-O---",
    ),
    builtIn(
        id = "prabhupada", group = "Gentle", name = "Prabhupada", note = "Gentle",
        bpm = 65, steps = 8, beatsPerBar = 4, cellsPerGroup = 2,
        description = "A slow, spacious beat in the style of Srila Prabhupada’s own playing: the bass head sits silent through the first half of the cycle, then answers. For early-morning programs, bhajans, and chanting that should stay meditative — let it breathe at a low tempo.",
        //      1  +  2  +  3  +  4  +
        dayan = "XOXOXOXO",
        bayan = "----XXOO",
        kartal = "O-O-O---",
    ),

    // ── Double-time (16 steps) ──
    builtIn(
        id = "double_time", group = "Building up", name = "Double Time", note = "Fast",
        bpm = 140, steps = 16, beatsPerBar = 4, cellsPerGroup = 4,
        description = "Forward doubled into sixteenths — the double-time beat used for the Nrsimha prayers and the Pancha-tattva mantra. For the fast section of kirtan when the chant doubles up; keep it controlled so the singers can stay with you.",
        //      1  e  +  a  2  e  +  a  3  e  +  a  4  e  +  a
        dayan = "X-OOX-OOX-OOX-OO",
        bayan = "O--X-OO-O--X-OO-",
        // 1-2-3 lands on the quarter-note pulses (steps 0, 4, 8), rest on 4 (12).
        kartal = "O---O---O-------",
    ),

    // ── Dadra taal (12 steps, felt as 4/4 with triplets) ──
    builtIn(
        id = "dadra", group = "Swing", name = "Dadra Taal", note = "Swing",
        bpm = 105, steps = 12, beatsPerBar = 4, cellsPerGroup = 3,
        description = "A 6/8 dadra-taal pattern that lands as a triplet “gallop” against the usual four-beat kirtan, making everything swing. Lovely under swaying melodies and Vrindavan-mellows moods — use it as seasoning rather than the whole meal, or open a kirtan in dadra and switch to double time as it builds.",
        //      1  t  l  2  t  l  3  t  l  4  t  l
        dayan = "X-O-O-X-O-O-",
        bayan = "O--X-----O--",
        // 1-2-3 on the pulses (steps 0, 3, 6), rest on the fourth (9).
        kartal = "O--O--O-----",
    ),
)

/** The ids compiled into this build; makes [Beat.isBuiltIn] work. */
internal val BUILT_IN_ID_SET: Set<String> = BEATS.mapNotNull { it.id }.toSet()

/** The distinct `group` headings, in the order the beats declare them. */
val BUILT_IN_GROUPS: List<String> = BEATS.mapNotNull { it.group }.distinct()

/** The default beat the app loads before the user chooses one. */
val DEFAULT_BEAT: Beat get() = BEATS.first()
