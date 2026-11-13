package com.kirtan.companion.engine

import com.kirtan.companion.data.model.Beat

/** One entry of a playing sequence: a beat, held for [bars] bars. */
data class SequenceStep(val beat: Beat, val bars: Int)

/**
 * Where a playing sequence is, counted in DOWNBEATS.
 *
 * Pure and audio-thread-owned: [KirtanEngine.render] calls [onDownbeat] at every
 * bar line it plays, and the answer says whether that 1 still belongs to the
 * current entry, starts the next one, or is past the end. Kept out of the render
 * loop so the counting can be tested without a clock or a device.
 *
 * The first downbeat after [reset] is bar 1 of entry 0 and is not an advance.
 */
class SequenceCursor(private val bars: List<Int>, var loop: Boolean) {

    sealed interface Outcome {
        /** Same entry, next bar. */
        data object Stay : Outcome
        /** This 1 starts entry [index] — switch the beat here. */
        data class Switch(val index: Int) : Outcome
        /** The last entry is done and loop is off: stop on this 1, unplayed. */
        data object End : Outcome
    }

    var index = 0
        private set
    /** 0-based bar within the current entry. */
    var bar = 0
        private set
    private var started = false

    fun reset() {
        index = 0; bar = 0; started = false
    }

    fun onDownbeat(): Outcome {
        if (bars.isEmpty()) return Outcome.End
        if (!started) {
            started = true
            return Outcome.Stay
        }
        bar++
        if (bar < bars[index].coerceAtLeast(1)) return Outcome.Stay
        bar = 0
        val next = index + 1
        return when {
            next < bars.size -> { index = next; Outcome.Switch(next) }
            loop -> { index = 0; Outcome.Switch(0) }
            else -> Outcome.End
        }
    }

    /**
     * Carry the position into an edited sequence (bars changed, entries added or
     * removed mid-play): same entry and bar where they still exist, else the top.
     */
    fun adopt(from: SequenceCursor) {
        started = from.started
        if (from.index < bars.size) {
            index = from.index
            bar = from.bar.coerceAtMost(bars[index].coerceAtLeast(1) - 1)
        }
    }
}
