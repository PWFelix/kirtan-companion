import SwiftUI

/// The Learn tab: a "falling notes" view of the loaded beat.
///
/// A port of Android's `LearnScreen.kt`. Each stroke drops down its own column (one
/// per hand and stroke, headed by its bol) and lands on the hit line at the instant
/// the drum sounds it, so a learner can watch what is coming and play along on a real
/// mridanga. Watch-only: no scoring — the point is to follow the drum.
///
/// It drives the SAME transport as Home, so beat, tempo and play state are shared.
struct LearnView: View {
    let container: AppContainer

    private var transport: TransportViewModel { container.transport }
    /// The active list (what the quick-pick last chose from), so ‹ › step through it.
    private var beats: [Beat] {
        let list = container.library.categoryBeats(container.library.state.activeCategoryId)
        return list.isEmpty ? container.library.allBeats : list
    }
    @State private var pickerOpen = false
    private var beat: Beat? { transport.loadedBeat ?? beats.first }

    var body: some View {
        VStack(spacing: 14) {
            if let beat {
                header(beat)
                handToggle
                HighwayCanvas(
                    beat: beat,
                    bpm: transport.bpm,
                    playing: transport.playing,
                    columns: Highway.columns(leftHanded: transport.settings.learnLeftHanded),
                    engine: transport.engineHandle
                )
                controls(beat)
            } else {
                Text(String(localized: "Pick a beat on Home to learn it here.", bundle: .strings))
                    .foregroundStyle(Palette.syahiSoft)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 100) // clear of the floating bottom bar
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.head.ignoresSafeArea())
        .sheet(isPresented: $pickerOpen) {
            BeatPickerSheet(container: container) { pickerOpen = false }
                .presentationDetents([.medium, .large])
        }
    }

    private func header(_ beat: Beat) -> some View {
        HStack {
            iconButton("chevron.left", String(localized: "Previous beat", bundle: .strings)) { cycle(-1) }
            VStack(spacing: 2) {
                Button { pickerOpen = true } label: {
                    HStack(spacing: 4) {
                        Text(beat.name)
                            .font(.system(size: 22, weight: .semibold, design: .serif))
                            .foregroundStyle(Palette.syahi)
                            .lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption).foregroundStyle(Palette.syahiSoft)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Choose a beat", bundle: .strings))
                Text(String(localized: "Watch the strokes land, and play along", bundle: .strings))
                    .font(.caption)
                    .foregroundStyle(Palette.syahiSoft)
            }
            .frame(maxWidth: .infinity)
            iconButton("chevron.right", String(localized: "Next beat", bundle: .strings)) { cycle(1) }
        }
    }

    /// Left-handed players sit the drum the other way round; mirror the columns.
    private var handToggle: some View {
        let left = transport.settings.learnLeftHanded
        return Button { transport.setLearnLeftHanded(!left) } label: {
            Text(left ? String(localized: "Left-handed · dayan on the left", bundle: .strings) : String(localized: "Right-handed · dayan on the right", bundle: .strings))
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Palette.syahi)
                .frame(maxWidth: .infinity, minHeight: 38)
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.rule, lineWidth: 1))
        }
    }

    private func controls(_ beat: Beat) -> some View {
        HStack(spacing: 10) {
            // Learning wants SLOWER more than anything: 5 BPM steps.
            iconButton("minus", String(localized: "Slower", bundle: .strings)) { transport.nudgeBpm(-5) }
            Text("\(transport.bpm) BPM")
                .font(.headline)
                .monospacedDigit()
                .foregroundStyle(Palette.syahi)
            iconButton("plus", String(localized: "Faster", bundle: .strings)) { transport.nudgeBpm(5) }
            Spacer()
            Button { transport.togglePlay(beat) } label: {
                Label(transport.playing ? String(localized: "Pause", bundle: .strings) : String(localized: "Play", bundle: .strings),
                      systemImage: transport.playing ? "pause.fill" : "play.fill")
                    .font(.headline)
                    .frame(width: 130, height: 48)
                    .background(Palette.clay, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(Palette.onClay)
            }
            .disabled(!transport.ready)
        }
    }

    private func iconButton(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(Palette.syahiSoft)
                .frame(width: 44, height: 44)
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.rule, lineWidth: 1))
        }
        .accessibilityLabel(label)
    }

    private func cycle(_ delta: Int) {
        guard !beats.isEmpty else { return }
        let index = beats.firstIndex { $0.id == beat?.id } ?? 0
        let next = beats[((index + delta) % beats.count + beats.count) % beats.count]
        transport.loadBeat(next)
    }
}

/// The highway itself. Redraws every display frame while playing, reading the
/// engine's phase inside the Canvas (as the beat strip does), and holds the first bar
/// at rest while stopped so the learner can read what is coming.
private struct HighwayCanvas: View {
    let beat: Beat
    let bpm: Int
    let playing: Bool
    let columns: [HighwayColumn]
    let engine: KirtanEngine

