package com.kirtan.companion.ui.learn

import com.kirtan.companion.data.Bols
import com.kirtan.companion.data.groupsFor
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import kotlin.math.floor
import kotlin.math.max

/**
 * The geometry of the Learn "highway": which strokes are on screen, and where.
 *
 * Pure (no Compose, no audio) so it can be unit-tested and so the Canvas that
 * draws it does nothing but paint. Everything is measured in STEPS, relative to
 * the playhead: a note at `rel = 0` is sounding now and sits on the hit line;
 * `rel = 3` will sound three cells from now and is three step-heights above it.
 *
 * WHY POSITION COMES FROM THE BAR PHASE. The engine's `getPhase()` is a pure
 * function of the audio frame actually being heard (it is already backed off by
 * the output latency), so `phase × steps` is a continuous, drift-free playhead.
 * Animating off step events instead would need interpolation and would lag the
 * sound by up to a whole cell.
 *
 * WHY ONE COLUMN PER HAND. The learner's eyes only need to know WHICH HAND
 * and WHEN; the hand already knows how to make the stroke. So the mridanga is
 * two columns — bayan (left hand) on the left, dayan (right hand) on the right,
 * the way the drum sits in front of a player — and the karatalas one. Each note
 * still says what it is: open is a disc, closed an X, with its bol beside it.
 *
 * A left-handed player sits the drum the other way round — dayan under the left
 * hand — so [Highway.columnsFor] mirrors the two.
 */
typealias HighwayColumn = LaneId

/**
 * One falling note: in [column] (its lane), struck [stroke]. [step] is its index
 * in the beat, [rel] its distance in steps.
 */
data class HighwayNote(val column: HighwayColumn, val stroke: Stroke, val step: Int, val rel: Double) {
    /** The spoken syllable for this stroke, drawn beside the note. */
    val bol: String get() = Bols.forStroke(column.wireId, stroke.code) ?: ""
}

/** A horizontal guide at a beat (group) boundary; [sam] marks the bar's first. */
data class HighwayLine(val rel: Double, val sam: Boolean)

data class HighwayFrame(
    val notes: List<HighwayNote>,
    val lines: List<HighwayLine>,
    /** The step under the hit line, for the "now playing" bol readout. */
    val currentStep: Int,
)

/**
 * What Learn teaches: the mridanga (two columns, one per hand, mirrored for a
 * left-handed player) or the karatalas (one column; handedness does not apply).
 * Same split as the web's highway.js INSTRUMENTS.
 */
enum class LearnInstrument(val columns: List<HighwayColumn>) {
    MRDANGA(listOf(LaneId.BAYAN, LaneId.DAYAN)),
    KARTAL(listOf(LaneId.KARTAL)),
    ;

    val lanes: List<LaneId> get() = columns
}

object Highway {

    /** The columns left to right; mirrored for a left-handed player. */
    fun columnsFor(leftHanded: Boolean, instrument: LearnInstrument = LearnInstrument.MRDANGA): List<HighwayColumn> =
        when {
            instrument != LearnInstrument.MRDANGA -> instrument.columns
            leftHanded -> instrument.columns.reversed()
            else -> instrument.columns
        }

    /** True when the beat gives [instrument] anything to play. */
    fun hasPart(beat: Beat, instrument: LearnInstrument): Boolean =
        instrument.lanes.any { lane -> beat.pattern(lane)?.any { it != null } == true }

    /** How much time the screen shows ahead of the hit line. */
    const val LOOKAHEAD_SECONDS = 1.8

    /** How far past the hit line a note keeps drawing (fading) before it goes. */
    const val TRAIL_STEPS = 1.0

    /**
     * How many steps fit in the lookahead at this tempo. Time-based rather than
     * a fixed count, so notes fall at a readable SPEED whatever the beat: a slow
     * 8-cell beat and a 140-BPM double-time both give the eye ~2 s of warning.
     */
    fun lookaheadSteps(bpm: Int, cellsPerQuarter: Int): Double =
        max(4.0, LOOKAHEAD_SECONDS * bpm * cellsPerQuarter / 60.0)

    /**
     * The notes and beat lines visible with the playhead at [position] steps
     * (fractional, `phase × steps`). The beat loops, so the window wraps past the
     * end of the pattern into the next bar.
     */
    fun frame(
        beat: Beat,
        position: Double,
        lookahead: Double,
        instrument: LearnInstrument = LearnInstrument.MRDANGA,
    ): HighwayFrame {
        val steps = beat.steps
        if (steps <= 0) return HighwayFrame(emptyList(), emptyList(), 0)
        val now = floor(position).toInt()
        val first = floor(position - TRAIL_STEPS).toInt()
        val last = floor(position + lookahead).toInt() + 1

        val notes = ArrayList<HighwayNote>()
        val lines = ArrayList<HighwayLine>()
        val boundaries = groupBoundaries(beat)

        for (abs in first..last) {
            val step = Math.floorMod(abs, steps)
            val rel = abs - position
            for (lane in instrument.lanes) {
                val stroke = beat.pattern(lane)?.getOrNull(step) ?: continue
                notes.add(HighwayNote(lane, stroke, step, rel))
            }
            if (step in boundaries) lines.add(HighwayLine(rel, sam = step == 0))
        }
        return HighwayFrame(notes, lines, Math.floorMod(now, steps))
    }

    /** The bol spoken at [step]: a two-hand combination if both heads strike. */
    fun bolAt(beat: Beat, step: Int): String? {
        val d = beat.pattern(LaneId.DAYAN)?.getOrNull(step)?.code
        val b = beat.pattern(LaneId.BAYAN)?.getOrNull(step)?.code
        return Bols.combo(d, b)
            ?: Bols.forStroke(LaneId.DAYAN.wireId, d)
            ?: Bols.forStroke(LaneId.BAYAN.wireId, b)
    }

    /** The step index at which each group (beat) of the bar begins. */
    fun groupBoundaries(beat: Beat): Set<Int> {
        val out = HashSet<Int>()
        var at = 0
        for (g in groupsFor(beat)) {
            if (at < beat.steps) out.add(at)
            at += g
        }
        if (out.isEmpty()) out.add(0)
        return out
    }
}
