import Foundation
import Observation

// The built-in beat set as served by the database, and the cache that stands in for it.
//
// Ported from storage/ShippedBeatsClient.kt. `shipped_beats` is canonical: the two people
// who maintain the set edit rows, and every installed app picks the change up on its next
// launch. Beats.beats — compiled in, identical to the seed — is what plays with no network.
//
// ── READS NEED NO IDENTITY ──
// requireSession false, like CommunityClient.browse: the built-ins are the one thing a
// signed-out user must still get.
//
// ── VALIDATION IS ALL OR NOTHING ──
// One unusable row rejects the WHOLE set, which then falls back to the cache, then to the
// compiled list. Playlists store built-in ids and the Beats screen takes its headings from
// the set's order, so a set with one beat silently missing is worse than an old set.
//
// ── THE CACHE HOLDS ROWS, NOT BEATS ──
// Written verbatim and validated again on the way back in, so a cache left by an older
// build cannot smuggle a beat past today's checks.

enum ShippedSource { case compiled, cached, server }

/// What the app knows about the built-in set, for the Beats screen's one line about it.
/// `notice` is set only by a check the USER asked for; a launch is not a conversation.
struct ShippedStatus: Equatable {
    var source: ShippedSource = .compiled
    var count: Int = Beats.beats.count
    var checking = false
    var checkedAt: Date? = nil
    var notice: String? = nil
}

@MainActor
@Observable
final class ShippedBeatsClient {

    private static let table = "shipped_beats"
    private static let maintainers = "maintainers"
    // Named rather than `*`, and the same list Android and the web select, so all three
    // caches hold the same bytes for the same table state.
    private static let columns =
        "id, ordinal, heading, name, note, bpm, groups, cpq, lanes, description, source_published_id, updated_at"
    static let rowsKey = "shipped_rows"
    static let revisionKey = "shipped_revision"

    @ObservationIgnored private let client: SupabaseClient
    @ObservationIgnored private let store: KirtanStore
    @ObservationIgnored private let log: StorageLog

    private(set) var status = ShippedStatus()

    init(client: SupabaseClient, store: KirtanStore, log: StorageLog = OSStorageLog()) {
        self.client = client
        self.store = store
        self.log = log
    }

    /// Adopt the cached rows, if they still validate. Runs before the first network answer
    /// so a returning user sees the set they last had. A damaged cache says nothing.
    func loadCached() {
        guard let text = store.string(Self.rowsKey),
              let beats = shippedBeatsOf(JsonEl.parse(text)) else { return }
        ShippedBeats.shared.install(beats)
        status.source = .cached
        status.count = beats.count
    }

    /// Fetch the server's set and adopt it. Never throws: a failed lookup leaves the app
    /// playing what it already had. `manual` only decides whether the user hears about it.
    func checkForUpdates(manual: Bool) async {
        status.checking = true
        status.notice = nil
        let previous = ShippedBeats.shared.effective
        let fetched: [Beat]?
        do {
            fetched = try await refetch()
        } catch {
            log.warn("[shipped] the server's beat set could not be read", error)
            status.checking = false
            status.checkedAt = Date()
            status.notice = manual ? storageErrorMessage(error) : nil
            return
        }
        let beats = ShippedBeats.shared.effective
        var notice: String? = nil
        if manual {
            if fetched == nil {
                notice = String(localized: "The server sent a beat set this app couldn't use, so nothing changed.", bundle: .strings)
            } else if beats == previous {
                notice = String(localized: "Already up to date · \(beats.count) beats", bundle: .strings)
            } else {
                notice = String(localized: "Built-in beats updated · \(beats.count) beats", bundle: .strings)
            }
        }
        status.checking = false
        status.checkedAt = Date()
        status.count = beats.count
        status.notice = notice
    }

