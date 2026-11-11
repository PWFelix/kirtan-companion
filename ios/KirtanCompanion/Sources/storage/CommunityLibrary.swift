// CommunityLibrary.swift
//
// Keeps the library's community beats in step with their originals. Mirrors Android's
// storage/CommunityLibrary.kt.
//
// Whenever the set of community items the library refers to changes (launch, an import,
// an edit that makes a copy), the Beats screen asks for a refresh, which fetches those
// originals once. From them CommunityTracking answers "update available" / "no longer
// shared" and whether an edited copy is still just the original. It also runs, once per
// beat, the migration of imports made back when they were editable.
//
// Offline or without a backend everything reads as unknown and nothing changes.

import Foundation
import Observation

@MainActor
@Observable
final class CommunityLibrary {
    /// Item id → its current beats; nil until loaded (or when it can't be).
    private(set) var originals: [String: [Beat]]?

    @ObservationIgnored private let library: LibraryRepository
    @ObservationIgnored private let community: CommunityClient?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let log: StorageLog
    @ObservationIgnored private var refreshing = false

    private static let checkedKey = "kirtan-community-migration-checked"

    init(library: LibraryRepository, community: CommunityClient?, defaults: UserDefaults = .standard, log: StorageLog = OSStorageLog()) {
        self.library = library
        self.community = community
        self.defaults = defaults
        self.log = log
    }

    /// The community items the library refers to — what a refresh fetches. Views key
    /// their refresh task on this, so it re-runs exactly when the set changes.
    var referencedIds: Set<String> {
        Set(library.state.customBeats.compactMap { $0.sourceItemId ?? $0.forkedFrom })
    }

    func status(_ beat: Beat) -> CommunityTracking.Status { CommunityTracking.status(beat, originals) }

    /// Bring a community beat up to its original. False when there is nothing to apply or
    /// the write failed.
    func update(_ beat: Beat) async -> Bool {
        guard case .updateAvailable(let upstream) = status(beat) else { return false }
        return await library.replaceBeat(CommunityTracking.applyUpdate(beat, upstream))
    }

    func refresh() async {
        guard let community, !library.state.loading, !refreshing else { return }
        let ids = referencedIds
        guard !ids.isEmpty else {
            originals = [:]
            return
        }
        refreshing = true
        defer { refreshing = false }
        do {
            let loaded = try await community.originals(Array(ids))
            originals = loaded
            await migrate(loaded)
        } catch {
            log.warn("[community] couldn't load originals", error)
        }
    }

    private func migrate(_ loaded: [String: [Beat]]) async {
        var checked = Set(defaults.stringArray(forKey: Self.checkedKey) ?? [])
        let before = checked.count
        for beat in library.state.customBeats {
            guard let id = beat.id, let source = beat.sourceItemId, !checked.contains(id) else { continue }
            var done = false
            switch CommunityTracking.migrate(beat, loaded[source]) {
            case .keep(let index):
                if index == beat.sourceIndex {
                    done = true
                } else {
                    var b = beat; b.sourceIndex = index
                    done = await library.replaceBeat(b)
                }
            case .fork:
                var b = beat; b.sourceItemId = nil; b.sourceIndex = nil; b.forkedFrom = source
                done = await library.replaceBeat(b)
            case .undecided:
                done = false
            }
            if done { checked.insert(id) }
        }
        if checked.count != before { defaults.set(Array(checked), forKey: Self.checkedKey) }
    }
}
