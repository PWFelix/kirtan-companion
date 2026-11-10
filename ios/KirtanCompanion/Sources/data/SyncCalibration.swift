import Foundation

/// Turns the sync test's taps into a display delay. The Android twin is
/// `ui/sync/SyncCalibration.kt`; the reasoning is there too.
///
/// Each tap arrives as its offset from the nearest click, measured against what
/// the phone SAYS is being heard. A listener taps on the sound, so the typical
/// offset is the delay the phone left out. MEDIAN, not mean, so one slip does
/// not drag it; taps spread wider than `maxSpreadMs` (median absolute
/// deviation) are refused rather than turned into a confident wrong answer. The
/// first `warmUp` taps are dropped while the listener locks on.
enum SyncCalibration {
    static let warmUp = 2
    static let tapsNeeded = 10
    static let maxSpreadMs = 45.0
    static let roundToMs = 5

    enum Result: Equatable {
        case delay(Int)
        case tooUneven
    }

    static func complete(_ offsets: [Double]) -> Bool { offsets.count >= warmUp + tapsNeeded }

    /// The delay to set, or `.tooUneven`; nil until `complete`.
    static func estimate(_ offsets: [Double]) -> Result? {
        guard complete(offsets) else { return nil }
        let taps = Array(offsets.dropFirst(warmUp))
        let mid = median(taps)
        let spread = median(taps.map { abs($0 - mid) })
        if spread > maxSpreadMs { return .tooUneven }
        return .delay(Int((mid / Double(roundToMs)).rounded()) * roundToMs)
    }

    private static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let m = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2
    }
}
