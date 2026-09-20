package com.kirtan.companion.data

/**
 * The spoken names of the strokes — the drum's language.
 *
 * Ported from `src/data/bols.js`. Maps lane → stroke → bol syllable. The strip
 * shows these when the user turns bols on in Settings, and the editor's pads are
 * labelled from here (including the two-hand combos).
 *
 * Names follow the stroke legend in Sita-pati das, "The Art and Science of
 * Harinam Sankirtan Yajna" (p. 43):
 *   Ta = top open, Te = top closed, Ge = bottom open, Khe = bottom closed,
 *   Da = bottom open + top open (Dha when struck strong together),
 *   Gi = bottom open + top closed.
 * Combinations the book leaves unnamed are labelled by their parts.
 */
object Bols {

    private val PER_LANE: Map<String, Map<String, String>> = mapOf(
        "dayan" to mapOf("O" to "Ta", "X" to "Te"),
        "bayan" to mapOf("O" to "Ge", "X" to "Khe"),
        // Karatalas aren't in the book's mridanga legend, so these are
        // descriptive onomatopoeia rather than transcribed bols: "Ching" the
        // open ring of the two discs, "Chip" the damped/choked strike.
        "kartal" to mapOf("O" to "Ching", "X" to "Chip"),
    )

    /**
     * Two-hand combinations (dayan+bayan sounding together) get their own names,
     * keyed "dayanValue+bayanValue". Used by the editor's combo pads.
     */
    private val COMBOS: Map<String, String> = mapOf(
        "O+O" to "Da",        // book also lists "Dha" for a strong combined strike
        "X+O" to "Gi",
        "O+X" to "Ta·Khe",    // not named in the book — labelled by its parts
        "X+X" to "Te·Khe",    // not named in the book — labelled by its parts
    )

    /** The bol for one lane's stroke, or null for a rest / unknown lane. */
    fun forStroke(laneId: String, code: String?): String? =
        if (code == null) null else PER_LANE[laneId]?.get(code)

    /** The bol for a two-hand combination, e.g. `combo("O", "X")` → "Ta·Khe". */
    fun combo(dayanCode: String?, bayanCode: String?): String? {
        if (dayanCode == null || bayanCode == null) return null
        return COMBOS["$dayanCode+$bayanCode"]
    }
}
