// One decoded drum sample: mono float PCM in [-1, 1] at the engine's rate.
//
// Decoding happens ONCE at load, not per trigger. The bundled recordings are
// stereo 44.1 kHz while the engine renders at its own rate, so the one-time
// conversion up front means the render loop only ever pays the per-end-tuning
// resample and never a format conversion in the signal path.

struct PcmSample {
    var data: [Float]
    var sampleRate: Int

    var frameCount: Int { data.count }
    var durationSeconds: Double { sampleRate > 0 ? Double(data.count) / Double(sampleRate) : 0 }

    /// Silence, used when a file is missing so a stroke degrades quietly.
    static func silence(_ sampleRate: Int) -> PcmSample { PcmSample(data: [], sampleRate: sampleRate) }
}
