import SwiftUI

/*
 * The editor's TIMELINE view: the beat as a vertical lane, like the Learn highway, read
 * past a fixed line in the MIDDLE. Time runs upward — what is coming sits above the line,
 * what has passed below — so playing scrolls the cells down through it as Learn's notes fall.
 *
 * The centre line IS the cursor. Dragging scrubs (and sounds each cell as it crosses the
 * line); letting go snaps to the nearest cell and puts the cursor there, so the pads below
 * write on the line. Tapping a cell edits it with the same rule as the strip view
 * (BeatEditorView.tapCell). Touching it while the preview plays pauses the preview.
 *
 * The bar loops, so the lane never ends: rows past either end are the next or previous
 * cycle, drawn faint, which shows the join between cycles. Cells keep one height at every
 * resolution, and heavier rules mark where each beat begins. Same design as Android's
 * EditorTimeline.kt and the web's ui/EditorTimeline.jsx; the strip view stays as an option.
 */

/// The scroll position lives in a reference box so the per-frame canvas can ease it
/// without invalidating SwiftUI state sixty times a second.
private final class TimelinePosition {
    var pos: Double
    var dragFrom: Double?
    var lastCell = Int.min
    var lastSound = Date.distantPast
    init(_ pos: Double) { self.pos = pos }
}

struct EditorTimeline: View {
    let lanes: [LaneId: [Stroke?]]
    let groups: [Int]
    let cursorLane: Int
    let cursorStep: Int
    let selection: ClosedRange<Int>?
    let previewing: Bool
    let getPhase: () -> Double
    let onCellTap: (_ lane: Int, _ step: Int) -> Void
    let onLaneTap: (_ lane: Int) -> Void
    let onCursor: (_ step: Int) -> Void
    let onPause: () -> Void
    let onScrubSound: (_ step: Int) -> Void

    private static let cell: Double = 44
    private static let gutter: Double = 34
    private static let height: Double = 242  // five and a half cells: the pads stay in reach

    @State private var box: TimelinePosition?

    private var steps: Int { groups.reduce(0, +) }

    var body: some View {
        let position = box ?? TimelinePosition(Double(cursorStep))
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                Color.clear.frame(width: Self.gutter, height: 1)
                ForEach(LaneId.ordered.indices, id: \.self) { i in
                    Button { onLaneTap(i) } label: {
                        Text(LaneId.ordered[i].label.uppercased())
                            .font(.system(size: 11, weight: i == cursorLane ? .bold : .medium))
                            .tracking(0.8)
                            .foregroundStyle(Palette.lane(LaneId.ordered[i]))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                }
            }
            GeometryReader { geo in
                TimelineView(.animation) { _ in
                    Canvas { ctx, size in
                        advance(position)
                        draw(ctx, size: size, pos: position.pos)
                    }
                }
                .contentShape(Rectangle())
                .gesture(drag(position, size: geo.size))
            }
            .frame(height: Self.height)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .onAppear { if box == nil { box = position } }
    }

    /// One frame: follow the transport while playing, else glide to the cursor the short way round.
    private func advance(_ p: TimelinePosition) {
        guard p.dragFrom == nil, steps > 0 else { return }
        if previewing {
            p.pos = getPhase() * Double(steps)
        } else {
            let target = EditorTimelineMath.nearest(cursorStep, from: p.pos, steps: steps)
            let d = target - p.pos
            p.pos = abs(d) < 0.01 ? target : p.pos + d * 0.3
        }
    }

