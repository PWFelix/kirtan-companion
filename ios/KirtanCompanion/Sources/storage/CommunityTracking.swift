// CommunityTracking.swift
//
// How a community beat in the library relates to the original it follows. Mirrors
// Android's storage/CommunityTracking.kt.
//
// A community beat (Beat.isCommunity) is read-only, so ANY difference from the original
// means the author changed it: that is all "update available" needs, no timestamps (and
// no database change). Editing makes a copy in Your beats (Beat.forkedFrom), and that
// copy can be shared only once it is musically different from the original — renaming or
// retagging someone else's beat is not a new beat.
//
// Pure: tested without a network. CommunityLibrary feeds it.

import Foundation

enum CommunityTracking {

    enum Status: Equatable {
        /// Not checked yet (offline, still loading).
        case unknown
        case current
        case updateAvailable(upstream: Beat)
        /// The author unpublished it (or it left the list it came in).
        case gone
    }

    /// What the update check compares: everything the author controls except the name,
    /// which the import may have suffixed ("Steady 2 (2)") to keep names unique.
    static func contentPrint(_ beat: Beat) -> JValue {
        var b = beat
        b.id = nil; b.name = ""; b.group = nil
        b.sourceItemId = nil; b.sourceIndex = nil; b.forkedFrom = nil
        return LibraryJson.encodeBody(b)
    }

    /// The music alone: what makes a copy a different beat rather than a relabel.
    static func musicalPrint(_ beat: Beat) -> JValue {
        var b = beat
        b.note = ""; b.description = nil; b.tags = []
        return contentPrint(b)
    }

    /// The original of `beat` inside its item's beats (`items`: item id → beats).
    static func upstreamOf(_ beat: Beat, _ items: [String: [Beat]]) -> Beat? {
        guard let source = beat.sourceItemId, let list = items[source] else { return nil }
        if let index = beat.sourceIndex { return list.indices.contains(index) ? list[index] : nil }
        return list.count == 1 ? list[0] : nil
    }

    /// `items` is nil when the originals couldn't be loaded. An item missing from a
    /// loaded map is GONE (the fetch asked for it by id).
    static func status(_ beat: Beat, _ items: [String: [Beat]]?) -> Status {
        guard beat.isCommunity else { return .current }
        guard let items else { return .unknown }
        guard let up = upstreamOf(beat, items) else { return .gone }
        return contentPrint(up) == contentPrint(beat) ? .current : .updateAvailable(upstream: up)
    }

    /// `beat` brought up to `upstream`: the author's content, the library's identity and name.
    static func applyUpdate(_ beat: Beat, _ upstream: Beat) -> Beat {
        var b = upstream
        b.id = beat.id; b.name = beat.name; b.group = nil
        b.sourceItemId = beat.sourceItemId; b.sourceIndex = beat.sourceIndex; b.forkedFrom = nil
        return b
    }

    /// True when an edited copy is still just the original (so it must not be shared).
    /// Unknown originals don't block.
    static func isUnchangedCopy(_ beat: Beat, _ items: [String: [Beat]]?) -> Bool {
        guard let from = beat.forkedFrom, let originals = items?[from] else { return false }
        let print = musicalPrint(beat)
        return originals.contains { musicalPrint($0) == print }
    }

    // ── One-time migration of imports made before community beats were read-only ──

    enum Migration: Equatable {
        /// Untouched: stays a community beat, at `sourceIndex` in its list.
        case keep(sourceIndex: Int?)
        /// Edited in place back then: becomes the user's own copy.
        case fork
        /// The original is gone: nothing to compare with, so leave it as it is.
        case undecided
    }

    /// Before this, imports were editable, so an old import that no longer matches its
    /// original was edited by the user. Matching is on the music alone, ignoring the
    /// name the import may have suffixed.
    static func migrate(_ beat: Beat, _ originals: [Beat]?) -> Migration {
        guard let originals, !originals.isEmpty else { return .undecided }
        let print = musicalPrint(beat)
        guard let index = originals.firstIndex(where: { musicalPrint($0) == print }) else { return .fork }
        return originals.count == 1 ? .keep(sourceIndex: nil) : .keep(sourceIndex: index)
    }
}
