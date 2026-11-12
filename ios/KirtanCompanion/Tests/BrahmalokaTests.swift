import XCTest

// Brahmaloka time's rules (data/Meter.swift), the same cases as Android's BrahmalokaTest
// and the web's meter.js. SessionPrefs is UI-adjacent glue; its one decision, the cap at
// 200 on launch, is `restoredBpm`, tested here.

final class BrahmalokaTests: XCTestCase {

    func testNormalRangeTopsOutAt200() {
        XCTAssertEqual(tempoRange(brahmaloka: false), 40...200)
        XCTAssertEqual(tempoRange(brahmaloka: true), 190...500)
    }

    func testBelow200AlwaysLeaves() {
        XCTAssertFalse(brahmalokaFor(bpm: 199, was: true))
        XCTAssertFalse(brahmalokaFor(bpm: 120, was: false))
    }

    func testAbove200AlwaysEnters() {
        // A loaded 300 BPM beat has to be shown on a slider that reaches it.
        XCTAssertTrue(brahmalokaFor(bpm: 300, was: false))
        XCTAssertTrue(brahmalokaFor(bpm: 201, was: true))
    }

    func testExactly200KeepsTheMode() {
        XCTAssertTrue(brahmalokaFor(bpm: 200, was: true))
        XCTAssertFalse(brahmalokaFor(bpm: 200, was: false))
    }

    func testLaunchRestoresAtMost200() {
        XCTAssertEqual(restoredBpm(350), 200)
        XCTAssertEqual(restoredBpm(96), 96)
        XCTAssertEqual(restoredBpm(5), 40)
    }
}
