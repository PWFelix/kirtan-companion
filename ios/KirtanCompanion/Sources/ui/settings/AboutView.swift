import SwiftUI

/// About: the name, the splash's mridanga, its gloss and the dedication.
///
/// Mirrors Android's `AboutScreen.kt`. Pushed from Settings; the drawing sits
/// behind the lockup at a faint clay tint, so it reads as the splash again, quietly.
struct AboutView: View {
    private var version: String? {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ZStack {
                    Image("SplashMridanga")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 380)
                        .foregroundStyle(Palette.clay.opacity(0.22))
                        .accessibilityHidden(true)
                    Wordmark(size: 34)
                }
                .frame(height: 340)
                .frame(maxWidth: .infinity)
                .clipped()

                Text(String(localized: "Mṛdaṅga-dhīra (मृदङ्गधीर) — someone who is steadfast, courageous or heroic in relation to the mṛdaṅga.", bundle: .strings))
                    .font(.subheadline)
                    .foregroundStyle(Palette.syahiSoft)
                Text("paraṁ vijayate śrī-kṛṣṇa-saṅkīrtanam")
                    .font(.title3.weight(.medium).italic())
                    .foregroundStyle(Palette.clay)
                Text(String(localized: "a World Sankirtan Party production", bundle: .strings))
                    .foregroundStyle(Palette.syahi)
                Text(String(localized: "All glories to Śrīla Prabhupāda!", bundle: .strings))
                    .font(.headline)
                    .foregroundStyle(Palette.syahi)
                if let version {
                    Text(String(localized: "Version \(version)", bundle: .strings))
                        .font(.caption)
                        .foregroundStyle(Palette.faint)
                }
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 24)
            .padding(.bottom, 120)
        }
        .background(Palette.head.ignoresSafeArea())
        .navigationTitle(String(localized: "About", bundle: .strings))
        .navigationBarTitleDisplayMode(.inline)
    }
}
