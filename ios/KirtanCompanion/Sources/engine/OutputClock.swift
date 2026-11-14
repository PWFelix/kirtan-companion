import Foundation

/// Where the listener is, in frames: the frame being HEARD right now.
///
/// The playhead used to be `framesRendered − 4 blocks`, a guess at the output
/// buffer that ignores everything after it — the mixer, the hardware, and above
/// all a Bluetooth link, which adds 150–300 ms the guess never saw. So the
/// display ran ahead of the drum, a little on the speaker and badly over
/// Bluetooth.
///
/// Instead the audio thread stamps each callback with its host time (the
/// `AudioTimeStamp` Core Audio hands the render block) and the frame it starts
/// at. The session's `outputLatency + ioBufferDuration` says how long after
/// that a frame is heard; iOS includes the Bluetooth link in `outputLatency`,
/// which Android cannot do as reliably. Between stamps the position is
/// extrapolated at the sample rate. What the phone still gets wrong is the
/// user's [KirtanEngine.displayDelayMs], measured by the sync screen.
///
/// Pure arithmetic, so it is tested without audio; the Android twin is
/// `engine/OutputClock.kt`.
enum OutputClock {

    /// One callback: frame [frame] began rendering at host time [nanos].
    struct Stamp: Equatable {
        let frame: Int64
        let nanos: UInt64
    }

    /// A stamp older than this is not trusted: the engine stalled or restarted,
    /// and extrapolating across the gap would race ahead of the sound.
    static let staleNanos: UInt64 = 1_000_000_000

    /// The frame heard at [nowNanos], or nil when [stamp] is absent or stale.
    /// Clamped to [0, rendered]: nothing is heard before it is written.
    static func presentedFrame(
        stamp: Stamp?, nowNanos: UInt64, latencyNanos: UInt64, sampleRate: Int, rendered: Int64
    ) -> Int64? {
        guard let stamp, nowNanos >= stamp.nanos else { return nil }
        let age = nowNanos - stamp.nanos
        if age > staleNanos { return nil }
        let heardAge = Int64(age) - Int64(latencyNanos)
        let frame = stamp.frame + heardAge * Int64(sampleRate) / 1_000_000_000
        return min(max(frame, 0), rendered)
    }

    /// Milliseconds as frames. Signed: a negative delay moves the display earlier.
    static func msToFrames(_ ms: Int, sampleRate: Int) -> Int64 {
        Int64(ms) * Int64(sampleRate) / 1000
    }

    /// Mach host ticks to nanoseconds.
    static func hostToNanos(_ host: UInt64) -> UInt64 {
        let info = timebase
        return host &* UInt64(info.numer) / UInt64(info.denom)
    }

    /// Now, on the same clock as the render stamps.
    static func nowNanos() -> UInt64 { hostToNanos(mach_absolute_time()) }

    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()
}
