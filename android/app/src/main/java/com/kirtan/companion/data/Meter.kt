package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The pure meter + tempo facts, with no UI and no audio behind them.
 *
 * Ported from `src/data/meter.js`. WHY THIS FILE EXISTS carries over: the
 * built-in beats predate the groups model. They carry `steps` / `beatsPerBar` /
 * `cellsPerGroup` but no `groups` list, while editor-made beats carry `groups`
 * and treat it as the truth. Anything that needs a beat's real meter — the
 * editor when it opens one, the share codec when it packs one — has to be able
 * to RECONSTRUCT the missing half, and that reconstruction is subtle enough
 * (see [cpqFor]) that it should exist once, not once per caller.
 *
 * The two vocabularies, because they are easy to confuse:
 *   groups   one entry per NUMBERED beat, its value = cells it spans.
 *            7/8 is [2,2,3]. Uneven meters need this.
 *   cpq      cells per QUARTER NOTE — the subdivision density. It sets timing:
 *            beatsPerBar = steps / cpq, and the transport turns that into a
 *            per-cell interval.
 *
 * These coincide for 4/4-in-eighths ([2,2,2,2], cpq 2) and diverge the moment a
 * beat is grouped in threes: 6/8 is groups [3,3] with cpq 2.
 */

/**
 * Tempo bounds. They live here rather than in the transport ViewModel so that
 * pure data modules can clamp a BPM without dragging in Android, Compose or the
 * audio engine.
 */
const val MIN_BPM = 40
const val MAX_BPM = 200

fun clampBpm(bpm: Int): Int = bpm.coerceIn(MIN_BPM, MAX_BPM)

fun sumGroups(groups: List<Int>): Int = groups.sum()

/**
 * A beat's groups — stored if it has them, reconstructed if it doesn't.
 */
fun groupsFor(beat: Beat): List<Int> {
    beat.groups?.let { return it.toList() }
    val cpg = if (beat.cellsPerGroup > 0) {
        beat.cellsPerGroup
    } else {
        max(1, (beat.steps.toDouble() / beat.beatsPerBar).roundToInt())
    }
    val beats = max(1, (beat.steps.toDouble() / cpg).roundToInt())
    return List(beats) { cpg }
}

/**
 * A beat's cells-per-quarter. Derived from the TIMING fields, never from
 * `cellsPerGroup` — for 6/8 the editor saves cellsPerGroup 3 while cpq is 2, so
 * reading cellsPerGroup here would make the beat play half again too fast.
 */
fun cpqFor(beat: Beat): Int =
    max(1, (beat.steps.toDouble() / if (beat.beatsPerBar > 0) beat.beatsPerBar else 4).roundToInt())
