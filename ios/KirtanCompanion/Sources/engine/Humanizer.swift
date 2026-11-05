import Foundation

// How much "human error" the drum plays with. One control rather than a page of
// milliseconds: each level scales every kind of variation below together.
// The raw values are what EqPrefs stores, and match the Android port's names.
enum Feel: String, CaseIterable {
    case off = "OFF"
    case tight = "TIGHT"
    case human = "HUMAN"
    case loose = "LOOSE"

    var amount: Double {
        switch self {
        case .off: return 0
        case .tight: return 0.4
        case .human: return 0.75
        case .loose: return 1
        }
    }

    var label: String {
        switch self {
        case .off: return "Off"
        case .tight: return "Tight"
        case .human: return "Human"
        case .loose: return "Loose"
        }
    }
}

// Small, deliberate imperfections that stop the loop sounding like a machine.
// Ported from the Android port's engine/Humanizer.kt; keep the numbers in step.
//
// EXPERIMENT. The constants are first guesses, tuned by ear on Android. Three
// things vary, all scaled by Feel.amount:
//
//  1. LOUDNESS. The downbeat is strongest, other beat starts a little softer,
//     the cells between softer again, plus a random ±dB on every stroke.
//  2. TIMING DRIFT. Not fresh noise per stroke — that sounds sloppy — but a slow
//     random walk pulled back towards the grid, so the feel wanders the way a
//     player's does, plus a little per-stroke jitter. The downbeat drifts less,
//     so the "1" stays solid for people singing.
//  3. TWO HANDS. The bayan lands a few ms after the dayan, as on a real
//     two-headed drum, rather than both samples starting on the same frame.
//
// EARLY HITS. A stroke can't sound before the block it's placed in, so with the
// feel on every stroke carries a fixed PAD_MS delay and the variation is applied
// around that. Voice supports a start offset longer than one block for this.
//
// REPEATABLE. The random stream is reseeded from the beat on every start (with a
// stable hash — Swift's String.hashValue changes per launch), so a beat has the
// same feel each time it plays.
//
// Audio thread only, except `feel` and `setBeat`, which swap whole values.
final class Humanizer {

    static let DOWNBEAT_LEVEL = 1.0
    static let BEAT_LEVEL = 0.82
    static let OFFBEAT_LEVEL = 0.65
    static let GAIN_JITTER_DB = 2.5

    static let PAD_MS = 18.0
    static let MAX_LATE_MS = 40.0
    static let DRIFT_PULL = 0.9
    static let DRIFT_STEP_MS = 3.0
    static let DRIFT_MAX_MS = 15.0
    static let DOWNBEAT_DRIFT_SCALE = 0.4
    static let JITTER_MS = 3.0
    static let BAYAN_LAG_MS = 14.0
    static let BAYAN_LAG_JITTER_MS = 3.0

    private let sampleRate: Int
    var feel: Feel = .off

    private var accents: [Double] = []
    private var seed: UInt64 = 0
    private var rng = SplitMix64(seed: 0)
    private var drift = 0.0

    init(sampleRate: Int) { self.sampleRate = sampleRate }

    func setBeat(_ beat: Beat) {
        var levels = [Double](repeating: Humanizer.OFFBEAT_LEVEL, count: max(1, beat.steps))
        var start = 0
        for group in groupsFor(beat) {
            if start >= 0 && start < levels.count { levels[start] = Humanizer.BEAT_LEVEL }
            start += group
        }
        levels[0] = Humanizer.DOWNBEAT_LEVEL
        accents = levels
        seed = fnv1a(beat.id ?? beat.name)
    }

    /// Back to the start of the beat's random stream. Audio thread, on start.
    func reset() {
        rng = SplitMix64(seed: seed)
        drift = 0
    }

    func padFrames() -> Int { feel.amount > 0 ? msToFrames(Humanizer.PAD_MS) : 0 }

    /// Advance the timing walk by one step. Once per step, before its strokes.
    func advance() {
        guard feel.amount > 0 else { return }
        drift = clamp(drift * Humanizer.DRIFT_PULL + rng.gaussian() * Humanizer.DRIFT_STEP_MS,
                      -Humanizer.DRIFT_MAX_MS, Humanizer.DRIFT_MAX_MS)
    }

    /// Frames to delay this stroke from its grid position, pad included. Never negative.
    func delayFrames(_ lane: LaneId, step: Int) -> Int {
        let amount = feel.amount
        guard amount > 0 else { return 0 }
        var ms = drift * (step == 0 ? Humanizer.DOWNBEAT_DRIFT_SCALE : 1) + rng.gaussian() * Humanizer.JITTER_MS
        if lane == .bayan { ms += Humanizer.BAYAN_LAG_MS + rng.gaussian() * Humanizer.BAYAN_LAG_JITTER_MS }
        ms = clamp(ms * amount, -Humanizer.PAD_MS, Humanizer.MAX_LATE_MS)
        return msToFrames(Humanizer.PAD_MS + ms)
    }

    /// Gain for this stroke, 1 meaning unchanged.
    func gain(step: Int) -> Float {
        let amount = feel.amount
        guard amount > 0 else { return 1 }
        let levels = accents
        let accent = step >= 0 && step < levels.count ? levels[step] : Humanizer.OFFBEAT_LEVEL
        let shaped = 1 - amount * (1 - accent)
        let j = Humanizer.GAIN_JITTER_DB
        let db = clamp(rng.gaussian() * j * amount, -2 * j, 2 * j)
        return Float(shaped * pow(10, db / 20))
    }

    private func msToFrames(_ ms: Double) -> Int {
        max(0, Int((ms * Double(sampleRate) / 1000).rounded()))
    }

    private func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(max(v, lo), hi) }

    private func fnv1a(_ s: String) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return h
    }
}

/// A tiny seedable generator with a Gaussian draw. Allocation-free, so it is
/// safe on the audio thread.
struct SplitMix64 {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }

    /// Uniform in (0, 1].
    mutating func unit() -> Double { (Double(next() >> 11) + 1) / Double(1 << 53) }

    /// Standard normal, by Box–Muller.
    mutating func gaussian() -> Double {
        sqrt(-2 * log(unit())) * cos(2 * Double.pi * unit())
    }
}
