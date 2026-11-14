import Foundation
import XCTest

// What Share says for a beat: Share, Already shared, or Share updated version.
// Mirrors Android's ShareStatusTest.

final class ShareStatusTests: XCTestCase {

    private var mine: Beat {
        var b = Beats.beats[0]
        b.id = "local-1"; b.name = "My Beat"; b.sourceItemId = nil
        return b
    }
    /// How it reads back from the server: no id.
    private var published: Beat { var b = mine; b.id = nil; return b }

    func testNotPublishedIsNew() {
        XCTAssertEqual(ShareIndex().status(mine), .new)
    }

    func testLinkedAndUnchangedIsShared() {
        let index = ShareIndex(mine: [(id: "item-9", beat: published)], links: ["local-1": "item-9"])
        XCTAssertEqual(index.status(mine), .shared)
    }

    func testLinkedAndEditedSinceIsChanged() {
        let index = ShareIndex(mine: [(id: "item-9", beat: published)], links: ["local-1": "item-9"])
        var faster = mine; faster.bpm += 4
        XCTAssertEqual(index.status(faster), .changed(itemId: "item-9"))
        var renamed = mine; renamed.name = "Renamed"
        XCTAssertEqual(index.status(renamed), .changed(itemId: "item-9"))
    }

    func testUnlinkedButIdenticalContentIsShared() {
        XCTAssertEqual(ShareIndex(mine: [(id: "item-9", beat: published)]).status(mine), .shared)
    }

    func testLinkToAnUnpublishedItemFallsBackToNew() {
        XCTAssertEqual(ShareIndex(links: ["local-1": "item-9"]).status(mine), .new)
    }

    func testAServerRoundTripStillReadsAsShared() {
        // The server's echo is decoded, so any normalisation in decodeBeat would make
        // every shared beat look edited.
        var grouped = mine; grouped.id = "g"; grouped.groups = [3, 3, 2]
        for var beat in Beats.beats + [grouped] {
            if beat.id == nil { beat.id = "l-\(beat.name)" }
            let wire = ShareIndex.fingerprint(beat)
            guard var back = LibraryJson.decodeBeat(wire) else { return XCTFail("decode \(beat.name)") }
            back.id = nil
            XCTAssertEqual(ShareIndex(mine: [(id: "x", beat: back)]).status(beat), .shared, beat.name)
        }
    }
}
