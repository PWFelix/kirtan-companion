import Foundation

// Home's running order: beats in order, each held for a number of bars
// ("daspahir for 8, then tehai for 4, then …").
//
// Pure data, no audio. Entries refer to beats BY ID, so editing a beat changes
// it everywhere it is sequenced, and a deleted beat simply drops out of playback
// (its entry stays, shown as missing, until removed). Same shapes and rules as
// Android's Sequences.kt and the web's data/sequences.js.

struct SequenceEntry: Codable, Equatable {
    var beatId: String
    var bars: Int
}

/// A saved sequence.
struct BeatSequence: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var entries: [SequenceEntry]

    var totalBars: Int { Sequences.totalBars(entries) }
}

/// What Home shows and plays, kept across launches. `savedId` says which saved
/// sequence it was opened from, so Save can overwrite it.
struct WorkingSequence: Codable, Equatable {
    var entries: [SequenceEntry] = []
    var savedId: String?
    var name: String = ""
    var loop: Bool = false

    /// Unsaved changes worth a Save? An empty, never-saved sequence has none.
    func isDirty(_ saved: [BeatSequence]) -> Bool {
        guard let from = saved.first(where: { $0.id == savedId }) else { return !entries.isEmpty }
        return from.name != name || from.entries != entries
    }
}

enum Sequences {
    static let defaultBars = 4
    static let maxBars = 64

    static func clampBars(_ n: Int) -> Int { min(max(n, 1), maxBars) }

    static func add(_ entries: [SequenceEntry], _ beatId: String, bars: Int = defaultBars) -> [SequenceEntry] {
        entries + [SequenceEntry(beatId: beatId, bars: clampBars(bars))]
    }

    static func setBars(_ entries: [SequenceEntry], _ i: Int, _ bars: Int) -> [SequenceEntry] {
        guard entries.indices.contains(i) else { return entries }
        var out = entries; out[i].bars = clampBars(bars); return out
    }

    static func setBeat(_ entries: [SequenceEntry], _ i: Int, _ beatId: String) -> [SequenceEntry] {
        guard entries.indices.contains(i) else { return entries }
        var out = entries; out[i].beatId = beatId; return out
    }

    static func remove(_ entries: [SequenceEntry], _ i: Int) -> [SequenceEntry] {
        guard entries.indices.contains(i) else { return entries }
        var out = entries; out.remove(at: i); return out
    }

    static func move(_ entries: [SequenceEntry], _ from: Int, _ to: Int) -> [SequenceEntry] {
        guard entries.indices.contains(from) else { return entries }
        var out = entries
        let e = out.remove(at: from)
        out.insert(e, at: min(max(to, 0), out.count))
        return out
    }

    static func totalBars(_ entries: [SequenceEntry]) -> Int {
        entries.reduce(0) { $0 + clampBars($1.bars) }
    }

    /// What actually plays: entries whose beat still exists, each with the index
    /// of the entry it came from (to light the right chip).
    static func resolve(_ entries: [SequenceEntry], _ lookup: (String) -> Beat?) -> [(entryIndex: Int, step: SequenceStep)] {
        entries.enumerated().compactMap { i, e in
            lookup(e.beatId).map { (i, SequenceStep(beat: $0, bars: clampBars(e.bars))) }
        }
    }

    /// Save the working sequence: overwrite the one it came from, or (asNew, or
    /// never saved) add a fresh one. The working copy then points at it.
    static func save(_ working: WorkingSequence, into saved: [BeatSequence], name: String, asNew: Bool,
                     newId: String) -> (saved: [BeatSequence], working: WorkingSequence) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        var out = saved
        var w = working
        if !asNew, let i = saved.firstIndex(where: { $0.id == working.savedId }) {
            out[i] = BeatSequence(id: saved[i].id, name: trimmed, entries: working.entries)
            w.savedId = saved[i].id
        } else {
            out.append(BeatSequence(id: newId, name: trimmed, entries: working.entries))
            w.savedId = newId
        }
        w.name = trimmed
        return (out, w)
    }

    // ── Storage codec: tolerant, never throws ──

    static func encodeSaved(_ saved: [BeatSequence]) -> Data? { try? JSONEncoder().encode(saved) }

    static func decodeSaved(_ data: Data?) -> [BeatSequence] {
        guard let data, let raw = try? JSONDecoder().decode([BeatSequence].self, from: data) else { return [] }
        return raw.map { s in
            var c = s; c.entries = s.entries.map { SequenceEntry(beatId: $0.beatId, bars: clampBars($0.bars)) }; return c
        }
    }

    static func encodeWorking(_ w: WorkingSequence) -> Data? { try? JSONEncoder().encode(w) }

    static func decodeWorking(_ data: Data?) -> WorkingSequence {
        guard let data, var w = try? JSONDecoder().decode(WorkingSequence.self, from: data) else { return WorkingSequence() }
        w.entries = w.entries.map { SequenceEntry(beatId: $0.beatId, bars: clampBars($0.bars)) }
        return w
    }
}
