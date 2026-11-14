import Foundation

/// BeatText — the words a built-in beat carries, in the reader's language.
///
/// A beat's `note` is data, not interface text: it arrives from Beats.swift, from the
/// server's built-in set, or from whatever a user typed. So it can't be a catalog literal
/// at the call site. Instead the known English values — the vocabulary the built-in sets
/// actually use, here and on Android and the web — are looked up, and anything
/// unrecognised (a user's own note) is shown exactly as written. Translating someone's own
/// words would be wrong; leaving the shipped vocabulary in English would be half a
/// translation. Mirrors android/.../ui/BeatText.kt.
enum BeatText {
    static func note(_ note: String) -> String {
        switch note {
        case "Custom": return String(localized: "Custom", bundle: .strings)
        case "Foundational": return String(localized: "Foundational", bundle: .strings)
        case "Everyday": return String(localized: "Everyday", bundle: .strings)
        case "Variation": return String(localized: "Variation", bundle: .strings)
        case "Lively": return String(localized: "Lively", bundle: .strings)
        case "Build up": return String(localized: "Build up", bundle: .strings)
        case "Gentle": return String(localized: "Gentle", bundle: .strings)
        case "Fast": return String(localized: "Fast", bundle: .strings)
        case "Swing": return String(localized: "Swing", bundle: .strings)
        default: break
        }
        // "4 beats" counts beats of the cycle (Spanish "tiempos"), not beat patterns
        // ("ritmos"), so it has its own catalog key rather than sharing "%lld beats".
        let words = note.split(separator: " ")
        if words.count == 2, words[1] == "beats" || words[1] == "beat", let n = Int(words[0]) {
            return String(localized: "\(n) beats in the cycle", bundle: .strings)
        }
        return note
    }
}
