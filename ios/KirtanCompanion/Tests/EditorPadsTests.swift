import XCTest

// Mirrors Android's EditorDraftTest palette/cursor cases.
final class EditorPadsTests: XCTestCase {
    func testEachRowsPaletteIsItsOpenClosedAndRest() {
        XCTAssertEqual(EditorPads.forLane(.dayan).map(\.label), ["Ta", "Te", "Rest"])
        XCTAssertEqual(EditorPads.forLane(.bayan).map(\.label), ["Ge", "Khe", "Rest"])
        XCTAssertEqual(EditorPads.forLane(.kartal).map(\.label), ["Ching", "Chip", "Rest"])
        for lane in LaneId.ordered {
            XCTAssertTrue(EditorPads.forLane(lane).allSatisfy { $0.lane == lane })
        }
        XCTAssertEqual(EditorPads.forLane(.kartal).map(\.stroke), [.open, .closed, nil])
    }

    func testEntryAdvancesAlongTheRowAndWraps() {
        XCTAssertEqual(EditorPads.nextStep(after: 0, steps: 8), 1)
        XCTAssertEqual(EditorPads.nextStep(after: 7, steps: 8), 0)
        XCTAssertEqual(EditorPads.nextStep(after: 0, steps: 0), 0)
    }
}

final class EditorCycleTests: XCTestCase {
    func testCycleRunsRestOpenClosedRest() {
        XCTAssertEqual(EditorPads.cycled(nil, lane: .bayan), .open)
        XCTAssertEqual(EditorPads.cycled(.open, lane: .bayan), .closed)
        XCTAssertNil(EditorPads.cycled(.closed, lane: .bayan))
        XCTAssertEqual(EditorPads.cycled(nil, lane: .kartal), .open)
    }
}
