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

    /// True when the signed-in user may change the built-in beat set. Probed once per
    /// session change (AppContainer), never per render, and held here because two screens
    /// gate a control on it. Fails closed: the database authorises the actual write.
    var isMaintainer = false

    /// The device library captured at a fresh sign-in when the account is empty, waiting
    /// for the user to say whether to move it up. Nil means nothing is pending.
    var pendingMigration: Library?

    /// A beat or list that arrived through a `kirtan://beat` link, waiting on the import
    /// confirmation. Held app-wide because a link can land on any tab.
    var pendingImport: ShareCodec.SharePayload?

    /// A one-line confirmation ("Added “Dadra” to Your beats.") shown briefly over any
    /// screen. App-wide for the same reason: the action and the screen may differ.
    var notice: String?

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
