import Foundation

// The PUBLIC side of the backend: the community library the Browse screen shows.
//
// Separate from BeatsProvider for the same reason it is there: this is a different surface
// with different rules — ANYONE MAY READ IT (the published_beats select policy is `using
// (true)`), only the author may write — so it is not per-user storage and does not belong
// behind the provider seam.
//
// A published item is a SNAPSHOT: publishing copies the whole shareable body into the row's
// `payload`, so editing your private beat afterwards never rewrites what the community
// already downloaded. That is why this takes and returns whole beats, and why toImportPayload
// hands back exactly the type a scanned share link decodes to — copying a community beat into
// the library is ONE code path with importing a deep link.

final class CommunityClient {

    private let client: SupabaseClient
    private let log: StorageLog

    private static let table = "published_beats"
    // The web app's browse column set — proven against the live project. `payload` carries
    // the snapshot so a card can preview the real pattern without a second request.
    private static let columns = "id, author_id, author_name, kind, name, payload, copies, rating_sum, rating_count, created_at"
    private static let ratings = "published_ratings"
    private static let anonymousAuthor = "A devotee"
    private static let defaultLimit = 40

    init(client: SupabaseClient, log: StorageLog = OSStorageLog()) {
        self.client = client
        self.log = log
    }

    /// What a community search matches against. Author search is a first-class scope rather
    /// than always matching both, because "everything" makes a common surname drown the
    /// library: searching for a beat called "Dadra" should not return every beat by someone
    /// whose name contains it.
    enum BrowseScope { case everything, names, authors }

    // The community list, newest first. Reads need no identity — RLS already allows the
    // world — so this is the one call that does not require a session.
    func browse(query: String = "", scope: BrowseScope = .everything, limit: Int = CommunityClient.defaultLimit) async throws -> [PublishedItem] {
        let rows = try await client.select(
            table: Self.table, columns: Self.columns,
            filters: browseFilters(query: query, scope: scope),
            order: "created_at.desc", limit: limit, requireSession: false,
            whileDoing: "load the community library",
        )
        return (rows?.asArray() ?? []).compactMap { itemOf($0) }
    }

    // Publish a beat or a playlist. Takes the same SharePayload the share sheet builds,
    // because "share by link" and "share to the library" are the same act with different
    // transports. The ids are stripped before upload: a published snapshot must not hand a
    // recipient an id that names one of the author's private beats.
    func publish(_ payload: ShareCodec.SharePayload, authorId: String, authorName: String?) async throws -> PublishedItem {
        let kind: PublishedKind
        let name: String
        let body: JValue
        switch payload {
        case .beat(let beat):
            kind = .beat; name = beat.name
            body = JValue.object([JPair(key: "beat", value: LibraryJson.encodeBody(beatWithoutId(beat)))])
        case .category(let catName, let beats):
            kind = .playlist; name = catName
            body = JValue.object([
                JPair(key: "name", value: .str(catName)),
                JPair(key: "beats", value: JValue.arr(beats.map { LibraryJson.encodeBody(beatWithoutId($0)) })),
            ])
        case .invalid:
            throw StorageError(.unknown, "An invalid share payload cannot be published.")
        }
        let row = JValue.object([
            JPair(key: "author_id", value: .str(authorId)),
            JPair(key: "author_name", value: .str(authorName?.isEmpty == false ? authorName! : Self.anonymousAuthor)),
            JPair(key: "kind", value: .str(kind.wireId)),
            JPair(key: "name", value: .str(name)),
            JPair(key: "payload", value: body),
        ])
        let created = try await client.insert(table: Self.table, body: row, one: true, whileDoing: "publish that")
        guard let item = itemOf(created) else {
            throw StorageError(.unknown, "The server didn't return the beat you just published.")
        }
        return item
    }

    // Everything the signed-in user has published, so they can unpublish it.
    func myPublished(authorId: String) async throws -> [PublishedItem] {
        let rows = try await client.select(
            table: Self.table, columns: Self.columns,
            filters: [("author_id", "eq.\(authorId)")], order: "created_at.desc",
            whileDoing: "load your shared beats",
        )
        return (rows?.asArray() ?? []).compactMap { itemOf($0) }
    }

    // Remove a published item. RLS lets only its author through.
    func unpublish(_ id: String) async throws {
        _ = try await client.delete(table: Self.table, filters: [("id", "eq.\(id)")], whileDoing: "unpublish that")
    }

    // Bump the copy counter when someone adds a community item. BEST-EFFORT, and the one
    // method that swallows a failure: the beat is already in the user's library by the time
    // this runs, and a count that didn't move is not worth telling them their import failed.
    func incrementCopies(_ id: String) async {
        do {
            try await client.rpc(function: "increment_published_copies", args: JValue.object([JPair(key: "pub_id", value: .str(id))]), whileDoing: "count that copy")
        } catch {
            log.warn("[community] copy count not bumped for \(id)", error)
        }
    }

    /// Set or clear the signed-in user's rating on an item. Upsert on (item_id, user_id): one
    /// rating per person per item. Nil CLEARS — tapping your own star again takes it back,
    /// and clearing is a delete rather than a zero, which would drag the average down. The
    /// parent row's counters are kept by a database trigger.
    func rate(itemId: String, userId: String, stars: Int?) async throws {
        guard let stars else {
            _ = try await client.delete(
                table: Self.ratings,
                filters: [("item_id", "eq.\(itemId)"), ("user_id", "eq.\(userId)")],
                whileDoing: "remove that rating",
            )
            return
        }
        precondition((1...5).contains(stars), "a rating is 1 to 5 stars")
        _ = try await client.upsert(
            table: Self.ratings,
            body: JValue.object([
                JPair(key: "item_id", value: .str(itemId)),
                JPair(key: "user_id", value: .str(userId)),
                JPair(key: "stars", value: .int(stars)),
            ]),
            onConflict: "item_id,user_id",
            whileDoing: "save that rating",
        )
    }

