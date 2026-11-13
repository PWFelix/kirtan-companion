import SwiftUI

/// Swing slider, shared by the mixer (a live nudge) and the editor (saved with the
/// beat). One view so the two read and behave the same; the hint says which it is.
struct SwingControl: View {
    let value: Int
    let onChange: (Int) -> Void
    let hint: String

    private var label: String {
        if value <= Swing.straight { return String(localized: "Straight", bundle: .strings) }
        if value >= Swing.max { return String(localized: "Triplet", bundle: .strings) }
        return "\(value)%"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(String(localized: "Swing", bundle: .strings))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.syahiSoft)
                Spacer()
                Text(label)
                    .font(.footnote.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Palette.syahi)
            }
            Slider(
                value: Binding(get: { Double(value) }, set: { onChange(Int($0.rounded())) }),
                in: Double(Swing.straight)...Double(Swing.max), step: 1
            )
            .tint(Palette.clay)
            .accessibilityLabel(String(localized: "Swing", bundle: .strings))
            .accessibilityValue(label)
            Text(hint)
                .font(.caption)
                .foregroundStyle(Palette.syahiSoft)
        }
    }
}
