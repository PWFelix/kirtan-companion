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
        XCTAssertEqual(doubled.addingBeat().groups, [4, 4, 6, 4])
        // A saved ×2 seven-eight (and six-eight) reopens with its group pattern.
        XCTAssertEqual(EditorShape.seeded(groups: [4, 4, 6], cpq: 4).unit, [4, 4, 6])
        XCTAssertEqual(EditorShape.seeded(groups: [6, 6], cpq: 4).unit, [6, 6])
    }

    func testThirtyTwoBeatsFitAtEveryResolution() {
        var shape = std
        for _ in 0..<28 { shape = shape.addingBeat() } // 32 beats of 2
        XCTAssertEqual(shape.steps, 64)
        XCTAssertFalse(shape.canAddBeat)
        XCTAssertNotNil(shape.atResolution(8))
        XCTAssertEqual(shape.atResolution(12)?.steps, ShareCodec.MAX_STEPS)
    }

    func testThirtyTwoBeatsAtEightCellsAndPlusStopsThere() {
        var shape = EditorShape(groups: Array(repeating: 8, count: 31), cpq: 8, unit: [8])
        XCTAssertTrue(shape.canAddBeat)
        shape = shape.addingBeat()
        XCTAssertEqual(shape.beatCount, 32)
        XCTAssertEqual(shape.steps, 256)
        XCTAssertFalse(shape.canAddBeat)  // a 33rd beat breaks the 32-beat cap
        // 32 beats of 12 is exactly the cell bound.
        XCTAssertEqual(EditorShape(groups: Array(repeating: 12, count: 32), cpq: 12, unit: [12]).steps, ShareCodec.MAX_STEPS)
    }

    func testPlusAndMinusMoveOneBeat() {
        let six = EditorShape(groups: [3, 3], cpq: 2, unit: [3, 3])
        XCTAssertEqual(six.addingBeat().groups, [3, 3, 3])
        XCTAssertEqual(six.addingBeat().addingBeat().groups, [3, 3, 3, 3])
        XCTAssertEqual(seven.addingBeat().addingBeat().addingBeat().groups, [2, 2, 3, 2, 2, 3])
        XCTAssertEqual(seven.removingBeat().groups, [2, 2])
        let one = six.removingBeat()
        XCTAssertEqual(one.groups, [3])
        XCTAssertFalse(one.canRemoveBeat)
    }

    func testTalaFeelsOfferOnlyMultiplesOfTheirBeat() {
        let t7 = EditorShape(groups: [7, 7, 7, 7], cpq: 7, unit: [7])
        XCTAssertEqual(t7.resolutionOptions, [7, 14, 21])
        let fine = t7.atResolution(21)!
        XCTAssertEqual(fine.groups, [21, 21, 21, 21])
        XCTAssertEqual(fine.resolutionOptions, [7, 14, 21])
        XCTAssertEqual(fine.addingBeat().groups.count, 5)
        XCTAssertNotNil(EditorShape(groups: [9, 9, 9, 9], cpq: 9, unit: [9]).atResolution(27))
        XCTAssertEqual(EditorShape(groups: [5, 5], cpq: 5, unit: [5]).resolutionOptions, [5, 10, 15])
    }
}
