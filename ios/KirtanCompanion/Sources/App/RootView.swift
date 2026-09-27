import SwiftUI

/// The one screen that is not a "view": it is the splash / running fork. The web
/// app gates on `ready` and shows a splash until the user has said they want sound;
/// this does the same, then hands off to the navigation shell.
struct RootView: View {
    let container: AppContainer

    @State private var running = false

    var body: some View {
        Group {
            if running {
                NavigationShell(container: container)
            } else {
                SplashView {
                    running = true
                    Task { await container.begin() }
                }
            }
        }
        .onAppear(perform: autostartIfRequested)
    }

    /// A debug back-door for unattended runs (the simulator test harness passes the
    /// argument): skip the splash and drive a full start so the logs prove the whole
    /// path — decode, arm, start, and a clock that keeps advancing.
    private func autostartIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-kirtanAutostart") else { return }
        running = true
        Task {
            await container.begin()
            try? await Task.sleep(for: .seconds(1))
            let beat = container.library.allBeats.first
                ?? container.library.shippedBeats.defaultBeat
            container.transport.togglePlay(beat)
            try? await Task.sleep(for: .seconds(4))
            await MainActor.run {
                #if DEBUG
                Self.writeAutostartReport(container)
                #endif
            }
        }
    }

    /// The unattended test writes what it can see to a file the host harness reads.
    #if DEBUG
    private static func writeAutostartReport(_ container: AppContainer) {
        let t = container.transport
        let lines = """
        autostart=ok
        ready=\(t.ready)
        playing=\(t.playing)
        outputFailed=\(t.outputFailed)
        step=\(t.step)
        bpm=\(t.bpm)
        beat=\(t.beatName ?? "nil")
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("autostart.txt")
        try? lines.write(to: url, atomically: true, encoding: .utf8)
    }
    #endif
}

/// The begin screen: the app name and a single button.
///
/// On the web the tap exists to satisfy the browser's "no sound before a user
/// gesture" rule. iOS has no such hard rule, but the tap is still the right
/// moment to decode the samples and open the output, because it is when the user
/// has said they want sound — so the first Play is never a dead tap waiting on a
/// decode.
struct SplashView: View {
    var onBegin: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Text("Kirtan\nCompanion")
                .font(.system(size: 40, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
                .multilineTextAlignment(.center)

            Text("Play along, one drummer fewer.")
                .font(.callout)
                .foregroundStyle(Palette.syahiSoft)

            Spacer()

            Button(action: onBegin) {
                Text("Begin")
                    .font(.title3.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Palette.clay)
                    .foregroundStyle(Palette.onClay)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .padding(.horizontal, 40)
            .padding(.bottom, 48)
        }
        .background(Palette.head.ignoresSafeArea())
    }
}
