import Foundation

// How much "human error" the drum plays with. One control rather than a page of
// milliseconds: each level scales every kind of variation below together.
// The raw values are what EqPrefs stores, and match the Android port's names.
enum Feel: String, CaseIterable {
    case off = "OFF"
    case tight = "TIGHT"
    case human = "HUMAN"
    case loose = "LOOSE"
    /// Laid back, the Bengali way: Human's variation plus the whole drum sitting
    /// consistently behind the beat — most between beats, least on sam.
    case drag = "DRAG"

    var amount: Double {
        switch self {
        case .off: return 0
        case .tight: return 0.4
        case .human: return 0.75
        case .loose: return 1
        case .drag: return 0.75
        }
    }

    var label: String {
        switch self {
        case .off: return String(localized: "Off", bundle: .strings)
        case .tight: return String(localized: "Tight", bundle: .strings)
        case .human: return String(localized: "Human", bundle: .strings)
        case .loose: return String(localized: "Loose", bundle: .strings)
        case .drag: return String(localized: "Drag", bundle: .strings)
        }
    }

    /// Constant lateness, in ms, before the per-step shape.
    var dragMs: Double { self == .drag ? 30 : 0 }
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
    static let OFFBEAT_LEVEL = 0.75
    static let GAIN_JITTER_DB = 1.5
    /// The quietest any stroke may be (-4.4 dB): a feel shades a stroke, never loses it.
    static let MIN_GAIN: Float = 0.6

    static let PAD_MS = 18.0
    static let MAX_LATE_MS = 40.0
    static let DRIFT_PULL = 0.9
    static let DRIFT_STEP_MS = 3.0
    static let DRIFT_MAX_MS = 15.0
    static let DOWNBEAT_DRIFT_SCALE = 0.4
    static let JITTER_MS = 3.0
    static let BAYAN_LAG_MS = 14.0
    static let BAYAN_LAG_JITTER_MS = 3.0
    static let DRAG_SAM_SCALE = 0.3
    static let DRAG_BEAT_SCALE = 0.7
    static let DRAG_RAMP_STEPS = 0.45
    static let DRAG_RAMP_CURVE = 1.6
    static let DRAG_RAMP_MAX_MS = 200.0
    static let FLAM_MS = 28.0
    static let FLAM_SAM_MS = 50.0
    static let DRAG_SAM_STEPS = 0.15
    static let DRAG_SAM_MAX_MS = 80.0

    private let sampleRate: Int
    var feel: Feel = .off

    private var accents: [Double] = []
    private var dragShape: [Double] = []
    /// Steps where Ge and Ta (bayan open + dayan open) fall together: Drag flams them.
    private var flamSteps: [Bool] = []
    private var seed: UInt64 = 0
    private var rng = SplitMix64(seed: 0)
    private var drift = 0.0
    /// The step where the drag stops growing and eases back to sam: the last beat.
    private var releaseStep = 0

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
        let dayan = beat.pattern(.dayan), bayan = beat.pattern(.bayan)
        flamSteps = (0..<levels.count).map { i in
            (dayan.map { i < $0.count ? $0[i] : nil } ?? nil) == .open &&
                (bayan.map { i < $0.count ? $0[i] : nil } ?? nil) == .open
        }
        dragShape = levels.map {
            $0 == Humanizer.DOWNBEAT_LEVEL ? Humanizer.DRAG_SAM_SCALE
                : $0 == Humanizer.BEAT_LEVEL ? Humanizer.DRAG_BEAT_SCALE : 1
        }
        releaseStep = Humanizer.releaseStep(groups: groupsFor(beat), steps: levels.count)
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
    func delayFrames(_ lane: LaneId, step: Int, stepMs: Double = 0) -> Int {
        let amount = feel.amount
        guard amount > 0 else { return 0 }
        var ms = drift * (step == 0 ? Humanizer.DOWNBEAT_DRIFT_SCALE : 1) + rng.gaussian() * Humanizer.JITTER_MS
        let flam = feel == .drag && step >= 0 && step < flamSteps.count && flamSteps[step]
        // On a flam the bayan LEADS, so it loses its usual lag behind the dayan.
        if lane == .bayan && !flam { ms += Humanizer.BAYAN_LAG_MS + rng.gaussian() * Humanizer.BAYAN_LAG_JITTER_MS }
        ms = clamp(ms * amount, -Humanizer.PAD_MS, Humanizer.MAX_LATE_MS)
        let shape = dragShape
        ms += feel.dragMs * (step >= 0 && step < shape.count ? shape[step] : 1)
        // Ge+Ta in Drag: a flam, the bottom end first and the Ta a grace later.
        // Sam's is wider: the "1" is announced, not just struck.
        if flam && lane == .dayan { ms += step == 0 ? Humanizer.FLAM_SAM_MS : Humanizer.FLAM_MS }
        // Drag's progressive part: later and later through the bar, easing back
        // over the last beat to sam's own small lateness.
        if feel.dragMs > 0 && !shape.isEmpty {
            ms += rampMs(step: step, steps: shape.count, stepMs: stepMs, release: releaseStep)
            // Sam too sits back: the ramp resets there, but not onto the grid.
            if step == 0 { ms += Humanizer.samDragMs(stepMs) }
        }
        return msToFrames(Humanizer.PAD_MS + ms)
    }

    /// Gain for this stroke, 1 meaning unchanged, bounded to [MIN_GAIN, 1].
    /// Unbounded, Loose put ~10 dB between neighbours and off-beat Ta strokes
    /// vanished at slow tempi; accents now come only from lowering the rest.
    func gain(step: Int) -> Float {
        let amount = feel.amount
        guard amount > 0 else { return 1 }
        let levels = accents
        let accent = step >= 0 && step < levels.count ? levels[step] : Humanizer.OFFBEAT_LEVEL
        let shaped = 1 - amount * (1 - accent)
        let j = Humanizer.GAIN_JITTER_DB
        let db = clamp(rng.gaussian() * j * amount, -2 * j, 2 * j)
        return min(1, max(Humanizer.MIN_GAIN, Float(shaped * pow(10, db / 20))))
    }

    /// 0 on sam, growing until `release` (the last beat), then easing linearly down to
    /// what sam carries, so the loop point is just one more of those easing gaps. Snapping
    /// straight back made the gap into sam a quarter short — a user heard the loop "jump".
    /// In steps, so it feels the same at any tempo.
    func rampMs(step: Int, steps: Int, stepMs: Double, release: Int? = nil) -> Double {
        let n = max(1, steps)
        let release = release ?? Humanizer.releaseStep(groups: [], steps: n)
        func grow(_ at: Int) -> Double {
            min(pow(Double(at) / Double(n), Humanizer.DRAG_RAMP_CURVE) * Humanizer.DRAG_RAMP_STEPS * stepMs,
                Humanizer.DRAG_RAMP_MAX_MS)
        }
        if step == 0 || release < 1 || release >= n || step <= release { return grow(step) }
        let peak = grow(release)
        let t = Double(step - release) / Double(n - release)
        return peak + (Humanizer.samDragMs(stepMs) - peak) * t
    }

    static func samDragMs(_ stepMs: Double) -> Double {
        min(DRAG_SAM_STEPS * stepMs, DRAG_SAM_MAX_MS)
    }

    /// Where the drag starts easing back: the last beat's first step, or the last
    /// quarter of the bar when the meter is one long group.
    static func releaseStep(groups: [Int], steps: Int) -> Int {
        var start = 0, last = 0
        for g in groups { if start < steps { last = start }; start += g }
        return last >= 1 && last < steps ? last : steps * 3 / 4
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
