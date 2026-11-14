import Foundation

// The value types every layer speaks in.
//
// Ported from data/model/Beat.kt, which in turn ports src/data/lanes.js,
// strokes.js and the shape in src/storage/BeatsProvider.js. A rest is a Swift
// `nil` and the two strokes are an enum, so an impossible stroke cannot be built.

/// One instrument row: an instrument, or one end of the mridanga.
enum LaneId: String, Codable, CaseIterable, Hashable {
    case dayan, bayan
    /// Karatalas — the congregation's timekeeper. Not primary: they colour a beat,
    /// they don't define its rhythmic identity.
    case kartal

    var wireId: String { rawValue }

    var label: String {
        switch self {
        case .dayan:  "Dayan"
        case .bayan:  "Bayan"
        case .kartal: "Kartal"
        }
    }

    /// Primary lanes are a beat's rhythmic identity (the mridanga). MINI strips —
    /// beat lists, quick-pick — render primary lanes only: at 8px marks four stacked
    /// lanes are noise. Mirrors the `primary` flag in lanes.js.
    var isPrimary: Bool { self != .kartal }

    /// Lanes in render order, top to bottom.
    static let ordered: [LaneId] = [.dayan, .bayan, .kartal]

    static func fromWire(_ id: String) -> LaneId? { LaneId(rawValue: id) }
}

/// A struck cell. The two values are the only strokes the app knows; a rest is
/// `nil` in a pattern, so `[Stroke?]` reads exactly like the JS `["O", null, "X"]`.
enum Stroke: String, Codable, Hashable {
    case open = "O"
    case closed = "X"

    var code: String { rawValue }
    static func fromCode(_ code: String) -> Stroke? { Stroke(rawValue: code) }
}

/// One playable pattern.
///
/// BPM is the quarter-note PULSE (the 1-2-3-4 you'd clap along to) and `beatsPerBar`
/// is how many of those pulses make one bar, so the transport derives the per-cell
/// interval from `steps / beatsPerBar`. 8 in 4 → eighths; 12 in 4 → eighth triplets;
/// 16 in 4 → sixteenths. Keeping both explicit means a future 3/4 or 6/8 needs no
/// engine change.
///
/// `beatsPerBar` is a Double because uneven meters are fractional: 7/8 at eighth
/// subdivision is 3.5 quarters to the bar. Timing never divides by it — the
/// MusicalClock turns `PPQ × beatsPerBar` into whole ticks — it declares the meter.
///
/// `id` is nil for an unsaved editor draft; that absence is what makes saving a
/// built-in beat a FORK rather than an edit.
struct Beat: Hashable {
    var id: String?
    var name: String
    var note: String
    var bpm: Int
    var steps: Int
    var beatsPerBar: Double
    var cellsPerGroup: Int
    var groups: [Int]?
    var description: String?
    var lanePatterns: [LaneId: [Stroke?]]
    /// The built-in beats' section heading — "Everyday", "Building up", etc. Display
    /// metadata, not a category: "builtin" is the category, group only sub-divides it.
    var group: String?
    /// The community item this beat was copied from, or nil for a beat the user made (or
    /// got from a share link). Kept in the beat's stored body, so it syncs with the beat
    /// and needs no new column. Lets Community show "In your library" instead of letting
    /// you add the same item twice (issue #11). Beats copied from a list carry the LIST's id.
    var sourceItemId: String? = nil
    /// The author's tags, ids from TagVocabulary. Published with the beat. The user's
    /// PRIVATE tags are not here: they live per beat id on the device (PrivateTags), so
    /// they work on built-in and community beats and are never published.
    var tags: [String] = []
    /// For a beat from a community PLAYLIST: its position in that list, so it can be
    /// matched to the original for updates. Nil for a single beat.
    var sourceIndex: Int? = nil
    /// The community item an EDITED COPY was made from: credit, and what stops a copy
    /// being re-shared until it actually differs. Exclusive with sourceItemId.
    var forkedFrom: String? = nil
    /// Swing, 50 (straight) to 67 (triplet); see Swing. Saved and shared with the beat.
    var swing: Int = Swing.straight

    /// A community beat the user added: read-only, follows the original, can't be
    /// shared. Editing one makes a copy in Your beats.
    var isCommunity: Bool { sourceItemId != nil }

    /// True when this beat is one of the built-ins rather than user-made. Asks
    /// ShippedBeats, not the compiled list: the built-in set is served from the
    /// database, so a beat promoted after this build was compiled must still read-only.
    var isBuiltIn: Bool { ShippedBeats.shared.isShippedId(id) }

    /// Built-in and community beats are read-only; editing one makes a copy.
    var readOnly: Bool { isBuiltIn || isCommunity }

    /// A lane's pattern, or nil when the beat has no row for that lane.
    func pattern(_ lane: LaneId) -> [Stroke?]? { lanePatterns[lane] }

    /// The stroke at `index` on `lane`. Absent lanes and short arrays both read as a
    /// rest, which is what lets a beat omit the karatalas entirely.
    func strokeAt(_ lane: LaneId, _ index: Int) -> Stroke? {
        guard let cells = lanePatterns[lane], index < cells.count else { return nil }
        return cells[index]
    }

    /// Lanes this beat actually uses, in render order.
    func activeLanes() -> [LaneId] { LaneId.ordered.filter { lanePatterns[$0] != nil } }
}

/// An ordered set of beats — a kirtan progression. Home's ‹ › cycles the ACTIVE
/// category, which is what makes a playlist more than a folder. `beatIds` may mix a
/// built-in id with a custom beat's uuid.
struct Category: Hashable {
    var id: String
    var name: String
    var beatIds: [String]
}

/// Everything a provider owns: the user's saved work, and nothing else.
struct Library: Hashable {
    var beats: [Beat]
    var categories: [Category]
    var activeCategoryId: String?

    static let empty = Library(beats: [], categories: [], activeCategoryId: nil)
}

/// A beat published to the community library.
struct PublishedBeat {
    var id: String
    var authorId: String?
    var authorName: String?
    var beat: Beat
    var createdAt: Int?
}
