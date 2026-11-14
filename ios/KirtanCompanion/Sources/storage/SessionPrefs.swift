import Foundation

/// Where you left off: the last loaded beat and its tempo, so reopening the app lands
/// on the beat you were playing. UserDefaults, read synchronously at Begin; two tiny
/// values, and a lost one costs a tap.
///
/// The tempo comes back capped at 200 (`restoredBpm`): every launch starts outside
/// Brahmaloka time, which has to be earned again.
enum SessionPrefs {
    private static let beatKey = "kirtan.session.beatId"
    private static let bpmKey = "kirtan.session.bpm"

    static var beatId: String? { UserDefaults.standard.string(forKey: beatKey) }

    static var bpm: Int? {
        let v = UserDefaults.standard.integer(forKey: bpmKey)
        return v > 0 ? restoredBpm(v) : nil
    }

    static func saveBeat(_ id: String) { UserDefaults.standard.set(id, forKey: beatKey) }

    static func saveBpm(_ bpm: Int) { UserDefaults.standard.set(bpm, forKey: bpmKey) }
}
