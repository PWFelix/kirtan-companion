import XCTest

final class EditorClipboardTests: XCTestCase {
    /// 8 cells, first half drawn: dayan O . O . / bayan O O . X
    private let guru: [LaneId: [Stroke?]] = [
        .dayan: [.open, nil, .open, nil, nil, nil, nil, nil],
        .bayan: [.open, .open, nil, .closed, nil, nil, nil, nil],
        .kartal: Array(repeating: nil, count: 8),
    ]

    func testRunIsOrderedEitherWayRound() {
        XCTAssertEqual(EditorClipboard.run(3, 1), 1...3)
        XCTAssertEqual(EditorClipboard.run(1, 3), 1...3)
    }

    func testCopyTakesEveryLaneAndPastesAtTheCursor() {
        let clip = EditorClipboard.copy(guru, 0...3)
        XCTAssertEqual(clip.count, 4)
        let r = EditorClipboard.paste(clip, into: guru, at: 4)
        XCTAssertEqual(r.written, 4)
        XCTAssertEqual(Array(r.lanes[.dayan]![4...]), [.open, nil, .open, nil])
        XCTAssertEqual(Array(r.lanes[.bayan]![4...]), [.open, .open, nil, .closed])
    }

    func testBayanClosedTurnsOnlyBayanOpensClosed() {
        let r = EditorClipboard.paste(EditorClipboard.copy(guru, 0...3), into: guru, at: 4, closeBayan: true)
        XCTAssertEqual(Array(r.lanes[.bayan]![4...]), [.closed, .closed, nil, .closed])
        XCTAssertEqual(Array(r.lanes[.dayan]![4...]), [.open, nil, .open, nil])
        XCTAssertEqual(r.lanes[.bayan]![0], .open)
    }

    func testPasteStopsAtTheEndRatherThanWrapping() {
        let r = EditorClipboard.paste(EditorClipboard.copy(guru, 0...3), into: guru, at: 6)
        XCTAssertEqual(r.written, 2)
        XCTAssertEqual(r.lanes[.dayan]![0], .open)
        XCTAssertEqual(r.lanes[.dayan]![1], nil)
        XCTAssertEqual(Array(r.lanes[.dayan]![6...]), [.open, nil])
    }
}
