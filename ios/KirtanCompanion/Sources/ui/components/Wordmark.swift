import SwiftUI

/// The name lockup: मृदङ्गधीर (mṛdaṅga-dhīra) in Devanagari over "Mrdanga Hero".
///
/// One view so the splash and About can never drift apart — the Android
/// `Wordmark.kt` plays the same role. Sized by the headline's point size; the
/// Devanagari line is set at 0.46 of it, as on Android and the web.
struct Wordmark: View {
    var size: CGFloat = 38

    var body: some View {
        VStack(spacing: size * 0.06) {
            Text("मृदङ्गधीर")
                .font(.system(size: size * 0.46, weight: .medium))
                .tracking(size * 0.04)
                .foregroundStyle(Palette.strap)
            Text("Mrdanga Hero")
                .font(.system(size: size, weight: .bold, design: .serif))
                .foregroundStyle(Palette.clay)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Mrdanga Hero")
    }
}
