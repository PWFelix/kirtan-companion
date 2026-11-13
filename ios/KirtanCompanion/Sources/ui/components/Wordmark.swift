import SwiftUI

/// The name lockup: कीर्तन (kīrtana) in Devanagari over "Kirtanero", with a
/// Vaishnava tilak standing in as the name's i.
///
/// One view so the splash and About can never drift apart — the Android
/// `Wordmark.kt` plays the same role. Sized by the headline's point size; the
/// Devanagari line is set at 0.46 of it and the tilak at 0.74, as on Android
/// and the web.
struct Wordmark: View {
    var size: CGFloat = 38

    var body: some View {
        VStack(spacing: size * 0.06) {
            Text("कीर्तन")
                .font(.system(size: size * 0.46, weight: .medium))
                .tracking(size * 0.04)
                .foregroundStyle(Palette.strap)
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text("K")
                Tilak()
                    .fill(Palette.strap)
                    .frame(width: size * 0.74 * 12 / 42, height: size * 0.74)
                    .padding(.horizontal, size * 0.015)
                    // Sit the mark on the baseline, as the inline SVG does on the web.
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] }
                Text("rtanero")
            }
            .font(.system(size: size, weight: .bold, design: .serif))
            .foregroundStyle(Palette.clay)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Kirtanero")
    }
}

/// The tilak, from the web's 12×42 SVG: a U of 3-unit stroke over a drop.
/// The U is built as its own outline so the whole mark fills in one colour.
private struct Tilak: Shape {
    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 12, sy = rect.height / 42
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * sx, y: rect.minY + y * sy) }
        var u = Path()
        u.move(to: p(1.5, 2))
        u.addLine(to: p(1.5, 19))
        u.addArc(center: p(6, 19), radius: 4.5 * sx, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: true)
        u.addLine(to: p(10.5, 2))
        var out = u.strokedPath(StrokeStyle(lineWidth: 3 * sx, lineCap: .round))
        out.move(to: p(3, 26))
        out.addArc(center: p(6, 26), radius: 3 * sx, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        out.addQuadCurve(to: p(6, 36), control: p(8, 32))
        out.addQuadCurve(to: p(3, 26), control: p(4, 32))
        out.closeSubpath()
        return out
    }
}