    /// Pull, validate, install and cache. Nil when the answer was unusable — the installed
    /// set stays and nothing is cached over it.
    @discardableResult
    private func refetch() async throws -> [Beat]? {
        let body = try await client.select(
            table: Self.table, columns: Self.columns, order: "ordinal.asc",
            requireSession: false, whileDoing: String(localized: "load the built-in beats", bundle: .strings),
        )
        guard let rows = body?.asArray() else { return nil }
        guard let beats = shippedBeatsOf(.array(rows)) else {
            log.warn("[shipped] the server's beat set was rejected: \(rows.count) rows", nil)
            return nil
        }
        ShippedBeats.shared.install(beats)
        status.source = .server
        status.count = beats.count
        // Only rewrite the blob when the set moved: every launch checks.
        let revision = shippedRevision(rows)
        if revision != store.string(Self.revisionKey) {
            store.setString(Self.rowsKey, JValue.array(rows).write())
            store.setString(Self.revisionKey, revision)
        }
        return beats
    }

    /// True when the signed-in user may change the built-in set. `maintainers` only lets
    /// you see your own row, so a non-maintainer gets an empty list. Fails CLOSED: this
    /// only decides whether a button shows; the database decides whether the write lands.
    func isMaintainer() async -> Bool {
        do {
            let rows = try await client.select(
                table: Self.maintainers, columns: "user_id", limit: 1,
                whileDoing: String(localized: "check whether you may edit the built-in beats", bundle: .strings),
            )
            return !(rows?.asArray() ?? []).isEmpty
        } catch {
            log.warn("[shipped] the maintainer check failed", error)
            return false
        }
    }

    /// Make a community beat one of the built-ins, for everyone. The id and ordinal are
    /// chosen against a FRESH read of the table: the write is an upsert, so an id picked
    /// from a stale list could silently overwrite a beat that ships to everybody.
    func promote(item: PublishedItem, beat: Beat, heading: String) async throws {
        let current = try await currentRows()
        let row = try checkedRow(
            shippedRowFor(
                id: shippedIdFor(beat.name, taken: current.ids), ordinal: current.nextOrdinal,
                heading: heading, beat: beat, sourcePublishedId: item.id, description: beat.description,
            ),
            refusal: String(localized: "That beat can't become a built-in one — its name or its pattern is outside what the built-in set allows.", bundle: .strings),
        )
        _ = try await client.upsert(table: Self.table, body: row, onConflict: "id", whileDoing: String(localized: "make that a built-in beat", bundle: .strings))
        try await refetch()
    }

    /// Write a corrected beat back onto the row it came from. The id is NEVER re-slugged,
    /// even on rename: playlists store built-in ids. The row is read first because
    /// ordinal/heading/description/provenance don't survive the editor, and the write is a
    /// filtered UPDATE (not an upsert) so a beat retired meanwhile cannot come back.
    func updateShippedBeat(_ edited: Beat) async throws {
        guard let id = edited.id else {
            throw StorageError(.unknown, String(localized: "That beat has no id, so there is no built-in row to update.", bundle: .strings))
        }
        guard let meta = try await currentRow(id) else { throw Self.notInTheSet() }
        let row = try checkedRow(
            shippedRowFor(
                id: id, ordinal: meta.ordinal, heading: meta.heading, beat: edited,
                sourcePublishedId: meta.sourcePublishedId, description: meta.description,
            ),
            refusal: String(localized: "That edit can't be saved — its name or its pattern is outside what the built-in set allows.", bundle: .strings),
        )
        let written = try await client.update(
            table: Self.table, body: row, filters: [("id", "eq.\(id)")], whileDoing: String(localized: "save that for everyone", bundle: .strings),
        )
        if (written?.asArray() ?? []).isEmpty { throw Self.notInTheSet() }
        try await refetch()
    }

    /// Retire one built-in for everyone. Its id stays addressable by playlists, so a
    /// progression that used it keeps working.
    func removeShippedBeat(_ beat: Beat) async throws {
        guard let id = beat.id else {
            throw StorageError(.unknown, String(localized: "That beat has no id, so there is no built-in row to remove.", bundle: .strings))
        }
        guard try await currentRow(id) != nil else { throw Self.notInTheSet() }
        let deleted = try await client.delete(
            table: Self.table, filters: [("id", "eq.\(id)")], returning: true,
            whileDoing: String(localized: "remove that from the built-in beats", bundle: .strings),
        )
        if (deleted?.asArray() ?? []).isEmpty { throw Self.notInTheSet() }
        try await refetch()
    }

