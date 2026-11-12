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

    func testHandsOverHalfABarPlusASecondEarly() {
        XCTAssertEqual(HillClimb.leadSeconds(bpm: 60, beatsPerBar: 4), 3)
        XCTAssertEqual(HillClimb.leadSeconds(bpm: 120, beatsPerBar: 4), 2)
        XCTAssertEqual(HillClimb.leadSeconds(bpm: 40, beatsPerBar: 3.5), 4)
    }

    /// Snap to bar: which 1 a tempo change aimed at a moment lands on.
    func testBarSnapPicksTheNearestDownbeatNeverAPlayedOne() {
        let sr = 48_000
        let clock = MusicalClock(sampleRate: sr)
        clock.setMeter(steps: 8, beatsPerBar: 4)
        clock.setBpm(60, atFrame: 0) // a 4 s bar, 768 ticks
        clock.start(atFrame: 0)
        func s(_ x: Double) -> Int64 { Int64(x * Double(sr)) }
        let st = clock.snapshot()
        XCTAssertEqual(st.barTickNear(s(5), now: s(1)), 768, accuracy: 1e-9)   // before
        XCTAssertEqual(st.barTickNear(s(7), now: s(1)), 1536, accuracy: 1e-9)  // after
        XCTAssertEqual(st.barTickNear(s(5), now: s(4.5)), 1536, accuracy: 1e-9) // 4 s already played
        let at = st.frameAt(768)
        XCTAssertEqual(at, s(4))
        clock.setBpm(120, atFrame: at)
        XCTAssertEqual(clock.tickAt(at), 768, accuracy: 1e-6)
        XCTAssertEqual(clock.frameAt(1536), s(6))
    }
}
