import XCTest

// Mirrors Android's HighwayTest.
final class HighwayTests: XCTestCase {
    private var doubleTime: Beat { Beats.beats.first { $0.id == "double_time_2" }! }
    private var daspahir: Beat { Beats.beats.first { $0.id == "daspahir_taal" }! }

    func testColumnsAreHeadedByTheirBols() {
        XCTAssertEqual(LearnInstrument.mrdanga.columns.map(\.bol), ["Ge", "Khe", "Te", "Ta"])
        XCTAssertEqual(LearnInstrument.kartal.columns.map(\.bol), ["Ching", "Chip"])
    }

    func testTheKartalHighwayCarriesOnlyTheKartalLine() {
        let beat = Beats.beats.first { Highway.hasPart($0, .kartal) }!
        let frame = Highway.frame(beat, position: 0, lookahead: Double(beat.steps - 1), instrument: .kartal)
        XCTAssertFalse(frame.notes.isEmpty)
        XCTAssertTrue(frame.notes.allSatisfy { $0.column.lane == .kartal })
        let expected = beat.pattern(.kartal)!.prefix(beat.steps).filter { $0 != nil }.count
        XCTAssertEqual(frame.notes.filter { $0.rel >= 0 && $0.rel < Double(beat.steps) }.count, expected)
        // The kartal never mirrors: handedness is the drum's.
        XCTAssertEqual(Highway.columns(leftHanded: true, instrument: .kartal), LearnInstrument.kartal.columns)
        var s = EqPrefsSnapshot.default; s.learnKartal = true
        XCTAssertTrue(EqPrefsCodec.decode(EqPrefsCodec.encode(s)).learnKartal)
        XCTAssertFalse(EqPrefsCodec.decode("{}").learnKartal)
    }

    func testANoteSitsOnTheHitLineAtItsStep() {
        let frame = Highway.frame(doubleTime, position: 0, lookahead: 8)
        let now = Set(frame.notes.filter { $0.rel == 0 }.map(\.column))
        XCTAssertEqual(now, [.dayanClosed, .bayanOpen])
        XCTAssertEqual(frame.currentStep, 0)
    }

    func testEveryStrokeInTheWindowAppearsExactlyOnce() {
        let pos = 5.25
        let frame = Highway.frame(doubleTime, position: pos, lookahead: 10)
        for lane in [LaneId.dayan, .bayan] {
            let pattern = doubleTime.pattern(lane)!
            for abs in 6...15 {
                guard let stroke = pattern[abs % 16] else { continue }
                let hits = frame.notes.filter {
                    $0.column == HighwayColumn.of(lane, stroke) && $0.rel == Double(abs) - pos
                }
                XCTAssertEqual(hits.count, 1, "lane \(lane) step \(abs)")
            }
        }
        XCTAssertTrue(frame.notes.allSatisfy { $0.rel >= -Highway.trailSteps - 1 })
    }

    func testTheWindowWrapsIntoTheNextBar() {
        let frame = Highway.frame(daspahir, position: 30, lookahead: 6)
        XCTAssertTrue(frame.lines.contains { $0.sam && $0.rel == 2 })
        let downbeat = frame.notes.filter { $0.step == 0 && $0.rel == 2 }.map(\.column)
        XCTAssertEqual(downbeat, [.bayanOpen])
    }

    func testBeatLinesFallOnGroupBoundaries() {
        XCTAssertEqual(Highway.groupBoundaries(doubleTime), [0, 4, 8, 12])
        XCTAssertEqual(Highway.groupBoundaries(daspahir), Set(stride(from: 0, to: 32, by: 4)))
    }

    func testLookaheadIsTimeBased() {
        XCTAssertEqual(Highway.lookaheadSteps(bpm: 140, cellsPerQuarter: 4), 16.8, accuracy: 1e-9)
        XCTAssertEqual(Highway.lookaheadSteps(bpm: 40, cellsPerQuarter: 1), 4.0, accuracy: 1e-9)
    }

    func testBolsNameTwoHandStrokes() {
        XCTAssertEqual(Highway.bolAt(daspahir, step: 16), "Khe")
        XCTAssertEqual(Highway.bolAt(daspahir, step: 4), "Te")
        XCTAssertEqual(Highway.bolAt(doubleTime, step: 0), "Gi")
    }

    func testLeftHandedMirrorsTheColumns() {
        XCTAssertEqual(Highway.columns(leftHanded: false).map(\.bol), ["Ge", "Khe", "Te", "Ta"])
        XCTAssertEqual(Highway.columns(leftHanded: true).map(\.bol), ["Ta", "Te", "Khe", "Ge"])
    }

    func testLeftHandedPrefRoundTrips() {
        var s = EqPrefsSnapshot.default; s.learnLeftHanded = true
        XCTAssertTrue(EqPrefsCodec.decode(EqPrefsCodec.encode(s)).learnLeftHanded)
        XCTAssertFalse(EqPrefsCodec.decode("{}").learnLeftHanded)
    }
}
