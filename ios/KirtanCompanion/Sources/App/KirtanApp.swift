import SwiftUI

// The composition root. Owns the single process-scoped object graph and the one
// piece of state that spans everything: which screen is showing.
//
// The scene is always the light "warm paper" scheme: `preferredColorScheme(.light)`
// ignores the system dark setting, because there is no dark variant of rawhide to
// invert into and the web app declares no `prefers-color-scheme` block at all.
@main
struct KirtanApp: App {

    var body: some Scene {
        WindowGroup {
            // No release on background: a kirtan must keep playing with the screen
            // off. The `.playback` session and the `audio` background mode keep the
            // engine alive; the process is torn down only on force-quit.
            RootView(container: AppContainer.shared)
                .preferredColorScheme(.light)
                .onOpenURL { url in
                    // The browser returns from an OAuth sign-in here (`kirtan://auth/callback`
                    // ?code=…). Anything else with this scheme is ignored.
                    guard url.scheme == "kirtan" else { return }
                    // A shared beat (`kirtan://beat?c=…`) never writes on arrival: it
                    // goes to the same confirmation a pasted code does.
                    if url.host == "beat" {
                        let code = ShareLinks.code(fromDeepLink: url)
                        AppContainer.shared.auth.pendingImport = ShareCodec.decodeShare(code) ?? .invalid
                        return
                    }
                    Task { try? await AppContainer.shared.handleAuthCallback(url) }
                }
        }
    }
}
