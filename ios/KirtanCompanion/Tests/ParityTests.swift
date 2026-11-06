import Foundation
import XCTest

// The parity work brought over from Android: the served built-in set's row rules, the
// community-copy provenance (issue #11), deep-link parsing and the shipped-row writer.
// Mirrors Android's ShippedBeatsClientTest and LibraryJsonTest cases.

@MainActor
final class ParityTests: XCTestCase {

    // ── Served built-in rows ──────────────────────────────────────────────

    private func row(id: String = "dadra", ordinal: Int = 0, bpm: Double = 90,
                     groups: [Double] = [2, 2, 2, 2], dayan: String = "OXOXOXOX",
                     bayan: String? = "O---O---", extra: [(String, JValue)] = []) -> JValue {
        var lanes: [JPair] = [JPair(key: "dayan", value: .str(dayan))]
        if let bayan { lanes.append(JPair(key: "bayan", value: .str(bayan))) }
        var pairs: [(String, JValue)] = [
            ("id", .str(id)), ("ordinal", .int(ordinal)), ("heading", .str("Everyday")),
            ("name", .str(id.capitalized)), ("note", .str("4 beats")), ("bpm", .num(bpm)),
            ("groups", .arr(groups.map { .num($0) })), ("cpq", .int(2)),
            ("lanes", .object(lanes)), ("description", .null),
        ]
        pairs.append(contentsOf: extra)
        return .object(pairs.map { JPair(key: $0.0, value: $0.1) })
    }

    func testAValidSetParsesSortedByOrdinal() {
        let beats = shippedBeatsOf(.arr([row(id: "b", ordinal: 1), row(id: "a", ordinal: 0)]))
        XCTAssertEqual(beats?.map(\.id), ["a", "b"])
        XCTAssertEqual(beats?.first?.steps, 8)
        XCTAssertEqual(beats?.first?.group, "Everyday")
        XCTAssertNil(beats?.first?.pattern(.kartal), "a missing lane key is an absent lane, not rests")
    }

    func testOneBadRowRejectsTheWholeSet() {
        XCTAssertNil(shippedBeatsOf(.arr([row(id: "a"), row(id: "b", dayan: "OXO")])), "short pattern")
        XCTAssertNil(shippedBeatsOf(.arr([row(id: "a"), row(id: "b", dayan: "OXOXOXOZ")])), "unknown stroke")
        XCTAssertNil(shippedBeatsOf(.arr([row(id: "a"), row(id: "b", bayan: nil)])), "missing primary lane")
        XCTAssertNil(shippedBeatsOf(.arr([row(id: "a"), row(id: "a", ordinal: 1)])), "duplicate id")
        XCTAssertNil(shippedBeatsOf(.arr([row(bpm: 90.5)])), "fractional bpm")
        XCTAssertNil(shippedBeatsOf(.arr([row(bpm: 999)])), "bpm out of range")
        XCTAssertNil(shippedBeatsOf(.arr([])), "empty set")
        XCTAssertNil(shippedBeatsOf(.str("oops")), "error body")
    }

    func testTheCompiledSetRoundTripsThroughTheRowWriter() {
        // A promote/edit writes a row the read path accepts, and reads back the same beat.
        for (i, beat) in Beats.beats.enumerated() {
            let written = shippedRowFor(id: beat.id!, ordinal: i, heading: beat.group ?? "X", beat: beat,
                                        sourcePublishedId: nil, description: beat.description)
            let back = shippedBeatsOf(.arr([written]))?.first
            XCTAssertEqual(back?.lanePatterns, beat.lanePatterns, beat.id!)
            XCTAssertEqual(back?.steps, beat.steps, beat.id!)
            XCTAssertEqual(back?.bpm, beat.bpm, beat.id!)
        }
    }

    func testRevisionIsTheNewestTimestamp() {
        let rows: [JValue] = [
            .obj(("updated_at", .str("2026-01-02T00:00:00+00:00"))),
            .obj(("updated_at", .str("2026-03-01T00:00:00+00:00"))),
        ]
        XCTAssertEqual(shippedRevision(rows), "2026-03-01T00:00:00+00:00")
        XCTAssertEqual(shippedRevision([]), "")
    }

    func testSlugsAreStableAndNeverCollide() {
        XCTAssertEqual(shippedIdFor("Double Time!", taken: []), "double_time")
        XCTAssertEqual(shippedIdFor("Dadra", taken: ["dadra"]), "dadra_2")
        XCTAssertEqual(shippedIdFor("Dadra", taken: ["dadra", "dadra_2"]), "dadra_3")
        XCTAssertEqual(shippedIdFor("ॐ", taken: []), "beat")
    }

