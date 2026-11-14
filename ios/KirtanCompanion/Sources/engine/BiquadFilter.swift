import Foundation

// A direct-form-I biquad, coefficient-compatible with the Web Audio
// BiquadFilterNode that Tone.Filter wraps.
//
// WHY BIT-EXACT COEFFICIENTS MATTER: the five-band chain in EQ_BANDS was voiced
// by ear against the web app's filters, and the same dB value must mean the same
// curve on both platforms — a user's saved EQ preset is shared through the cloud
// and must not sound different on iOS. So these follow the RBJ Audio EQ Cookbook
// exactly as the Web Audio spec implements it.
//
// The one place the spec differs from the raw cookbook, and it is easy to get
// wrong: Q is IGNORED for shelf filters. The spec fixes the shelf slope at S = 1,
// which collapses (A + 1/A)(1/S - 1) + 2 to 2, so alpha = sin(w0)/2 * sqrt(2).
// That is why EqBand.q is null for the two shelves.
//
// State is two delay registers per instance, so a chain of five costs ten
// doubles. Coefficients are written on the UI thread (setGain) and read on the
// audio thread (process); the small lock keeps a consistent set across a block.
// The delay registers are audio-thread only and never locked.

final class BiquadFilter {

    // Normalised coefficients: a0 is divided out at build time, so the recurrence
    // needs no per-sample division. Guarded because setGain runs on the UI thread.
    private let coefLock = NSLock()
    private var b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0

    // Delay registers (Direct Form I) — audio thread only.
    private var x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0

    private var sampleRate = 0.0
    private var band: EqBand?

    /// Currently applied gain in dB. Unity (0 dB) makes the filter a wire.
    private(set) var gainDb = 0.0

    /// Point the filter at a band and a sample rate. Coefficients depend on both,
    /// so call before the first process and again if either changes.
    func configure(_ band: EqBand, sampleRate: Double) {
        coefLock.lock()
        self.band = band
        self.sampleRate = sampleRate
        recomputeLocked(gainDb)
        coefLock.unlock()
    }

    /// Set the gain and rebuild the coefficients. The web app ramps the resulting
    /// gain over 50 ms in EndChannel, not here: a coefficient jump at block
    /// granularity is inaudible where a gain jump would click.
    func setGain(_ db: Double) {
        coefLock.lock()
        if db != gainDb {
            gainDb = db
            recomputeLocked(db)
        }
        coefLock.unlock()
    }

    /// Drop the delay registers — e.g. when the chain is re-voiced.
    func reset() {
        x1 = 0; x2 = 0; y1 = 0; y2 = 0
    }

    private func recomputeLocked(_ db: Double) {
        guard let b = band, sampleRate > 0 else { return }

        // A = 10^(dB/40): the cookbook's A is an *amplitude* ratio whose square is
        // the power ratio the dB names.
        let a = pow(10.0, db / 40.0)
        let w0 = 2.0 * Double.pi * b.frequency / sampleRate
        let cosW0 = cos(w0)
        let sinW0 = sin(w0)

        switch b.type {
        case .peaking:
            let q = b.q ?? 1.0
            let alpha = sinW0 / (2.0 * q)
            let a0 = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / a0
            b1 = (-2.0 * cosW0) / a0
            b2 = (1.0 - alpha * a) / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha / a) / a0
        case .lowShelf, .highShelf:
            // Shelf slope fixed at S = 1 by the spec — Q is not used.
            let alpha = sinW0 / 2.0 * sqrt(2.0)
            let twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            let aPlusOne = a + 1.0
            let aMinusOne = a - 1.0

            if b.type == .lowShelf {
                let a0 = aPlusOne + aMinusOne * cosW0 + twoSqrtAAlpha
                b0 = a * (aPlusOne - aMinusOne * cosW0 + twoSqrtAAlpha) / a0
                b1 = 2.0 * a * (aMinusOne - aPlusOne * cosW0) / a0
                b2 = a * (aPlusOne - aMinusOne * cosW0 - twoSqrtAAlpha) / a0
                a1 = -2.0 * (aMinusOne + aPlusOne * cosW0) / a0
                a2 = (aPlusOne + aMinusOne * cosW0 - twoSqrtAAlpha) / a0
            } else {
                let a0 = aPlusOne - aMinusOne * cosW0 + twoSqrtAAlpha
                b0 = a * (aPlusOne + aMinusOne * cosW0 + twoSqrtAAlpha) / a0
                b1 = -2.0 * a * (aMinusOne + aPlusOne * cosW0) / a0
                b2 = a * (aPlusOne + aMinusOne * cosW0 - twoSqrtAAlpha) / a0
                a1 = 2.0 * (aMinusOne - aPlusOne * cosW0) / a0
                a2 = (aPlusOne - aMinusOne * cosW0 - twoSqrtAAlpha) / a0
            }
        }
    }

    /// Filter one block in place.
    func process(_ buffer: inout [Float], offset: Int, count: Int) {
        coefLock.lock()
        let cB0 = b0, cB1 = b1, cB2 = b2, cA1 = a1, cA2 = a2
        coefLock.unlock()

        var lx1 = x1, lx2 = x2, ly1 = y1, ly2 = y2
        let end = offset + count
        var i = offset
        while i < end {
            let x0 = Double(buffer[i])
            let y0 = cB0 * x0 + cB1 * lx1 + cB2 * lx2 - cA1 * ly1 - cA2 * ly2
            buffer[i] = Float(y0)
            lx2 = lx1; lx1 = x0
            ly2 = ly1; ly1 = y0
            i += 1
        }
        x1 = lx1; x2 = lx2; y1 = ly1; y2 = ly2
    }
}
