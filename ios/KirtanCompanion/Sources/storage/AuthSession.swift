import Foundation

// A signed-in user, as persisted.
//
// Deliberately a plain value with its own JSON rather than a GoTrue response kept whole:
// the session has to survive process death, and the fields that matter afterwards are the
// two tokens, the expiry, and who the user is.
struct AuthSession: Equatable {
    let accessToken: String
    let refreshToken: String
    /// Seconds since the epoch. Refreshing early avoids racing the server's clock.
    let expiresAtEpochSeconds: Int
    let userId: String
    let email: String?
    /// The author name a published beat is credited to.
    let displayName: String?

    /// Refresh once we are within a minute of expiry, rather than racing it.
    func needsRefresh(now: Int = nowEpochSeconds()) -> Bool {
        now >= expiresAtEpochSeconds - AuthSession.refreshMarginSeconds
    }

    private static let refreshMarginSeconds = 60
}

/// Seconds since the epoch.
func nowEpochSeconds() -> Int {
    Int(Date().timeIntervalSince1970)
}

// The session blob's JSON. Never throws on decode: a blob that will not parse means "signed
// out", which is a state the app handles — refusing to launch over it would be worse than
// the corruption, and re-signing-in costs seconds.
enum SessionJson {

    static func encode(_ session: AuthSession) -> String {
        var pairs: [(String, JValue)] = [
            ("accessToken", .str(session.accessToken)),
            ("refreshToken", .str(session.refreshToken)),
            ("expiresAtEpochSeconds", .num(Double(session.expiresAtEpochSeconds))),
            ("userId", .str(session.userId)),
        ]
        if let email = session.email { pairs.append(("email", .str(email))) }
        if let displayName = session.displayName { pairs.append(("displayName", .str(displayName))) }
        return JValue.object(pairs.map { JPair(key: $0.0, value: $0.1) }).write()
    }

    static func decode(_ raw: String?) -> AuthSession? {
        guard let raw else { return nil }
        guard case .object(let pairs) = JsonEl.parse(raw) else { return nil }
        func str(_ key: String) -> String? {
            for pair in pairs where pair.key == key { if case .string(let s) = pair.value { return s } }
            return nil
        }
        func int(_ key: String) -> Int? {
            for pair in pairs where pair.key == key {
                if case .number(let d) = pair.value { return Int(d) }
            }
            return nil
        }
        let accessToken = str("accessToken")
        let refreshToken = str("refreshToken")
        let expires = int("expiresAtEpochSeconds")
        let userId = str("userId")
        guard let accessToken, let refreshToken, let expires, let userId else { return nil }
        return AuthSession(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAtEpochSeconds: expires,
            userId: userId,
            email: str("email"),
            displayName: str("displayName"),
        )
    }
}
