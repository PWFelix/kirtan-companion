import Foundation
import XCTest

// Rhythm notes ride on the beat's description: the editor's draft carries them, counts
// them as an edit, and keeps them across a relaunch; share links leave them out.
final class RhythmNotesTests: XCTestCase {
    private func beat(_ notes: String?) -> Beat {
        let cells: [Stroke?] = [.open, nil, .closed, nil, .open, nil, .closed, nil]
        return Beat(id: "n", name: "Noted", note: "", bpm: 90, steps: 8, beatsPerBar: 4, cellsPerGroup: 2,
                    groups: [2, 2, 2, 2], description: notes,
                    lanePatterns: [.dayan: cells, .bayan: cells], group: nil)
    }

    func testDraftSeedsFromTheDescriptionAndCountsChanges() {
        let seeded = EditorSnapshot.seeded(from: beat("Ring finger on na."))
        XCTAssertEqual(seeded.notes, "Ring finger on na.")
        var same = seeded
        same.notes += "  \n"
        XCTAssertTrue(same.sameContent(as: seeded))
        var changed = seeded
        changed.notes = "From Vrindavan."
        XCTAssertFalse(changed.sameContent(as: seeded))
        let session = EditorSession(mode: .new, draft: changed)
        XCTAssertEqual(EditorSessionStore.decode(EditorSessionStore.encode(session))?.draft.notes, "From Vrindavan.")
    }

    func testShareLinksLeaveTheNotesOut() {
        XCTAssertEqual(ShareCodec.encodeBeat(beat("A long history…")), ShareCodec.encodeBeat(beat(nil)))
    }

    func testThePracticeGroupHoldsTheThreeExercises() {
        let g = TagVocabulary.groups.first { $0.id == "practice" }
        XCTAssertEqual(g?.tags.map(\.id), ["exercise-beginner", "exercise-intermediate", "exercise-advanced"])
    }
}
