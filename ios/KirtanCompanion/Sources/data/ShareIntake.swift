import Foundation
import Observation

// Turning an inbound link into a share payload, EXACTLY ONCE.
//
// Ported from data/ShareIntake.kt. The web app reads its share link at module scope and —
// the important half — clears location.hash BEFORE decoding: a hostile payload that got
// past the decoder and wedged the app would otherwise re-wedge on every refresh, because
// the payload lives in the URL.
//
// iOS has the same hazard with a different mechanism: an onOpenURL can fire again, and a
// pending payload that is never cleared re-triggers on every launch. consume() is that
// clear: it returns the payload and drops the reference, so a second read gets nothing.
//
// The accepted link forms, in the order they are tried: the web app's `#b=<code>`
// fragment (already in circulation), this app's own `kirtan://beat?c=<code>` deep link,
// and a bare code pasted into the import field.

@Observable
final class ShareIntake {

    static let shared = ShareIntake()

    /// The accepted link forms.
    static let paramCode = "c"
    static let fragmentMarker = "#b="

    /// The base64url alphabet, for the bare-code shape check.
    private static let base64Url = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")

    /// The decoded payload waiting to be collected. Observable so the UI can react to a
    /// link arriving at ANY time (cold start or warm onOpenURL), not just at composition.
    var pending: ShareCodec.SharePayload?

    private init() {}

    /// Is a decoded payload waiting to be collected?
    var hasPending: Bool { pending != nil }

    /// Decode `text` and hold the result. A link that carries a marker but decodes to
    /// nothing is held as .invalid so the UI can say "that link didn't work" instead of
    /// silently ignoring a tap. Text with no marker is not a share link and is ignored.
    func offer(_ text: String?) {
        guard let code = Self.codeFrom(text) else { return }
        pending = ShareCodec.decodeShare(code) ?? .invalid
    }

    /// Take the pending payload and clear it. The SECOND call returns nil — that is the
    /// whole point, and the property the web app gets by clearing the hash.
    func consume() -> ShareCodec.SharePayload? {
        let payload = pending
        pending = nil
        return payload
    }

    /// Drop anything pending without reporting it — e.g. the user dismissed it.
    func clear() { pending = nil }

    /// Extract a share code from a link, a pasted URL, or a bare code. Returns nil when
    /// `text` carries no share code, which is what distinguishes "an ordinary launch"
    /// from "a shared beat arrived".
    static func codeFrom(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)

        // The web app's fragment form. Backwards range so a link whose own query contains
        // the marker still yields the intended payload — the same rule codeFromInput applies.
        if let fragmentAt = trimmed.range(of: fragmentMarker, options: .backwards) {
            let start = trimmed.index(fragmentAt.lowerBound, offsetBy: fragmentMarker.count)
            let suffix = trimmed[start...].trimmingCharacters(in: .whitespacesAndNewlines)
            return suffix.isEmpty ? nil : suffix
        }

        // Our own deep link's query parameter.
        if trimmed.contains("://") {
            let value = queryParam(trimmed, name: paramCode)
            if let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return value }
        }

        // A bare code pasted into the import field: accept only if shaped like one, so an
        // arbitrary string doesn't become an "invalid share link" error.
        let shapedLikeCode = trimmed.unicodeScalars.allSatisfy { base64Url.contains($0) }
        return (shapedLikeCode && trimmed.count >= 8) ? trimmed : nil
    }

    /// Read one query parameter without constructing a URL — keeps this a pure function.
    /// Only the first `?` starts the query and only `&` separates pairs, all a share link needs.
    private static func queryParam(_ url: String, name: String) -> String? {
        guard let question = url.firstIndex(of: "?") else { return nil }
        let afterQuestion = url[url.index(after: question)...]
        let query: String
        if let hash = afterQuestion.firstIndex(of: "#") {
            query = afterQuestion[..<hash].description
        } else {
            query = afterQuestion.description
        }
        for pair in query.components(separatedBy: "&") {
            guard let eq = pair.firstIndex(of: "="), pair[pair.startIndex..<eq] == name else { continue }
            return pair[pair.index(after: eq)...].description
        }
        return nil
    }
}
