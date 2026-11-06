import Foundation

// The stored shape of a beat and a category — the one codec both the on-device store and
// the cloud jsonb column go through.
//
// WHY THIS FILE EXISTS SEPARATELY. LocalBeatsProvider keeps beats as a JSON array under
// one store key and SupabaseBeatsProvider keeps each beat's body in a `beats.data` jsonb
// column; those are the same bytes in two envelopes, and the cloud envelope is shared with
// the WEB app. A beat saved from a browser has to open on a phone and vice versa, so the
// field names below are the web's, not an invention of this port:
//
//     { id, name, note, bpm, steps, beatsPerBar, cellsPerGroup,
//       groups?, description?, group?, dayan, bayan, kartal? }
//
// with each lane an array of "O" / "X" / null, one entry per cell. That is the STORED
// shape; ShareCodec's compact "X-OO-XOO" strings are the SHARED shape — a link has to
// survive a messenger, a database row does not.
//
// `id` is written by the local store and deliberately NOT written into the cloud blob,
// where the primary-key column already holds it. Duplicating it there is how two sources
// of truth get out of step; supabaseProvider.js strips it for the same reason.
//
// ── POSTURE ──
// Like ShareCodec, this codec NEVER THROWS: a shape it does not recognise decodes to
// null and the CALLER decides what that means. The two callers decide differently, on
// purpose — LocalBeatsProvider turns a null into a StorageError so a corrupt store
// refuses to be overwritten, while SupabaseBeatsProvider turns it into a StorageError
// naming the row. Keeping the policy out of the codec is what lets both be right.
//
// ── WHY DECODING IS ALL-OR-NOTHING PER LIST ──
// Skipping a beat that fails to decode and writing the rest back would be a SILENT
// PARTIAL DELETE: the user opens the app, one beat is missing, and nothing anywhere says
// why. Refusing the whole list leaves the bytes where a later release can recover them,
// and surfaces one honest error instead.
//
// ── TOLERANCE ──
// Unknown fields are ignored, so a row written by a NEWER app still reads here. Numbers
// are read as doubles and rounded, because every JSON number the web encoder emits is a
// JS double: a browser-saved "bpm": 90 and a hypothetical 90.0 are the same beat.

enum LibraryJson {

    private static let keyID = "id"
    private static let keyName = "name"
    private static let keyNote = "note"
    private static let keyBpm = "bpm"
    private static let keySteps = "steps"
    private static let keyBeatsPerBar = "beatsPerBar"
    private static let keyCellsPerGroup = "cellsPerGroup"
    private static let keyGroups = "groups"
    private static let keyDescription = "description"
    private static let keyGroup = "group"
    private static let keySourceItemId = "sourceItemId"
    private static let keyBeatIds = "beatIds"

    // ── Encoding ───────────────────────────────────────────────────────────

    /// One lane's cells as ["O", null, "X", …], one entry per cell.
    private static func encodePattern(_ cells: [Stroke?]) -> JValue {
        .arr(cells.map { $0.map { JValue.string($0.code) } ?? .null })
    }

    /// The beat's body — everything except its id. Lanes are iterated in render order and
    /// an absent lane is OMITTED rather than written as all-rests, so a beat with no
    /// karatala row stays a two-lane beat on the wire.
    static func encodeBody(_ beat: Beat) -> JValue {
        var pairs: [JPair] = [
            JPair(key: keyName, value: .string(beat.name)),
            JPair(key: keyNote, value: .string(beat.note)),
            JPair(key: keyBpm, value: .int(beat.bpm)),
            JPair(key: keySteps, value: .int(beat.steps)),
            JPair(key: keyBeatsPerBar, value: .number(beat.beatsPerBar)),
            JPair(key: keyCellsPerGroup, value: .int(beat.cellsPerGroup)),
        ]
        // Omitted, not written as null: the web encoder leaves `groups` off a built-in
        // entirely, and an explicit null would read as "a groups array that happens to be
        // empty" to anything checking for the key's presence.
        if let groups = beat.groups {
            pairs.append(JPair(key: keyGroups, value: .arr(groups.map { .int($0) })))
        }
        if let description = beat.description {
            pairs.append(JPair(key: keyDescription, value: .string(description)))
        }
        if let group = beat.group {
            pairs.append(JPair(key: keyGroup, value: .string(group)))
        }
        if let source = beat.sourceItemId {
            pairs.append(JPair(key: keySourceItemId, value: .string(source)))
        }
        for lane in LaneId.ordered {
            if let cells = beat.pattern(lane) {
                pairs.append(JPair(key: lane.wireId, value: encodePattern(cells)))
            }
        }
        return .object(pairs)
    }

    /// The body plus its id — the shape the on-device store keeps.
    static func encodeStored(_ beat: Beat) -> JValue {
        var pairs: [JPair] = []
        if let id = beat.id { pairs.append(JPair(key: keyID, value: .string(id))) }
        if case .object(let body) = encodeBody(beat) { pairs.append(contentsOf: body) }
        return .object(pairs)
    }

    static func encodeBeats(_ beats: [Beat]) -> String {
        JValue.arr(beats.map(encodeStored)).write()
    }

    static func encodeCategory(_ category: Category) -> JValue {
        .obj(
            (keyID, .string(category.id)),
            (keyName, .string(category.name)),
            (keyBeatIds, .arr(category.beatIds.map { .string($0) })),
        )
    }

    static func encodeCategories(_ categories: [Category]) -> String {
        JValue.arr(categories.map(encodeCategory)).write()
    }

    // ── Decoding ───────────────────────────────────────────────────────────

