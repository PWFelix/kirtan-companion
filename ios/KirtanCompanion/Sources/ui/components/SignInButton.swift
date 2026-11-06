import SwiftUI

// The one "Sign in" action, shared by every cloud surface (Settings, Browse).
//
// Signing in is a redirect round trip, not a form: the button asks the container for the
// OAuth URL, opens it in an in-app browser, and the app catches the `kirtan://` return
// to finish the exchange. This component owns only the presentation half — the sheet and
// the "dismiss when a session appears" — and hides itself the moment the account arrives,
// so a screen that shows it in its signed-out branch re-renders into its signed-in branch
// for free when the round trip lands.
struct SignInButton: View {
    let container: AppContainer
    var title: String = "Sign in to sync & share"
    var systemImage: String = "person.crop.circle"

    @State private var signInURL: URL?
    @State private var showSheet = false
    @State private var isBusy = false

    /// Read from the observable account; the change is what dismisses the sheet.
    private var signedIn: Bool { container.account != nil }

    var body: some View {
        Button {
            Task { await start() }
        } label: {
            if isBusy {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Opening sign-in…")
                }
            } else {
                Label(title, systemImage: systemImage)
            }
        }
        .disabled(signedIn || isBusy)
        .sheet(isPresented: $showSheet) {
            if let url = signInURL {
                SafariView(url: url)
            }
        }
        .onChange(of: signedIn) { nowSignedIn in
            if nowSignedIn {
                showSheet = false
                signInURL = nil
            }
        }
    }

    private func start() async {
        isBusy = true
        defer { isBusy = false }
        guard let urlString = await container.beginSignIn(),
              let url = URL(string: urlString) else {
            return
        }
        signInURL = url
        showSheet = true
    }
}
