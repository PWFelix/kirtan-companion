import XCTest

// The editor timeline's scrolling arithmetic: rows wrap round the loop both ways, a point
// above the line is a later cell, and the line reaches the cursor the short way round.
final class EditorTimelineMathTests: XCTestCase {
    func testRowsWrapRoundTheLoopBothWays() {
        XCTAssertEqual(EditorTimelineMath.wrap(8, steps: 8), 0)
        XCTAssertEqual(EditorTimelineMath.wrap(-1, steps: 8), 7)
        XCTAssertEqual(EditorTimelineMath.cycle(of: -1, steps: 8), -1)
        XCTAssertEqual(EditorTimelineMath.cycle(of: 8, steps: 8), 1)
    }

    func testAPointAboveTheLineIsALaterCell() {
        XCTAssertEqual(EditorTimelineMath.row(at: 3, dyUp: 88, cell: 44), 5)
        XCTAssertEqual(EditorTimelineMath.row(at: 3, dyUp: -88, cell: 44), 1)
        XCTAssertEqual(EditorTimelineMath.row(at: 3, dyUp: 10, cell: 44), 3)
    }

    func testTheCursorIsReachedTheShortWayRound() {
        XCTAssertEqual(EditorTimelineMath.nearest(0, from: 7, steps: 8), 8)
        XCTAssertEqual(EditorTimelineMath.nearest(7, from: 0, steps: 8), -1)
        XCTAssertEqual(EditorTimelineMath.nearest(3, from: 2.6, steps: 8), 3)
    }
}
