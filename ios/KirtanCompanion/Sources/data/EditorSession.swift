import Foundation
import Observation

/// The editor's four jobs. A built-in is never edited in place by a player — it FORKS,
/// because it is not theirs; only a maintainer may write it back, for everyone.
enum BeatEditMode: Equatable {
    /// A blank draft, saved as a new beat.
    case new
    /// One of your beats, saved over itself (same id, so playlists keep it).
    case edit(Beat)
    /// A built-in, saved as a new beat of your own.
    case fork(Beat)
    /// A built-in, written back to `shipped_beats` for every install (maintainers).
    case everyone(Beat)

    var seed: Beat? {
        switch self {
        case .new: return nil
        case .edit(let b), .fork(let b), .everyone(let b): return b
        }
    }

    var title: String {
        switch self {
        case .new: return String(localized: "New beat", bundle: .strings)
        case .edit: return String(localized: "Edit beat", bundle: .strings)
        case .fork: return String(localized: "Edit a copy", bundle: .strings)
        case .everyone: return String(localized: "Edit for everyone", bundle: .strings)
        }
    }
}

/// The editor's draft as plain values: everything a save writes, plus the cursor.
struct EditorSnapshot: Equatable {
    var name: String
    var bpm: Int
    var shape: EditorShape
    var dayan: [Stroke?]
    var bayan: [Stroke?]
    var kartal: [Stroke?]
    var tags: [String]
    var cursorLane = 0
    var cursorStep = 0

    /// The draft a beat opens as — or a blank one for a new beat.
    static func seeded(from beat: Beat?) -> EditorSnapshot {
        guard let beat else {
            let shape = EditorShape(groups: [2, 2, 2, 2], cpq: 2, unit: [2])
            let blank = [Stroke?](repeating: nil, count: shape.steps)
            return EditorSnapshot(name: String(localized: "New beat", bundle: .strings), bpm: 120, shape: shape,
                                  dayan: blank, bayan: blank, kartal: blank, tags: [])
        }
        let n = beat.steps
        func lane(_ id: LaneId) -> [Stroke?] {
            let cells = beat.pattern(id) ?? []
            return Array(cells.prefix(n)) + Array(repeating: nil, count: max(0, n - cells.count))
        }
        // The beat's own grouping, verbatim: a beat like matan (16 groups of 3) must not be
        // re-metered by an edit that never touched the meter.
        let groups = groupsFor(beat)
        let shape = groups.reduce(0, +) == n
            ? EditorShape.seeded(groups: groups, cpq: cpqFor(beat))
            : EditorShape.seeded(groups: [n], cpq: cpqFor(beat))
        return EditorSnapshot(name: beat.name, bpm: beat.bpm, shape: shape,
                              dayan: lane(.dayan), bayan: lane(.bayan), kartal: lane(.kartal), tags: beat.tags)
    }

    /// What a save would write, compared; the cursor doesn't count.
    func sameContent(as o: EditorSnapshot) -> Bool {
        let n = shape.steps
        func pad(_ a: [Stroke?]) -> [Stroke?] { Array(a.prefix(n)) + Array(repeating: nil, count: max(0, n - a.count)) }
        return n == o.shape.steps && shape.groups == o.shape.groups && shape.cpq == o.shape.cpq && bpm == o.bpm &&
            name.trimmingCharacters(in: .whitespaces) == o.name.trimmingCharacters(in: .whitespaces) &&
            pad(dayan) == pad(o.dayan) && pad(bayan) == pad(o.bayan) && pad(kartal) == pad(o.kartal) &&
            tags == o.tags
    }
}

/// The ONE beat being edited, held above the editor view.
///
/// The editor used to own its draft, and closing it was how a draft was discarded —
/// so stepping out and back threw the work away. Now the shell holds this session and
/// the view starts from it and writes every change back: close it, come back, and the
/// draft is where you left it. One session, not one per beat: opening a different edit
/// over unsaved changes asks first (NavigationShell).
@Observable
final class EditorSession {
    let mode: BeatEditMode
    var draft: EditorSnapshot

    init(mode: BeatEditMode, draft: EditorSnapshot? = nil) {
        self.mode = mode
        self.draft = draft ?? EditorSnapshot.seeded(from: mode.seed)
    }

    /// The draft's CONTENT differs from where it started — not "was touched", so
    /// putting a cell back clears it.
    var dirty: Bool { !draft.sameContent(as: EditorSnapshot.seeded(from: mode.seed)) }

    /// The same edit, back at its start: Revert, or Start over for a new beat.
    func fresh() -> EditorSession { EditorSession(mode: mode) }
}

/// The session on disk while dirty, so a draft survives the app being killed; removed
/// when clean. UserDefaults, like SessionPrefs, holding the LibraryJson beat format.
enum EditorSessionStore {
    private static let key = "kirtan.editorDraft"

    static func load() -> EditorSession? { decode(UserDefaults.standard.string(forKey: key)) }

    static func save(_ session: EditorSession?) {
        if let session, session.dirty {
            UserDefaults.standard.set(encode(session), forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    static func encode(_ s: EditorSession) -> String {
        let d = s.draft
        var lanes: [LaneId: [Stroke?]] = [.dayan: d.dayan, .bayan: d.bayan]
        if d.kartal.contains(where: { $0 != nil }) { lanes[.kartal] = d.kartal }
        let allSame = d.shape.groups.allSatisfy { $0 == d.shape.groups.first }
        let beat = Beat(id: nil, name: d.name.isEmpty ? "-" : d.name, note: "", bpm: d.bpm, steps: d.shape.steps,
                        beatsPerBar: d.shape.beatsPerBar,
                        cellsPerGroup: allSame ? (d.shape.groups.first ?? d.shape.cpq) : d.shape.cpq,
                        groups: d.shape.groups, description: nil, lanePatterns: lanes, group: nil)
        let kind: String
        switch s.mode {
        case .new: kind = "new"
        case .edit: kind = "edit"
        case .fork: kind = "fork"
        case .everyone: kind = "everyone"
        }
        var pairs: [(String, JValue)] = [
            ("mode", .str(kind)), ("name", .str(d.name)), ("draft", LibraryJson.encodeStored(beat)),
            ("tags", .arr(d.tags.map { .str($0) })), ("cursorLane", .int(d.cursorLane)), ("cursorStep", .int(d.cursorStep)),
        ]
        if let seed = s.mode.seed { pairs.append(("seed", LibraryJson.encodeStored(seed))) }
        return JValue.object(pairs.map { JPair(key: $0.0, value: $0.1) }).write()
    }

    static func decode(_ raw: String?) -> EditorSession? {
        guard let raw, let o = JsonEl.parse(raw), let beat = LibraryJson.decodeBeat(o.element("draft")) else { return nil }
        let seed = LibraryJson.decodeBeat(o.element("seed"))
        let mode: BeatEditMode
        switch (o.stringField("mode"), seed) {
        case ("edit", let b?): mode = .edit(b)
        case ("fork", let b?): mode = .fork(b)
        case ("everyone", let b?): mode = .everyone(b)
        default: mode = .new
        }
        var draft = EditorSnapshot.seeded(from: beat)
        draft.name = o.stringField("name") ?? draft.name
        draft.tags = (o.element("tags")?.asArray() ?? []).compactMap { $0.asString() }
        draft.cursorLane = min(max(o.intField("cursorLane") ?? 0, 0), 2)
        draft.cursorStep = min(max(o.intField("cursorStep") ?? 0, 0), draft.shape.steps - 1)
        return EditorSession(mode: mode, draft: draft)
    }
}