    // ── Issue #11: provenance travels with the stored beat ────────────────

    func testSourceItemIdRoundTripsThroughTheStoredBody() {
        var beat = Beats.beats[0]
        beat.id = "x"
        beat.sourceItemId = "item-123"
        let back = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))
        XCTAssertEqual(back?.sourceItemId, "item-123")

        beat.sourceItemId = nil
        let plain = LibraryJson.decodeBeat(LibraryJson.encodeStored(beat))
        XCTAssertNil(plain?.sourceItemId)
    }

    func testImportStampsTheSourceAndRepeatsAreVisible() async {
        let repo = LibraryRepository(initialProvider: MemoryProvider())
        await repo.reload()
        let beat = Beats.beats[1]
        let result = await repo.importShared(.beat(beat), sourceItemId: "pub-1")
        XCTAssertEqual(result.beats.first?.sourceItemId, "pub-1")
        XCTAssertEqual(Set(repo.state.customBeats.compactMap(\.sourceItemId)), ["pub-1"])

        // An edit keeps the provenance, so the community card still says "In your library".
        var edited = result.beats[0]
        edited.sourceItemId = nil
        edited.bpm = 100
        let saved = await repo.saveBeat(edited)
        XCTAssertEqual(saved?.sourceItemId, "pub-1")
    }

    func testRenameCategorySuffixesAClash() async {
        let repo = LibraryRepository(initialProvider: MemoryProvider())
        await repo.reload()
        let a = await repo.createCategory("Sunday")
        _ = await repo.createCategory("Evening")
        await repo.renameCategory(a!, "Evening")
        XCTAssertEqual(repo.state.categories.map(\.name).sorted(), ["Evening", "Evening (2)"])
        await repo.renameCategory(a!, "   ")
        XCTAssertTrue(repo.state.categories.contains { $0.name == "Evening (2)" }, "blank rename ignored")
    }

    // ── Deep links ────────────────────────────────────────────────────────

    func testBeatDeepLinksYieldTheirCode() {
        let code = ShareCodec.encodeBeat(Beats.beats[0])
        let url = URL(string: "kirtan://beat?c=\(code)")!
        XCTAssertEqual(ShareLinks.code(fromDeepLink: url), code)
        XCTAssertNotNil(ShareCodec.decodeShare(ShareLinks.code(fromDeepLink: url)))
        XCTAssertNil(ShareLinks.code(fromDeepLink: URL(string: "kirtan://auth/callback?code=1")!))
        XCTAssertEqual(ShareLinks.forCode("abc"), "kirtan://beat?c=abc", "no web base configured in tests")
    }

    // ── Ratings ───────────────────────────────────────────────────────────

    func testAverageIsNilUntilSomeoneRates() {
        var item = PublishedItem(id: "1", authorId: nil, authorName: nil, kind: .beat, name: "x",
                                 payload: .null, copies: 0, createdAt: nil)
        XCTAssertNil(item.averageStars)
        item.ratingSum = 9
        item.ratingCount = 2
        XCTAssertEqual(item.averageStars, 4.5)
    }
}

/// A provider that keeps everything in memory, so repository tests touch no file.
final class MemoryProvider: BeatsProvider {
    private var library = Library.empty

    func loadAll() async throws -> Library { library }

    func createBeat(_ draft: Beat) async throws -> Beat {
        var beat = draft
        beat.id = newId()
        library.beats.append(beat)
        return beat
    }

    func createBeats(_ drafts: [Beat]) async throws -> [Beat] {
        var out: [Beat] = []
        for draft in drafts { out.append(try await createBeat(draft)) }
        return out
    }

    func updateBeat(id: String, patch: Beat) async throws -> Beat {
        var beat = patch
        beat.id = id
        library.beats = library.beats.map { $0.id == id ? beat : $0 }
        return beat
    }

    func deleteBeat(id: String) async throws { library.beats.removeAll { $0.id == id } }

    func createCategory(_ draft: CategoryDraft) async throws -> Category {
        let category = Category(id: newId(), name: draft.name, beatIds: draft.beatIds)
        library.categories.append(category)
        return category
    }

    func updateCategory(id: String, patch: CategoryPatch) async throws -> Category {
        guard var category = library.categories.first(where: { $0.id == id }) else {
            throw StorageError(.notFound, "missing")
        }
        if let name = patch.name { category.name = name }
        if let ids = patch.beatIds { category.beatIds = ids }
        library.categories = library.categories.map { $0.id == id ? category : $0 }
        return category
    }

    func deleteCategory(id: String) async throws { library.categories.removeAll { $0.id == id } }

    func setActiveCategory(id: String) async throws { library.activeCategoryId = id }
}
