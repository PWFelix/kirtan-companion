import SwiftUI

/// The Learn tab: a "falling notes" view of the loaded beat.
///
/// A port of Android's `LearnScreen.kt`. Each stroke drops down its hand's column (one
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
    // Hill climb. On/off is per session (a lesson never speeds up unasked); the step
    // and interval are remembered in prefs, so turning it back on offers the last choice.
    @State private var climbOn = false
    @State private var climbLeft = HillClimb.defaultIntervalSec
    @State private var climbSheet = false
    private var climbStep: Int { transport.settings.hillClimbStep }
    private var climbEvery: Int { transport.settings.hillClimbSeconds }
    private var ceiling: Int { tempoRange(brahmaloka: transport.brahmaloka).upperBound }
    private var beat: Beat? { transport.loadedBeat ?? beats.first }

    var body: some View {
        VStack(spacing: 14) {
            if let beat {
                header(beat)
                instrumentSwitch
                // Handedness is about which side the drum's heads sit; karatalas have none.
                if instrument == .mrdanga { handToggle }
                HighwayCanvas(
                    beat: beat,
                    bpm: transport.bpm,
                    playing: transport.playing,
                    instrument: instrument,
                    emptyMessage: Highway.hasPart(beat, instrument) ? nil
                        : String(localized: "This beat has no kartal part", bundle: .strings),
                    columns: Highway.columns(leftHanded: transport.settings.learnLeftHanded, instrument: instrument),
                    engine: transport.engineHandle
                )
                controls(beat)
                if climbOn { climbStatus }
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
        #if DEBUG
        // Screenshot hook: `-kirtanLearnKartal` opens Learn on the kartal highway.
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("-kirtanLearnKartal") { transport.setLearnKartal(true) }
            // `-kirtanClimb` opens the hill-climb sheet; `-kirtanClimbOn` turns it on.
            if ProcessInfo.processInfo.arguments.contains("-kirtanClimb") { climbSheet = true }
            if ProcessInfo.processInfo.arguments.contains("-kirtanClimbOn") { climbLeft = climbEvery; climbOn = true }
        }
        #endif
        // The countdown runs on PLAYING time: pausing holds it, and Play carries on.
        .task(id: ClimbKey(on: climbOn, playing: transport.playing, step: climbStep, every: climbEvery)) {
            guard climbOn, transport.playing else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { return }
                if HillClimb.atTop(transport.bpm, ceiling: ceiling) { continue }
                climbLeft -= 1
                if climbLeft <= 0 {
                    transport.changeBpm(HillClimb.next(transport.bpm, step: climbStep, ceiling: ceiling))
                    climbLeft = climbEvery
                }
            }
        }
        .sheet(isPresented: $climbSheet) {
            HillClimbSheet(step: climbStep, seconds: climbEvery, onCancel: { climbSheet = false }) { step, seconds in
                transport.setHillClimb(step: step, seconds: seconds)
                climbLeft = seconds
                climbOn = true
                climbSheet = false
            }
            .presentationDetents([.medium])
        }
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

    private var instrument: LearnInstrument { transport.settings.learnKartal ? .kartal : .mrdanga }

    /// Mrdanga | Kartals: which part the highway teaches. Remembered in settings.
    private var instrumentSwitch: some View {
        HStack(spacing: 4) {
            ForEach(LearnInstrument.allCases, id: \.self) { option in
                let on = option == instrument
                Button { transport.setLearnKartal(option == .kartal) } label: {
                    Text(option == .kartal ? String(localized: "Kartals", bundle: .strings) : String(localized: "Mrdanga", bundle: .strings))
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(on ? Palette.syahi : Palette.syahiSoft)
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background(on ? Palette.head : .clear, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(on ? Palette.rule : .clear, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Learn which part", bundle: .strings))
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
                .lineLimit(1)
                .fixedSize()
                .foregroundStyle(Palette.syahi)
            iconButton("plus", String(localized: "Faster", bundle: .strings)) { transport.nudgeBpm(5) }
            Spacer()
            // Off: opens the choice (last one pre-selected). On: one tap turns it off.
            Button { if climbOn { climbOn = false } else { climbSheet = true } } label: {
                Image(systemName: "stairs")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(climbOn ? Palette.onClay : Palette.syahiSoft)
                    .frame(width: 44, height: 44)
                    .background(climbOn ? Palette.clay : .clear, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(climbOn ? Palette.clay : Palette.rule, lineWidth: 1))
            }
            .accessibilityLabel(climbOn
                ? String(localized: "Hill climb is on. Tap to turn it off", bundle: .strings)
                : String(localized: "Hill climb", bundle: .strings))
            .accessibilityAddTraits(climbOn ? .isSelected : [])
            Button { transport.togglePlay(beat) } label: {
                Label(transport.playing ? String(localized: "Pause", bundle: .strings) : String(localized: "Play", bundle: .strings),
                      systemImage: transport.playing ? "pause.fill" : "play.fill")
                    .font(.headline)
                    .frame(width: 112, height: 48)
                    .background(Palette.clay, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(Palette.onClay)
            }
            .disabled(!transport.ready)
        }
    }

    private var climbStatus: some View {
        let every = HillClimbSheet.intervalLabel(climbEvery)
        let text: String
        if HillClimb.atTop(transport.bpm, ceiling: ceiling) {
            text = String(localized: "Reached the top of the range: \(transport.bpm) BPM", bundle: .strings)
        } else if transport.playing {
            text = String(localized: "Climbing +\(climbStep) BPM every \(every) · next in \(HillClimb.clock(climbLeft))", bundle: .strings)
        } else {
            text = String(localized: "Climbing +\(climbStep) BPM every \(every) · paused until you play", bundle: .strings)
        }
        return Text(text)
            .font(.caption.weight(.semibold))
            .monospacedDigit()
            .foregroundStyle(Palette.clay)
            .frame(maxWidth: .infinity)
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

private struct ClimbKey: Equatable {
    let on: Bool
    let playing: Bool
    let step: Int
    let every: Int
}

/// The hill-climb choice: N BPM every X. Start turns it on; Cancel leaves it off.
private struct HillClimbSheet: View {
    let onCancel: () -> Void
    let onAccept: (Int, Int) -> Void
    @State private var step: Int
    @State private var seconds: Int

    init(step: Int, seconds: Int, onCancel: @escaping () -> Void, onAccept: @escaping (Int, Int) -> Void) {
        _step = State(initialValue: step)
        _seconds = State(initialValue: seconds)
        self.onCancel = onCancel
        self.onAccept = onAccept
    }

    static func intervalLabel(_ s: Int) -> String {
        s < 60 ? String(localized: "\(s) s", bundle: .strings) : String(localized: "\(s / 60) min", bundle: .strings)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "Hill climb", bundle: .strings))
                .font(.title2.weight(.semibold))
                .foregroundStyle(Palette.syahi)
            Text(String(localized: "Speed up a little at a time while you play, starting from the tempo you're at now.", bundle: .strings))
                .font(.subheadline)
                .foregroundStyle(Palette.syahiSoft)
            label(String(localized: "Go up by", bundle: .strings))
            chips(HillClimb.steps, selected: step, text: { String(localized: "+\($0) BPM", bundle: .strings) }) { step = $0 }
            label(String(localized: "Every", bundle: .strings))
            chips(HillClimb.intervalsSec, selected: seconds, text: Self.intervalLabel) { seconds = $0 }
            HStack(spacing: 8) {
                Button(action: onCancel) {
                    Text(String(localized: "Cancel", bundle: .strings))
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .foregroundStyle(Palette.syahi)
                        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.rule, lineWidth: 1))
                }
                Button { onAccept(step, seconds) } label: {
                    Label(String(localized: "Start climbing", bundle: .strings), systemImage: "stairs")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Palette.clay, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(Palette.onClay)
                }
            }
            .padding(.top, 14)
        }
        .padding(22)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.head.ignoresSafeArea())
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption2.weight(.bold))
            .tracking(1)
            .foregroundStyle(Palette.syahiSoft)
            .padding(.top, 8)
    }

    private func chips(_ values: [Int], selected: Int, text: @escaping (Int) -> String, pick: @escaping (Int) -> Void) -> some View {
        // Rows of three: "+20 BPM" is too wide for five abreast on a phone.
        let rows = stride(from: 0, to: values.count, by: 3).map { Array(values[$0..<min($0 + 3, values.count)]) }
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { v in
                        Button { pick(v) } label: {
                            Text(text(v))
                                .font(.caption.weight(.bold))
                                .lineLimit(1)
                                .fixedSize()
                                .padding(.horizontal, 14)
                                .frame(minHeight: 36)
                                .foregroundStyle(v == selected ? Palette.onClay : Palette.syahi)
                                .background(v == selected ? Palette.clay : .clear, in: Capsule())
                                .overlay(Capsule().strokeBorder(v == selected ? Palette.clay : Palette.rule, lineWidth: 1))
                        }
                        .accessibilityAddTraits(v == selected ? .isSelected : [])
                    }
                }
            }
        }
    }
}

