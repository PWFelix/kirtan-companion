import Foundation
import Observation

// Everything the user has saved, and its persistence — the port of src/hooks/useBeatLibrary.js,
// the ONLY consumer of a BeatsProvider in the whole app.
//
// Ported from storage/LibraryRepository.kt. Two things live here, and keeping them apart is
// what makes the layer safe:
//
//  - CUSTOM BEATS: built or forked in the editor. The compiled-in Beats.beats are read-only,
//    so every write touches only this list and a shipped beat can never be corrupted by a save.
//  - CATEGORIES: ordered lists of beat ids, each one a kirtan PROGRESSION. Two categories are
//    always present and are NEVER stored: BUILTIN_CATEGORY and CUSTOM_CATEGORY.
//
// BUILT-INS ARE MERGED HERE, NOT IN THE PROVIDER (rule 2 of the contract). allBeats is
// built-ins first, then the user's. The built-in set comes from ShippedBeats.effective —
// the served set, falling back to the compiled one — so it can change WHILE THE APP IS
// RUNNING and everything that reads it (the merge, the two virtual categories, the name
// de-duping) goes through the same live object.
//
// WHY SOME WRITES AWAIT AND SOME DON'T. Creates await (the id is minted by the provider and
// the caller needs the saved beat back). Everything else is OPTIMISTIC: state changes at once
// and persistence happens behind it, because a reorder that waited for a round trip would feel
// broken. If an optimistic write fails the state goes back and the error surfaces.
//
// SWAPPING THE STORE UNDER A RUNNING APP. attachProvider replaces the provider and reloads;
// a generation counter drops a stale load that finishes after a newer one started — the race
// that would otherwise sign a user back into the library they just left.
//
// ── CONCURRENCY ──
// The whole class is @MainActor: every state read and write is serialised by the main actor
// alone, which is what the Kotlin writeMutex was for. The provider calls hop OFF the main
// actor (they are nonisolated), so the only interleaving risk is two writes each rolling back
// to a snapshot the other already moved past — `withWrite` chains writes onto one another so
// that cannot happen. Every withWrite body catches its own provider failures and returns a
// value (never throws), which is what lets withWrite be non-throwing.

@MainActor
@Observable
final class LibraryRepository {

    /// The library as one immutable snapshot.
    ///
    /// One object rather than five independent values because they are meaningless apart from
    /// each other: a beat list without the categories that reference it, or an active category
    /// without the list it was chosen from, is a state the UI cannot render correctly. The web
    /// hook's eight useState calls are the same fact spread out.
    struct State {
        /// True until the first load settles. A failed load settles too.
        var loading: Bool = true
        /// A sentence for the user, or nil.
        var error: String? = nil
        /// What the user has made. Never contains a built-in beat.
        var customBeats: [Beat] = []
        var categories: [Category] = []
        /// May be BUILTIN_CATEGORY or CUSTOM_CATEGORY, which are not rows.
        var activeCategoryId: String = BUILTIN_CATEGORY
    }

    var state = State()

    /// The live built-in set, served from the database with the compiled list as fallback.
    /// A separate @Observable object so its updates flow into `allBeats` on their own.
    let shippedBeats: ShippedBeats
    @ObservationIgnored private let log: StorageLog

    @ObservationIgnored private var provider: BeatsProvider
    /// Bumped on every load so a stale one can recognise itself and stop.
    @ObservationIgnored private var generation = 0
    /// Chains writes so two overlapping ones cannot roll each other back.
    @ObservationIgnored private var writeChain: Task<Void, Never>?

