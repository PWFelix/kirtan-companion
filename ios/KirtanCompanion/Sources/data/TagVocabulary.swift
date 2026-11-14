// TagVocabulary.swift
//
// The curated tag vocabulary. Fixed for now: free-text tags drift ("keherwa", "kaherva",
// "8 beat") and can't drive the community filters. Authors tag their own beats
// (published with the beat); anyone can add PRIVATE tags to any beat (PrivateTags).
//
// Ids are the wire format, stable forever; labels are localised. A tag id this build
// doesn't know (added in a later version) is kept on the beat and simply not shown, so
// nothing is lost on a round trip through an older app.
// Mirrors Android data/TagVocabulary.kt and web src/data/tags.js.

import Foundation
import Observation

enum TagVocabulary {
    struct Tag: Hashable, Identifiable {
        let id: String
        let label: String
    }

    struct Group: Identifiable {
        let id: String
        let label: String
        let tags: [Tag]
    }

    // Computed, so labels follow an in-app language switch.
    static var groups: [Group] {
        [
            Group(id: "tala", label: String(localized: "Tala", bundle: .strings), tags: [
                Tag(id: "keherwa", label: String(localized: "Keherwa", bundle: .strings)),
                Tag(id: "dadra", label: String(localized: "Dadra", bundle: .strings)),
                Tag(id: "jhaptal", label: String(localized: "Jhaptal", bundle: .strings)),
                Tag(id: "rupak", label: String(localized: "Rupak", bundle: .strings)),
                Tag(id: "teental", label: String(localized: "Teental", bundle: .strings)),
            ]),
            Group(id: "feel", label: String(localized: "Feel", bundle: .strings), tags: [
                Tag(id: "steady", label: String(localized: "Steady", bundle: .strings)),
                Tag(id: "double-time", label: String(localized: "Double time", bundle: .strings)),
                Tag(id: "laid-back", label: String(localized: "Laid back", bundle: .strings)),
                Tag(id: "build-up", label: String(localized: "Build-up", bundle: .strings)),
            ]),
            Group(id: "use", label: String(localized: "Use", bundle: .strings), tags: [
                Tag(id: "kirtan", label: String(localized: "Kirtan", bundle: .strings)),
                Tag(id: "bhajan", label: String(localized: "Bhajan", bundle: .strings)),
                Tag(id: "arati", label: String(localized: "Arati", bundle: .strings)),
                Tag(id: "prayers", label: String(localized: "Prayers", bundle: .strings)),
            ]),
            Group(id: "part", label: String(localized: "Part", bundle: .strings), tags: [
                Tag(id: "main", label: String(localized: "Main beat", bundle: .strings)),
                Tag(id: "fill", label: String(localized: "Fill", bundle: .strings)),
                Tag(id: "turnaround", label: String(localized: "Turnaround", bundle: .strings)),
                Tag(id: "ending", label: String(localized: "Ending", bundle: .strings)),
            ]),
            Group(id: "level", label: String(localized: "Level", bundle: .strings), tags: [
                Tag(id: "beginner", label: String(localized: "Beginner", bundle: .strings)),
                Tag(id: "intermediate", label: String(localized: "Intermediate", bundle: .strings)),
                Tag(id: "advanced", label: String(localized: "Advanced", bundle: .strings)),
            ]),
            Group(id: "practice", label: String(localized: "Hasta sadhana (practice)", bundle: .strings), tags: [
                Tag(id: "exercise-beginner", label: String(localized: "Beginner exercise", bundle: .strings)),
                Tag(id: "exercise-intermediate", label: String(localized: "Intermediate exercise", bundle: .strings)),
                Tag(id: "exercise-advanced", label: String(localized: "Advanced exercise", bundle: .strings)),
            ]),
        ]
    }

    static var allTags: [Tag] { groups.flatMap(\.tags) }

    /// `ids` in vocabulary order, unknown ones dropped — for display.
    static func known<C: Collection>(_ ids: C) -> [Tag] where C.Element == String {
        let wanted = Set(ids)
        return allTags.filter { wanted.contains($0.id) }
    }

    /// "Keherwa · Kirtan", or nil when none are known.
    static func line<C: Collection>(_ ids: C) -> String? where C.Element == String {
        let tags = known(ids)
        return tags.isEmpty ? nil : tags.map(\.label).joined(separator: " · ")
    }
}

/// The user's private tags, per beat id, on this device only. Separate from the beat so
/// they work on built-in and community beats (which are read-only) and are never published.
@MainActor
@Observable
final class PrivateTags {
    private static let key = "kirtan-private-tags"
    private let defaults: UserDefaults
    private(set) var all: [String: [String]]

    init(_ defaults: UserDefaults = .standard) {
        self.defaults = defaults
        all = defaults.dictionary(forKey: Self.key) as? [String: [String]] ?? [:]
    }

    func tags(_ beatId: String?) -> [String] { beatId.flatMap { all[$0] } ?? [] }

    func set(_ beatId: String, _ tags: [String]) {
        if tags.isEmpty { all[beatId] = nil } else { all[beatId] = tags }
        defaults.set(all, forKey: Self.key)
    }
}
