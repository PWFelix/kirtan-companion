// The spoken names of the strokes — the drum's language.
//
// Ported from src/data/bols.js. Maps lane → stroke → bol syllable. The strip shows
// these when the user turns bols on in Settings, and the editor's pads are labelled
// from here (including the two-hand combos).
//
// Names follow the stroke legend in Sita-pati das, "The Art and Science of Harinam
// Sankirtan Yajna" (p. 43):
//   Ta = top open, Te = top closed, Ge = bottom open, Khe = bottom closed,
//   Da = bottom open + top open (Dha when struck strong together),
//   Gi = bottom open + top closed.
// Combinations the book leaves unnamed are labelled by their parts.
enum Bols {

    private static let PER_LANE: [String: [String: String]] = [
        "dayan":  ["O": "Ta", "X": "Te"],
        "bayan":  ["O": "Ge", "X": "Khe"],
        // Karatalas aren't in the book's mridanga legend, so these are descriptive
        // onomatopoeia rather than transcribed bols: "Ching" the open ring of the two
        // discs, "Chip" the damped/choked strike.
        "kartal": ["O": "Ching", "X": "Chip"],
    ]

    /// Two-hand combinations (dayan+bayan sounding together) get their own names, keyed
    /// "dayanValue+bayanValue". Used by the editor's combo pads.
    private static let COMBOS: [String: String] = [
        "O+O": "Da",       // book also lists "Dha" for a strong combined strike
        "X+O": "Gi",
        "O+X": "Ta·Khe",   // not named in the book — labelled by its parts
        "X+X": "Te·Khe",   // not named in the book — labelled by its parts
    ]

    /// The bol for one lane's stroke, or nil for a rest / unknown lane.
    static func forStroke(laneId: String, code: String?) -> String? {
        guard let code else { return nil }
        return PER_LANE[laneId]?[code]
    }

    /// The bol for a two-hand combination, e.g. combo("O", "X") → "Ta·Khe".
    static func combo(dayanCode: String?, bayanCode: String?) -> String? {
        guard let dayanCode, let bayanCode else { return nil }
        return COMBOS["\(dayanCode)+\(bayanCode)"]
    }
}
