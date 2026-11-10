import Foundation

// The BeatsProvider backed by this device's store. The only place in the app that writes
// the user's beats to disk.
//
// Ported from storage/LocalBeatsProvider.kt, which ports src/storage/localStorageProvider.js.
// That web file's rule carries over verbatim: NOTHING OUTSIDE storage/ MAY TOUCH THE
// STORE. Keeping the store behind one class is the layer's entire job.
//
// Two things worth knowing about the implementation:
//
//  - EVERY WRITE IS A READ-MODIFY-WRITE OF A WHOLE LIST, because a key-value store has no
//    notion of a row. That is fine here (a library is tens of beats, one process writes)
//    and is deliberately hidden behind per-entity methods. KirtanStore serialises the
//    read-modify-write on one queue, so it IS atomic: two tasks creating beats at once
//    cannot lose one — the thing the web app's getItem/setItem pair got wrong.
//
//  - THE ERRORS ARE THE POINT. Writing to disk fails in ways that really happen: the
//    device is out of space, the file sits on unavailable storage, or the process is
//    killed mid-write. The web code caught all of them and carried on, which is why a
//    failed save used to look identical to a good one. Every failure becomes a
//    StorageError carrying a sentence a person can read.
//
// ── A CORRUPT STORE IS REFUSED, NOT WIPED ──
// The web falls back to [] when a value won't parse — survivable there because the browser
// holds the only copy. Here the NEXT write would persist that empty list over the real one,
// turning a truncated blob into a deleted library. So a value that won't decode throws:
// the read fails, the write never happens, the bytes stay on disk, and LibraryRepository
// tells the user their beats could not be read rather than showing them an empty shelf.

final class LocalBeatsProvider: BeatsProvider {

    private let store: KirtanStore
    private let log: StorageLog

    init(store: KirtanStore, log: StorageLog = OSStorageLog()) {
        self.store = store
        self.log = log
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /// The stored beats, or a StorageError explaining why they could not be read. Called
    /// inside the write transform as well as on load, which is what makes a corrupt store
    /// abort the write that would have overwritten it.
    private func beatsIn(_ ctx: KirtanStore.Context) throws -> [Beat] {
        guard let beats = LibraryJson.decodeBeats(ctx.string(KirtanStore.Keys.beats)) else {
            throw corrupt(KirtanStore.Keys.beats)
        }
        return beats
    }

    private func categoriesIn(_ ctx: KirtanStore.Context) throws -> [Category] {
        guard let categories = LibraryJson.decodeCategories(ctx.string(KirtanStore.Keys.categories)) else {
            throw corrupt(KirtanStore.Keys.categories)
        }
        return categories
    }

    // ── The interface ──────────────────────────────────────────────────────

    func loadAll() async throws -> Library {
        try await store.perform { ctx in
            let active = ctx.string(KirtanStore.Keys.activeCategory)?
                .trimmingCharacters(in: .whitespaces)
            return Library(
                beats: try self.beatsIn(ctx),
                categories: try self.categoriesIn(ctx),
                activeCategoryId: (active?.isEmpty == false ? active : nil) ?? BUILTIN_CATEGORY,
            )
        }
    }

    func createBeat(_ draft: Beat) async throws -> Beat {
        // The id is minted OUTSIDE the write: the store may run the transform again after
        // a failed persist, and minting inside would mean two ids for one beat.
        let beat = draft.withID(newId())
        try await store.perform { ctx in
            let beats = try self.beatsIn(ctx)
            ctx.setString(KirtanStore.Keys.beats, LibraryJson.encodeBeats(beats + [beat]))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
        }
        return beat
    }

    // One write for the whole batch — see the note on BeatsProvider.createBeats.
    func createBeats(_ drafts: [Beat]) async throws -> [Beat] {
        let beats = drafts.map { $0.withID(newId()) }
        try await store.perform { ctx in
            let existing = try self.beatsIn(ctx)
            ctx.setString(KirtanStore.Keys.beats, LibraryJson.encodeBeats(existing + beats))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
        }
        return beats
    }

    func updateBeat(id: String, patch: Beat) async throws -> Beat {
        let beat = patch.withID(id)
        try await store.perform { ctx in
            var beats = try self.beatsIn(ctx)
            guard let index = beats.firstIndex(where: { $0.id == id }) else {
                throw self.notFound(String(localized: "That beat no longer exists."))
            }
            beats[index] = beat
            ctx.setString(KirtanStore.Keys.beats, LibraryJson.encodeBeats(beats))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
        }
        return beat
    }

    func deleteBeat(id: String) async throws {
        try await store.perform { ctx in
            let beats = try self.beatsIn(ctx).filter { $0.id != id }
            ctx.setString(KirtanStore.Keys.beats, LibraryJson.encodeBeats(beats))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
        }
    }

    func createCategory(_ draft: CategoryDraft) async throws -> Category {
        let category = Category(id: newId(), name: draft.name, beatIds: draft.beatIds)
        try await store.perform { ctx in
            let categories = try self.categoriesIn(ctx)
            ctx.setString(KirtanStore.Keys.categories, LibraryJson.encodeCategories(categories + [category]))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
        }
        return category
    }

    func updateCategory(id: String, patch: CategoryPatch) async throws -> Category {
        try await store.perform { ctx in
            var categories = try self.categoriesIn(ctx)
            guard let index = categories.firstIndex(where: { $0.id == id }) else {
                throw self.notFound(String(localized: "That list no longer exists."))
            }
            let current = categories[index]
            // A null field in the patch means "leave it alone" — see CategoryPatch.
            let next = Category(
                id: current.id,
                name: patch.name ?? current.name,
                beatIds: patch.beatIds ?? current.beatIds,
            )
            categories[index] = next
            ctx.setString(KirtanStore.Keys.categories, LibraryJson.encodeCategories(categories))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
            return next
        }
    }

    func deleteCategory(id: String) async throws {
        try await store.perform { ctx in
            let categories = try self.categoriesIn(ctx).filter { $0.id != id }
            ctx.setString(KirtanStore.Keys.categories, LibraryJson.encodeCategories(categories))
            ctx.setInt(KirtanStore.Keys.schemaVersion, KirtanStore.schemaVersion)
        }
    }

    func setActiveCategory(id: String) async throws {
        try await store.perform { ctx in
            ctx.setString(KirtanStore.Keys.activeCategory, id)
        }
    }

    // ── Errors ─────────────────────────────────────────────────────────────

    private func corrupt(_ key: String) -> StorageError {
        let error = StorageError(
            .unavailable,
            String(localized: "Your saved beats couldn't be read — the file holding them is damaged. Nothing has been deleted; try again, and if it keeps happening the library may need to be reset."),
        )
        log.warn("[storage] \(key) was unreadable and has NOT been overwritten", error)
        return error
    }

    private func notFound(_ message: String) -> StorageError {
        StorageError(.notFound, message)
    }
}

private extension Beat {
    /// Copy with a fresh id, leaving every other field untouched.
    func withID(_ id: String) -> Beat {
        var copy = self
        copy.id = id
        return copy
    }
}