    init(
        initialProvider: BeatsProvider,
        shippedBeats: ShippedBeats = .shared,
        log: StorageLog = OSStorageLog(),
    ) {
        self.provider = initialProvider
        self.shippedBeats = shippedBeats
        self.log = log
        // The library starts empty and fills in — the same shape as the web hook's effect. The
        // splash gates on state.loading, so the empty first frame is never shown.
        Task { await self.reload() }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /// Built-ins then the user's beats, in that order — the list every screen reads. Derived
    /// so no screen can get the order or the merge wrong.
    var allBeats: [Beat] { shippedBeats.effective + state.customBeats }

    /// True when id is one of the user's beats rather than a shipped one.
    func isCustomBeat(_ id: String?) -> Bool {
        guard let id else { return false }
        return state.customBeats.contains { $0.id == id }
    }

    /// The ordered beats of a category: "builtin", "custom", or a stored id. The two virtual
    /// categories are answered here and are not rows anywhere. An id that names no category
    /// falls back to allBeats, which keeps a stale active-category preference from leaving Home
    /// with nothing to play. A progression drops ids that name no beat, so it can never hand
    /// the transport a hole.
    func categoryBeats(_ categoryId: String) -> [Beat] {
        switch categoryId {
        case BUILTIN_CATEGORY: return shippedBeats.effective
        case CUSTOM_CATEGORY: return state.customBeats
        default: break
        }
        let everything = allBeats
        guard let category = state.categories.first(where: { $0.id == categoryId }) else {
            return everything
        }
        return category.beatIds.compactMap { id in everything.first(where: { $0.id == id }) }
    }

    /// The heading for a category, including the two virtual ones.
    func categoryName(_ categoryId: String) -> String {
        switch categoryId {
        case BUILTIN_CATEGORY: return "Built in"
        case CUSTOM_CATEGORY: return "Your beats"
        default:
            return state.categories.first(where: { $0.id == categoryId })?.name ?? String(localized: "Beats")
        }
    }

    func dismissError() { state.error = nil }

    // ── Loading and provider swapping ──────────────────────────────────────

    /// Point the library at a different store and reload from it (sign-in → cloud, sign-out →
    /// device). The state is reset to "loading, nothing known yet" at once: keeping the
    /// previous store's beats on screen while the new one loads would show one account's
    /// library to whoever just signed in as another, and if the new load then failed those
    /// beats would sit there with only an error banner to say they aren't really there. An
    /// empty shelf that says "couldn't be read" is honest.
    func attachProvider(_ next: BeatsProvider) {
        provider = next
        state = State()
        Task { await self.reload() }
    }

    /// (Re)read everything from the current provider. A failed load is NOT fatal: the built-in
    /// beats are compiled in, so the app still plays; the user just cannot see their own work,
    /// and needs telling that rather than being shown an empty library. A failure leaves
    /// whatever was already loaded in place.
    func reload() async {
        let started = generation + 1
        generation = started
        state.loading = true
        state.error = nil
        do {
            let library = try await provider.loadAll()
            guard started == generation else { return }   // a newer load superseded this one
            state.loading = false
            state.customBeats = library.beats
            state.categories = library.categories
            let active = library.activeCategoryId?.trimmingCharacters(in: .whitespaces)
            state.activeCategoryId = (active?.isEmpty == false ? active : nil) ?? BUILTIN_CATEGORY
        } catch {
            guard started == generation else { return }
            log.warn("[library] load failed", error)
            state.loading = false
            state.error = storageErrorMessage(error)
        }
    }

    // ── Writing ────────────────────────────────────────────────────────────

    /// Save from the editor. A beat with no id is new (or a fork of a built-in) and gets one
    /// minted; an id already in the library is edited in place; an unrecognised id is treated
    /// as new rather than as an error — refusing to save the user's work over a bookkeeping
    /// mismatch is the worse outcome. Returns the saved beat, or nil if it wasn't kept (and
    /// state.error is set — never a silent failure).
    func saveBeat(_ newBeat: Beat) async -> Beat? {
        await withWrite {
            let current = self.state
            let id = newBeat.id

            // Every name in the library EXCEPT this beat's own — otherwise editing a beat
            // without renaming it would suffix it a little further on every save. Built-ins
            // are included, so a custom beat cannot shadow "Te Ta" either.
            var taken = Set(self.allBeats.filter { $0.id != id }.map { $0.name })
            let name = uniqueName(newBeat.name, &taken)

            let editing = id != nil && current.customBeats.contains { $0.id == id }
            do {
                var fields = newBeat
                fields.id = nil
                fields.name = name
                // The editor builds a fresh Beat and knows nothing of where it came from;
                // carry the community origin over so an edited copy still counts as "in
                // your library" (issue #11).
                fields.sourceItemId = newBeat.sourceItemId
                    ?? current.customBeats.first { id != nil && $0.id == id }?.sourceItemId
                let saved: Beat
                if let id, editing {
                    saved = try await self.provider.updateBeat(id: id, patch: fields)
                } else {
                    saved = try await self.provider.createBeat(fields)
                }
                self.state.customBeats = editing
                    ? current.customBeats.map { $0.id == id ? saved : $0 }
                    : current.customBeats + [saved]
                self.state.error = nil
                return saved
            } catch {
                self.log.warn("[library] save failed", error)
                self.state.error = storageErrorMessage(error)
                return nil
            }
        }
    }

    /// Delete a beat, and drop it from every progression that referenced it. Two writes, not
    /// one. If the second fails the beat is still gone and the categories are put back — a
    /// progression pointing at a missing beat, which categoryBeats filters out, is a smaller
    /// wrong than a beat that refuses to die.
    func deleteBeat(_ id: String) async {
        await withWrite {
            let current = self.state
            let affected = current.categories.filter { $0.beatIds.contains(id) }

            let deleted = await self.optimisticBeats(
                prev: current.customBeats,
                next: current.customBeats.filter { $0.id != id },
            ) { try await self.provider.deleteBeat(id: id) }
            guard deleted, !affected.isEmpty else { return }

            let next = current.categories.map { category in
                category.beatIds.contains(id)
                    ? Category(id: category.id, name: category.name, beatIds: category.beatIds.filter { $0 != id })
                    : category
            }
            await self.optimisticCategories(prev: current.categories, next: next) {
                // One write per affected category. Sequential on purpose: a handful of
                // progressions is nothing, and a concurrent fan-out would only add risk.
                for category in affected {
                    _ = try await self.provider.updateCategory(
                        id: category.id,
                        patch: CategoryPatch(beatIds: category.beatIds.filter { $0 != id }),
                    )
                }
            }
        }
    }

    /// Add a decoded share payload — or a community copy, which arrives as the same type — to
    /// the library. The ONLY way beats from outside this device get in. It mints fresh ids
    /// (ShareCodec carries none, so a stranger's link cannot name one of the user's beats and
    /// quietly replace it) and writes once per list (a loop of createBeat would read stale
    /// state and drop beats).
    ///
    /// `sourceItemId` is the community item being copied, stamped on every beat so the
    /// community screen can tell it's already here. Nil for share links.
    func importShared(_ payload: ShareCodec.SharePayload?, sourceItemId: String? = nil) async -> ImportResult {
        await withWrite {
            guard let payload else { return ImportResult(beats: [], categoryId: nil) }

            let incoming: [Beat]
            let categoryName: String?
            switch payload {
            case .beat(let beat):
                incoming = [beat]; categoryName = nil
            case .category(let name, let beats):
                incoming = beats; categoryName = name
            case .invalid:
                // A link that failed to decode: nothing to import.
                return ImportResult(beats: [], categoryId: nil)
            }
            guard !incoming.isEmpty else { return ImportResult(beats: [], categoryId: nil) }

            // Re-importing your own link shouldn't produce two rows with one name.
            var taken = Set(self.allBeats.map { $0.name })
            var drafts: [Beat] = []
            for beat in incoming {
                var d = beat
                d.id = nil
                d.name = uniqueName(beat.name, &taken)
                d.sourceItemId = sourceItemId
                drafts.append(d)
            }

            let created: [Beat]
            do {
                created = try await self.provider.createBeats(drafts)
            } catch {
                self.log.warn("[library] import failed", error)
                self.state.error = storageErrorMessage(error)
                return ImportResult(beats: [], categoryId: nil)
            }
            self.state.customBeats = self.state.customBeats + created
            self.state.error = nil

            guard let categoryName else { return ImportResult(beats: created, categoryId: nil) }

            do {
                var takenCategoryNames = Set(self.state.categories.map { $0.name })
                let name = uniqueName(categoryName, &takenCategoryNames)
                let beatIds = created.compactMap { $0.id }
                let category = try await self.provider.createCategory(
                    CategoryDraft(name: name, beatIds: beatIds),
                )
                self.state.categories = self.state.categories + [category]
                return ImportResult(beats: created, categoryId: category.id)
            } catch {
                // The beats are genuinely saved; only the grouping failed. Keeping them and
                // saying so beats unwinding a successful write — they land in "Your beats".
                self.log.warn("[library] imported beats, but the list wasn't created", error)
                self.state.error = storageErrorMessage(error)
                return ImportResult(beats: created, categoryId: nil)
            }
        }
    }

    /// Bulk-move a whole local library into the current store — the first-sign-in "bring your
    /// on-device beats to your account" step. Uploading mints FRESH ids, so any category that
    /// referenced a local beat by its old id has to be repointed to the new one. Built-in ids
    /// pass straight through. Names are de-duped against what the account already holds.
    func importLibrary(_ local: Library) async -> MigrationResult {
        await withWrite {
            if local.beats.isEmpty && local.categories.isEmpty {
                return MigrationResult(beats: 0, categories: 0)
            }

            var taken = Set(self.allBeats.map { $0.name })
            var oldIds: [String?] = []
            var drafts: [Beat] = []
            for beat in local.beats {
                oldIds.append(beat.id)
                var d = beat
                d.id = nil
                d.name = uniqueName(beat.name, &taken)
                drafts.append(d)
            }

            let created: [Beat]
            do {
                created = try await self.provider.createBeats(drafts)
            } catch {
                self.log.warn("[library] migration: beats failed", error)
                self.state.error = storageErrorMessage(error)
                return MigrationResult(beats: 0, categories: 0, failed: true)
            }
            if !created.isEmpty { self.state.customBeats = self.state.customBeats + created }

            var idMap: [String: String] = [:]
            for (index, beat) in created.enumerated() {
                if let newId = beat.id, let old = oldIds[index] { idMap[old] = newId }
            }

            var takenCategoryNames = Set(self.state.categories.map { $0.name })
            var madeCategories = 0
            do {
                for category in local.categories {
                    let beatIds = category.beatIds.map { idMap[$0] ?? $0 }
                    let saved = try await self.provider.createCategory(
                        CategoryDraft(name: uniqueName(category.name, &takenCategoryNames), beatIds: beatIds),
                    )
                    self.state.categories = self.state.categories + [saved]
                    madeCategories += 1
                }
                self.state.error = nil
            } catch {
                self.log.warn("[library] migration: a playlist failed", error)
                self.state.error = storageErrorMessage(error)
            }
            return MigrationResult(beats: created.count, categories: madeCategories)
        }
    }

    /// Create a category and return its id, so the caller can land the user in it. A second
    /// "Sunday kirtan" becomes "Sunday kirtan (2)" rather than a twin the user cannot tell
    /// apart — a row shows the name and nothing else.
    func createCategory(_ name: String) async -> String? {
        await withWrite {
            do {
                var taken = Set(self.state.categories.map { $0.name })
                let category = try await self.provider.createCategory(
                    CategoryDraft(name: uniqueName(name, &taken), beatIds: []),
                )
                self.state.categories = self.state.categories + [category]
                self.state.error = nil
                return category.id
            } catch {
                self.log.warn("[library] category not created", error)
                self.state.error = storageErrorMessage(error)
                return nil
            }
        }
    }

    /// Rename a progression. A clash with another list gets the same "(2)" suffix a new one
    /// would, for the same reason: a row shows the name and nothing else. A blank name is
    /// ignored rather than saved — a list with no name is a row nobody can find.
    func renameCategory(_ categoryId: String, _ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return }
        await withWrite {
            let current = self.state.categories
            guard let existing = current.first(where: { $0.id == categoryId }), existing.name != trimmed else { return }
            var taken = Set(current.filter { $0.id != categoryId }.map { $0.name })
            let unique = uniqueName(trimmed, &taken)
            let next = current.map { $0.id == categoryId ? Category(id: $0.id, name: unique, beatIds: $0.beatIds) : $0 }
            _ = await self.optimisticCategories(prev: current, next: next) {
                _ = try await self.provider.updateCategory(id: categoryId, patch: CategoryPatch(name: unique))
            }
        }
    }

