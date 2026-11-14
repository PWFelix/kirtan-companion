import XCTest

/// The one editor draft held above the view: what counts as unsaved, Revert, and the
/// copy on disk that survives a relaunch. Mirrors Android's EditorSessionTest.
final class EditorSessionTests: XCTestCase {
    private var keherva: Beat { Beats.beats.first { $0.id == "keherva_medium_speed" } ?? Beats.beats[0] }

    func testAFreshSessionIsClean() {
        XCTAssertFalse(EditorSession(mode: .new).dirty)
        XCTAssertFalse(EditorSession(mode: .edit(keherva)).dirty)
    }

    func testDirtyIsAboutContentNotTheCursor() {
        let s = EditorSession(mode: .fork(keherva))
        s.draft.cursorStep = 3
        XCTAssertFalse(s.dirty)
        let was = s.draft.dayan[0]
        s.draft.dayan[0] = was == Stroke.open ? Stroke.closed : Stroke.open
        XCTAssertTrue(s.dirty)
        s.draft.dayan[0] = was
        XCTAssertFalse(s.dirty, "putting the cell back clears it")
        s.draft.bpm += 5
        XCTAssertTrue(s.dirty)
    }

    func testNameAndTagsCountSurroundingSpacesDoNot() {
        let s = EditorSession(mode: .edit(keherva))
        s.draft.name = keherva.name + "  "
        XCTAssertFalse(s.dirty)
        s.draft.name = "Mine"
        XCTAssertTrue(s.dirty)
        let t = EditorSession(mode: .new)
        t.draft.tags = ["slow"]
        XCTAssertTrue(t.dirty)
    }

    func testFreshGoesBackToTheSeed() {
        let s = EditorSession(mode: .everyone(keherva))
        s.draft.bpm = 200
        let f = s.fresh()
        XCTAssertFalse(f.dirty)
        XCTAssertEqual(f.mode, BeatEditMode.everyone(keherva))
    }

    func testADirtySessionRoundTrips() throws {
        let s = EditorSession(mode: .edit(keherva))
        s.draft.bayan[1] = s.draft.bayan[1] == nil ? .open : nil
        s.draft.name = "Variation"
        s.draft.tags = ["slow", "practice"]
        s.draft.cursorLane = 1
        s.draft.cursorStep = 5
        let back = try XCTUnwrap(EditorSessionStore.decode(EditorSessionStore.encode(s)))
        XCTAssertEqual(back.mode.seed?.id, keherva.id)
        if case .edit = back.mode {} else { XCTFail("mode lost: \(back.mode)") }
        XCTAssertTrue(back.draft.sameContent(as: s.draft))
        XCTAssertEqual(back.draft.cursorLane, 1)
        XCTAssertEqual(back.draft.cursorStep, 5)
        XCTAssertTrue(back.dirty)
    }

    func testANewBeatRoundTripsAndJunkDecodesToNothing() throws {
        let s = EditorSession(mode: .new)
        s.draft.kartal[0] = .open
        s.draft.name = ""
        let back = try XCTUnwrap(EditorSessionStore.decode(EditorSessionStore.encode(s)))
        XCTAssertEqual(back.mode, BeatEditMode.new)
        XCTAssertEqual(back.draft.name, "")
        XCTAssertTrue(back.draft.sameContent(as: s.draft))
        XCTAssertNil(EditorSessionStore.decode("not json"))
        XCTAssertNil(EditorSessionStore.decode(nil))
    }
}