/// The highway itself. Redraws every display frame while playing, reading the
/// engine's phase inside the Canvas (as the beat strip does), and holds the first bar
/// at rest while stopped so the learner can read what is coming.
private struct HighwayCanvas: View {
    let beat: Beat
    let bpm: Int
    let playing: Bool
    let instrument: LearnInstrument
    let emptyMessage: String?
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
        let frame = Highway.frame(beat, position: position, lookahead: lookahead, instrument: instrument)
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
            let color = Palette.lane(note.column)
            let landed = note.rel <= 0
            let alpha = landed ? min(max(1 + note.rel / Highway.trailSteps, 0), 1) : 1
            if playing && landed && note.rel > -0.6 {
                ctx.fill(circle(CGPoint(x: cx, y: hitY), noteR * 2), with: .color(color.opacity(0.25 * alpha)))
            }
            let c = CGPoint(x: cx, y: y)
            switch note.stroke {
            case .open: ctx.fill(circle(c, noteR), with: .color(color.opacity(alpha)))
            case .closed:
                let d = (noteR - 2) * 0.75 / 0.72 // CrossMark insets 14% each side
                ctx.stroke(CrossMark().path(in: CGRect(x: c.x - d, y: c.y - d, width: d * 2, height: d * 2)),
                           with: .color(color.opacity(alpha)), style: StrokeStyle(lineWidth: 4, lineCap: .round))
            }
            // Its bol beside it: one column per hand, so the stroke is named here.
            // (Not once it has passed the line: it would run into the hand's label.)
            if y <= hitY {
                ctx.draw(Text(note.bol).font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.syahi.opacity(alpha)),
                         at: CGPoint(x: cx + noteR + 6, y: y), anchor: .leading)
            }
        }

        // Targets on the hit line, with the hand (lane) underneath.
        for (i, column) in columns.enumerated() {
            let cx = colX(i) + colW / 2
            let color = Palette.lane(column)
            let hot = playing && frame.notes.contains { $0.column == column && $0.rel <= 0 && $0.rel > -0.5 }
            ctx.stroke(circle(CGPoint(x: cx, y: hitY), noteR + 4),
                       with: .color(hot ? color : color.opacity(0.35)), lineWidth: hot ? 4 : 2)
            ctx.draw(Text(column.label).font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.syahi),
                     at: CGPoint(x: cx, y: size.height - labelH + 12))
        }

        // A beat with nothing for this instrument says so, rather than leaving an empty
        // highway that reads as broken.
        if !playing || emptyMessage != nil {
            let hint = ctx.resolve(Text(emptyMessage ?? String(localized: "Press Play — strokes land on the line", bundle: .strings))
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
