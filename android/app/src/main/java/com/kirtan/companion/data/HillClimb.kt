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
    /** BPM added per step. Small steps: a learner should barely notice each one. */
    val STEPS: List<Int> = listOf(1, 2, 3, 5, 10)

    /** Seconds between steps. */
    val INTERVALS_SEC: List<Int> = listOf(30, 60, 120, 180, 300, 600)

    const val DEFAULT_STEP = 5
    const val DEFAULT_INTERVAL_SEC = 120

    /** One step up, never past [ceiling] (the top of the slider's range, 200 or 500). */
    fun next(bpm: Int, step: Int, ceiling: Int): Int = maxOf(bpm, minOf(bpm + step, ceiling))

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
