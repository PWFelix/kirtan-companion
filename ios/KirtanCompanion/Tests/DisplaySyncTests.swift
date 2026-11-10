import AVFoundation
import XCTest

/// Display sync in its pure parts: the presented-frame extrapolation, how taps
/// become a delay, route classification, and the per-device prefs round-trip.
/// The Android twin is `DisplaySyncTest.kt`.
final class DisplaySyncTests: XCTestCase {

    private let rate = 48_000

    // ── OutputClock ────────────────────────────────────────────────────────

    func testPresentedFrameAdvancesFromTheStampLessLatency() {
        let stamp = OutputClock.Stamp(frame: 10_000, nanos: 1_000_000_000)
        // 150 ms after the stamp with 50 ms latency: 100 ms of it heard = 4800 frames.
        XCTAssertEqual(
            OutputClock.presentedFrame(stamp: stamp, nowNanos: 1_150_000_000, latencyNanos: 50_000_000,
                                       sampleRate: rate, rendered: 50_000),
            14_800
        )
    }

    func testMissingOrStaleStampIsNotTrusted() {
        XCTAssertNil(OutputClock.presentedFrame(stamp: nil, nowNanos: 0, latencyNanos: 0, sampleRate: rate, rendered: 1))
        let stamp = OutputClock.Stamp(frame: 10, nanos: 0)
        XCTAssertNil(OutputClock.presentedFrame(stamp: stamp, nowNanos: OutputClock.staleNanos + 1,
                                                latencyNanos: 0, sampleRate: rate, rendered: 50_000))
    }

    func testPresentedFrameIsClampedToWhatExists() {
        let stamp = OutputClock.Stamp(frame: 10_000, nanos: 0)
        XCTAssertEqual(OutputClock.presentedFrame(stamp: stamp, nowNanos: 500_000_000, latencyNanos: 0,
                                                  sampleRate: rate, rendered: 12_000), 12_000)
        XCTAssertEqual(OutputClock.presentedFrame(stamp: OutputClock.Stamp(frame: 100, nanos: 0), nowNanos: 0,
                                                  latencyNanos: 500_000_000, sampleRate: rate, rendered: 12_000), 0)
    }

    func testMsToFramesIsSigned() {
        XCTAssertEqual(OutputClock.msToFrames(200, sampleRate: rate), 9_600)
        XCTAssertEqual(OutputClock.msToFrames(-50, sampleRate: rate), -2_400)
    }

    // ── Calibration ────────────────────────────────────────────────────────

    func testNoEstimateUntilEnoughTaps() {
        XCTAssertNil(SyncCalibration.estimate(Array(repeating: 100, count: SyncCalibration.warmUp + SyncCalibration.tapsNeeded - 1)))
    }

    func testDelayIsTheRoundedMedianAfterWarmUp() {
        let offsets: [Double] = [-300, 380, 148, 152, 150, 146, 155, 151, 149, 400, 153, 147]
        XCTAssertEqual(SyncCalibration.estimate(offsets), .delay(150))
    }

    func testScatteredTapsAreRejected() {
        let offsets: [Double] = [0, 0, -200, 150, -50, 220, 10, -180, 90, 300, -120, 40]
        XCTAssertEqual(SyncCalibration.estimate(offsets), .tooUneven)
    }

    func testOverReportingPhoneGivesNegativeDelay() {
        XCTAssertEqual(SyncCalibration.estimate(Array(repeating: -42, count: 12)), .delay(-40))
    }

    // ── Routes ─────────────────────────────────────────────────────────────

    func testRoutesAreClassifiedAndKeyedLikeAndroid() {
        XCTAssertEqual(OutputRoute.from(portType: .bluetoothA2DP, portName: "JBL Flip 5"),
                       OutputRoute(key: "bt:JBL Flip 5", label: "JBL Flip 5", bluetooth: true))
        XCTAssertTrue(OutputRoute.from(portType: .bluetoothLE, portName: "Buds").bluetooth)
        XCTAssertEqual(OutputRoute.from(portType: .headphones, portName: "Headphones").key, "wired")
        XCTAssertEqual(OutputRoute.from(portType: .builtInSpeaker, portName: "Speaker"), .speaker)
    }

    // ── Prefs ──────────────────────────────────────────────────────────────

    func testDelaysAndPromptedDevicesRoundTrip() {
        var snapshot = EqPrefsSnapshot.default
        snapshot.displayDelays = ["bt:JBL Flip 5": 180, "speaker": -20]
        snapshot.syncPrompted = ["bt:JBL Flip 5"]
        let back = EqPrefsCodec.decode(EqPrefsCodec.encode(snapshot))
        XCTAssertEqual(back.displayDelays, snapshot.displayDelays)
        XCTAssertEqual(back.syncPrompted, snapshot.syncPrompted)
        XCTAssertEqual(back.displayDelay("bt:never seen"), 0)
    }

    func testJunkDelaysAreClampedOrDropped() {
        let back = EqPrefsCodec.decode(#"{"displayDelays":{"a":5000,"b":-900,"c":"soon","d":42.4},"syncPrompted":["x",3]}"#)
        XCTAssertEqual(back.displayDelays, ["a": EqPrefsSnapshot.displayDelayMaxMs, "b": EqPrefsSnapshot.displayDelayMinMs, "d": 42])
        XCTAssertEqual(back.syncPrompted, ["x"])
    }
}
