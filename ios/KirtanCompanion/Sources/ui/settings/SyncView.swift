import SwiftUI

/// Sync sound and display: line the playhead up with what is actually heard on
/// the current output, typically a Bluetooth speaker that lags the screen.
///
/// Two ways in, as on Android: TAP ALONG (taps on a steady click measure the
/// delay) and LINE UP (a light flashes where the display thinks each click is;
/// a slider moves it). The click runs only while this screen is open.
struct SyncView: View {
    let transport: TransportViewModel

    @State private var clicking = false
    @State private var tapping = false
    @State private var taps: [Double] = []
    @State private var result: SyncCalibration.Result?
    @State private var pressed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(String(localized: "Sync sound and display"))
                    .font(.system(size: 26, weight: .semibold, design: .serif))
                    .foregroundStyle(Palette.syahi)
                Text(String(localized: "Bluetooth speakers play a little after the screen. If the strip or the highway runs ahead of the drum, set the delay here. Each speaker remembers its own."))
                    .font(.subheadline)
                    .foregroundStyle(Palette.syahiSoft)

                routeCard

                sectionLabel(String(localized: "1 · Tap along"))
                Text(tapping
                     ? String(localized: "Tap on every click. Listen, don't watch.")
                     : String(localized: "Start the click, then look away and tap the circle on every click you hear."))
                    .font(.subheadline)
                    .foregroundStyle(Palette.syahiSoft)

                tapPad.frame(maxWidth: .infinity)

                switch result {
                case .delay(let ms):
                    Text(String(localized: "Done. The display now runs \(describe(ms)). Check it below and fine-tune if you need to."))
                        .font(.subheadline).foregroundStyle(Palette.syahi)
                case .tooUneven:
                    Text(String(localized: "Those taps were too uneven to measure. Try again, keeping a steady tap with the click."))
                        .font(.subheadline).foregroundStyle(Palette.clay)
                case nil:
                    EmptyView()
                }

                Button {
                    if tapping {
                        tapping = false
                    } else {
                        taps = []
                        result = nil
                        startClick()
                        tapping = true
                    }
                } label: {
                    Text(tapping ? String(localized: "Cancel") : (result != nil ? String(localized: "Tap again") : String(localized: "Start tapping")))
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .foregroundStyle(Palette.head)
                        .background(Palette.clay, in: RoundedRectangle(cornerRadius: 18))
                }

                sectionLabel(String(localized: "2 · Line up"))
                Text(String(localized: "The light flashes where the display thinks each click is. Slide until flash and click happen together."))
                    .font(.subheadline)
                    .foregroundStyle(Palette.syahiSoft)

                HStack(spacing: 16) {
                    TimelineView(.animation(paused: !clicking)) { _ in
                        Circle()
                            .fill(Palette.clay.opacity(0.12 + 0.88 * flash()))
                            .frame(width: 56, height: 56)
                    }
                    .accessibilityLabel(String(localized: "Flash on each click"))
                    VStack(alignment: .leading) {
                        Text(describe(transport.displayDelayMs).prefix(1).uppercased()
                             + describe(transport.displayDelayMs).dropFirst())
                            .font(.headline).foregroundStyle(Palette.syahi)
                        Slider(
                            value: Binding(
                                get: { Double(transport.displayDelayMs) },
                                set: { transport.setDisplayDelay(Int(($0 / 5).rounded()) * 5) }
                            ),
                            in: Double(EqPrefsSnapshot.displayDelayMinMs)...Double(EqPrefsSnapshot.displayDelayMaxMs)
                        )
                    }
                }

