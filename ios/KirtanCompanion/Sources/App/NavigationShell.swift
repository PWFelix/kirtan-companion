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
            case .home: return String(localized: "Play", bundle: .strings)
            case .beats: return String(localized: "Beats", bundle: .strings)
            case .learn: return String(localized: "Learn", bundle: .strings)
            case .settings: return String(localized: "Settings", bundle: .strings)
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
    /// The ONE draft, kept across closing the editor and (while dirty) across launches.
    /// `editorMode` above is only whether the editor is showing, and on what.
    @State private var editorSession: EditorSession? = EditorSessionStore.load()
    /// An edit asked for while the draft has unsaved changes: Keep or Discard.
    @State private var pendingEdit: BeatEditorView.EditorMode?

    /// Open the editor on `mode`, unless that would replace unsaved work.
    private func openEditor(_ mode: BeatEditorView.EditorMode, force: Bool = false) {
        if !force, let current = editorSession, current.dirty {
            // The same edit again: resume it.
            if current.mode == mode { editorMode = current.mode } else { pendingEdit = mode }
            return
        }
        editorSession = EditorSession(mode: mode)
        editorMode = mode
    }

    /// The + button: unsaved work comes back; otherwise a blank draft.
    private func openEditorButton() {
        if let current = editorSession, current.dirty { editorMode = current.mode } else { openEditor(.new) }
    }

    /// DEBUG: `-kirtanEditor` opens a new beat in the editor a moment after launch, for
    /// screenshots (presenting the cover during the first layout pass draws it half-built).
    private func openDebugEditor() async {
        #if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("-kirtanEditor") else { return }
        try? await Task.sleep(for: .seconds(1))
        openEditor(.new)
        #endif
    }
    /// The sync screen, opened from the Bluetooth banner over whatever tab is up.
    @State private var syncOpen = false
    /// The language picked in Settings ("" = system). Keying the tree on it re-renders
    /// every screen when it changes; `destination` lives outside, so the tab stays.
    @AppStorage(AppLanguage.defaultsKey) private var language = ""

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
                .task { await openDebugEditor() }

            content

            BottomBar(
                destination: $destination,
                onEditor: openEditorButton,
                editorDirty: editorSession?.dirty == true,
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if editorMode != nil, let session = editorSession {
                Color.clear
                    .onTapGesture { self.editorMode = nil }
                    .ignoresSafeArea()
                BeatEditorView(
                    container: container,
                    session: session,
                    onDone: { self.editorMode = nil },
                    onSaved: {
                        editorSession = nil
                        EditorSessionStore.save(nil)
                        self.editorMode = nil
                    },
                    onDiscard: { editorSession = session.fresh() },
                )
                .id(ObjectIdentifier(session))
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let route = container.transport.syncPrompt,
               destination == .home || destination == .learn, editorMode == nil {
                SyncPromptBanner(
                    deviceLabel: route.label,
                    onSync: {
                        container.transport.dismissSyncPrompt()
                        syncOpen = true
                    },
                    onDismiss: { container.transport.dismissSyncPrompt() }
                )
                .padding(.bottom, 96)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let notice = auth.notice {
                NoticeBanner(text: notice) { auth.notice = nil }
                    .padding(.bottom, 96)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .id(language)
        .environment(\.locale, AppLanguage.choice.map(Locale.init(identifier:)) ?? .current)
        .animation(.easeInOut(duration: 0.22), value: editorMode != nil)
        .animation(.easeInOut(duration: 0.2), value: auth.notice)
        // Kept on disk while dirty, cleared when clean.
        .onChange(of: editorSession?.draft) { _, _ in EditorSessionStore.save(editorSession) }
        .confirmationDialog(
            String(localized: "Unsaved changes", bundle: .strings),
            isPresented: Binding(get: { pendingEdit != nil }, set: { if !$0 { pendingEdit = nil } }),
            titleVisibility: .visible,
        ) {
            Button(String(localized: "Keep editing", bundle: .strings)) {
                pendingEdit = nil
                editorMode = editorSession?.mode
            }
            Button(String(localized: "Discard and continue", bundle: .strings), role: .destructive) {
                if let next = pendingEdit { openEditor(next, force: true) }
                pendingEdit = nil
            }
        } message: {
            let name = editorSession?.draft.name.trimmingCharacters(in: .whitespaces) ?? ""
            Text(String(format: String(localized: "You have unsaved changes to %@.", bundle: .strings),
                        name.isEmpty ? String(localized: "a new beat", bundle: .strings) : name))
        }
        .sheet(isPresented: $syncOpen) { SyncView(transport: container.transport) }
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
            BeatsView(container: container, onEdit: { openEditor($0) })
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
    /// The editor holds unsaved changes: a dot on its button, visible from anywhere.
    var editorDirty = false

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
                    .overlay(alignment: .topTrailing) {
                        if editorDirty {
                            Circle()
                                .fill(Palette.syahi)
                                .overlay(Circle().strokeBorder(Palette.headWorn, lineWidth: 2))
                                .frame(width: 13, height: 13)
                                .offset(x: 1, y: -1)
                                .accessibilityLabel(String(localized: "Unsaved changes", bundle: .strings))
                        }
                    }
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
