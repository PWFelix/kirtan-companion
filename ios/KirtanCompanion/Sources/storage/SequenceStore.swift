import Foundation

/// Saved sequences and the working one, in UserDefaults as JSON. Small, local
/// and per-device — like the session prefs, not the beat library. The codec
/// (and its tolerance of bad data) lives in data/Sequences.swift.
struct SequenceStore {
    static let savedKey = "kirtan-sequences"
    static let workingKey = "kirtan-working-sequence"

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func loadSaved() -> [BeatSequence] { Sequences.decodeSaved(defaults.data(forKey: Self.savedKey)) }
    func saveSaved(_ saved: [BeatSequence]) { defaults.set(Sequences.encodeSaved(saved), forKey: Self.savedKey) }

    func loadWorking() -> WorkingSequence { Sequences.decodeWorking(defaults.data(forKey: Self.workingKey)) }
    func saveWorking(_ w: WorkingSequence) { defaults.set(Sequences.encodeWorking(w), forKey: Self.workingKey) }
}
