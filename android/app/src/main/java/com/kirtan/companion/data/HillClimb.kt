package com.kirtan.companion.data

/**
 * Hill climb: Learn's slow, automatic speed-up. "Go up N BPM every X minutes"
 * while the beat plays, so a learner starts where they're comfortable and is
 * walked up towards kirtan speed without touching the controls.
 *
 * Pure rules only — the choices offered, the defaults, and what one climb step
 * does. The countdown lives with the Learn screen (it only runs while playing),
 * and the last choice is remembered in EqPrefs. Same numbers on web and iOS
 * (data/hillClimb.js, data/HillClimb.swift).
 */
object HillClimb {
    /** BPM added per step: steps a learner can feel, from a nudge to a real climb. */
    val STEPS: List<Int> = listOf(5, 10, 15, 20, 25)

    /** Seconds between steps. */
    val INTERVALS_SEC: List<Int> = listOf(30, 60, 120, 180, 300, 600)

    const val DEFAULT_STEP = 5
    const val DEFAULT_INTERVAL_SEC = 120

    /** One step up, never past [ceiling] (the top of the slider's range, 200 or 500). */
    fun next(bpm: Int, step: Int, ceiling: Int): Int = maxOf(bpm, minOf(bpm + step, ceiling))

    /**
     * SNAP TO BAR. A step doesn't land mid-phrase: the new tempo starts on the 1
     * nearest the moment the countdown runs out — up to half a bar before it or
     * after it. So the change is handed to the engine this many whole seconds
     * BEFORE the deadline (half a bar, rounded up, plus a second for the
     * countdown's own granularity), early enough that the nearest 1 is still
     * ahead. The engine picks that 1 and applies the tempo exactly on it.
     */
    fun leadSeconds(bpm: Int, beatsPerBar: Double): Int {
        val barSeconds = 60.0 / bpm.coerceAtLeast(1) * beatsPerBar
        return kotlin.math.ceil(barSeconds / 2).toInt() + 1
    }

    /** True when another step can't go any higher. */
    fun atTop(bpm: Int, ceiling: Int): Boolean = bpm >= ceiling

    /** A remembered step snapped to an offered choice (prefs may hold anything). */
    fun stepOf(raw: Int?): Int = raw?.takeIf { it in STEPS } ?: DEFAULT_STEP

    /** A remembered interval snapped to an offered choice. */
    fun intervalOf(raw: Int?): Int = raw?.takeIf { it in INTERVALS_SEC } ?: DEFAULT_INTERVAL_SEC

    /** "0:30", "2:00" — the countdown to the next step. */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
