import Observation

// The built-in beat set — which is no longer only the one compiled into this build.
//
// Ported from data/ShippedBeats.kt. BEATS is the FALLBACK: what the app plays with no
// network, on first launch before the server answers, and against a server that returns
// something unusable. The canonical set lives in the `shipped_beats` table, so the two
// maintainers can correct a pattern and have it reach every installed app on its next
// launch, without a rebuild or a reinstall.
//
// ── WHY A GLOBAL RATHER THAN A PARAMETER ──
// Beat.isBuiltIn is a property ON THE MODEL, read from list rows, the editor's
// fork-or-edit decision and the transport alike. It cannot be handed the current set as
// an argument without every call site threading a list they otherwise have no use for.
// A server-sourced set means a built-in id this build has never seen — a beat promoted
// after the build was compiled. Such a beat MUST still read as built in, or readOnly
// goes false and the editor offers to overwrite a beat that is not the user's to
// overwrite. Answering from the live set rather than the compiled one is what makes that
// impossible.
//
// ── ONE WAY IN ──
// install() is the only writer, and it refuses an empty set. The emptiness check belongs
// here rather than in each caller: the consequence of getting it wrong is app-wide (no
// default beat, an empty Built-in section, nothing for the transport to load).

@Observable
final class ShippedBeats {

    static let shared = ShippedBeats()

    /// The beats compiled into this build. Never empty; never replaced.
    let compiled: [Beat] = Beats.beats

    /// The set the app is currently treating as built in: `compiled` until a usable
    /// server set arrives, then that. Never empty.
    private(set) var effective: [Beat]

    private init() {
        self.effective = Beats.beats
    }

    /// Adopt a fetched (or cached) set as the built-ins. The caller (ShippedBeatsClient)
    /// owns validation, which is all-or-nothing; an empty set is rejected wholesale.
    func install(_ beats: [Beat]) {
        if beats.isEmpty { return }
        effective = beats
    }

    /// True when `id` names one of the CURRENT built-ins, compiled or fetched.
    func isShippedId(_ id: String?) -> Bool {
        guard let id else { return false }
        return effective.contains { $0.id == id }
    }

    /// The distinct section headings of the live set, in first-appearance order.
    /// NOT contiguous: the Beats screen takes these headings and FILTERS the list by
    /// each, so a beat between two others of a different heading still lands in its own.
    var groups: [String] {
        var seen = Set<String>()
        var out: [String] = []
        for beat in effective {
            if let g = beat.group, !seen.contains(g) {
                seen.insert(g)
                out.append(g)
            }
        }
        return out
    }

    /// The beat the app loads before the user chooses one. Never null.
    var defaultBeat: Beat { effective.first ?? compiled.first! }
}
