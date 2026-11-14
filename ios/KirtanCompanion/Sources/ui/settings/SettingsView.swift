import SwiftUI

/// The settings screen: the notation-display toggles, and — when a cloud is compiled
/// in — the account (sign in / sign out).
///
/// Ported from `src/views/SettingsView.jsx`. The mixer (volumes, EQ, tuning, feel, swing) opens
/// from here as a sheet: set once, rarely touched mid-kirtan, so it no longer sits on Home. The cloud section is deliberately minimal — an account row
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

    @State private var mixerOpen = false

    var body: some View {
        // A stack only so About and Sync can be pushed; Settings draws its own title.
        NavigationStack {
            settings
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(isPresented: $aboutOpen) { AboutView() }
                .navigationDestination(isPresented: $syncOpen) { SyncView(transport: transport) }
                .sheet(isPresented: $mixerOpen) {
                    MixerSheet(container: container)
                        .presentationDetents([.medium, .large])
                }
        }
    }

    private var settings: some View {
        VStack(spacing: 12) {
            Text(String(localized: "Settings", bundle: .strings))
                .font(.system(size: 26, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)

            Form {
                Section(String(localized: "Notation", bundle: .strings)) {
                    Toggle(String(localized: "Show bol syllables", bundle: .strings), isOn: bolBinding)
                    Toggle(String(localized: "Show step labels", bundle: .strings), isOn: stepLabelBinding)
                }

                Section(String(localized: "Sound", bundle: .strings)) {
                    Button {
                        mixerOpen = true
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(localized: "Mixer", bundle: .strings)).foregroundStyle(Palette.syahi)
                            Text(String(localized: "Volume, EQ, tuning, feel and swing", bundle: .strings))
                                .font(.footnote)
                                .foregroundStyle(Palette.syahiSoft)
                        }
                    }
                    Button {
                        syncOpen = true
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(localized: "Sync sound and display", bundle: .strings)).foregroundStyle(Palette.syahi)
                            Text(String(localized: "For Bluetooth speakers: line the beat on screen up with what you hear.", bundle: .strings) + " \(transport.route.label): " +
                                 (transport.displayDelayMs == 0 ? String(localized: "no delay set.", bundle: .strings) : String(localized: "display \(transport.displayDelayMs) ms later.", bundle: .strings)))
                                .font(.footnote)
                                .foregroundStyle(Palette.syahiSoft)
                        }
                    }
                }

                if container.cloudConfigured {
                    cloudSection
                }

                Section(String(localized: "Language", bundle: .strings)) {
                    Picker(String(localized: "Language", bundle: .strings), selection: Binding(
                        get: { AppLanguage.choice ?? "" },
                        set: { AppLanguage.set($0.isEmpty ? nil : $0) }
                    )) {
                        Text(String(localized: "System (\(AppLanguage.nativeName(AppLanguage.systemLanguage)))", bundle: .strings)).tag("")
                        ForEach(AppLanguage.supported, id: \.self) { tag in
                            Text(AppLanguage.nativeName(tag)).tag(tag)
                        }
                    }
                }

                Section {
                    Button(String(localized: "About", bundle: .strings)) { aboutOpen = true }
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
        Section(String(localized: "Cloud", bundle: .strings)) {
            if let account = container.account {
                LabeledContent(String(localized: "Signed in as", bundle: .strings), value: container.auth.displayName)
                if let email = account.email, !email.isEmpty {
                    LabeledContent(String(localized: "Email", bundle: .strings), value: email)
                }
                Button(String(localized: "Sign out", bundle: .strings), role: .destructive) {
                    Task { await container.signOut() }
                }
            } else {
                SignInButton(container: container)
                Text(String(localized: "An account keeps your beats and playlists in the cloud and lets you share to the community library.", bundle: .strings))
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
