// ShareStatus.swift
//
// Whether one of the user's beats is already in the community library, so Share can
// say "Already shared" (disabled) or "Share updated version" instead of quietly
// publishing a duplicate. Mirrors Android's ui/community/ShareStatus.kt.
//
// Published snapshots carry no beat id (stripped on upload, by design), so the link
// from a beat to the item it was published as is a per-device record. A beat with no
// record still reads as shared when one of the user's items holds exactly its
// content — shares from before the record existed, or from another device. Only
// "edited since" needs the record, since an edited beat matches nothing.

import Foundation

enum ShareStatus: Equatable {
    /// Not shared yet (or unpublished since): Share publishes a new item.
    case new
    /// The community copy matches exactly: nothing to do.
    case shared
    /// Shared before, edited since: Share updates `itemId` in place.
    case changed(itemId: String)
    /// Added from the community: someone else's beat, never re-shared.
    case notYours
    /// A copy of a community beat that is musically still the original (renamed or
    /// retagged at most). Sharable once the music actually changes.
    case unchangedCopy

    /// True when Share may publish or update.
    var canShare: Bool {
        switch self {
        case .new, .changed: return true
        default: return false
        }
    }
}

struct ShareIndex: Equatable {
    /// (item id, the beat it holds) for every BEAT the user has published.
    var mine: [(id: String, beat: Beat)] = []
    /// Local beat id → the community item it was published as.
    var links: [String: String] = [:]
    /// The originals of the library's community beats and copies, when loaded.
    var originals: [String: [Beat]]? = nil

    func status(_ beat: Beat) -> ShareStatus {
        if beat.isCommunity { return .notYours }
        if CommunityTracking.isUnchangedCopy(beat, originals) { return .unchangedCopy }
        let print = Self.fingerprint(beat)
        if let linkedId = beat.id.flatMap({ links[$0] }),
           let linked = mine.first(where: { $0.id == linkedId }) {
            return Self.fingerprint(linked.beat) == print ? .shared : .changed(itemId: linked.id)
        }
        return mine.contains(where: { Self.fingerprint($0.beat) == print }) ? .shared : .new
    }

    /// The beat as it would be published: wire shape, ids stripped. Compared through the
    /// encoder so fields the wire doesn't carry can't make a match look like a change.
    static func fingerprint(_ beat: Beat) -> JValue {
        var copy = beat
        copy.id = nil
        copy.sourceItemId = nil
        copy.sourceIndex = nil
        copy.forkedFrom = nil
        return LibraryJson.encodeBody(copy)
    }

    static func == (a: ShareIndex, b: ShareIndex) -> Bool {
        a.links == b.links && a.mine.map(\.id) == b.mine.map(\.id)
            && a.mine.map { fingerprint($0.beat) } == b.mine.map { fingerprint($0.beat) }
            && a.originals == b.originals
    }

    // ── The per-device record ──
    private static let linksKey = "kirtan-community-share-links"

    static func loadLinks(_ defaults: UserDefaults = .standard) -> [String: String] {
        defaults.dictionary(forKey: linksKey) as? [String: String] ?? [:]
    }

    static func saveLink(beatId: String, itemId: String, _ defaults: UserDefaults = .standard) {
        var all = loadLinks(defaults)
        all[beatId] = itemId
        defaults.set(all, forKey: linksKey)
    }
}
