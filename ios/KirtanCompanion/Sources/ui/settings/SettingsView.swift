import SwiftUI

/// The settings screen: the notation-display toggles, and — when a cloud is compiled
/// in — the account (sign in / sign out).
///
/// Ported from `src/views/SettingsView.jsx`. The EQ/tuning panels live on the Home mixer,
/// not here, matching the web. The cloud section is deliberately minimal — an account row
/// and one action — because sign-in is a redirect round trip the app owns, and the Browse
/// screen offers the same action for those who land there first.
struct SettingsView: View {
    let container: AppContainer

    private var transport: TransportViewModel { container.transport }

    /// Open on About in DEBUG with `-kirtanAbout`, so the harness can screenshot it.
    @State private var aboutOpen: Bool = {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-kirtanAbout")
        #else
        false
        #endif
    }()

    /// Open on Sync in DEBUG with `-kirtanSync`, for screenshots.
    @State private var syncOpen: Bool = {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-kirtanSync")
        #else
        false
        #endif
    }()

    var body: some View {
        // A stack only so About and Sync can be pushed; Settings draws its own title.
        NavigationStack {
            settings
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(isPresented: $aboutOpen) { AboutView() }
                .navigationDestination(isPresented: $syncOpen) { SyncView(transport: transport) }
        }
    }

    private var settings: some View {
        VStack(spacing: 12) {
            Text(String(localized: "Settings"))
                .font(.system(size: 26, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)

            Form {
                Section(String(localized: "Notation")) {
                    Toggle(String(localized: "Show bol syllables"), isOn: bolBinding)
                    Toggle(String(localized: "Show step labels"), isOn: stepLabelBinding)
                }

                Section(String(localized: "Sound")) {
                    Button {
                        syncOpen = true
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(localized: "Sync sound and display")).foregroundStyle(Palette.syahi)
                            Text(String(localized: "For Bluetooth speakers: line the beat on screen up with what you hear.") + " \(transport.route.label): " +
                                 (transport.displayDelayMs == 0 ? String(localized: "no delay set.") : String(localized: "display \(transport.displayDelayMs) ms later.")))
                                .font(.footnote)
                                .foregroundStyle(Palette.syahiSoft)
                        }
                    }
                }

                if container.cloudConfigured {
                    cloudSection
                }

                Section {
                    Button(String(localized: "About")) { aboutOpen = true }
                        .foregroundStyle(Palette.syahi)
                }
            }
            .scrollContentBackground(.hidden)
            .tint(Palette.clay)
            Spacer(minLength: 0)
        }
        .background(Palette.head.ignoresSafeArea())
    }

    // ── Cloud ───────────────────────────────────────────────────────────────

    @ViewBuilder
    private var cloudSection: some View {
        Section(String(localized: "Cloud")) {
            if let account = container.account {
                LabeledContent(String(localized: "Signed in as"), value: container.auth.displayName)
                if let email = account.email, !email.isEmpty {
                    LabeledContent(String(localized: "Email"), value: email)
                }
                Button(String(localized: "Sign out"), role: .destructive) {
                    Task { await container.signOut() }
                }
            } else {
                SignInButton(container: container)
                Text(String(localized: "An account keeps your beats and playlists in the cloud and lets you share to the community library."))
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
            }
        }
    }

    // ── Notation ────────────────────────────────────────────────────────────

    private var bolBinding: Binding<Bool> {
        Binding(
            get: { transport.settings.showBols },
            set: { transport.setShowBols($0) }
        )
    }

    private var stepLabelBinding: Binding<Bool> {
        Binding(
            get: { transport.settings.showStepLabels },
            set: { transport.setShowStepLabels($0) }
        )
    }
}
