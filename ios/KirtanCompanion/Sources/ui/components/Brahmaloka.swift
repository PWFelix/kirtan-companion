import AVFoundation
import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// Brahmaloka time: the tempo slider's high range and the weather that opens it.
// The rules are pure and live in data/Meter.swift; this file is only the feel:
// the hold-to-unlock slider, the ✦ badge, the storm overlay, the thunder and the
// haptics. Same design as the web's useBrahmaHold.js + BrahmalokaBurst.jsx and
// Android's ui/components/Brahmaloka.kt.
// ─────────────────────────────────────────────────────────────────────────────

private let gold = Color(hex: 0xF2C14E)
private let fire = Color(hex: 0xD9541E)
private let ember = Color(hex: 0xB8471A)

/// The tempo slider: 40–200, or zoomed out to 40–500 in Brahmaloka (same width, the thumb moves). Holding the thumb at 200 for
/// `BRAHMA_HOLD_SECONDS` calls `onUnlock`, with a glow swelling behind the thumb
/// while you hold so it is clear something is coming.
///
/// Identified BY THE MODE so the slider is rebuilt when the range changes. That ends
/// the drag in progress, which is the point: the finger is still pressed at the
/// right-hand end, and a live drag on the new 40–500 range would fling the tempo
/// to ~490 on its next move.
struct BpmSlider: View {
    let bpm: Int
    let brahmaloka: Bool
    var enabled = true
    let onChange: (Int) -> Void
    let onUnlock: () -> Void

    var body: some View {
        BpmSliderInner(bpm: bpm, brahmaloka: brahmaloka, enabled: enabled, onChange: onChange, onUnlock: onUnlock)
            .id(brahmaloka)
    }
}

private struct BpmSliderInner: View {
    let bpm: Int
    let brahmaloka: Bool
    let enabled: Bool
    let onChange: (Int) -> Void
    let onUnlock: () -> Void

    @State private var editing = false
    @State private var glow = 0.0

    private var charging: Bool { enabled && !brahmaloka && bpm >= NORMAL_MAX_BPM && editing }

    var body: some View {
        let range = tempoRange(brahmaloka: brahmaloka)
        Slider(
            value: Binding(
                get: { Double(min(max(bpm, range.lowerBound), range.upperBound)) },
                set: { onChange(Int($0.rounded())) },
            ),
            in: Double(range.lowerBound)...Double(range.upperBound),
            step: 1,
            onEditingChanged: { editing = $0 },
        )
        .tint(brahmaloka ? fire : Palette.clay)
        .disabled(!enabled)
        .background(alignment: .trailing) {
            // Charging only happens at 200, so the thumb is at the trailing end.
            Circle()
                .fill(RadialGradient(colors: [gold.opacity(0.8), fire.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: 40))
                .frame(width: 80, height: 80)
                .scaleEffect(0.4 + 0.6 * glow)
                .opacity(glow)
                .offset(x: 26)
                .allowsHitTesting(false)
        }
        .task(id: charging) {
            guard charging else {
                glow = 0
                return
            }
            withAnimation(.easeIn(duration: BRAHMA_HOLD_SECONDS)) { glow = 1 }
            try? await Task.sleep(for: .seconds(BRAHMA_HOLD_SECONDS))
            if !Task.isCancelled { onUnlock() }
        }
    }
}

/// Shown in Brahmaloka: says so, and is the way back to 40–200.
struct BrahmalokaBadge: View {
    let onLeave: () -> Void