    private func drag(_ p: TimelinePosition, size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in
                let dy = v.translation.height
                if p.dragFrom == nil {
                    guard abs(dy) > 6 else { return }
                    if previewing { onPause() }
                    p.dragFrom = p.pos - dy / Self.cell
                    p.lastCell = Int(p.pos.rounded())
                }
                // Content follows the finger: dragging down brings later cells to the line.
                p.pos = (p.dragFrom ?? p.pos) + dy / Self.cell
                let cell = Int(p.pos.rounded())
                if cell != p.lastCell {
                    p.lastCell = cell
                    // Sound what crosses the line, but not a machine-gun on a fast drag.
                    if Date().timeIntervalSince(p.lastSound) > 0.045 {
                        p.lastSound = Date()
                        onScrubSound(EditorTimelineMath.wrap(cell, steps: steps))
                    }
                }
            }
            .onEnded { v in
                if p.dragFrom != nil {
                    p.dragFrom = nil
                    onCursor(EditorTimelineMath.wrap(Int(p.pos.rounded()), steps: steps))
                    return
                }
                // A tap: which lane column, which row.
                let x = v.location.x, y = v.location.y
                guard x >= Self.gutter, steps > 0 else { return }
                let colW = (size.width - Self.gutter) / Double(LaneId.ordered.count)
                let lane = min(LaneId.ordered.count - 1, Int((x - Self.gutter) / colW))
                let row = EditorTimelineMath.row(at: p.pos, dyUp: size.height / 2 - y, cell: Self.cell)
                if previewing { onPause() }
                onCellTap(lane, EditorTimelineMath.wrap(row, steps: steps))
            }
    }

    private func draw(_ ctx: GraphicsContext, size: CGSize, pos: Double) {
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Palette.headWorn))
        guard steps > 0 else { return }
        let cell = Self.cell, gutter = Self.gutter
        let colW = (size.width - gutter) / Double(LaneId.ordered.count)
        let cy = size.height / 2
        let half = size.height / 2 / cell
        let lineCycle = EditorTimelineMath.cycle(of: Int(pos.rounded()), steps: steps)
        var starts = Set<Int>(); var acc = 0
        for g in groups { starts.insert(acc); acc += g }
        let labels = labelsFromGroups(groups)

        // The band the line sits in: the cell the pads will write to.
        ctx.fill(Path(CGRect(x: 0, y: cy - cell / 2, width: size.width, height: cell)), with: .color(Palette.clay.opacity(0.10)))

        for row in Int((pos - half - 1).rounded(.down))...Int((pos + half + 1).rounded(.up)) {
            let idx = EditorTimelineMath.wrap(row, steps: steps)
            let y = cy - (Double(row) - pos) * cell  // time runs UP
            let faint = EditorTimelineMath.cycle(of: row, steps: steps) != lineCycle
            let a = faint ? 0.35 : 1.0
            if let s = selection, !faint, s.contains(idx) {
                ctx.fill(Path(CGRect(x: gutter, y: y - cell / 2, width: size.width - gutter, height: cell)), with: .color(Palette.clay.opacity(0.18)))
            }
            // A beat begins at a cell's lower edge: a heavier rule there.
            let beat = starts.contains(idx)
            var rule = Path()
            rule.move(to: CGPoint(x: beat ? 0 : gutter, y: y + cell / 2))
            rule.addLine(to: CGPoint(x: size.width, y: y + cell / 2))
            ctx.stroke(rule, with: .color((beat ? Palette.syahiSoft : Palette.rule).opacity((beat ? 0.55 : 0.6) * a)), lineWidth: beat ? 2 : 1)
            if idx < labels.count {
                let label = labels[idx]
                ctx.draw(
                    Text(label).font(.system(size: beat ? 13 : 11, weight: beat ? .bold : .regular))
                        .foregroundStyle(Palette.syahiSoft.opacity((label == "·" ? 0.5 : 0.95) * a)),
                    at: CGPoint(x: gutter / 2, y: y),
                )
            }
            for (i, lane) in LaneId.ordered.enumerated() {
                let cx = gutter + colW * Double(i) + colW / 2
                let color = Palette.lane(lane).opacity(a)
                let pattern = lanes[lane] ?? []
                switch idx < pattern.count ? pattern[idx] : nil {
                case .open?:
                    let r = cell * 0.26
                    ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: y - r, width: r * 2, height: r * 2)), with: .color(color))
                case .closed?:
                    let r = cell * 0.2
                    var x = Path()
                    x.move(to: CGPoint(x: cx - r, y: y - r)); x.addLine(to: CGPoint(x: cx + r, y: y + r))
                    x.move(to: CGPoint(x: cx - r, y: y + r)); x.addLine(to: CGPoint(x: cx + r, y: y - r))
                    ctx.stroke(x, with: .color(color), style: StrokeStyle(lineWidth: 2.6, lineCap: .round))
                case nil:
                    ctx.fill(Path(ellipseIn: CGRect(x: cx - 2, y: y - 2, width: 4, height: 4)), with: .color(Palette.rule.opacity(a)))
                }
                if !faint, idx == cursorStep, i == cursorLane, abs(Double(row) - pos) < 0.5 {
                    ctx.stroke(
                        Path(roundedRect: CGRect(x: gutter + colW * Double(i) + 4, y: y - cell / 2 + 3, width: colW - 8, height: cell - 6), cornerRadius: 8),
                        with: .color(Palette.clay), lineWidth: 2,
                    )
                }
            }
        }
        // The line itself, across everything.
        var line = Path()
        line.move(to: CGPoint(x: 0, y: cy + cell / 2)); line.addLine(to: CGPoint(x: size.width, y: cy + cell / 2))
        ctx.stroke(line, with: .color(Palette.clay), lineWidth: 2)
    }
}
