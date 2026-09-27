import Observation
import Foundation

// The one observable slice of the cloud.
//
// `SupabaseClient` is a plain networking class, not observable: making its whole
// surface (config, store, log, in-flight tasks) observation-tracked would be noise.
// Instead this thin model mirrors the two facts the UI actually needs — is a cloud
// compiled in, and who is signed in — and the container publishes to it after every
// auth operation. Views read `account`; because it is `@Observable`, a sign-in that
// returns through the deep link re-renders exactly the screens that display it.
@MainActor
@Observable
final class AuthViewModel {

    /// Whether the cloud is compiled into this build at all. When false every cloud
    /// affordance hides itself — the degraded, no-project mode the app must run in.
    let configured: Bool

    /// The signed-in account, or nil when signed out. Mirrors `SupabaseClient.session`.
    private(set) var session: AuthSession?

    init(configured: Bool) {
        self.configured = configured
    }

    /// The name to greet the user with, falling back to the email, to "You".
    var displayName: String {
        let name = session?.displayName?.trimmingCharacters(in: .whitespaces)
        if let name, !name.isEmpty { return name }
        if let email = session?.email { return email }
        return "You"
    }

    /// Publish a freshly-read session from the client.
    func setSession(_ session: AuthSession?) {
        self.session = session
    }
}
