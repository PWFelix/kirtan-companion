import SwiftUI

/// The top-level navigation: which screen is showing and the bar that switches it.
///
/// Ported from `src/App.jsx`, which routes on a `view` string (`home | beats |
/// editor | settings`) and shows the bottom nav. The `view` state lives here — not
/// in any screen — because it is the one piece of state that spans all of them.
struct NavigationShell: View {
    let container: AppContainer

    enum Destination: String, CaseIterable, Identifiable {
        case home, beats, settings
        var id: String { rawValue }

        var title: String {
            switch self {
            case .home: return "Play"
            case .beats: return "Beats"
            case .settings: return "Settings"
            }
        }

        var symbol: String {
            switch self {
            case .home: return "play.circle.fill"
            case .beats: return "music.note.list"
            case .settings: return "slider.horizontal.3"
            }
        }
    }

    @State private var destination: Destination = NavigationShell.defaultDestination()
    /// The editor opens full-screen over the shell and returns here on save/cancel.
    @State private var editing: Bool = false

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
                onEditor: { editing = true },
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if editing {
                Color.clear
                    .onTapGesture { editing = false }
                    .ignoresSafeArea()
                BeatEditorView(
                    container: container,
                    onDone: { editing = false },
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: editing)
    }

    @ViewBuilder
    private var content: some View {
        switch destination {
        case .home:
            HomeView(container: container)
        case .beats:
            BeatsView(container: container)
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
            item(.settings)
            Color.clear.frame(width: 52, height: 52) // balance the raised centre
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
                    .frame(width: 56)
            }
            .foregroundStyle(destination == dest ? Palette.clay : Palette.faint)
            .frame(maxWidth: .infinity)
        }
    }
}
