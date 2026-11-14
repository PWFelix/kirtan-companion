import Foundation
import XCTest

// Swing: which cells play late, how late, and that the setting survives storage, the
// editor's draft, and share links — while a straight beat's forms stay as they were,
// so older apps read them unchanged.
final class SwingTests: XCTestCase {

    func testSecondCellOfEachEvenBeatSwingsAndOddBeatsStayStraight() {
        XCTAssertEqual(Swing.offbeats([2, 2, 3, 4]),
                       [false, true, false, true, false, false, false, false, true, false, true])
    }

    func testLatenessRunsFromNoneToAThirdOfACell() {
        XCTAssertEqual(Swing.lateFraction(50), 0, accuracy: 1e-9)
        XCTAssertEqual(Swing.lateFraction(67), 0.34, accuracy: 1e-9)
        XCTAssertEqual(Swing.lateFraction(90), 0.34, accuracy: 1e-9)   // clamped
        XCTAssertEqual(Swing.clamp(10), Swing.straight)
    }

    private func beat(swing: Int) -> Beat {
        var b = Beats.beats[0]
        b.swing = swing
        return b
    }

    func testSwingSurvivesStorageAndStraightIsWrittenAsBefore() {
        let stored = LibraryJson.encodeStored(beat(swing: 60))
        XCTAssertEqual(LibraryJson.decodeBeat(stored)?.swing, 60)
        XCTAssertNil(LibraryJson.encodeStored(beat(swing: 50)).element("swing"))
    }

    func testSwingRidesShareLinksAsW() {
        guard case .some(.beat(let shared)) = ShareCodec.decodeShare(ShareCodec.encodeBeat(beat(swing: 62))) else {
            return XCTFail("did not decode")
        }
        XCTAssertEqual(shared.swing, 62)
        guard case .some(.beat(let straight)) = ShareCodec.decodeShare(ShareCodec.encodeBeat(beat(swing: 50))) else {
            return XCTFail("did not decode")
        }
        XCTAssertEqual(straight.swing, Swing.straight)
        XCTAssertGreaterThan(ShareCodec.encodeBeat(beat(swing: 62)).count, ShareCodec.encodeBeat(beat(swing: 50)).count)
    }

    func testEditorDraftCarriesSwingAndCountsItAsAnEdit() {
        let seeded = EditorSnapshot.seeded(from: beat(swing: 58))
        XCTAssertEqual(seeded.swing, 58)
        var changed = seeded
        changed.swing = 50
        XCTAssertFalse(changed.sameContent(as: seeded))
        let session = EditorSession(mode: .new, draft: seeded)
        XCTAssertEqual(EditorSessionStore.decode(EditorSessionStore.encode(session))?.draft.swing, 58)
    }
}