    private static func notInTheSet() -> StorageError {
        StorageError(.notFound, String(localized: "That beat isn't in the built-in set any more, so there was nothing to update.", bundle: .strings))
    }

    private func currentRow(_ id: String) async throws -> ShippedRowMeta? {
        let body = try await client.select(
            table: Self.table, columns: "ordinal, heading, description, source_published_id",
            filters: [("id", "eq.\(id)")], limit: 1, requireSession: false,
            whileDoing: String(localized: "load the built-in beats", bundle: .strings),
        )
        guard let row = body?.asArray()?.first,
              let ordinal = wholeNumber(row.element("ordinal")),
              let heading = row.element("heading")?.asString() else { return nil }
        return ShippedRowMeta(
            ordinal: ordinal, heading: heading,
            description: row.element("description")?.asString(),
            sourcePublishedId: row.element("source_published_id")?.asString(),
        )
    }

    /// Refuse to write a row the READ path would reject: one unparseable row takes every
    /// user back to the compiled fallback.
    private func checkedRow(_ row: JValue, refusal: String) throws -> JValue {
        if shippedBeatsOf(.array([row])) == nil { throw StorageError(.unknown, refusal) }
        return row
    }

    private func currentRows() async throws -> (ids: Set<String>, nextOrdinal: Int) {
        let body = try await client.select(
            table: Self.table, columns: "id, ordinal", requireSession: false,
            whileDoing: String(localized: "load the built-in beats", bundle: .strings),
        )
        let rows = body?.asArray() ?? []
        let ids = Set(rows.compactMap { $0.element("id")?.asString() }.filter { !$0.isEmpty })
        let highest = rows.compactMap { wholeNumber($0.element("ordinal")) }.max()
        return (ids, (highest ?? -1) + 1)
    }
}

// ── Pure row parsing (testable against a literal) ───────────────────────────

struct ShippedRowMeta: Equatable {
    let ordinal: Int
    let heading: String
    let description: String?
    let sourcePublishedId: String?
}

/// A whole server set as beats, sorted by ordinal, or nil when ANY part is unusable.
func shippedBeatsOf(_ rows: JValue?) -> [Beat]? {
    guard let array = rows?.asArray(), !array.isEmpty else { return nil }
    var parsed: [(Int, Beat)] = []
    var ids = Set<String>()
    for element in array {
        guard let row = shippedRowOf(element), let id = row.1.id else { return nil }
        if !ids.insert(id).inserted { return nil }
        parsed.append(row)
    }
    return parsed.sorted { $0.0 < $1.0 }.map { $0.1 }
}

/// The newest `updated_at`, compared as text (PostgREST renders timestamptz in one fixed
/// ISO form, so lexicographic order is chronological).
func shippedRevision(_ rows: [JValue]) -> String {
    rows.compactMap { $0.element("updated_at")?.asString() }.max() ?? ""
}

/// A whole-number JSON value; a real fraction is refused rather than rounded, as on
/// Android and the web, so the platforms cannot serve different sets from one table.
func wholeNumber(_ v: JValue?) -> Int? {
    guard let d = v?.asNumber(), d.isFinite, d == d.rounded() else { return nil }
    return Int(d)
}

