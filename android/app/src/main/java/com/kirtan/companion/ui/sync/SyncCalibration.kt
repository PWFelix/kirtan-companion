package com.kirtan.companion.ui.sync

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the sync test's taps into a display delay.
 *
 * Each tap arrives as its offset from the nearest click in ms, measured against
 * what the phone SAYS is being heard (see
 * [com.kirtan.companion.engine.KirtanEngine.offsetFromNearestStepMs]). A
 * listener taps on the sound, so if the phone's figure were complete the
 * offsets would sit around zero; their typical value is the delay it left out.
 *
 * MEDIAN, NOT MEAN. People tap with the odd early or late slip, and one slip of
 * 300 ms would drag a mean by 25 ms over twelve taps. The median ignores it.
 * The spread is the median absolute deviation for the same reason, and taps
 * spread wider than [MAX_SPREAD_MS] are rejected as "try again" rather than
 * producing a confident wrong answer.
 *
 * The first [WARM_UP] taps are dropped: everyone needs a couple of beats to lock
 * on.
 */
internal object SyncCalibration {

    const val WARM_UP = 2
    const val TAPS_NEEDED = 10
    const val MAX_SPREAD_MS = 45.0

    /** Delays are rounded to this, so the result reads as a setting, not a measurement. */
    const val ROUND_TO_MS = 5

    sealed interface Result {
        data class Delay(val ms: Int) : Result
        data object TooUneven : Result
    }

    /** True once enough taps have been collected to [estimate]. */
    fun complete(offsetsMs: List<Double>): Boolean = offsetsMs.size >= WARM_UP + TAPS_NEEDED

    /** The delay to set, or [Result.TooUneven]. Null until [complete]. */
    fun estimate(offsetsMs: List<Double>): Result? {
        if (!complete(offsetsMs)) return null
        val taps = offsetsMs.drop(WARM_UP)
        val median = median(taps)
        val spread = median(taps.map { abs(it - median) })
        if (spread > MAX_SPREAD_MS) return Result.TooUneven
        val rounded = (median / ROUND_TO_MS).roundToInt() * ROUND_TO_MS
        return Result.Delay(rounded)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
