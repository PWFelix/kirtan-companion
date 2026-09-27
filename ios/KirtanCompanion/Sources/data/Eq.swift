// The pure equaliser facts — no UI, no audio, no storage behind them.
//
// Ported from src/data/eq.js. WHY THIS FILE EXISTS: the per-end EQ contract was once
// hard-coded in three places at once — the engine's filter table, the mixer's slider
// labels, and persistence's band count — so retuning a band meant editing all three in
// lockstep and missing one silently desynced the UI from the DSP chain. Everything
// that needs to know which bands exist, what they're called, or how far a gain may
// swing reads here, so the contract stays mechanically consistent.
//
// FROZEN design: five cascaded bands per mridanga end — low shelf, three peaking,
// high shelf — covering the drums' body. Band order here is band order everywhere.

/// Biquad shape, matching the Web Audio BiquadFilterNode types.
enum FilterType { case lowShelf, peaking, highShelf }

/// One EQ band. `q` applies to peaking filters only and is nil for the shelves.
struct EqBand {
    var type: FilterType
    var frequency: Double
    var q: Double?
    var label: String
}

/// Per-band gain bounds in dB — a symmetric cut/boost range. The mixer sliders, the
/// transport's clamp, persistence's sanitise and the engine's ramp all enforce this.
let EQ_MIN_DB = -12.0
let EQ_MAX_DB = 12.0

/// The fixed five-filter chain, in cascade order.
let EQ_BANDS: [EqBand] = [
    EqBand(type: .lowShelf, frequency: 100.0,  q: nil,   label: "100 Hz"),
    EqBand(type: .peaking,  frequency: 300.0,  q: 1.0,   label: "300 Hz"),
    EqBand(type: .peaking,  frequency: 1000.0, q: 1.0,   label: "1 kHz"),
    EqBand(type: .peaking,  frequency: 3000.0, q: 1.0,   label: "3 kHz"),
    EqBand(type: .highShelf, frequency: 8000.0, q: nil,   label: "8 kHz"),
]

let EQ_BAND_COUNT = 5

/// Clamp a dB value into the shared range.
func clampEqDb(_ db: Double) -> Double { min(max(db, EQ_MIN_DB), EQ_MAX_DB) }

/// A flat (0 dB) chain, the default for both mridanga ends.
func flatEqBands() -> [Double] { Array(repeating: 0, count: EQ_BAND_COUNT) }