                HStack(spacing: 8) {
                    secondary(clicking ? String(localized: "Stop click") : String(localized: "Play click")) {
                        if clicking { stopClick() } else { startClick() }
                    }
                    secondary(String(localized: "Reset to 0")) { transport.setDisplayDelay(0) }
                }
            }
            .padding(24)
            // Clear of the floating bottom bar.
            .padding(.bottom, 96)
        }
        .tint(Palette.clay)
        .background(Palette.head.ignoresSafeArea())
        .onDisappear { transport.stopSyncTest() }
    }

    // ── Pieces ─────────────────────────────────────────────────────────────

    private var routeCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(String(localized: "PLAYING THROUGH"))
                .font(.caption.weight(.semibold)).tracking(1)
                .foregroundStyle(Palette.syahiSoft)
            Text(transport.route.bluetooth ? "\(transport.route.label) · Bluetooth" : transport.route.label)
                .font(.title3.bold()).foregroundStyle(Palette.syahi)
            Text(delayLine)
                .font(.footnote).foregroundStyle(Palette.syahiSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Palette.rule.opacity(0.35), in: RoundedRectangle(cornerRadius: 14))
    }

    private var delayLine: String {
        let ms = transport.displayDelayMs
        let signed = "\(ms > 0 ? "+" : "")\(ms)"
        var line = ms == 0 ? String(localized: "Display delay: none") : String(localized: "Display delay: \(signed) ms")
        if clicking { line += " · " + String(localized: "phone reports \(transport.reportedOutputLatencyMs) ms, already allowed for") }
        return line
    }

    private var tapPad: some View {
        let counted = min(max(taps.count - SyncCalibration.warmUp, 0), SyncCalibration.tapsNeeded)
        return Text(tapping ? "\(counted) / \(SyncCalibration.tapsNeeded)" : String(localized: "sync.pad", defaultValue: "Tap"))
            .font(.system(size: 30, weight: .bold))
            .foregroundStyle(Palette.clayDeep)
            .frame(width: 190, height: 190)
            .background(Circle().fill(Palette.clay.opacity(pressed ? 0.30 : 0.14)))
            .overlay(Circle().stroke(Palette.clay, lineWidth: 3))
            .scaleEffect(pressed ? 0.95 : 1)
            .opacity(tapping ? 1 : 0.45)
            .contentShape(Circle())
            // A zero-distance drag reports the touch-down, where a tap gesture
            // would only report the lift — a hundred-odd ms later.
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        tap(at: OutputClock.nowNanos())
                    }
                    .onEnded { _ in pressed = false }
            )
            .accessibilityLabel(String(localized: "Tap pad: tap on every click"))
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 12.5, weight: .semibold)).tracking(1.1)
            .foregroundStyle(Palette.syahiSoft)
    }

    private func secondary(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(Palette.syahi)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.rule, lineWidth: 1))
        }
    }

    // ── Behaviour ──────────────────────────────────────────────────────────

    private func startClick() {
        guard !clicking else { return }
        transport.startSyncTest()
        clicking = true
    }

    private func stopClick() {
        transport.stopSyncTest()
        clicking = false
        tapping = false
    }

    private func tap(at nanos: UInt64) {
        guard tapping else { return }
        taps.append(transport.syncTapOffsetMs(atNanos: nanos))
        guard SyncCalibration.complete(taps) else { return }
        let r = SyncCalibration.estimate(taps)
        result = r
        tapping = false
        if case .delay(let ms) = r { transport.setDisplayDelay(ms) }
    }

    /// Lit for the first fifth of each beat, by the playhead WITH the delay
    /// applied, so it shows exactly what the strip will.
    private func flash() -> Double {
        guard clicking else { return 0 }
        let position = transport.getPhase() * 4
        let into = position - position.rounded(.down)
        return into < 0.2 ? 1 - into / 0.2 : 0
    }
}

/// "on time", "120 ms later", "30 ms earlier".
private func describe(_ ms: Int) -> String {
    if ms == 0 { return String(localized: "on time, with no extra delay") }
    return ms > 0 ? String(localized: "\(ms) ms later") : String(localized: "\(-ms) ms earlier")
}

/// "Playing through JBL Flip 5 · Sync the display?" — shown once per Bluetooth
/// device, the first time it plays, because that is when the lag shows.
struct SyncPromptBanner: View {
    let deviceLabel: String
    let onSync: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 1) {
                Text(String(localized: "Playing through \(deviceLabel)"))
                    .font(.footnote.bold()).lineLimit(1)
                Text(String(localized: "Is the screen ahead of the drum? Sync them."))
                    .font(.caption).opacity(0.75)
            }
            .foregroundStyle(Palette.head)
            Spacer(minLength: 4)
            Button(String(localized: "Sync"), action: onSync)
                .font(.subheadline.bold())
                .foregroundStyle(Palette.head)
                .frame(minWidth: 44, minHeight: 44)
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.footnote)
            }
            .foregroundStyle(Palette.head.opacity(0.7))
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel(String(localized: "Dismiss"))
        }
        .padding(.leading, 16).padding(.trailing, 6).padding(.vertical, 6)
        .background(Palette.syahi, in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
    }
}
