import Foundation
import CryptoKit

// PKCE, by hand.
//
// `supabase-js` does this inside its browser flow; there is no library for it here, and it is
// a few lines of RFC 7636: a high-entropy verifier, its SHA-256 as the challenge, both
// base64url-encoded without padding. The verifier never leaves the device until the token
// exchange, which is the whole point — an intercepted authorization code is worthless
// without it.
enum Pkce {

    /// 32 random bytes → 43 characters, inside the RFC's 43…128 window.
    static func verifier() -> String { base64Url(randomBytes(32)) }

    /// An unguessable anti-CSRF token for the round trip.
    static func state() -> String { base64Url(randomBytes(16)) }

    /// `BASE64URL(SHA256(ASCII(verifier)))`, the `S256` challenge method.
    static func challenge(_ verifier: String) -> String {
        base64Url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    private static func randomBytes(_ count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }

    private static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

// The code and state GoTrue appended to the redirect.
struct OAuthCallback {
    let code: String
    let state: String?
}

// Where GoTrue sends the browser back to: a custom-scheme deep link. It must appear in the
// app's declared URL types AND in the Supabase project's Redirect URLs allowlist, or GoTrue
// refuses the flow before the provider is ever reached. A distinct host from the inbound
// share link (`kirtan://beat`) so the two deep links cannot be confused.
let supabaseRedirectUri = "kirtan://auth/callback"

// The `/auth/v1/authorize` URL for a browser sign-in. `code_challenge_method=S256` is what
// makes GoTrue answer with `?code=` rather than putting tokens in the fragment: the implicit
// flow would hand an access token to whatever app is registered for the `kirtan://` scheme,
// and a code that needs a verifier only this device holds does not.
func oauthAuthorizeUrl(config: SupabaseConfig, provider: String, redirectUri: String, challenge: String, state: String) -> String {
    var components = URLComponents(string: "\(config.baseUrl)/auth/v1/authorize")
    components?.queryItems = [
        URLQueryItem(name: "provider", value: provider),
        URLQueryItem(name: "redirect_to", value: redirectUri),
        URLQueryItem(name: "code_challenge", value: challenge),
        URLQueryItem(name: "code_challenge_method", value: "S256"),
        URLQueryItem(name: "state", value: state),
    ]
    return components?.url?.absoluteString ?? ""
}

// Pull `code` and `state` out of a `kirtan://auth/callback?…` deep link. Nil when the link
// carried no code — the one case the sign-in must refuse on its own terms.
func parseOAuthCallback(_ callbackUrl: String) -> OAuthCallback? {
    guard let components = URLComponents(string: callbackUrl) else { return nil }
    var code: String?
    var state: String?
    for item in components.queryItems ?? [] {
        switch item.name {
        case "code": code = item.value?.isEmpty == false ? item.value : nil
        case "state": state = item.value
        default: break
        }
    }
    guard let code else { return nil }
    return OAuthCallback(code: code, state: state)
}
