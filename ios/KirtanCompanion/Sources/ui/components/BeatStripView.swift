import SwiftUI

/// The straight-line beat visualizer: the beat as stacked instrument lanes of cells,
/// read left to right in time, with a playhead that glides across it.
///
/// Ported from `src/BeatStrip.jsx`. Two motion channels, exactly as the web:
///   1. A thin **playhead line** that glides continuously, driven by the engine's
///      bar phase via `TimelineView(.animation)` — always sample-accurate to the
///      audio, self-correcting, no animation drift.
///   2. The **sounding cell** flips to the ink colour and swells when its step hits.
///
/// The playhead is read from `getPhase()` *inside* the Canvas, on the animation
/// clock, so it moves every frame. The discrete `step`/`playing` come from the
/// transport's observable state and only flip the highlight — never re-laying-out
/// the whole strip 60 times a second.
struct BeatStripView: View {
    let beat: Beat
    var step: Int = -1
    var playing: Bool = false
    /// Bar phase in [0,1). Called on the animation clock — must be cheap.
    /// Nil for a static strip; then `step` alone decides the lit cell.
    var getPhase: (() -> Double)? = nil
    var mutedEnds: Set<LaneId> = []
    var showBols: Bool = false
    /// A tiny display-only rendering (list rows, quick-pick): primary lanes, no text.
    var mini: Bool = false

    var lanes: [LaneId] { stripLanes(for: beat, mini: mini) }

    var body: some View {
        TimelineView(.animation(paused: !playing)) { _ in
            Canvas { context, size in
                draw(&context, size: size, phase: getPhase?() ?? 0)
            }
        }
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, phase: Double) {
        let rows = lanes
        guard !rows.isEmpty else { return }
        let steps = max(1, beat.steps)
        let rowHeight = size.height / CGFloat(rows.count)
        let cellWidth = size.width / CGFloat(steps)
        // The lit cell follows the PLAYHEAD when there is one, not the engine's
        // step events: those fire when a block is rendered, a whole output
        // latency before it is heard (a third of a second over Bluetooth).
        let litStep = getPhase == nil ? step : min(Int(phase * Double(steps)), steps - 1)

        // A muted lane is drawn faintly but not erased — the player still sees where
        // the mridanga end is, just knows it is quiet.
        for (r, lane) in rows.enumerated() {
            let pattern = beat.pattern(lane) ?? []
            let muted = mutedEnds.contains(lane)
            let rowTop = CGFloat(r) * rowHeight

            for s in 0..<steps where s < pattern.count {
                guard let stroke = pattern[s] else { continue } // rest: draw nothing
                let active = playing && s == litStep
                let mark = markSize(cellWidth: cellWidth, rowHeight: rowHeight, active: active)
                let rect = CGRect(
                    x: CGFloat(s) * cellWidth + (cellWidth - mark) / 2,
                    y: rowTop + (rowHeight - mark) / 2,
                    width: mark, height: mark
                )
                let color = (active ? Palette.syahi : Palette.stroke(stroke, laneId: lane))
                drawStroke(stroke, in: rect, into: &context, color: color, muted: muted)
            }
        }

        // Faint separators between the beat's meter groups (the "1 + 2 + 3" phrasing).
        if let groups = beat.groups {
            var col = 0
            for g in groups {
                col += g
                if col < steps {
                    let x = CGFloat(col) * cellWidth
                    var line = Path()
                    line.move(to: CGPoint(x: x, y: 0))
                    line.addLine(to: CGPoint(x: x, y: size.height))
                    context.stroke(line, with: .color(Palette.rule.opacity(0.6)), lineWidth: 1)
                }
            }
        }

        // The gliding playhead.
        if playing, phase > 0 {
            let x = CGFloat(phase) * size.width
            var line = Path()
            line.move(to: CGPoint(x: x, y: 0))
            line.addLine(to: CGPoint(x: x, y: size.height))
            context.stroke(line, with: .color(Palette.syahi.opacity(0.9)), lineWidth: 1.5)
        }
    }

    private func markSize(cellWidth: CGFloat, rowHeight: CGFloat, active: Bool) -> CGFloat {
        let base = min(cellWidth, rowHeight) * (active ? 0.62 : 0.46)
        return max(3, base)
    }

    private func drawStroke(_ stroke: Stroke, in rect: CGRect, into context: inout GraphicsContext, color: Color, muted: Bool) {
        var ctx = context
        if muted { ctx.opacity = 0.35 }
        let shape = strokeVisual(stroke).shape
        switch shape {
        case .ring:
            ctx.stroke(Path(ellipseIn: rect), with: .color(color), lineWidth: max(1.5, rect.width * 0.16))
        case .rest:
            break // nothing for a rest
        default:
            ctx.fill(shapePath(shape, in: rect), with: .color(color))
        }
    }

    private func shapePath(_ shape: StrokeShape, in rect: CGRect) -> Path {
        switch shape {
        case .circle:
            return Path(ellipseIn: rect)
        case .square:
            return Path(roundedRect: rect, cornerRadius: rect.width * 0.18)
        case .diamond:
            var p = Path()
            p.move(to: CGPoint(x: rect.midX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.midY))
            p.closeSubpath()
            return p
        case .triangle:
            var p = Path()
            p.move(to: CGPoint(x: rect.midX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            p.closeSubpath()
            return p
        case .ring, .rest:
            return Path(ellipseIn: rect)
        }
    }
}

/// The lanes a beat actually gets drawn with — shared by the strip and anything
/// that must line up beside it (the label column on Home).
///   • Mini strips show only PRIMARY lanes (the beat's rhythmic identity).
///   • Full strips always draw the primary lanes, and a NON-primary lane (kartal…)
///     only when the beat has a hit in it.
func stripLanes(for beat: Beat, mini: Bool = false) -> [LaneId] {
    LaneId.ordered.filter { lane in
        guard let pattern = beat.pattern(lane) else { return false }
        if mini { return lane.isPrimary }
        if lane.isPrimary { return true }
        return pattern.contains { $0 != nil }
    }
}