    /// An optional groups array. Every entry must be a sane group size and the array a
    /// sane length; the SUM is not required to equal `steps`, because a mismatch there
    /// mislabels a bar rather than hanging the app. Returns (groups, wasPresentButInvalid)
    /// — one nullable return cannot say both, and conflating them would reject every
    /// shipped beat.
    private static func groupsField(_ obj: JValue) -> (groups: [Int]?, invalid: Bool) {
        guard let element = obj.element(keyGroups) else { return (nil, false) }
        if element.isNull() { return (nil, false) }
        guard let array = element.asArray() else { return (nil, true) }
        if array.isEmpty || array.count > ShareCodec.MAX_GROUPS { return (nil, true) }
        var groups: [Int] = []
        for item in array {
            guard let value = item.asNumber(), value.isFinite else { return (nil, true) }
            let cells = Int(value.rounded())
            if cells < 1 || cells > ShareCodec.MAX_GROUP_CELLS { return (nil, true) }
            groups.append(cells)
        }
        return (groups, false)
    }

    /// One lane's cells, with the same two-answer contract as groupsField: nil with
    /// `invalid` false means the beat has no row for that lane, and nil with `invalid`
    /// true means the row is there and wrong (a short array, an unknown stroke code).
    /// Telling those apart matters for the karatalas: a beat with no cymbal row is
    /// perfectly ordinary; a DAMAGED one must not be quietly dropped.
    private static func patternField(_ obj: JValue, lane: LaneId, steps: Int) -> (cells: [Stroke?]?, invalid: Bool) {
        guard let element = obj.element(lane.wireId) else { return (nil, false) }
        if element.isNull() { return (nil, false) }
        guard let array = element.asArray() else { return (nil, true) }
        guard array.count == steps else { return (nil, true) }
        var cells: [Stroke?] = []
        for item in array {
            if item.isNull() { cells.append(nil); continue }
            guard let code = item.asString() else { return (nil, true) }
            // An unrecognised code is corruption, not a rest: reading it as a rest would
            // silently delete a stroke the user drew.
            guard let stroke = Stroke.fromCode(code) else { return (nil, true) }
            cells.append(stroke)
        }
        return (cells, false)
    }

    /// A stored beat, or null when the blob is not a beat this app wrote. Both mridanga
    /// ends are REQUIRED: a blob without them would put a patternless row in the library
    /// that plays silence while looking fine in the list.
    static func decodeBeat(_ element: JValue?) -> Beat? {
        guard let obj = element, case .object = obj else { return nil }

        guard let name = obj.stringField(keyName),
              let note = obj.stringField(keyNote),
              let bpm = obj.intField(keyBpm),
              let steps = obj.intField(keySteps),
              let beatsPerBar = obj.doubleField(keyBeatsPerBar),
              let cellsPerGroup = obj.intField(keyCellsPerGroup)
        else { return nil }

        if !(MIN_BPM...MAX_BPM).contains(bpm) { return nil }
        if !(1...ShareCodec.MAX_STEPS).contains(steps) { return nil }
        if beatsPerBar < 1 || cellsPerGroup < 1 { return nil }

        let (groups, badGroups) = groupsField(obj)
        if badGroups { return nil }

        var lanes: [LaneId: [Stroke?]] = [:]
        for lane in LaneId.ordered {
            let (cells, rejected) = patternField(obj, lane: lane, steps: steps)
            if rejected { return nil }
            if let cells { lanes[lane] = cells }
        }
        guard lanes[.dayan] != nil, lanes[.bayan] != nil else { return nil }

        return Beat(
            id: obj.stringField(keyID),
            name: name,
            note: note,
            bpm: bpm,
            steps: steps,
            beatsPerBar: beatsPerBar,
            cellsPerGroup: cellsPerGroup,
            groups: groups,
            description: obj.stringField(keyDescription),
            lanePatterns: lanes,
            group: obj.stringField(keyGroup),
            sourceItemId: obj.stringField(keySourceItemId),
        )
    }

    /// A stored category, or null when the blob is not one. An absent or null beatIds
    /// list is an empty progression, not corruption; a NUMBER in it is damage — coercing
    /// it would quietly rename a beat reference to something that matches nothing.
    static func decodeCategory(_ element: JValue?) -> Category? {
        guard let obj = element, case .object = obj else { return nil }
        guard let id = obj.stringField(keyID), !id.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        guard let name = obj.stringField(keyName) else { return nil }

        var beatIds: [String] = []
        if let element = obj.element(keyBeatIds), !element.isNull() {
            guard let array = element.asArray() else { return nil }
            for item in array {
                guard let s = item.asString() else { return nil }
                beatIds.append(s)
            }
        }
        return Category(id: id, name: name, beatIds: beatIds)
    }

    /// Parse a stored list of beats. Returns the beats, or nil when the text is not a
    /// JSON array of decodable beats. Nil and "an empty library" are DIFFERENT answers:
    /// the first is a failure to surface, the second is a perfectly good state.
    static func decodeBeats(_ raw: String?) -> [Beat]? {
        decodeList(raw) { LibraryJson.decodeBeat($0) }
    }

    static func decodeCategories(_ raw: String?) -> [Category]? {
        decodeList(raw) { LibraryJson.decodeCategory($0) }
    }

    private static func decodeList<T>(_ raw: String?, _ decode: (JValue?) -> T?) -> [T]? {
        // Absent is an empty library — the ONLY tolerant case in this file.
        guard let raw else { return [] }
        guard let array = JsonEl.parse(raw), let items = array.asArray() else { return nil }
        var out: [T] = []
        for item in items {
            // One undecodable row rejects the whole list; see the header.
            guard let decoded = decode(item) else { return nil }
            out.append(decoded)
        }
        return out
    }
}