private func shippedRowOf(_ row: JValue) -> (Int, Beat)? {
    guard case .object = row,
          let id = row.element("id")?.asString(), !id.trimmingCharacters(in: .whitespaces).isEmpty,
          let heading = row.element("heading")?.asString(),
          let name = row.element("name")?.asString(), !name.trimmingCharacters(in: .whitespaces).isEmpty,
          let ordinal = wholeNumber(row.element("ordinal")),
          let note = row.element("note")?.asString(),
          let bpm = wholeNumber(row.element("bpm")), (MIN_BPM...MAX_BPM).contains(bpm),
          let cpq = wholeNumber(row.element("cpq")), (1...ShareCodec.MAX_CPQ).contains(cpq),
          let groupsArray = row.element("groups")?.asArray(),
          !groupsArray.isEmpty, groupsArray.count <= ShareCodec.MAX_GROUPS,
          case .object = row.element("lanes") ?? .null
    else { return nil }

    var groups: [Int] = []
    for g in groupsArray {
        guard let cells = wholeNumber(g), (1...ShareCodec.MAX_GROUP_CELLS).contains(cells) else { return nil }
        groups.append(cells)
    }
    let steps = sumGroups(groups)
    guard (1...ShareCodec.MAX_STEPS).contains(steps) else { return nil }

    let lanes = row.element("lanes")!
    var patterns: [LaneId: [Stroke?]] = [:]
    // Read by lane id, so an unknown key can never become a lane. A missing key is an
    // ABSENT lane (no cymbals), not a lane of rests.
    for lane in LaneId.ordered {
        guard let raw = lanes.element(lane.wireId) else { continue }
        guard let text = raw.asString(), let cells = shippedCells(text, steps: steps) else { return nil }
        patterns[lane] = cells
    }
    if LaneId.ordered.contains(where: { $0.isPrimary && patterns[$0] == nil }) { return nil }

    var description: String? = nil
    if let d = row.element("description"), !d.isNull() {
        guard let s = d.asString() else { return nil }
        description = s
    }

    // Derived exactly as builtIn() and ShareCodec derive them, so a served beat equals the
    // compiled one with the same row.
    let uniform = groups.allSatisfy { $0 == groups[0] }
    return (ordinal, Beat(
        id: id, name: name, note: note, bpm: bpm, steps: steps,
        beatsPerBar: Double(steps) / Double(cpq),
        cellsPerGroup: uniform ? groups[0] : cpq,
        groups: groups, description: description, lanePatterns: patterns, group: heading,
    ))
}

/// Compact lane notation as cells; nil unless exactly `steps` of O/X/-. An unknown
/// character is refused, never read as a rest: a typo would play wrong but look right.
private func shippedCells(_ notation: String, steps: Int) -> [Stroke?]? {
    guard notation.count == steps else { return nil }
    var cells: [Stroke?] = []
    for ch in notation {
        let s = String(ch)
        let cell = Stroke.fromCode(s)
        if cell == nil && s != ShareCodec.REST_CHAR { return nil }
        cells.append(cell)
    }
    return cells
}

// ── Pure row writing ───────────────────────────────────────────────────────

/// A beat's slug for the primary key. Stable by requirement (playlists store it), and
/// suffixed on collision because the write is an upsert and would otherwise REPLACE.
func shippedIdFor(_ name: String, taken: Set<String>) -> String {
    var slug = name.lowercased()
        .replacingOccurrences(of: "[^a-z0-9]+", with: "_", options: .regularExpression)
        .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
    slug = String(slug.prefix(48))
    while slug.hasSuffix("_") { slug.removeLast() }
    if slug.isEmpty { slug = "beat" }
    if !taken.contains(slug) { return slug }
    var suffix = 2
    while taken.contains("\(slug)_\(suffix)") { suffix += 1 }
    return "\(slug)_\(suffix)"
}

/// The row a promote or an edit writes. `note` is derived (the editor stamps "Custom"),
/// absent lanes are omitted rather than sent as rests, and the nullable columns are
/// written explicitly so an upsert cannot leave a previous row's prose behind.
func shippedRowFor(id: String, ordinal: Int, heading: String, beat: Beat, sourcePublishedId: String?, description: String?) -> JValue {
    let groups = groupsFor(beat)
    var lanes: [JPair] = []
    for lane in LaneId.ordered {
        let notation = BeatSourceExport.patternNotation(beat, lane)
        if !notation.isEmpty { lanes.append(JPair(key: lane.wireId, value: .str(notation))) }
    }
    return .obj(
        ("id", .str(id)),
        ("ordinal", .int(ordinal)),
        ("heading", .str(heading)),
        ("name", .str(beat.name)),
        ("note", .str("\(groups.count) beats")),
        ("bpm", .int(beat.bpm)),
        ("groups", .arr(groups.map { .int($0) })),
        ("cpq", .int(cpqFor(beat))),
        ("lanes", .object(lanes)),
        ("description", description.map { .str($0) } ?? .null),
        ("source_published_id", sourcePublishedId.map { .str($0) } ?? .null),
    )
}
