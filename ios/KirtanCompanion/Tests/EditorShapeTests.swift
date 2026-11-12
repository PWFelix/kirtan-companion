import XCTest

/// The editor's two levers — beats and resolution — and the resampling that lets
/// the resolution change without losing the rhythm. Mirrors Android's EditorDraftTest.
final class EditorShapeTests: XCTestCase {
    private let std = EditorShape(groups: [2, 2, 2, 2], cpq: 2, unit: [2])
    private let seven = EditorShape(groups: [2, 2, 3], cpq: 2, unit: [2, 2, 3])

    private func lane(_ steps: Int, _ hits: Int...) -> [Stroke?] {
        var cells = [Stroke?](repeating: nil, count: steps)
        for h in hits { cells[h] = .open }
        return cells
    }

    private func hits(_ cells: [Stroke?]) -> [Int] { cells.indices.filter { cells[$0] != nil } }

    func testSimpleMeterOffersCellsPerBeatOddMeterAMultiplier() {
        XCTAssertFalse(std.isMultiplier)
        XCTAssertEqual(std.resolution, 2)
        XCTAssertEqual(std.resolutionOptions, [1, 2, 3, 4, 6, 8])
        XCTAssertTrue(seven.isMultiplier)
        XCTAssertEqual(seven.resolution, 1)
        XCTAssertEqual(seven.resolutionOptions, [1, 2, 3])
    }

    func testGoingFinerKeepsEveryHitAndTheBeatCount() {
        let next = std.atResolution(4)!
        XCTAssertEqual(next.groups, [4, 4, 4, 4])
        var report = ResampleReport(moved: 0, dropped: 0)
        let out = resample(lane(8, 0, 3, 5), fromCpq: 2, toCpq: 4, steps: next.steps, report: &report)
        XCTAssertEqual(hits(out), [0, 6, 10])
        XCTAssertEqual(report, ResampleReport(moved: 0, dropped: 0))
    }

    func testGoingCoarserSnapsToTheNearestCellAndReportsLosses() {
        var report = ResampleReport(moved: 0, dropped: 0)
        let out = resample(lane(16, 0, 1, 2, 4), fromCpq: 4, toCpq: 2, steps: 8, report: &report)
        XCTAssertEqual(hits(out), [0, 1, 2])
        XCTAssertEqual(report, ResampleReport(moved: 1, dropped: 1))
    }

    func testAnOddMeterKeepsItsShapeUnderTheMultiplier() {
        let doubled = seven.atResolution(2)!
        XCTAssertEqual(doubled.groups, [4, 4, 6])
        XCTAssertEqual(doubled.cpq, 4)
        XCTAssertEqual(doubled.resolution, 2)
        XCTAssertEqual(doubled.addingBeat().groups, [4, 4, 6, 4, 4, 6])
        // A saved ×2 seven-eight (and six-eight) reopens with a whole-cycle ± unit.
        XCTAssertEqual(EditorShape.seeded(groups: [4, 4, 6], cpq: 4).unit, [4, 4, 6])
        XCTAssertEqual(EditorShape.seeded(groups: [6, 6], cpq: 4).unit, [6, 6])
    }

    func testOptionsPast64CellsAreRefusedAndSoIsA65thCell() {
        var shape = std
        for _ in 0..<12 { shape = shape.addingBeat() } // 16 beats of 2
        XCTAssertEqual(shape.steps, 32)
        XCTAssertNotNil(shape.atResolution(4))
        XCTAssertNil(shape.atResolution(6))
        shape = shape.atResolution(4)!
        XCTAssertFalse(shape.canAddBeat)
        XCTAssertEqual(shape.addingBeat().steps, 64)
    }
}