    var body: some View {
        TimelineView(.animation(paused: !playing)) { _ in
            Canvas { context, size in
                let position = playing ? engine.getPhase() * Double(beat.steps) : 0
                draw(&context, size: size, position: position)
            }
        }
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, position: Double) {
        let lookahead = Highway.lookaheadSteps(bpm: bpm, cellsPerQuarter: cpqFor(beat))
        let frame = Highway.frame(beat, position: position, lookahead: lookahead)
        let gap: CGFloat = 6
        let colW = (size.width - gap * CGFloat(columns.count - 1)) / CGFloat(columns.count)
        let labelH: CGFloat = 34
        let hitY = size.height - labelH - 28
        let pxPerStep = hitY / CGFloat(lookahead)
        let noteR = min(max(min(colW, pxPerStep) * 0.36, 7), 20)
        func colX(_ i: Int) -> CGFloat { CGFloat(i) * (colW + gap) }
        func yFor(_ rel: Double) -> CGFloat { hitY - CGFloat(rel) * pxPerStep }

        // Column tracks.
        for i in columns.indices {
            let r = CGRect(x: colX(i), y: 0, width: colW, height: size.height - labelH)
            ctx.fill(Path(roundedRect: r, cornerRadius: 10), with: .color(Palette.headWorn))
        }

        // Beat lines: a bolder one on sam.
        for line in frame.lines {
            let y = yFor(line.rel)
            guard y >= 0, y <= hitY else { continue }
            var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
            ctx.stroke(p, with: .color(line.sam ? Palette.syahiSoft.opacity(0.55) : Palette.rule),
                       lineWidth: line.sam ? 2.5 : 1)
        }

        // The hit line.
        var hit = Path(); hit.move(to: CGPoint(x: 0, y: hitY)); hit.addLine(to: CGPoint(x: size.width, y: hitY))
        ctx.stroke(hit, with: .color(Palette.syahi), lineWidth: 3)

        // Notes; one that has just landed glows its column's target.
        for note in frame.notes {
            let cx = colX(columns.firstIndex(of: note.column) ?? 0) + colW / 2
            let y = yFor(note.rel)
            guard y >= -noteR, y <= hitY + noteR * 3 else { continue }
            let color = Palette.lane(note.column.lane)
            let landed = note.rel <= 0
            let alpha = landed ? min(max(1 + note.rel / Highway.trailSteps, 0), 1) : 1
            if playing && landed && note.rel > -0.6 {
                ctx.fill(circle(CGPoint(x: cx, y: hitY), noteR * 2), with: .color(color.opacity(0.25 * alpha)))
            }
            let c = CGPoint(x: cx, y: y)
            switch note.column.stroke {
            case .open: ctx.fill(circle(c, noteR), with: .color(color.opacity(alpha)))
            case .closed: ctx.stroke(circle(c, noteR - 2), with: .color(color.opacity(alpha)), lineWidth: 4)
            }
        }

        // Targets on the hit line, with each column's bol underneath.
        for (i, column) in columns.enumerated() {
            let cx = colX(i) + colW / 2
            let color = Palette.lane(column.lane)
            let hot = playing && frame.notes.contains { $0.column == column && $0.rel <= 0 && $0.rel > -0.5 }
            ctx.stroke(circle(CGPoint(x: cx, y: hitY), noteR + 4),
                       with: .color(hot ? color : color.opacity(0.35)), lineWidth: hot ? 4 : 2)
            ctx.draw(Text(column.bol).font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.syahi),
                     at: CGPoint(x: cx, y: size.height - labelH + 12))
            let sub = "\(column.lane.label) · " + (column.stroke == .open ? String(localized: "open", bundle: .strings) : String(localized: "closed", bundle: .strings))
            ctx.draw(Text(sub).font(.system(size: 9)).foregroundStyle(Palette.syahiSoft),
                     at: CGPoint(x: cx, y: size.height - 6))
        }

        if !playing {
            let hint = ctx.resolve(Text(String(localized: "Press Play — strokes land on the line", bundle: .strings))
                .font(.system(size: 12)).foregroundStyle(Palette.syahiSoft))
            let s = hint.measure(in: size)
            let center = CGPoint(x: size.width / 2, y: hitY - pxPerStep / 2)
            let box = CGRect(x: center.x - s.width / 2 - 12, y: center.y - s.height / 2 - 6,
                             width: s.width + 24, height: s.height + 12)
            ctx.fill(Path(roundedRect: box, cornerRadius: 12), with: .color(Palette.head.opacity(0.92)))
            ctx.draw(hint, at: center)
        }
    }

    private func circle(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    }
}
