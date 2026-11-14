import Foundation

// Packing a beat (or a whole category) into a share code, and — the half that matters —
// unpacking one safely.
//
// Ported from src/data/shareCodec.js, which is the app's FIRST UNTRUSTED INPUT.
// Everything else comes from the user's own hands; this comes from a stranger and ends
// up written to storage, where it persists across sessions. So this file is a TRUST
// BOUNDARY: the only place that touches a payload it did not create, and everything
// downstream receives either a clean, freshly-constructed object or nil.
//
// The rules that keep it a boundary (all preserved):
//  1. NEVER COPY AN OBJECT WHOLESALE — read NAMED fields and construct new values.
//     Patterns are read by iterating LaneId.ordered, an allowlist not a denylist.
//  2. NEVER TRUST AN ID — there is no id in the format; ids are minted locally.
//  3. NEVER THROW — decodeShare returns nil on anything unexpected. The caller must
//     also consume the link once and clear it (see ShareIntake).
//  4. HARD BOUNDS ON EVERY NUMBER — steps: 1e9 must not allocate anything.
//  5. NO PROSE — description and group are dropped, not validated.
//
// ── WIRE FORMAT (must stay byte-compatible with the web app) ──
//   one beat    { v:1, t:"b", n, m, g, q, p }
//   a category  { v:1, t:"c", n, b:[ {n,m,g,q,p}, ... ] }
// where n = name, m = bpm, g = groups, q = cells per quarter, p = patterns keyed by
// LANE ID, each a string with "-" for a rest. steps/beatsPerBar/cellsPerGroup/note are
// DERIVED on import, mirroring the editor's save, so a beat round-trips identically.

enum ShareCodec {

    static let VERSION = 1
    static let REST_CHAR = "-"

    // Bounds: generous enough no real beat hits them, tight enough a hostile payload
    // can't allocate anything interesting.
    static let MAX_CODE_LEN = 8000
    static let MAX_NAME_LEN = 40
    static let MAX_GROUPS = 32
    static let MAX_GROUP_CELLS = 27  // a 9-matra tala ×3; 12 before 0.4.35
    static let MAX_STEPS = 384
    static let MAX_CPQ = 27  // a 9-matra tala ×3; 12 before 0.4.35
    static let MAX_CAT_BEATS = 50

    static let DEFAULT_NAME_BEAT = "Shared Beat"
    static let DEFAULT_NAME_CATEGORY = "Shared List"
    static let DEFAULT_BPM = 90

    // base64url alphabet: everything a share code may legitimately contain.
    private static let codeChars = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")

    // ── Result types ───────────────────────────────────────────────────────

    /// What a decoded share turned out to be.
    indirect enum SharePayload: Equatable {
        /// One beat, with id == nil — the library mints one on import.
        case beat(Beat)
        case category(name: String, beats: [Beat])
        /// A #b= link was present but unusable. Distinct from "no link at all" so the
        /// UI can say "that link didn't work". The caller MUST consume it once.
        case invalid
    }

    // ── base64url ──────────────────────────────────────────────────────────
    // Plain base64 is not URL-safe: "+" becomes a space, "=" terminates awkwardly.
    // UTF-8 first, so a name in Devanagari survives the trip.

    static func toBase64Url(_ text: String) -> String {
        var s = Data(text.utf8).base64EncodedString()
        s = s.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return s
    }