    func deleteCategory(_ categoryId: String) async {
        await withWrite {
            let current = self.state
            let deleted = await self.optimisticCategories(
                prev: current.categories,
                next: current.categories.filter { $0.id != categoryId },
            ) { try await self.provider.deleteCategory(id: categoryId) }
            // Deleting the category you were standing in leaves Home with nothing to cycle, so
            // the built-ins take over. Only on success: a refused delete must not move the user.
            if deleted, self.state.activeCategoryId == categoryId {
                self.setActiveCategory(BUILTIN_CATEGORY)
            }
        }
    }

    /// Add a beat to a progression, or remove it if it is already in there.
    func toggleBeatInCategory(_ categoryId: String, _ beatId: String) async {
        await withWrite {
            guard let category = self.state.categories.first(where: { $0.id == categoryId }) else { return }
            let beatIds = category.beatIds.contains(beatId)
                ? category.beatIds.filter { $0 != beatId }
                : category.beatIds + [beatId]
            await self.patchCategory(categoryId, beatIds)
        }
    }

    /// Reorder a progression after a drag. The order IS the progression, so this is the one
    /// write where the list's contents are unchanged and only their sequence matters.
    func reorderCategory(_ categoryId: String, activeId: String, overId: String) async {
        guard activeId != overId else { return }
        await withWrite {
            guard let category = self.state.categories.first(where: { $0.id == categoryId }) else { return }
            guard let from = category.beatIds.firstIndex(of: activeId),
                  let to = category.beatIds.firstIndex(of: overId) else { return }
            await self.patchCategory(categoryId, arrayMove(category.beatIds, from: from, to: to))
        }
    }

