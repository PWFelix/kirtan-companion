import SwiftUI

/// The top-level navigation: which screen is showing and the bar that switches it.
///
/// Ported from `src/App.jsx`, which routes on a `view` string (`home | beats |
/// editor | settings`) and shows the bottom nav. The `view` state lives here — not
/// in any screen — because it is the one piece of state that spans all of them.
struct NavigationShell: View {
    let container: AppContainer

    enum Destination: String, CaseIterable, Identifiable {
        case home, beats, learn, settings
        var id: String { rawValue }

        var title: String {
            switch self {
            case .home: return "Play"
            case .beats: return "Beats"
            case .learn: return "Learn"
            case .settings: return "Settings"
            }
        }

        var symbol: String {
            switch self {
            case .home: return "play.circle.fill"
            case .beats: return "music.note.list"
            case .learn: return "graduationcap"
            case .settings: return "slider.horizontal.3"
            }
        }
    }

    @State private var destination: Destination = NavigationShell.defaultDestination()
    /// The editor opens full-screen over the shell and returns here on save/cancel. Nil
    /// when closed; otherwise what it is editing (new, yours, a fork, or for everyone).
    @State private var editorMode: BeatEditorView.EditorMode?

    private var auth: AuthViewModel { container.auth }

    /// The tab to open on launch. In DEBUG it honours `-kirtanStartView <name>` so the
    /// harness can screenshot a specific screen; `.home` otherwise.
    static func defaultDestination() -> Destination {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-kirtanStartView"), i + 1 < args.count,
           let dest = Destination(rawValue: args[i + 1]) {
            return dest
        }
        #endif
        return .home
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Palette.head.ignoresSafeArea()

            content

            BottomBar(
                destination: $destination,
                onEditor: { editorMode = .new },
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if let editorMode {
                Color.clear
                    .onTapGesture { self.editorMode = nil }
                    .ignoresSafeArea()
                BeatEditorView(
                    container: container,
                    mode: editorMode,
                    onDone: { self.editorMode = nil },
                )
                .id(editorMode.title + (editorMode.seed?.id ?? ""))
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let notice = auth.notice {
                NoticeBanner(text: notice) { auth.notice = nil }
                    .padding(.bottom, 96)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: editorMode != nil)
        .animation(.easeInOut(duration: 0.2), value: auth.notice)
        // Inbound links and the first-sign-in offer can arrive on any tab.
        .sheet(isPresented: Binding(get: { auth.pendingImport != nil }, set: { if !$0 { auth.pendingImport = nil } })) {
            if let payload = auth.pendingImport {
                ImportConfirmView(payload: payload, onDismiss: { auth.pendingImport = nil }) {
                    auth.pendingImport = nil
                    destination = .beats
                    Task { await container.acceptImport(payload) }
                }
            }
        }
        .sheet(isPresented: Binding(get: { auth.pendingMigration != nil && auth.pendingImport == nil },
                                    set: { if !$0 { auth.pendingMigration = nil } })) {
            if let local = auth.pendingMigration {
                MigrationSheetView(container: container, local: local)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch destination {
        case .home:
            HomeView(container: container)
        case .beats:
            BeatsView(container: container, onEdit: { editorMode = $0 })
        case .learn:
            LearnView(container: container)
        case .settings:
            SettingsView(container: container)
        }
    }
}

/// The bottom bar: the three destinations plus a central button that opens the
/// editor. Reads nothing observable, so it never recomposes with the transport.
struct BottomBar: View {
    @Binding var destination: NavigationShell.Destination
    var onEditor: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            item(.home)
            item(.beats)
            Button(action: onEditor) {
                Image(systemName: "plus")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Palette.onClay)
                    .frame(width: 52, height: 52)
                    .background(Palette.clay)
                    .clipShape(Circle())
                    .offset(y: -10)
            }
            item(.learn)
            item(.settings)
        }
        .padding(8)
        .background(Palette.headWorn)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20).strokeBorder(Palette.rule, lineWidth: 1)
        )
    }

    private func item(_ dest: NavigationShell.Destination) -> some View {
        Button { destination = dest } label: {
            VStack(spacing: 3) {
                Image(systemName: dest.symbol)
                    .font(.body.weight(destination == dest ? .semibold : .regular))
                    .frame(width: 40, height: 26)
                Text(dest.title)
                    .font(.caption2)
                    .lineLimit(1)
                    .fixedSize()
            }
            .foregroundStyle(destination == dest ? Palette.clay : Palette.faint)
            .frame(maxWidth: .infinity)
        }
    }
}

/// A short confirmation that clears itself, so a finished action never needs a tap.
struct NoticeBanner: View {
    let text: String
    var onDone: () -> Void

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(Palette.onClay)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Palette.syahi, in: Capsule())
            .padding(.horizontal, 24)
            .onTapGesture(perform: onDone)
            .task(id: text) {
                try? await Task.sleep(for: .seconds(3.5))
                onDone()
            }
    }
}
