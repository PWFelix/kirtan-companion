package com.kirtan.companion.data

/**
 * The pure equaliser facts — no UI, no audio, no storage behind them.
 *
 * Ported from `src/data/eq.js`. WHY THIS FILE EXISTS carries over unchanged:
 * the per-end EQ contract was once hard-coded in three places at once — the
 * engine's filter table, the mixer's slider labels, and persistence's band
 * count — so retuning a band meant editing all three in lockstep and missing
 * one silently desynced the UI from the DSP chain. Everything that needs to
 * know which bands exist, what they're called, or how far a gain may swing
 * reads it here, so the contract stays mechanically consistent.
 *
 * The design is FROZEN: five cascaded bands per mridanga end — low shelf,
 * three peaking, high shelf — covering the drums' body. Band order here is band
 * order everywhere: the engine's filter chains, the mixer's sliders and the
 * persisted bands arrays all index this table.
 */

/** Biquad shape, matching the Web Audio `BiquadFilterNode` types. */
enum class FilterType { LOW_SHELF, PEAKING, HIGH_SHELF }

/**
 * One EQ band. [q] applies to peaking filters only and is null for the shelves,
 * exactly as in the JS table.
 */
data class EqBand(
    val type: FilterType,
    val frequency: Double,
    val q: Double?,
    val label: String,
)

/**
 * Per-band gain bounds in dB — a symmetric cut/boost range. The mixer sliders,
 * the transport's clamp, persistence's load-time sanitise and the engine's ramp
 * all enforce this one range.
 */
const val EQ_MIN_DB = -12.0
const val EQ_MAX_DB = 12.0

/** The fixed five-filter chain, in cascade order. */
val EQ_BANDS: List<EqBand> = listOf(
    EqBand(FilterType.LOW_SHELF, 100.0, null, "100 Hz"),
    EqBand(FilterType.PEAKING, 300.0, 1.0, "300 Hz"),
    EqBand(FilterType.PEAKING, 1000.0, 1.0, "1 kHz"),
    EqBand(FilterType.PEAKING, 3000.0, 1.0, "3 kHz"),
    EqBand(FilterType.HIGH_SHELF, 8000.0, null, "8 kHz"),
)

const val EQ_BAND_COUNT: Int = 5

/** Clamp a dB value into the shared range. */
fun clampEqDb(db: Double): Double = db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)

/** A flat (0 dB) chain, the default for both mridanga ends. */
fun flatEqBands(): DoubleArray = DoubleArray(EQ_BAND_COUNT)