    /// Remember which category Home cycles within. NOT rolled back on failure, unlike
    /// everything else here: this is a preference, not the user's work. The write is
    /// fire-and-forget — a failed persist just means the wrong tab is restored next launch.
    func setActiveCategory(_ categoryId: String) {
        state.activeCategoryId = categoryId
        Task {
            do {
                try await self.provider.setActiveCategory(id: categoryId)
            } catch {
                self.log.warn("[library] active category not saved", error)
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private func patchCategory(_ categoryId: String, _ beatIds: [String]) async {
        let current = state.categories
        let next = current.map {
            $0.id == categoryId ? Category(id: $0.id, name: $0.name, beatIds: beatIds) : $0
        }
        _ = await optimisticCategories(prev: current, next: next) {
            _ = try await self.provider.updateCategory(id: categoryId, patch: CategoryPatch(beatIds: beatIds))
        }
    }

    /// Paint next immediately, persist behind it, put prev back if the store refuses. Returns
    /// whether it stuck. The rollback restores ONLY the beat list (and sets the error), never
    /// the whole snapshot: an unrelated change that landed in between survives the revert.
    private func optimisticBeats(
        prev: [Beat], next: [Beat], persist: @MainActor () async throws -> Void,
    ) async -> Bool {
        state.customBeats = next
        do {
            try await persist()
            return true
        } catch {
            log.warn("[library] write failed", error)
            state.customBeats = prev
            state.error = storageErrorMessage(error)
            return false
        }
    }

    /// optimisticBeats for the category list.
    private func optimisticCategories(
        prev: [Category], next: [Category], persist: @MainActor () async throws -> Void,
    ) async -> Bool {
        state.categories = next
        do {
            try await persist()
            return true
        } catch {
            log.warn("[library] write failed", error)
            state.categories = prev
            state.error = storageErrorMessage(error)
            return false
        }
    }

    /// Serialise overlapping writes. Everything here is on the main actor, so state access is
    /// already serial; this chains the provider round-trips so one write's rollback can never
    /// erase the change a concurrent write made (see the class header). The body must not
    /// throw — every call site handles its own provider failures and returns a value.
    private func withWrite<T>(_ operation: @MainActor @escaping () async -> T) async -> T {
        let previous = writeChain
        let job: Task<T, Never> = Task {
            if let previous { await previous.value }
            return await operation()
        }
        writeChain = Task { _ = await job.value }
        return await job.value
    }
}

/// What importShared landed, so the caller can select and navigate to it.
struct ImportResult {
    var beats: [Beat]
    var categoryId: String?
}

/// What importLibrary moved, for a prompt that says "moved 4 beats and 2 playlists". failed
/// is true only when the BEATS failed — a category that didn't make it is reported through
/// State.error with the beats kept.
struct MigrationResult {
    var beats: Int
    var categories: Int
    var failed: Bool = false
}

/// A name nobody else in the library is using: "My Beat" → "My Beat (2)". Naming is the
/// library's job, not the editor's, because this is the only place that knows what is already
/// taken. It MUTATES taken, which is what lets a batch import stay unique against ITSELF:
/// without it, importing a playlist of three beats all called "My Beat" would produce three
/// beats all called "My Beat (2)".
func uniqueName(_ wanted: String, _ taken: inout Set<String>) -> String {
    var name = wanted
    var suffix = 2
    while taken.contains(name) {
        name = "\(wanted) (\(suffix))"
        suffix += 1
    }
    taken.insert(name)
    return name
}

/// dnd-kit's arrayMove: lift the element at `from` and put it at `to`.
func arrayMove<T>(_ list: [T], from: Int, to: Int) -> [T] {
    var result = list
    result.insert(result.remove(at: from), at: to)
    return result
}

/// True when there is anything on this device worth offering to a freshly signed-in account.
extension Library {
    func hasContent() -> Bool { !beats.isEmpty || !categories.isEmpty }
}
