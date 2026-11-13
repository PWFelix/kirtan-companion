import XCTest

final class SequenceCursorTests: XCTestCase {
    func testFirstDownbeatIsBarOneNotAnAdvance() {
        let c = SequenceCursor(bars: [2, 1], loop: false)
        XCTAssertEqual(c.onDownbeat(), .stay)
        XCTAssertEqual(c.index, 0); XCTAssertEqual(c.bar, 0)
    }

    func testSwitchesAfterEachEntrysBarsThenEnds() {
        let c = SequenceCursor(bars: [2, 1], loop: false)
        XCTAssertEqual([c.onDownbeat(), c.onDownbeat(), c.onDownbeat(), c.onDownbeat()],
                       [.stay, .stay, .switchTo(1), .end])
    }

    func testLoopGoesBackToTheTopAndCanBeTurnedOnWhilePlaying() {
        let c = SequenceCursor(bars: [1, 1], loop: false)
        _ = c.onDownbeat(); _ = c.onDownbeat()
        c.loop = true
        XCTAssertEqual(c.onDownbeat(), .switchTo(0))
        XCTAssertEqual(c.onDownbeat(), .switchTo(1))
    }

    func testZeroBarsCountsAsOneAndEmptyEndsAtOnce() {
        let c = SequenceCursor(bars: [0, 3], loop: false)
        XCTAssertEqual([c.onDownbeat(), c.onDownbeat()], [.stay, .switchTo(1)])
        XCTAssertEqual(SequenceCursor(bars: [], loop: true).onDownbeat(), .end)
    }

    func testResetStartsOver() {
        let c = SequenceCursor(bars: [1, 1], loop: false)
        _ = c.onDownbeat(); _ = c.onDownbeat()
        c.reset()
        XCTAssertEqual(c.onDownbeat(), .stay)
        XCTAssertEqual(c.index, 0)
    }

    func testAdoptKeepsThePlaceAndClampsTheBar() {
        let old = SequenceCursor(bars: [1, 8], loop: false)
        _ = old.onDownbeat(); _ = old.onDownbeat(); _ = old.onDownbeat(); _ = old.onDownbeat()
        XCTAssertEqual(old.index, 1); XCTAssertEqual(old.bar, 2)
        let shorter = SequenceCursor(bars: [1, 2], loop: false)
        shorter.adopt(old)
        XCTAssertEqual(shorter.index, 1); XCTAssertEqual(shorter.bar, 1)
        XCTAssertEqual(shorter.onDownbeat(), .end)  // started carried over: not a fresh bar 1
        let gone = SequenceCursor(bars: [4], loop: false)
        gone.adopt(old)
        XCTAssertEqual(gone.index, 0)
    }
}

final class SequencesTests: XCTestCase {
    private let abc = [SequenceEntry(beatId: "a", bars: 4), SequenceEntry(beatId: "b", bars: 2), SequenceEntry(beatId: "c", bars: 8)]

    func testAddDefaultsToFourBarsAndClamps() {
        XCTAssertEqual(Sequences.add([], "x"), [SequenceEntry(beatId: "x", bars: 4)])
        XCTAssertEqual(Sequences.add([], "x", bars: 500)[0].bars, 64)
        XCTAssertEqual(Sequences.setBars(abc, 1, 0)[1].bars, 1)
    }

    func testMoveRemoveAndSetBeat() {
        XCTAssertEqual(Sequences.move(abc, 0, 2).map(\.beatId), ["b", "c", "a"])
        XCTAssertEqual(Sequences.move(abc, 2, -5).map(\.beatId), ["c", "a", "b"])
        XCTAssertEqual(Sequences.move(abc, 9, 0), abc)
        XCTAssertEqual(Sequences.remove(abc, 1).map(\.beatId), ["a", "c"])
        XCTAssertEqual(Sequences.setBeat(abc, 2, "z")[2].beatId, "z")
        XCTAssertEqual(Sequences.totalBars(abc), 14)
    }

    func testResolveSkipsMissingBeatsButKeepsEntryIndexes() {
        let beat = Beats.beats[0]
        let played = Sequences.resolve(abc) { $0 == "b" ? nil : beat }
        XCTAssertEqual(played.map(\.entryIndex), [0, 2])
        XCTAssertEqual(played.map(\.step.bars), [4, 8])
    }

    func testSaveOverwritesOrAddsAsNew() {
        let first = Sequences.save(WorkingSequence(entries: abc), into: [], name: "  Evening ", asNew: false, newId: "1")
        XCTAssertEqual(first.saved, [BeatSequence(id: "1", name: "Evening", entries: abc)])
        XCTAssertEqual(first.working.savedId, "1")
        var w = first.working; w.entries = Sequences.setBars(abc, 0, 1)
        XCTAssertTrue(w.isDirty(first.saved))
        let over = Sequences.save(w, into: first.saved, name: "Evening", asNew: false, newId: "2")
        XCTAssertEqual(over.saved.count, 1); XCTAssertEqual(over.saved[0].entries[0].bars, 1)
        XCTAssertFalse(over.working.isDirty(over.saved))
        let copy = Sequences.save(w, into: first.saved, name: "Copy", asNew: true, newId: "2")
        XCTAssertEqual(copy.saved.map(\.id), ["1", "2"]); XCTAssertEqual(copy.working.savedId, "2")
        XCTAssertFalse(WorkingSequence().isDirty([]))
    }

    func testCodecRoundTripsAndToleratesJunk() {
        let saved = [BeatSequence(id: "1", name: "Evening", entries: abc)]
        XCTAssertEqual(Sequences.decodeSaved(Sequences.encodeSaved(saved)), saved)
        XCTAssertEqual(Sequences.decodeSaved(Data("nope".utf8)), [])
        let w = WorkingSequence(entries: abc, savedId: "1", name: "Evening", loop: true)
        XCTAssertEqual(Sequences.decodeWorking(Sequences.encodeWorking(w)), w)
        XCTAssertEqual(Sequences.decodeWorking(nil), WorkingSequence())
        // The web's stored shape decodes too (same keys).
        let web = Data(#"{"entries":[{"beatId":"te_ta","bars":99}],"savedId":null,"name":"","loop":false}"#.utf8)
        XCTAssertEqual(Sequences.decodeWorking(web).entries, [SequenceEntry(beatId: "te_ta", bars: 64)])
    }

    func testStoreRoundTrips() {
        let defaults = UserDefaults(suiteName: "seq-test-\(UUID())")!
        let store = SequenceStore(defaults: defaults)
        let w = WorkingSequence(entries: abc, savedId: nil, name: "", loop: true)
        store.saveWorking(w)
        store.saveSaved([BeatSequence(id: "1", name: "x", entries: abc)])
        XCTAssertEqual(store.loadWorking(), w)
        XCTAssertEqual(store.loadSaved().count, 1)
    }
}
