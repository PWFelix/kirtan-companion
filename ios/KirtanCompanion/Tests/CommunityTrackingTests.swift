import Foundation
import XCTest

// Community beats follow their originals, can't be re-shared, and an edited copy shares
// only once its music differs. Mirrors Android's CommunityTrackingTest.

final class CommunityTrackingTests: XCTestCase {

    /// The original, as read back from the server: no id, no local fields.
    private var original: Beat {
        var b = Beats.beats[0]
        b.id = nil; b.group = nil; b.name = "Their Beat"; b.tags = ["keherwa"]
        return b
    }

    /// The same beat in the library, renamed on import to keep names unique.
    private var added: Beat {
        var b = original
        b.id = "local-1"; b.name = "Their Beat (2)"; b.sourceItemId = "item-9"
        return b
    }

    private var items: [String: [Beat]] { ["item-9": [original]] }

    private var second: Beat {
        var b = Beats.beats[1]
        b.id = nil; b.group = nil
        return b
    }

    /// The beat with one stroke flipped: a musically different beat.
    private func edited(_ beat: Beat) -> Beat {
        var b = beat
        var cells = b.pattern(.dayan)!
        cells[1] = cells[1] == .open ? .closed : .open
        b.lanePatterns[.dayan] = cells
        return b
    }

    func testUnknownUntilLoaded() {
        XCTAssertEqual(CommunityTracking.status(added, nil), .unknown)
    }

    func testMatchingOriginalIsCurrentDespiteTheImportRename() {
        XCTAssertEqual(CommunityTracking.status(added, items), .current)
    }

    func testAuthorChangeIsAnUpdateAndApplyingItKeepsIdentity() {
        var newer = original
        newer.bpm += 6; newer.tags = ["keherwa", "kirtan"]
        guard case .updateAvailable(let upstream) = CommunityTracking.status(added, ["item-9": [newer]]) else {
            return XCTFail("expected an update")
        }
        let updated = CommunityTracking.applyUpdate(added, upstream)
        XCTAssertEqual(updated.id, "local-1")
        XCTAssertEqual(updated.name, "Their Beat (2)")
        XCTAssertEqual(updated.sourceItemId, "item-9")
        XCTAssertEqual(updated.bpm, newer.bpm)
        XCTAssertEqual(updated.tags, ["keherwa", "kirtan"])
        XCTAssertEqual(CommunityTracking.status(updated, ["item-9": [newer]]), .current)
    }

    func testMissingFromALoadedMapIsGone() {
        XCTAssertEqual(CommunityTracking.status(added, [:]), .gone)
    }

    func testListBeatsFollowTheirPosition() {
        var fromList = second
        fromList.id = "local-2"; fromList.sourceItemId = "list-1"; fromList.sourceIndex = 1
        XCTAssertEqual(CommunityTracking.status(fromList, ["list-1": [original, second]]), .current)
        XCTAssertEqual(CommunityTracking.status(fromList, ["list-1": [original]]), .gone)
    }

    func testCommunityBeatsAreReadOnlyAndNeverShared() {
        XCTAssertTrue(added.readOnly)
        let status = ShareIndex(originals: items).status(added)
        XCTAssertEqual(status, .notYours)
        XCTAssertFalse(status.canShare)
    }

    func testACopyIsShareableOnlyOnceTheMusicDiffers() {
        var copy = added
        copy.id = "local-3"; copy.sourceItemId = nil; copy.forkedFrom = "item-9"
        // Renamed and retagged is still their beat.
        var relabelled = copy
        relabelled.name = "Mine Now"; relabelled.tags = ["bhajan"]
        XCTAssertFalse(relabelled.readOnly)
        XCTAssertEqual(ShareIndex(originals: items).status(relabelled), .unchangedCopy)
        XCTAssertEqual(ShareIndex(originals: items).status(edited(copy)), .new)
        // Originals not loaded: don't block.
        XCTAssertEqual(ShareIndex().status(relabelled), .new)
    }

    func testMigrationKeepsUntouchedImportsAndForksEditedOnes() {
        XCTAssertEqual(CommunityTracking.migrate(added, [original]), .keep(sourceIndex: nil))
        XCTAssertEqual(CommunityTracking.migrate(edited(added), [original]), .fork)
        XCTAssertEqual(CommunityTracking.migrate(added, nil), .undecided)
        var legacyFromList = second
        legacyFromList.id = "local-4"; legacyFromList.sourceItemId = "list-1"
        XCTAssertEqual(CommunityTracking.migrate(legacyFromList, [original, second]), .keep(sourceIndex: 1))
    }

    func testNewFieldsRoundTripAndUntaggedBodiesAreUnchanged() {
        var beat = added
        beat.sourceIndex = 2; beat.forkedFrom = "item-1"; beat.tags = ["keherwa", "future-tag"]
        let back = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))!
        XCTAssertEqual(back.tags, beat.tags)
        XCTAssertEqual(back.sourceIndex, 2)
        XCTAssertEqual(back.forkedFrom, "item-1")
        var untagged = original
        untagged.tags = []
        XCTAssertNil(LibraryJson.encodeBody(untagged).element("tags"))
    }

    func testTagFilterIsAJsonContainmentTest() {
        XCTAssertTrue(tagFilter(nil).isEmpty)
        let filter = tagFilter("dadra")
        XCTAssertEqual(filter.count, 1)
        XCTAssertEqual(filter[0].0, "payload->beat->tags")
        XCTAssertEqual(filter[0].1, "cs.[\"dadra\"]")
    }

    func testVocabularyKnownDropsUnknownAndKeepsOrder() {
        XCTAssertEqual(TagVocabulary.known(["kirtan", "nope", "keherwa"]).map(\.id), ["keherwa", "kirtan"])
    }
}