    static func fromBase64Url(_ code: String) -> String? {
        var s = code.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        guard let data = Data(base64Encoded: s),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    // ── Encoding (trusted side — these beats are already in the library) ────

    /// One lane's pattern as a fixed-length string: exactly `steps` characters whatever
    /// the array holds — extra data ignored, missing data rests.
    private static func packPattern(_ beat: Beat, _ lane: LaneId, steps: Int) -> String? {
        guard let cells = beat.pattern(lane) else { return nil }
        var s = ""
        s.reserveCapacity(steps)
        for i in 0..<steps {
            s.append(i < cells.count ? (cells[i]?.code ?? REST_CHAR) : REST_CHAR)
        }
        return s
    }

    private static func packBeat(_ beat: Beat) -> JValue {
        let groups = groupsFor(beat)
        let steps = sumGroups(groups)
        var p: [JPair] = []
        for lane in LaneId.ordered {
            if let packed = packPattern(beat, lane, steps: steps) {
                p.append(JPair(key: lane.wireId, value: .string(packed)))
            }
        }
        let base = JValue.obj(
            ("n", .string(beat.name)),
            ("m", .int(beat.bpm)),
            ("g", .arr(groups.map { .int($0) })),
            ("q", .int(cpqFor(beat))),
            ("p", .object(p)),
        )
        // Swing rides as "w", only when set: older apps ignore it and play straight.
        guard beat.swing > Swing.straight, case .object(var pairs) = base else { return base }
        pairs.append(JPair(key: "w", value: .int(Swing.clamp(beat.swing))))
        return .object(pairs)
    }

    static func encodeBeat(_ beat: Beat) -> String {
        // Key order matches the JS encoder field-for-field, so the same beat produces
        // the same code on both platforms and links are diffable.
        var pairs = [JPair(key: "v", value: .int(VERSION)), JPair(key: "t", value: .string("b"))]
        if case .object(let extra) = packBeat(beat) { pairs.append(contentsOf: extra) }
        return toBase64Url(JValue.object(pairs).write())
    }

    static func encodeCategory(name: String, beats: [Beat]) -> String {
        let payload = JValue.obj(
            ("v", .int(VERSION)),
            ("t", .string("c")),
            ("n", .string(name)),
            ("b", .arr(beats.map(packBeat))),
        )
        return toBase64Url(payload.write())
    }

    // ── Decoding (untrusted side — assume every field is hostile) ──────────

    /// Strip control/format characters, trim, cap. Not ASCII-only: real names may be
    /// Devanagari. The cap is about layout; the strip is about invisible
    /// direction-override and zero-width tricks in a name that will sit beside "Built in".
    private static func cleanName(_ value: JValue?, fallback: String) -> String {
        guard let raw = value?.asString() else { return fallback }
        // Strip control (Cc) and format (Cf) scalars — the invisible characters used for
        // direction-override and zero-width tricks in a name that will sit beside
        // "Built in". This is the \p{C} rule from the web codec, narrowed to the two
        // categories that are actually dangerous.
        var cleaned = ""
        cleaned.reserveCapacity(raw.count)
        for scalar in raw.unicodeScalars {
            let g = scalar.properties.generalCategory
            if g == .control || g == .format { continue }
            cleaned.unicodeScalars.append(scalar)
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty { return fallback }
        return String(cleaned.prefix(MAX_NAME_LEN))
    }

    private static func validGroups(_ element: JValue?) -> [Int]? {
        guard let array = element?.asArray(), !array.isEmpty, array.count <= MAX_GROUPS else { return nil }
        var groups: [Int] = []
        for item in array {
            guard let n = item.asNumber(), n.isFinite, n == n.rounded() else { return nil }
            let iv = Int(n)
            if iv < 1 || iv > MAX_GROUP_CELLS { return nil }
            groups.append(iv)
        }
        let steps = sumGroups(groups)
        guard (1...MAX_STEPS).contains(steps) else { return nil }
        return groups
    }

    /// One lane's pattern. Returns (cells, rejected): nil-with-false = absent lane
    /// (all rests, normal); nil-with-true = present but invalid (a rejection).
    private static func unpackPattern(_ patterns: JValue, lane: LaneId, steps: Int) -> ([Stroke?]?, Bool) {
        let element = patterns.element(lane.wireId)
        if element == nil || element?.isNull() == true {
            return (Array(repeating: nil, count: steps), false)
        }
        guard let text = element?.asString(), text.count == steps else { return (nil, true) }
        var cells: [Stroke?] = []
        for ch in text {
            cells.append(String(ch) == REST_CHAR ? nil : Stroke.fromCode(String(ch)))
        }
        return (cells, false)
    }

    /// One beat, as a DRAFT: everything a beat needs except an id, minted on import.
    private static func decodeBeat(_ raw: JValue?) -> Beat? {
        guard let obj = raw, case .object = obj else { return nil }

        guard let groups = validGroups(obj.element("g")) else { return nil }

        guard let q = obj.intField("q"), (1...MAX_CPQ).contains(q) else { return nil }
        guard let p = obj.element("p"), case .object = p else { return nil }
        let patterns = p

        let steps = sumGroups(groups)
        var lanes: [LaneId: [Stroke?]] = [:]
        for lane in LaneId.ordered {
            let (cells, rejected) = unpackPattern(patterns, lane: lane, steps: steps)
            if rejected || cells == nil { return nil }
            lanes[lane] = cells
        }

        let bpmRaw = obj.doubleField("m")
        let bpm = (bpmRaw != nil && bpmRaw!.isFinite)
            ? min(max(Int(bpmRaw!.rounded()), MIN_BPM), MAX_BPM)
            : DEFAULT_BPM

        // Derived exactly as the editor's save derives them, so a shared beat and its
        // original are the same object apart from the id.
        let uniform = groups.allSatisfy { $0 == groups[0] }

        return Beat(
            id: nil,                 // NEVER trusted from a payload; see rule 2
            name: cleanName(obj.element("n"), fallback: DEFAULT_NAME_BEAT),
            note: "Custom",
            bpm: bpm,
            steps: steps,
            beatsPerBar: Double(steps) / Double(q),
            cellsPerGroup: uniform ? groups[0] : q,
            groups: groups,
            description: nil,        // dropped, not validated; see rule 5
            lanePatterns: lanes,
            group: nil,              // dropped for the same reason
            // Junk or out-of-range plays straight (or at the nearest end) rather than refusing the beat.
            swing: Swing.clamp(obj.doubleField("w").flatMap { $0.isFinite ? Int($0.rounded()) : nil } ?? Swing.straight),
        )
    }

    /// The one entry point for untrusted data. Never throws — that is the contract.
    static func decodeShare(_ code: String?) -> SharePayload? {
        guard let code else { return nil }
        // Cheap checks first, so a 20 MB paste never reaches the decoder or the parser.
        guard !code.isEmpty, code.count <= MAX_CODE_LEN else { return nil }
        guard code.unicodeScalars.allSatisfy({ codeChars.contains($0) }) else { return nil }

        guard let jsonText = fromBase64Url(code),
              let raw = JsonEl.parse(jsonText),
              case .object = raw else { return nil }

        // Exact version match: a future v2 fails cleanly rather than half-parsing.
        guard let version = raw.intField("v"), version == VERSION else { return nil }

        switch raw.stringField("t") {
        case "b":
            if let beat = decodeBeat(raw) { return .beat(beat) }
            return nil

        case "c":
            guard let list = raw.element("b")?.asArray(), !list.isEmpty, list.count <= MAX_CAT_BEATS else { return nil }
            var beats: [Beat] = []
            for entry in list {
                // One bad beat rejects the whole list: a half-imported playlist is worse
                // than a refused one, because the user cannot tell which beats are missing.
                guard let beat = decodeBeat(entry) else { return nil }
                beats.append(beat)
            }
            return .category(
                name: cleanName(raw.element("n"), fallback: DEFAULT_NAME_CATEGORY),
                beats: beats,
            )

        default:
            return nil
        }
    }

    /// A pasted value may be a whole URL or a bare code; take the code either way.
    /// Uses the LAST occurrence, so a link whose own query contains `#b=` still works.
    static func codeFromInput(_ text: String?) -> String {
        guard let text else { return "" }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let last = trimmed.range(of: "#b=", options: .backwards) else { return trimmed }
        let suffixStart = trimmed.index(last.lowerBound, offsetBy: 3)
        return trimmed[suffixStart...].description
    }
}