    /// The signed-in user's own stars per item, so their stars show what they chose rather
    /// than the community average. One query for the whole page.
    func myRatings(userId: String, itemIds: [String]) async throws -> [String: Int] {
        guard !itemIds.isEmpty else { return [:] }
        let rows = try await client.select(
            table: Self.ratings, columns: "item_id,stars",
            filters: [("user_id", "eq.\(userId)"), ("item_id", "in.(\(itemIds.joined(separator: ",")))")],
            whileDoing: "load your ratings",
        )
        var out: [String: Int] = [:]
        for row in rows?.asArray() ?? [] {
            guard let id = row.element("item_id")?.asString(),
                  let stars = row.element("stars")?.asNumber() else { continue }
            out[id] = Int(stars)
        }
        return out
    }

    /// A published row as the payload LibraryRepository.importShared consumes. Nil when the
    /// row's snapshot will not decode — an item published by a future app version, or a
    /// damaged payload. Ids are stripped on the way out.
    func toImportPayload(_ item: PublishedItem) -> ShareCodec.SharePayload? {
        switch item.kind {
        case .beat:
            guard let beat = LibraryJson.decodeBeat(item.payload.element("beat")) else { return nil }
            return .beat(beatWithoutId(beat))
        case .playlist:
            guard let beatsArray = item.payload.element("beats")?.asArray() else { return nil }
            var beats: [Beat] = []
            for element in beatsArray {
                guard let beat = LibraryJson.decodeBeat(element) else { return nil }  // one bad beat refuses the whole playlist
                beats.append(beatWithoutId(beat))
            }
            guard !beats.isEmpty else { return nil }
            let rawName = item.payload.element("name")?.asString()?.trimmingCharacters(in: .whitespaces)
            let name = (rawName?.isEmpty == false ? rawName : nil) ?? item.name
            return .category(name: name, beats: beats)
        }
    }

    // The snapshot's beats never carry the author's private ids.
    private func beatWithoutId(_ beat: Beat) -> Beat {
        var copy = beat
        copy.id = nil
        // Publishing a copy makes a NEW item; the old item's id would make other
        // people's "In your library" wrong.
        copy.sourceItemId = nil
        return copy
    }

    // One row as a PublishedItem, or nil when it is not shaped like one.
    private func itemOf(_ row: JValue?) -> PublishedItem? {
        let id = row?.element("id")?.asString()
        let kind = PublishedKind.fromWire(row?.element("kind")?.asString())
        guard let id, let kind else { return nil }
        return PublishedItem(
            id: id,
            authorId: row?.element("author_id")?.asString(),
            authorName: row?.element("author_name")?.asString(),
            kind: kind,
            name: row?.element("name")?.asString() ?? "",
            payload: row?.element("payload") ?? JValue.object([]),
            copies: row?.element("copies")?.asNumber().map { Int($0) } ?? 0,
            ratingSum: row?.element("rating_sum")?.asNumber().map { Int($0) } ?? 0,
            ratingCount: row?.element("rating_count")?.asNumber().map { Int($0) } ?? 0,
            createdAt: parseTimestamp(row?.element("created_at")?.asString()),
        )
    }
}

// Whether a published snapshot holds one beat or a whole progression.
enum PublishedKind: Equatable {
    case beat
    case playlist

    var wireId: String { self == .beat ? "beat" : "playlist" }
    static func fromWire(_ wire: String?) -> PublishedKind? {
        switch wire { case "beat": return .beat; case "playlist": return .playlist; default: return nil }
    }
}

// One row of the community library, as the table holds it.
struct PublishedItem: Identifiable {
    let id: String
    let authorId: String?
    let authorName: String?
    let kind: PublishedKind
    let name: String
    /// The snapshot: `{ beat }` or `{ name, beats }`, in LibraryJson's shape.
    let payload: JValue
    let copies: Int
    /// Sum of all stars, maintained by a database trigger.
    var ratingSum: Int = 0
    /// How many people rated it; zero means "nobody yet", not "zero stars".
    var ratingCount: Int = 0
    /// Epoch seconds, or nil when the server's timestamp could not be read.
    let createdAt: Int?

    /// The community's average, or nil when nobody has rated it yet.
    var averageStars: Double? { ratingCount > 0 ? Double(ratingSum) / Double(ratingCount) : nil }
}

// The PostgREST filters for a community search. Pure so the wiring is testable: EVERYTHING
// is a single `or=(…)` rather than two queries unioned client-side, and an empty query means
// "no filters" rather than a filter that matches nothing.
func browseFilters(query: String, scope: CommunityClient.BrowseScope) -> [(String, String)] {
    let trimmed = query.trimmingCharacters(in: .whitespaces)
    guard !trimmed.isEmpty else { return [] }
    let like = "ilike.%\(trimmed)%"
    switch scope {
    case .names: return [("name", like)]
    case .authors: return [("author_name", like)]
    case .everything: return [("or", "(name.\(like),author_name.\(like))")]
    }
}

// A Postgres `timestamptz` as epoch seconds, or nil. PostgREST sends an offset (`+00:00`)
// rather than a `Z`; a missing or unparseable timestamp just sorts to the back and nothing
// else, so nil is the right answer for the failure rather than a throw.
func parseTimestamp(_ text: String?) -> Int? {
    guard let text, !text.isEmpty else { return nil }
    for formatter in [isoWithFraction, isoWithoutFraction] {
        if let date = formatter.date(from: text) {
            return Int(date.timeIntervalSince1970)
        }
    }
    return nil
}

private let isoWithFraction: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

private let isoWithoutFraction: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
}()
