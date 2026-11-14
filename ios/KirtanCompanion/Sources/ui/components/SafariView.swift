import SwiftUI
import SafariServices

// An in-app browser for the OAuth sign-in round trip.
//
// The user completes authentication in this sheet; the token exchange never happens
// here. Supabase finishes by redirecting to the `kirtan://` deep link, which the app
// intercepts via `.onOpenURL` — Safari is never asked to keep any credential. The
// presenting screen watches the account appear and dismisses the sheet on success, so
// this type has no state of its own.
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
