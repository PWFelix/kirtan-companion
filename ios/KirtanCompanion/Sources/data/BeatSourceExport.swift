import Foundation

// Exports a beat as SOURCE, for replacing the built-in set.
//
// Ported from data/BeatSourceExport.kt. This exists because ShareCodec CANNOT do this
// job, and the reason is the opposite of an oversight: the share format is a trust
// boundary, so it drops `id`, `group` and `description` on purpose — an id from a
// stranger's link could silently overwrite one of your beats, and a description renders
// as prose in the info sheet, which is a fine place to phish from. Those same three
// fields are exactly what a MAINTAINER needs in order to replace a baked-in beat.
//
// So the export is deliberately separate, deliberately explicit about what it is doing,
// and deliberately not reachable from a shared link. It writes out the literals you paste
// into data/Beats.swift, data/Beats.kt or src/data/beats.js, preserving every field
// including the transcription prose. THE THREE MUST STAY IN SYNC — a shared link carries
// no id, so each client resolves built-in ids against its own compiled list, and
// divergent patterns mean the same link plays a different beat on each platform.

enum BeatSourceExport {

    /// One lane as the compact notation used by the share format and by hand.
    static func patternNotation(_ beat: Beat, _ lane: LaneId) -> String {
        guard let cells = beat.pattern(lane) else { return "" }
        var s = ""
        for cell in cells { s.append(cell?.code ?? ShareCodec.REST_CHAR) }
        return s
    }

    /// Every field, as JSON — the machine-readable form: it carries what the wire format
    /// refuses to, so a script (or the test suite) can diff a baked-in beat against a
    /// corrected one.
    static func toJson(_ beat: Beat) -> JValue {
        var lanes: [JPair] = []
        for lane in LaneId.ordered {
            let notation = patternNotation(beat, lane)
            if !notation.isEmpty { lanes.append(JPair(key: lane.wireId, value: .string(notation))) }
        }
        return .obj(
            ("id", beat.id.map(JValue.string) ?? .null),
            ("name", .string(beat.name)),
            ("note", .string(beat.note)),
            ("group", beat.group.map(JValue.string) ?? .null),
            ("bpm", .int(beat.bpm)),
            ("steps", .int(beat.steps)),
            ("beatsPerBar", .number(beat.beatsPerBar)),
            ("cellsPerGroup", .int(beat.cellsPerGroup)),
            ("groups", .arr(groupsFor(beat).map { .int($0) })),
            ("description", beat.description.map(JValue.string) ?? .null),
            ("p", .object(lanes)),
        )
    }

    /// A `builtIn(…)` call ready to paste into data/Beats.swift, laid out the same way the
    /// existing entries are, because the file is read far more often than it is written
    /// and its value is that a transcriber can line the pattern up against the book.
    static func toSwiftSource(_ beat: Beat) -> String {
        let groups = groupsFor(beat)
        var s = "        builtIn(\n"
        s += "            id: \(swiftString(beat.id)), group: \(swiftString(beat.group)), name: \(swiftString(beat.name)), note: \(swiftString(beat.note)),\n"
        // groups and cpq, NOT steps/beatsPerBar/cellsPerGroup: builtIn derives those three
        // the same way ShareCodec does, so an exported entry cannot contradict the meter
        // it declares. Emitting the derived fields instead would let a paste introduce an
        // inconsistency the compiler accepts.
        s += "            bpm: \(beat.bpm), groups: [\(intList(groups))], cpq: \(cpqFor(beat)),\n"
        if let description = beat.description {
            s += "            description: \(swiftString(description)),\n"
        }
        s += "            //      \(labelsFromGroups(groups).joined(separator: " ")),\n"
        s += "            dayan: \(swiftString(patternNotation(beat, .dayan))),\n"
        s += "            bayan: \(swiftString(patternNotation(beat, .bayan))),\n"
        if beat.pattern(.kartal) != nil {
            s += "            kartal: \(swiftString(patternNotation(beat, .kartal))),\n"
        }
        s += "        ),"
        return s
    }

