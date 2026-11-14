import Foundation

/// The output-device facts the engine needs before it is constructed.
///
/// Kept separate from [KirtanEngine] so the engine stays free of `AVAudioSession`
/// and remains constructible in a plain test with nothing but a sample rate.
///
/// The engine's output node declares a fixed sample rate, and `WavDecoder`
/// resamples the bundled recordings (44.1 kHz) straight to it in one pass.
/// 48 kHz is the rate modern iPhones run their output at, so decoding there means
/// the platform does no second resample; the simulator matches it too. (The Android
/// port reads a device property and falls back to the same 48 kHz.)
enum AudioDeviceConfig {
    static let sampleRate = 48_000
}
