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
                // Brahmaloka time's storm plays over everything, the shake included.
                BrahmalokaStage(fireKey: container.transport.burstKey) {
                    NavigationShell(container: container)
                }
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
        #if DEBUG
        // Screenshot runs: past the splash, but SILENT — nothing should keep playing in a
        // simulator nobody is listening to.
        if ProcessInfo.processInfo.arguments.contains("-kirtanSkipSplash") {
            running = true
            Task {
                await container.begin()
                // Screenshot the Brahmaloka storm without a finger on the slider.
                if ProcessInfo.processInfo.arguments.contains("-kirtanBrahmaloka") {
                    try? await Task.sleep(for: .seconds(1.5))
                    container.transport.changeBpm(NORMAL_MAX_BPM)
                    container.transport.unlockBrahmaloka()
                }
            }
            return
        }
        #endif
        guard ProcessInfo.processInfo.arguments.contains("-kirtanAutostart") else { return }
        running = true
        Task {
            await container.begin()
            try? await Task.sleep(for: .seconds(1))
            let beat = container.library.allBeats.first
                ?? container.library.shippedBeats.defaultBeat
            // DEBUG: `-kirtanSequence` plays the stored sequence instead.
            if ProcessInfo.processInfo.arguments.contains("-kirtanSequence") {
                container.transport.setBeatLookup(container.library.allBeats)
                container.transport.toggleSequence()
            } else {
                container.transport.togglePlay(beat)
            }
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
        content
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            // Line drawing of a mridanga and karatalas, faint behind the wordmark —
            // the same art and tint (clay at 14%) as the Android splash. The asset is
            // an alpha mask, rendered as a template so the tint is ours to choose.
            Image("SplashMridanga")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: 560)
                .foregroundStyle(Palette.clay.opacity(0.14))
                .accessibilityHidden(true)
        }
        .clipped()
        .background(Palette.head.ignoresSafeArea())
    }

    private var content: some View {
        VStack(spacing: 24) {
            Spacer()

            Wordmark(size: 38)

            Text(String(localized: "Play along, one drummer fewer.", bundle: .strings))
                .font(.callout)
                .foregroundStyle(Palette.syahiSoft)

            Spacer()

            Button(action: onBegin) {
                Text(String(localized: "Begin", bundle: .strings))
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
    }
}