    /// A `builtIn(…)` call ready to paste into data/Beats.kt (keeps the Android file in
    /// step with a correction made on iOS).
    static func toKotlinSource(_ beat: Beat) -> String {
        let groups = groupsFor(beat)
        var s = "    builtIn(\n"
        s += "        id = \(kotlinString(beat.id)), group = \(kotlinString(beat.group)), name = \(kotlinString(beat.name)), note = \(kotlinString(beat.note)),\n"
        s += "        bpm = \(beat.bpm), groups = listOf(\(intList(groups))), cpq = \(cpqFor(beat)),\n"
        if let description = beat.description {
            s += "        description = \(kotlinString(description)),\n"
        }
        s += "        //      \(labelsFromGroups(groups).joined(separator: " ")),\n"
        s += "        dayan = \"\(patternNotation(beat, .dayan))\",\n"
        s += "        bayan = \"\(patternNotation(beat, .bayan))\",\n"
        if beat.pattern(.kartal) != nil {
            s += "        kartal = \"\(patternNotation(beat, .kartal))\",\n"
        }
        s += "    ),"
        return s
    }

    /// A beat object ready to paste into src/data/beats.js — the ARRAY form the web file
    /// uses rather than the compact string, so the files read the same way to a human.
    static func toJsSource(_ beat: Beat) -> String {
        let groups = groupsFor(beat)
        var s = "  {\n"
        s += "    id: \(jsString(beat.id)),\n"
        s += "    name: \(jsString(beat.name)),\n"
        s += "    note: \(jsString(beat.note)),\n"
        s += "    group: \(jsString(beat.group)),\n"
        s += "    bpm: \(beat.bpm),\n"
        s += "    steps: \(beat.steps),\n"
        s += "    beatsPerBar: \(formatDouble(beat.beatsPerBar)),\n"
        s += "    cellsPerGroup: \(beat.cellsPerGroup),\n"
        s += "    groups: [\(intList(groups))],\n"
        s += "    description: \(jsString(beat.description)),\n"
        s += "    // \(labelsFromGroups(groups).joined(separator: " "))\n"
        for lane in LaneId.ordered {
            guard let cells = beat.pattern(lane) else { continue }
            let items = cells.map { cell in cell == nil ? "null" : "\"\(cell!.code)\"" }
            s += "    \(lane.wireId): [\(items.joined(separator: ","))],\n"
        }
        s += "  },"
        return s
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    /// A Swift string literal, or `nil` for an absent value.
    static func swiftString(_ value: String?) -> String {
        guard let value else { return "nil" }
        return "\"" + escape(value) + "\""
    }

    static func kotlinString(_ value: String?) -> String {
        guard let value else { return "null" }
        return "\"" + escape(value) + "\""
    }

    static func jsString(_ value: String?) -> String {
        guard let value else { return "null" }
        return "\"" + escape(value) + "\""
    }

    /// Escape the characters that would break out of a quoted literal. The transcribed
    /// descriptions contain typographic quotes (“ ”) and apostrophes, which are harmless,
    /// but a straight double quote or a backslash in a name would terminate the literal
    /// early — and a pasted source file that fails to compile is worse than one that is
    /// ugly. Swift/Kotlin need `$` escaped in strings too (string interpolation); JS does
    /// not, but escaping it is harmless there, so one helper serves all three.
    private static func escape(_ value: String) -> String {
        var out = ""
        for ch in value {
            switch ch {
            case "\\": out += "\\\\"
            case "\"": out += "\\\""
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "$":  out += "\\$"
            default:   out.append(ch)
            }
        }
        return out
    }

    /// `4` rather than `4.0` where the value is whole, so a pasted 4/4 beat reads like
    /// every existing entry; `3.5` stays `3.5` where the meter really is fractional.
    static func formatDouble(_ value: Double) -> String {
        if value.isFinite, value == value.rounded(), abs(value) < 1e15 {
            return String(Int(value))
        }
        return String(value)
    }

    /// A comma-joined `[Int]`, for the `groups = [...]` literal.
    static func intList(_ groups: [Int]) -> String { groups.map { String($0) }.joined(separator: ", ") }
}
