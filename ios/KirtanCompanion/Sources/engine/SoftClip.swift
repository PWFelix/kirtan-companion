import Foundation

/// Level above which `softClip` starts shaping.
let SOFT_CLIP_KNEE = 0.8

// A soft knee above SOFT_CLIP_KNEE, asymptotically approaching 1.0.
//
// A deliberate, documented divergence from the web engine, which simply lets Web
// Audio clip. Three lanes summed — and the bayan carries a fixed 1.25 makeup gain
// — can exceed full scale on a coincident downbeat, and hard clipping a drum
// transient is the harshest distortion there is.
//
// `tanh` rather than a polynomial, for the knee's continuity. A cubic that starts
// at the knee and lands on 1.0 necessarily leaves it with slope n, so a
// polynomial knee has a DERIVATIVE discontinuity — audible as exactly the harshness
// we're avoiding. `tanh` gives f(knee) = knee and f'(knee) = 1 exactly, so the
// curve leaves the linear region smoothly, and is bounded by 1.0 for any input.
//
// Below the knee this is exactly unity, so ordinary playing is untouched and a
// mixer setting still means what it means on the web; only peaks that would
// otherwise clip are shaped. Full scale maps to ~0.95, a level loss too small to
// hear.
func softClip(_ x: Float) -> Float {
    let xf = Double(x)
    let a = abs(xf)
    if a <= SOFT_CLIP_KNEE { return x }
    let sign: Double = xf < 0 ? -1 : 1
    let knee = SOFT_CLIP_KNEE
    let headroom = 1 - knee
    let shaped = knee + headroom * tanh((a - knee) / headroom)
    return Float(sign * shaped)
}

/// Apply `softClip` in place to a block.
func softClipInPlace(_ buffer: inout [Float], count: Int) {
    for i in 0..<count { buffer[i] = softClip(buffer[i]) }
}