    var body: some View {
        Button(action: onLeave) {
            Text("✦")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(ember)
                .frame(width: 44, height: 44)
                .background(Color(hex: 0xF7E3B5), in: Circle())
                .overlay(Circle().stroke(Color(hex: 0xD49A2A), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Leave Brahmaloka time (back to 40–200 BPM)", bundle: .strings))
    }
}

/// The thunder: brown noise through a lowpass sinking 900→120 Hz, over a sub-bass sine
/// falling 48→30 Hz, rendered once into an in-memory WAV and played by its own
/// AVAudioPlayer. A UI sound, deliberately outside the engine's graph and clock, so it
/// can never disturb a beat that may be playing.
enum Rumble {
    private static var player: AVAudioPlayer?
    private static let rate = 44_100
    private static let seconds = 2.4

    static func play() {
        DispatchQueue.global(qos: .userInitiated).async {
            let data = render()
            DispatchQueue.main.async {
                player = try? AVAudioPlayer(data: data)
                player?.play()
            }
        }
    }

    private static func render() -> Data {
        let n = Int(Double(rate) * seconds)
        var pcm = [Int16](repeating: 0, count: n)
        var brown = 0.0, lp = 0.0, phase = 0.0
        for i in 0..<n {
            let t = Double(i) / Double(rate)
            brown = (brown + 0.02 * Double.random(in: -1...1)) / 1.02
            let cutoff = 900 * pow(120.0 / 900, t / seconds)
            lp += (1 - exp(-2 * .pi * cutoff / Double(rate))) * (brown * 3.5 - lp)
            let noiseEnv = min(1, t / 0.03) * 0.9 * pow(1 - t / seconds, 2)
            let f = 48 * pow(30.0 / 48, min(t, 1.8) / 1.8)
            phase += 2 * .pi * f / Double(rate)
            let subEnv = min(1, t / 0.08) * 0.5 * pow(max(0, 1 - t / 1.8), 2)
            let s = min(1, max(-1, lp * 4 * noiseEnv + sin(phase) * subEnv))
            pcm[i] = Int16(s * 0.8 * Double(Int16.max))
        }
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(n * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes)
        d.append(contentsOf: Array("WAVEfmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(bytes)
        pcm.withUnsafeBytes { d.append(contentsOf: $0) }
        return d
    }
}

// ── The storm ────────────────────────────────────────────────────────────────

private let burstSeconds = 2.2

private struct Bolt { let at: Double; let pts: [CGPoint]; let thin: Bool }
private struct EmberDrop { let x, born, vy, drift, size, hue, phase: Double }

/// Built on the first frame (it needs the size), kept for the rest of the burst.
private final class StormCache {
    var key = -1
    var bolts: [Bolt] = []
    var embers: [EmberDrop] = []

    func ensure(_ key: Int, _ size: CGSize) {
        guard key != self.key else { return }
        self.key = key
        let w = size.width, h = size.height
        bolts = []
        for at in [0.0, 0.05, 0.42] {
            let x = w * .random(in: 0.15...0.85)
            let main = Self.path(x, -10, x + .random(in: -0.2...0.2) * w, h * .random(in: 0.55...0.85), w * 0.25)
            bolts.append(Bolt(at: at, pts: main, thin: false))
            let fork = main[Int(Double(main.count) * .random(in: 0.3...0.6))]
            bolts.append(Bolt(at: at, pts: Self.path(fork.x, fork.y, fork.x + .random(in: -0.25...0.25) * w, fork.y + h * 0.25, w * 0.12), thin: true))
        }
        embers = (0..<140).map { _ in
            EmberDrop(x: .random(in: 0...w), born: .random(in: 0...0.75), vy: .random(in: 320...840) / 2.6 * 1.2,
                      drift: .random(in: -30...30) / 2.6, size: .random(in: 1.5...5) / 1.4,
                      hue: .random(in: 18...48), phase: .random(in: 0...(2 * .pi)))
        }
    }

    /// A jagged line by midpoint displacement.
    private static func path(_ x0: Double, _ y0: Double, _ x1: Double, _ y1: Double, _ spread0: Double) -> [CGPoint] {
        var pts = [CGPoint(x: x0, y: y0), CGPoint(x: x1, y: y1)]
        var spread = spread0
        for _ in 0..<6 {
            var next = [pts[0]]
            for i in 1..<pts.count {
                let a = pts[i - 1], b = pts[i]
                next.append(CGPoint(x: (a.x + b.x) / 2 + .random(in: -0.5...0.5) * spread, y: (a.y + b.y) / 2))
                next.append(b)
            }
            pts = next
            spread *= 0.55
        }
        return pts
    }
}

/// Wraps the app. Each new `fireKey` (above zero) plays ~2 s of weather over
/// everything (flash, forked lightning, fire rain, the content shuddering, a haptic
/// thump and the title) then leaves nothing behind. The overlay takes no touches.
///
/// With Reduce Motion on there is no shake, flash or particles: only the title fading.
struct BrahmalokaStage<Content: View>: View {
    let fireKey: Int
    @ViewBuilder let content: Content

    @Environment(\.accessibilityReduceMotion) private var calm
    @State private var start: Date?
    @State private var cache = StormCache()

    var body: some View {
        TimelineView(.animation(paused: start == nil)) { tl in
            let f = start.map { min(1, tl.date.timeIntervalSince($0) / burstSeconds) } ?? 1
            let ms = f * burstSeconds * 1000
            let shaking = f < 1 && !calm && ms < 650
            let amp = shaking ? 7 * (1 - ms / 650) : 0
            content
                .offset(x: amp * sin(ms / 1000 * 2 * .pi * 12), y: amp * 0.45 * sin(ms / 1000 * 2 * .pi * 9))
                .rotationEffect(.degrees(shaking ? 0.4 * (1 - ms / 650) * sin(ms / 1000 * 2 * .pi * 7) : 0))
                .overlay {
                    if f < 1 { storm(f).allowsHitTesting(false).ignoresSafeArea() }
                }
        }
        .onChange(of: fireKey) { _, key in
            guard key > 0 else { return }
            start = Date()
            thump()
            Task {
                try? await Task.sleep(for: .seconds(burstSeconds + 0.1))
                start = nil
            }
        }
    }

    private func thump() {
        let heavy = UIImpactFeedbackGenerator(style: .heavy)
        heavy.impactOccurred(intensity: 1)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { heavy.impactOccurred(intensity: 0.8) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.42) {
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 1)
        }
    }

    @ViewBuilder
    private func storm(_ f: Double) -> some View {
        ZStack {
            if !calm {
                Canvas { ctx, size in
                    cache.ensure(fireKey, size)
                    draw(&ctx, size, f)
                }
                .ignoresSafeArea()
            }
            caption(f)
        }
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ f: Double) {
        let full = Path(CGRect(origin: .zero, size: size))
        // Storm-dark wash so the light has something to burn against, under a saffron glow.
        ctx.fill(full, with: .color(Color(hex: 0x260E08).opacity(0.62 * pow(1 - f, 0.7))))
        ctx.fill(full, with: .color(Color(hex: 0xFF781E).opacity(0.22 * (1 - f))))
        let flash = max(0, 1 - f / 0.08) * 0.85 + max(0, 1 - abs(f - 0.43) / 0.05) * 0.5
        if flash > 0 { ctx.fill(full, with: .color(Color(hex: 0xFFF8E1).opacity(min(1, flash)))) }

        for b in cache.bolts {
            let age = f - b.at
            guard age >= 0, age <= 0.35 else { continue }
            let a = (1 - age / 0.35) * .random(in: 0.6...1)
            var path = Path()
            path.addLines(b.pts)
            let base: CGFloat = b.thin ? 1.5 : 3.5
            ctx.drawLayer { glow in
                glow.addFilter(.blur(radius: 10))
                glow.stroke(path, with: .color(Color(hex: 0xFFBE50).opacity(0.8 * a)), style: StrokeStyle(lineWidth: base * 4, lineCap: .round, lineJoin: .round))
            }
            ctx.stroke(path, with: .color(Color(hex: 0xFFECAA).opacity(a)), style: StrokeStyle(lineWidth: base * 1.4, lineCap: .round, lineJoin: .round))
            ctx.stroke(path, with: .color(.white.opacity(a)), style: StrokeStyle(lineWidth: base * 0.5, lineCap: .round, lineJoin: .round))
        }

        let secs = f * burstSeconds
        let fade = min(1, (1 - f) * 3)
        for e in cache.embers where f >= e.born {
            let t = secs - e.born * burstSeconds
            let y = -20 + e.vy * t
            guard y <= size.height + 20 else { continue }
            let x = e.x + e.drift * t + sin(e.phase + t * 6) * 6
            let head = Color(hue: min(1, (e.hue + 15) / 360), saturation: 0.85, brightness: 1).opacity(fade)
            var streak = Path()
            streak.move(to: CGPoint(x: x, y: y - e.size * 7))
            streak.addLine(to: CGPoint(x: x, y: y))
            ctx.stroke(streak, with: .linearGradient(Gradient(colors: [head.opacity(0), head]),
                                                     startPoint: CGPoint(x: x, y: y - e.size * 7), endPoint: CGPoint(x: x, y: y)),
                       style: StrokeStyle(lineWidth: e.size, lineCap: .round))
        }
    }

    /// In by 18%, held, out over the last quarter.
    private func caption(_ f: Double) -> some View {
        let alpha = f < 0.18 ? f / 0.18 : (f > 0.75 ? (1 - f) / 0.25 : 1)
        return VStack(spacing: 8) {
            Text(String(localized: "Brahmaloka time", bundle: .strings))
                .font(.system(size: 38, weight: .bold, design: .serif))
                .foregroundStyle(Color(hex: 0xFFF4DC))
                .shadow(color: Color(hex: 0xE8762A), radius: 14)
            Text(String(localized: "A day of Brahmā is a thousand ages.", bundle: .strings))
                .font(.system(size: 15).italic())
                .foregroundStyle(Color(hex: 0xFFF4DC))
                .shadow(color: Color(hex: 0x9A3412), radius: 7)
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 24)
        .opacity(max(0, min(1, alpha)))
    }
}
