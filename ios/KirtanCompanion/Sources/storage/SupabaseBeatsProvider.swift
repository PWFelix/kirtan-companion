import Foundation

// The BeatsProvider backed by Supabase — the cloud twin of LocalBeatsProvider, implementing
// the SAME contract so LibraryRepository cannot tell them apart.
//
// IT IS CREATED WITH THE SIGNED-IN USER'S ID, because inserts must stamp `user_id` for the
// Row-Level Security check to pass (`auth.uid() = user_id`). Reads do not need the id — RLS
// already hides other people's rows — but every query filters by it anyway, to hit the index
// and to read the same way it writes.
//
// A beat's whole body lives in the `data` JSONB column; `name` is duplicated into its own
// column purely so the database can sort and search on it. A row becomes an app beat as "the
// column id, plus the blob" — which is why toBeat copies the id over whatever the blob
// claimed, exactly as the web provider's `{ id: row.id, ...row.data }` does.

final class SupabaseBeatsProvider: BeatsProvider {

    private let client: SupabaseClient
    private let userId: String

    private var ownerFilter: [(String, String)] { [("user_id", "eq.\(userId)")] }

    init(client: SupabaseClient, userId: String) {
        self.client = client
        self.userId = userId
    }

    // The provider for the signed-in user, or NULL when the cloud is not available — no
    // project configured in this build, or nobody signed in. Null is the signal
    // LibraryRepository stays on LocalBeatsProvider for.
    static func create(client: SupabaseClient?, session: AuthSession?) -> BeatsProvider? {
        guard let session, let client else { return nil }
        return SupabaseBeatsProvider(client: client, userId: session.userId)
    }

    func loadAll() async throws -> Library {
        async let beats = client.select(
            table: "beats", columns: "id,name,data", filters: ownerFilter,
            order: "created_at.asc", whileDoing: String(localized: "your beats", bundle: .strings),
        )
        async let playlists = client.select(
            table: "playlists", columns: "id,name,beat_ids", filters: ownerFilter,
            order: "created_at.asc", whileDoing: String(localized: "your playlists", bundle: .strings),
        )
        async let profile = activeCategoryId()

        let beatRows = try await beats
        let playlistRows = try await playlists
        let active = await profile

        return Library(
            beats: rowsOf(beatRows).compactMap { SupabaseRows.toBeat($0) },
            categories: rowsOf(playlistRows).compactMap { SupabaseRows.toCategory($0) },
            activeCategoryId: active,
        )
    }

    // Which category Home cycles within, or BUILTIN_CATEGORY. A brand-new user may have no
    // profile row yet, and PostgREST reports "no rows" for a single-object request as
    // PGRST116, which maps to NOT_FOUND — so that one code is caught here and read as "no
    // preference yet" rather than failing the whole load.
    private func activeCategoryId() async -> String {
        do {
            let row = try await client.select(
                table: "profiles", columns: "active_category_id",
                filters: [("id", "eq.\(userId)")], one: true, whileDoing: String(localized: "your settings", bundle: .strings),
            )
            let value = row?.stringField("active_category_id")
            return (value?.isEmpty == false ? value : nil) ?? BUILTIN_CATEGORY
        } catch let error as StorageError where error.code == .notFound {
            return BUILTIN_CATEGORY
        } catch {
            return BUILTIN_CATEGORY
        }
    }

    func createBeat(_ draft: Beat) async throws -> Beat {
        let row = try await client.insert(table: "beats", body: SupabaseRows.beatRow(userId: userId, draft: draft), one: true, whileDoing: String(localized: "that beat", bundle: .strings))
        guard let beat = SupabaseRows.toBeat(row) else {
            throw StorageError(.unknown, String(localized: "The server didn't return the beat you just created.", bundle: .strings))
        }
        return beat
    }

    // One insert for the whole batch. A single Postgres insert returns its rows in input
    // order, so the drafts and the returned beats line up without an id map.
    func createBeats(_ drafts: [Beat]) async throws -> [Beat] {
        guard !drafts.isEmpty else { return [] }
        let array = JValue.arr(drafts.map { SupabaseRows.beatRow(userId: userId, draft: $0) })
        let rows = try await client.insert(table: "beats", body: array, whileDoing: String(localized: "those beats", bundle: .strings))
        return rowsOf(rows).compactMap { SupabaseRows.toBeat($0) }
    }

