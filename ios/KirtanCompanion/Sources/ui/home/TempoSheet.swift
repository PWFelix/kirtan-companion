import SwiftUI

/// Precise tempo entry, opened by tapping the BPM number on Home — the iOS twin
/// of the web tempo sheet and Android's TempoSheet.
///
/// The typed number is a DRAFT string, never clamped mid-type, so "8" on the way
/// to "80" isn't heard as 40. An in-range draft applies live; anything else is
/// clamped only on confirm. The −10 / +10 pills are for "a lot faster/slower" in
/// one tap; +10 at the top of the normal range opens Brahmaloka, the same as +.
struct TempoSheet: View {
    let container: AppContainer
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @FocusState private var typing: Bool

    private var transport: TransportViewModel { container.transport }
    private var ceiling: Int { transport.brahmaloka ? MAX_BPM : NORMAL_MAX_BPM }

    var body: some View {
        VStack(spacing: 18) {
            HStack {
                Text(String(localized: "Tempo", bundle: .strings))
                    .font(.system(size: 26, weight: .semibold, design: .serif))
                    .foregroundStyle(Palette.syahi)
                Spacer()
                Button(action: confirm) {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.bold))
                        .frame(width: 44, height: 44)
                        .background(Palette.clay, in: RoundedRectangle(cornerRadius: 12))
                        .foregroundStyle(Palette.onClay)
                }
                .accessibilityLabel(String(localized: "Set tempo", bundle: .strings))
            }

            HStack(spacing: 14) {
                jump(-10, enabled: transport.bpm > MIN_BPM)
                TextField("", text: $draft)
                    .keyboardType(.numberPad)
                    .focused($typing)
                    .multilineTextAlignment(.center)
                    .font(.system(size: 44, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Palette.syahi)
                    .frame(width: 130, height: 72)
                    .background(Palette.headSunken, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(typing ? Palette.clay : Palette.rule, lineWidth: 1))
                    .accessibilityLabel(String(localized: "Tempo in BPM", bundle: .strings))
                    .onChange(of: draft) { _, text in
                        let digits = String(text.filter(\.isNumber).prefix(3))
                        if digits != text { draft = digits; return }
                        if let v = Int(digits), (MIN_BPM...ceiling).contains(v), v != transport.bpm {
                            transport.changeBpm(v)
                        }
                    }
                jump(10, enabled: !(transport.brahmaloka && transport.bpm >= MAX_BPM))
            }

            Text("BPM")
                .font(.caption.weight(.bold))
                .tracking(1)
                .foregroundStyle(Palette.syahiSoft)

            BpmSlider(
                bpm: transport.bpm,
                brahmaloka: transport.brahmaloka,
                enabled: !transport.settings.tempoLocked,
                onChange: { transport.changeBpm($0) },
                onUnlock: { transport.unlockBrahmaloka() },
            )
            Spacer(minLength: 0)
        }
        .padding(20)
        .background(Palette.head.ignoresSafeArea())
        .onAppear { draft = String(transport.bpm) }
        // Follow the tempo when it moves by anything other than typing.
        .onChange(of: transport.bpm) { _, bpm in
            if Int(draft) != bpm { draft = String(bpm) }
        }
        .onDisappear(perform: commitDraft)
    }

    private func jump(_ delta: Int, enabled: Bool) -> some View {
        Button {
            typing = false
            transport.nudgeBpm(delta)
        } label: {
            Text(delta > 0 ? "+\(delta)" : "−\(-delta)")
                .font(.system(size: 18, weight: .bold).monospacedDigit())
                .frame(width: 64, height: 48)
                .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.rule, lineWidth: 1))
                .foregroundStyle(Palette.syahi)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
    }

    /// Apply whatever is typed, clamped to the range in force.
    private func commitDraft() {
        guard let v = Int(draft) else { return }
        transport.changeBpm(min(max(v, MIN_BPM), ceiling))
    }

    private func confirm() {
        commitDraft()
        dismiss()
    }
}
