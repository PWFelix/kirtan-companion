// One instrument's channel: its EQ chain, then its fader.
//
// The signal path is the one drawn in the header of SoundPlayer.js, and the two
// decisions it records are both audible:
//
//   dayan/bayan:  voices → EQ chain → end gain ─┐
//   kartal:       voices ───────────→ end gain ─┼→ master gain
//
//  1. THE EQ SITS PRE-FADER — between the voices and the end gain — so tone
//     shaping never disturbs volume, mute, or the bayan makeup gain, which are
//     all applied on the end gain. Put the EQ after the fader and moving a
//     slider would change how loud the drum is.
//  2. THE KARATALAS CARRY NO EQ. They're a separate instrument, not a head of
//     the mridanga, so `eq` is nil for them and setEqBand is a no-op — the mixer
//     offers EQ controls for two ends and simply omits them for the third.
//
// An end's FINAL gain is `volume × makeup × (muted ? 0 : 1)`. Volume and mute
// stay separate facts so unmuting restores the user's volume instead of ramping
// back to 1 — collapsing them is the bug that makes a muted-then-unmuted lane
// come back at the wrong level.
//
// MAKEUP: phone speakers reproduce the bayan's bass far more weakly than the
// dayan's ring, so the bayan carries a fixed 1.25. The mixer's "100%" therefore
// means "balanced on a phone", not "raw sample level". Karatalas are bright and
// cut through, so they sit at 1.
//
// Volume/mute are published from the UI thread through the ramp's target; the
// DSP reads them on the audio thread. Nothing here is touched from both threads
// except the ramp, which locks its control plane.

final class EndChannel {

    static let makeup: [LaneId: Float] = [
        .dayan: 1.0, .bayan: 1.25, .kartal: 1.0,
    ]

    private let RAMP_SECONDS = 0.05

    let lane: LaneId
    let makeup: Float

    /// The five cascaded bands, or nil for a lane that carries no EQ.
    private var eq: [BiquadFilter]?

    let hasEq: Bool

    private let ramp: GainRamp

    init(_ lane: LaneId, sampleRate: Double) {
        self.lane = lane
        self.makeup = EndChannel.makeup[lane] ?? 1.0
        self.ramp = GainRamp(sampleRate: sampleRate, seconds: RAMP_SECONDS, initial: makeup)
        if lane == .kartal {
            self.eq = nil
            self.hasEq = false
        } else {
            var chain = [BiquadFilter]()
            for i in 0..<EQ_BAND_COUNT {
                let filter = BiquadFilter()
                filter.configure(EQ_BANDS[i], sampleRate: sampleRate)
                chain.append(filter)
            }
            self.eq = chain
            self.hasEq = true
        }
    }

    /// The gain in force right now — mid-ramp values included.
    var gain: Float { ramp.gain }

    private var userVolume: Float = 1.0
    private var muted = false

    func setVolume(_ value: Float) {
        userVolume = min(max(value, 0), 1)
        publishTarget()
    }

    func setMuted(_ value: Bool) {
        muted = value
        publishTarget()
    }

    func isMuted() -> Bool { muted }
    func volume() -> Float { userVolume }

    /// One band's gain, clamped to the shared range. An out-of-range band index
    /// is ignored rather than throwing, matching setEqBand's "unknown end or band
    /// is ignored safely" contract.
    func setEqBand(_ bandIndex: Int, _ db: Double) {
        guard let chain = eq else { return }
        guard bandIndex >= 0, bandIndex < chain.count else { return }
        guard db.isFinite else { return }
        chain[bandIndex].setGain(clampEqDb(db))
    }

    func eqBandDb(_ bandIndex: Int) -> Double? {
        guard let chain = eq, bandIndex >= 0, bandIndex < chain.count else { return nil }
        return chain[bandIndex].gainDb
    }

    /// Restore a whole chain — used when a saved preset is applied.
    func setEqBands(_ db: [Double]) {
        for i in 0..<db.count { setEqBand(i, db[i]) }
    }

    /// Fold volume, mute and makeup into the ramp's target. The three stay
    /// separate fields and combine only here.
    private func publishTarget() {
        ramp.setTarget(muted ? 0 : userVolume * makeup)
        ramp.retarget()
    }

    /// Filter and fade one block in place: EQ first, then the fader (pre-fader
    /// tone shaping, per decision 1).
    func process(_ buffer: inout [Float], count: Int) {
        if let chain = eq {
            for filter in chain { filter.process(&buffer, offset: 0, count: count) }
        }
        ramp.applyTo(&buffer, count: count)
    }
}
