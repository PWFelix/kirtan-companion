import XCTest

final class HillClimbTests: XCTestCase {
    func testStepsUp() { XCTAssertEqual(HillClimb.next(80, step: 5, ceiling: 200), 85) }

    func testStopsAtTheTopOfTheRange() {
        XCTAssertEqual(HillClimb.next(198, step: 5, ceiling: 200), 200)
        XCTAssertEqual(HillClimb.next(200, step: 5, ceiling: 200), 200)
        XCTAssertTrue(HillClimb.atTop(200, ceiling: 200))
        XCTAssertFalse(HillClimb.atTop(199, ceiling: 200))
    }

    func testInBrahmalokaClimbsPast200() { XCTAssertEqual(HillClimb.next(200, step: 5, ceiling: 500), 205) }

    func testNeverPullsATempoAboveTheCeilingDown() { XCTAssertEqual(HillClimb.next(230, step: 5, ceiling: 200), 230) }

    func testRememberedValuesSnapToChoices() {
        XCTAssertEqual(HillClimb.step(of: 10), 10)
        XCTAssertEqual(HillClimb.step(of: 7), HillClimb.defaultStep)
        XCTAssertEqual(HillClimb.step(of: nil), HillClimb.defaultStep)
        XCTAssertEqual(HillClimb.interval(of: 300), 300)
        XCTAssertEqual(HillClimb.interval(of: 45), HillClimb.defaultIntervalSec)
    }

    func testClock() {
        XCTAssertEqual(HillClimb.clock(120), "2:00")
        XCTAssertEqual(HillClimb.clock(5), "0:05")
        XCTAssertEqual(HillClimb.clock(-3), "0:00")
    }

    func testChoiceIsRememberedInPrefs() {
        var s = EqPrefsSnapshot.default
        s.hillClimbStep = 10; s.hillClimbSeconds = 30
        let back = EqPrefsCodec.decode(EqPrefsCodec.encode(s))
        XCTAssertEqual(back.hillClimbStep, 10)
        XCTAssertEqual(back.hillClimbSeconds, 30)
        XCTAssertEqual(EqPrefsCodec.decode("{}").hillClimbStep, HillClimb.defaultStep)
    }
}
