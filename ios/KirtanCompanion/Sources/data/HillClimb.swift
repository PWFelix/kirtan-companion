import Foundation

/// Hill climb: Learn's slow, automatic speed-up. "Go up N BPM every X minutes" while
/// the beat plays, so a learner starts where they're comfortable and is walked up
/// towards kirtan speed without touching the controls.
///
/// Pure rules only — the choices offered, the defaults, and what one climb step does.
/// The countdown lives in LearnView (it only runs while playing), and the last choice
/// is remembered in EqPrefs. Same numbers as Android's HillClimb.kt and the web's
/// data/hillClimb.js.
enum HillClimb {
    /// BPM added per step: steps a learner can feel, from a nudge to a real climb.
    static let steps = [5, 10, 15, 20, 25]
    /// Seconds between steps.
    static let intervalsSec = [30, 60, 120, 180, 300, 600]
    static let defaultStep = 5
    static let defaultIntervalSec = 120

    /// One step up, never past `ceiling` (the top of the slider's range, 200 or 500).
    static func next(_ bpm: Int, step: Int, ceiling: Int) -> Int { max(bpm, min(bpm + step, ceiling)) }

    /// True when another step can't go any higher.
    /// SNAP TO BAR. A step doesn't land mid-phrase: the new tempo starts on the 1
    /// nearest the moment the countdown runs out — up to half a bar before or after.
    /// So it is handed to the engine this many whole seconds BEFORE the deadline (half
    /// a bar rounded up, plus one for the countdown's own granularity), early enough
    /// that the nearest 1 is still ahead. The engine picks that 1.
    static func leadSeconds(bpm: Int, beatsPerBar: Double) -> Int {
        Int((60.0 / Double(max(1, bpm)) * beatsPerBar / 2).rounded(.up)) + 1
    }

    static func atTop(_ bpm: Int, ceiling: Int) -> Bool { bpm >= ceiling }

    /// A remembered step snapped to an offered choice (prefs may hold anything).
    static func step(of raw: Int?) -> Int { raw.flatMap { steps.contains($0) ? $0 : nil } ?? defaultStep }

    /// A remembered interval snapped to an offered choice.
    static func interval(of raw: Int?) -> Int { raw.flatMap { intervalsSec.contains($0) ? $0 : nil } ?? defaultIntervalSec }

    /// "0:30", "2:00" — the countdown to the next step.
    static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
