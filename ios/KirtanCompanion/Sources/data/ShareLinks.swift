import Foundation

/// Where an outbound link points. The WEB origin when one is configured (a recipient
/// almost certainly has the site, not this app); otherwise the native scheme, which at
/// least works between two installs of the app. Configured through the Info.plist key
/// KIRTAN_SHARE_WEB_BASE, like Android's SHARE_WEB_BASE.
enum ShareLinks {
    static var webBase: String {
        let raw = Bundle.main.infoDictionary?["KIRTAN_SHARE_WEB_BASE"] as? String ?? ""
        var base = raw.trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base.removeLast() }
        return base
    }

    static func forCode(_ code: String) -> String {
        webBase.isEmpty ? "kirtan://beat?c=\(code)" : "\(webBase)/#b=\(code)"
    }

    /// The share code inside an inbound `kirtan://beat?c=…` link, or nil for any other URL.
    static func code(fromDeepLink url: URL) -> String? {
        guard url.scheme == "kirtan", url.host == "beat",
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        if let c = parts.queryItems?.first(where: { $0.name == "c" })?.value, !c.isEmpty { return c }
        // A link that carries the web form in its fragment works too.
        if let fragment = parts.fragment, fragment.hasPrefix("b=") { return String(fragment.dropFirst(2)) }
        return nil
    }
}