    // The sole caller hands over the complete beat body, so the jsonb blob is REPLACED
    // wholesale rather than merged — a field the editor deleted stays deleted.
    func updateBeat(id: String, patch: Beat) async throws -> Beat {
        let body = JValue.object([
            JPair(key: "name", value: .str(patch.name)),
            JPair(key: "data", value: LibraryJson.encodeBody(patch)),
        ])
        let row = try await client.update(table: "beats", body: body, filters: [("id", "eq.\(id)")] + ownerFilter, one: true, whileDoing: String(localized: "that beat", bundle: .strings))
        guard let beat = SupabaseRows.toBeat(row) else {
            throw StorageError(.unknown, String(localized: "The server didn't return the beat you just saved.", bundle: .strings))
        }
        return beat
    }

    func deleteBeat(id: String) async throws {
        _ = try await client.delete(table: "beats", filters: [("id", "eq.\(id)")] + ownerFilter, whileDoing: String(localized: "that change", bundle: .strings))
    }

    func createCategory(_ draft: CategoryDraft) async throws -> Category {
        let body = JValue.object([
            JPair(key: "user_id", value: .str(userId)),
            JPair(key: "name", value: .str(draft.name)),
            JPair(key: "beat_ids", value: SupabaseRows.beatIdsArray(draft.beatIds)),
        ])
        let row = try await client.insert(table: "playlists", body: body, one: true, whileDoing: String(localized: "that playlist", bundle: .strings))
        guard let category = SupabaseRows.toCategory(row) else {
            throw StorageError(.unknown, String(localized: "The server didn't return the playlist you just created.", bundle: .strings))
        }
        return category
    }

    // A partial patch: only the columns present are touched.
    func updateCategory(id: String, patch: CategoryPatch) async throws -> Category {
        var pairs: [JPair] = []
        if let name = patch.name { pairs.append(JPair(key: "name", value: .str(name))) }
        if let beatIds = patch.beatIds { pairs.append(JPair(key: "beat_ids", value: SupabaseRows.beatIdsArray(beatIds))) }
        let body = JValue.object(pairs)
        let row = try await client.update(table: "playlists", body: body, filters: [("id", "eq.\(id)")] + ownerFilter, one: true, whileDoing: String(localized: "that playlist", bundle: .strings))
        guard let category = SupabaseRows.toCategory(row) else {
            throw StorageError(.unknown, String(localized: "The server didn't return the playlist you just saved.", bundle: .strings))
        }
        return category
    }

    func deleteCategory(id: String) async throws {
        _ = try await client.delete(table: "playlists", filters: [("id", "eq.\(id)")] + ownerFilter, whileDoing: String(localized: "that change", bundle: .strings))
    }

    // Upsert, so the row is created on first use even if the new-user trigger never ran.
    func setActiveCategory(id: String) async throws {
        _ = try await client.upsert(
            table: "profiles",
            body: JValue.object([JPair(key: "id", value: .str(userId)), JPair(key: "active_category_id", value: .str(id))]),
            onConflict: "id", whileDoing: String(localized: "that change", bundle: .strings),
        )
    }
}

// The row ↔ value mapping for the `beats` and `playlists` tables, outside the class so it can
// be tested without a network, a session or a client: this is where a shape mismatch between
// the two platforms would show up.

enum SupabaseRows {

    /// The insert body for one beat. `name` rides in its own column; the whole body in `data`.
    /// The blob carries NO id — the primary-key column is the id.
    static func beatRow(userId: String, draft: Beat) -> JValue {
        JValue.object([
            JPair(key: "user_id", value: .str(userId)),
            JPair(key: "name", value: .str(draft.name)),
            JPair(key: "data", value: LibraryJson.encodeBody(draft)),
        ])
    }

    /// A Postgres `text[]` is a JSON array on the wire.
    static func beatIdsArray(_ beatIds: [String]) -> JValue {
        JValue.arr(beatIds.map { .str($0) })
    }

    /// A `beats` row as an app beat: the COLUMN id over the blob, everything else from the
    /// blob. A blob that will not decode is an unreadable row, skipped rather than failing the
    /// whole load (the load's other rows still come through).
    static func toBeat(_ row: JValue?) -> Beat? {
        let id = row?.element("id")?.asString()
        guard let id else { return nil }
        var beat = LibraryJson.decodeBeat(row?.element("data"))
        guard beat != nil else { return nil }
        beat?.id = id
        return beat
    }

    /// A `playlists` row as an app category.
    static func toCategory(_ row: JValue?) -> Category? {
        let id = row?.element("id")?.asString()
        guard let id else { return nil }
        let beatIds = (row?.element("beat_ids")?.asArray() ?? []).compactMap { $0.asString() }
        return Category(id: id, name: row?.element("name")?.asString() ?? "", beatIds: beatIds)
    }
}

/// The rows of a PostgREST collection response. A null body is not an error — a 204 carries
/// no content — and reading it as "no rows" keeps an empty library and an empty response
/// indistinguishable.
private func rowsOf(_ body: JValue?) -> [JValue] {
    body?.asArray() ?? []
}
