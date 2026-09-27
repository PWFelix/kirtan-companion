import Foundation
import XCTest

// Storage tests. The data + storage sources are compiled into this test module (see
// project.pbxproj), so no import is needed. The EqPrefs codec is pure (no disk), which is
// exactly what makes its "a poisoned blob cannot reach the DSP" promise testable in isolation.

final class StorageTests: XCTestCase {

    // MARK: EqPrefsSnapshot helpers

    func testEndReturnsNilForKartal() {
        let s = EqPrefsSnapshot.default
        XCTAssertNotNil(s.end(.dayan))
        XCTAssertNotNil(s.end(.bayan))
        XCTAssertNil(s.end(.kartal), "kartal has no EQ/tune")
    }

    func testVolumeDefaultsToUnityForAbsentLane() {
        let s = EqPrefsSnapshot.default
        XCTAssertEqual(s.volume(.dayan), 1.0, accuracy: 1e-9)
        var s2 = s
        s2.endVolumes = [.dayan: 0.25]
        XCTAssertEqual(s2.volume(.dayan), 0.25, accuracy: 1e-9)
        XCTAssertEqual(s2.volume(.bayan), 1.0, accuracy: 1e-9, "a lane without an entry reads 1")
    }

    // MARK: EqPrefsCodec

    private func aLoadedSnapshot() -> EqPrefsSnapshot {
        var s = EqPrefsSnapshot.default
        s.masterVolume = 0.5
        s.endVolumes = [.dayan: 0.3, .bayan: 0.7]
        s.mutedEnds = [.bayan]
        s.tempoLocked = true
        s.showBols = true
        s.uiScale = 1.3
        s.dayan.bands = [2, -1, 0, 1, -2]
        s.dayan.tuneCents = 50
        s.bayan.tuneCents = -30
        return s
    }

    func testCodecRoundTrips() {
        let s = aLoadedSnapshot()
        let back = EqPrefsCodec.decode(EqPrefsCodec.encode(s))
        XCTAssertEqual(back.masterVolume, 0.5, accuracy: 1e-9)
        XCTAssertEqual(back.endVolumes[.dayan] ?? 0, 0.3, accuracy: 1e-9)
        XCTAssertEqual(back.endVolumes[.bayan] ?? 0, 0.7, accuracy: 1e-9)
        XCTAssertTrue(back.isMuted(.bayan))
        XCTAssertFalse(back.isMuted(.dayan))
        XCTAssertTrue(back.tempoLocked)
        XCTAssertTrue(back.showBols)
        XCTAssertEqual(back.uiScale, 1.3, accuracy: 1e-9)
        XCTAssertEqual(back.dayan.tuneCents, 50)
        XCTAssertEqual(back.bayan.tuneCents, -30)
        XCTAssertEqual(back.dayan.bands, [2, -1, 0, 1, -2])
    }

    func testDecodeNilIsDefault() {
        XCTAssertEqual(EqPrefsCodec.decode(nil), EqPrefsSnapshot.default)
    }

    func testDecodeGarbageIsDefault() {
        XCTAssertEqual(EqPrefsCodec.decode("this is { not json"), EqPrefsSnapshot.default)
        XCTAssertEqual(EqPrefsCodec.decode("[1,2,3]"), EqPrefsSnapshot.default, "a non-object degrades to default")
    }

    func testCodecClampsOutOfRangeValues() {
        var s = EqPrefsSnapshot.default
        s.uiScale = 5.0            // above uiScaleMax
        s.dayan.bands = [99, -99, 0, 0, 0]  // beyond the ±12 dB range
        s.dayan.tuneCents = 9999   // beyond the ±600 cent range
        let back = EqPrefsCodec.decode(EqPrefsCodec.encode(s))
        XCTAssertLessThanOrEqual(back.uiScale, EqPrefsSnapshot.uiScaleMax)
        for b in back.dayan.bands {
            XCTAssertGreaterThanOrEqual(b, EQ_MIN_DB)
            XCTAssertLessThanOrEqual(b, EQ_MAX_DB)
        }
        XCTAssertLessThanOrEqual(abs(back.dayan.tuneCents), 600)
    }

    // MARK: KirtanStore — real round-trips against the test process's Documents

    func testStoreStringRoundTrip() async {
        let store = KirtanStore()
        try? await store.perform { $0.setString("test.key", "value") }
        let got = await store.read { $0.string("test.key") }
        XCTAssertEqual(got, "value")
        // setting nil deletes the key
        try? await store.perform { $0.setString("test.key", nil) }
        let afterDelete = await store.read { $0.string("test.key") }
        XCTAssertNil(afterDelete)
    }

    func testStoreIntRoundTrip() async {
        let store = KirtanStore()
        try? await store.perform { $0.setInt("test.count", 42) }
        let count = await store.read { $0.int("test.count") }
        XCTAssertEqual(count, 42)
    }
}
