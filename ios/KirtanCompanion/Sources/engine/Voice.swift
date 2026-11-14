// One sounding sample.
//
// A voice is created the instant a stroke is triggered and lives until its
// recording runs out — there is no envelope, because the decay IS the recording.
// That is the whole reason the app is sample-based rather than synthesised: a
// mridanga's ring is not something you want to model.
//
// `startOffset` places the voice at an exact frame WITHIN the block it was
// triggered in, which is what makes timing sample-accurate. A stroke scheduled
// for frame 137 of a 256-frame block contributes silence for 137 frames and
// then its first sample at 137 — no rounding to the block boundary, so the grid
// cannot smear. It may exceed one block (the Feel humanizer delays strokes by up
// to ~60 ms): each silent block then just counts it down.
//
// `gain` scales the whole voice — the humanizer's per-stroke loudness.
//
// Resampling by `rate` is what per-end tuning does: the pitch slider sets a
// playback rate, and a higher rate both raises the pitch and shortens the decay,
// exactly as tightening a real head would. Linear interpolation is used because
// the rate is at most ±600 cents (×0.71 to ×1.41) over broadband percussion.

struct Voice {
    let sample: PcmSample
    /// The lane this voice sounds into. Stored rather than looked up: a sample
    /// always belongs to exactly one lane, so the voice can name it directly.
    let lane: LaneId

    /// Fractional read position in the sample, in source frames.
    var position: Double = 0
    var finished = false

    /// Frames from the start of the current block until this voice starts;
    /// zero once it is sounding.
    var startOffset: Int
    /// Linear gain for this stroke.
    var gain: Float

    init(_ sample: PcmSample, lane: LaneId, startOffset: Int, gain: Float = 1) {
        self.sample = sample
        self.lane = lane
        self.startOffset = startOffset
        self.gain = gain
    }

    /// Add this voice into `out` for `count` frames, advancing by `rate` per
    /// output frame. `out` must be the current block (additive, not overwriting).
    mutating func render(into out: inout [Float], count: Int, rate: Double) {
        let data = sample.data
        let last = data.count - 1
        if last <= 0 {
            finished = true
            return
        }

        // Read per block rather than latched at trigger, so moving the tune slider
        // audibly bends a drum that is still ringing — what Tone.Player.playbackRate
        // does, and what a player expects from something labelled like head tension.
        let step = rate > 0 ? rate : 1.0

        if startOffset >= count {
            startOffset -= count   // still waiting: silent for this whole block
            return
        }
        var i = max(0, startOffset)
        startOffset = 0
        var pos = position
        let g = gain

        while i < count && pos < Double(last) {
            let i0 = Int(pos)
            let frac = Float(pos - Double(i0))
            let s0 = data[i0]
            out[i] += (s0 + (data[i0 + 1] - s0) * frac) * g
            pos += step
            i += 1
        }

        position = pos
        if pos >= Double(last) { finished = true }
    }
}
