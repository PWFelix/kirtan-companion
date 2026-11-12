import SwiftUI

/// The custom-beat editor: a paintable grid of the two mridanga ends (and cymbals)
/// that saves a new beat into the library.
///
/// Ported in spirit from `src/BeatEditor.jsx`. That editor is the most stateful view
/// in the app; this keeps the same *contract* — a beat in, `onSave(beat)` out, takes
/// the transport over for preview while open — and the same CELL-DRIVEN entry: tap any
/// cell to select it, the pads become that row's strokes, and each pad writes the cell
/// and steps along the row (see data/EditorPads.swift).
/// Unmounting the view discards the draft for free, which is why the draft lives in
/// `@State` here and nowhere else.
struct BeatEditorView: View {
    let container: AppContainer
    /// What the editor is doing, which decides both the seed and where Save writes.
    var mode: EditorMode = .new
    var onDone: () -> Void

    /// The editor's four jobs. A built-in is never edited in place by a player — it FORKS,
    /// because it is not theirs; only a maintainer may write it back, for everyone.
    enum EditorMode: Equatable {
        /// A blank draft, saved as a new beat.
        case new
        /// One of your beats, saved over itself (same id, so playlists keep it).
        case edit(Beat)
        /// A built-in, saved as a new beat of your own.
        case fork(Beat)
        /// A built-in, written back to `shipped_beats` for every install (maintainers).
        case everyone(Beat)

        var seed: Beat? {
            switch self {
            case .new: return nil
            case .edit(let b), .fork(let b), .everyone(let b): return b
            }
        }

        var title: String {
            switch self {
            case .new: return String(localized: "New beat", bundle: .strings)
            case .edit: return String(localized: "Edit beat", bundle: .strings)
            case .fork: return String(localized: "Edit a copy", bundle: .strings)
            case .everyone: return String(localized: "Edit for everyone", bundle: .strings)
            }
        }
    }

    private var transport: TransportViewModel { container.transport }

    // ── The draft. All of it means nothing outside this view. ──
    @State private var name = String(localized: "New beat", bundle: .strings)
    @State private var bpm = 120
    /// The editor's own Brahmaloka mode: + at 200 opens it, below 200 closes it, and a
    /// beat saved above 200 opens in it.
    @State private var brahmaloka = false
    /// The grid's shape: beats, cells per beat, and what ± adds. See EditorShape.
    @State private var shape = EditorMeter.all[0].shape
    @State private var dayan: [Stroke?] = Array(repeating: nil, count: 8)
    @State private var bayan: [Stroke?] = Array(repeating: nil, count: 8)
    @State private var kartal: [Stroke?] = Array(repeating: nil, count: 8)
    @State private var cursor: (lane: Int, step: Int) = (0, 0)
    @State private var previewing = false
    /// Grid cell size, rescaled by a pinch. Bounded so it stays tappable on a phone.
    @State private var cellSize: CGFloat = 32
    /// The cell size when the current pinch began, so `magnification` scales relative to it.
    @State private var magnifyBase: CGFloat?
    /// The grid and hits before the last resolution change, while its notice shows:
    /// the editor has no general undo, so this one change gets its own.
    @State private var lastResample: (shape: EditorShape, lanes: [[Stroke?]], report: ResampleReport)?
    @State private var seeded = false
    @State private var saving = false
    @State private var saveError: String?
    /// The author's tags, published with the beat.
    @State private var tags: [String] = []
    @State private var pickingTags = false

    /// Whole cells in the bar: the beats × the cells in each.
    private var steps: Int { shape.steps }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().background(Palette.rule)
            ScrollView {
                VStack(spacing: 18) {
                    nameField
                    tagsLine
                    meterRow
                    lengthRow
                    resolutionRow
                    grid
                    pads
                    saveRow
                }
                .padding(18)
            }
            .background(Palette.head.ignoresSafeArea())
        }
        // Opaque: the editor floats over the shell, whose title otherwise shows through.
        .background(Palette.head.ignoresSafeArea())
        .onAppear(perform: seed)
        .alert(String(localized: "Not saved", bundle: .strings), isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
            Button(String(localized: "OK", bundle: .strings), role: .cancel) { saveError = nil }
        } message: {
            Text(saveError ?? "")
        }
    }

    /// Load the beat being edited into the draft, once.
    private func seed() {
        guard !seeded, let beat = mode.seed else { return }
        seeded = true
        name = beat.name
        tags = beat.tags
        bpm = beat.bpm
        brahmaloka = beat.bpm > NORMAL_MAX_BPM
        let n = beat.steps
        func lane(_ id: LaneId) -> [Stroke?] {
            let cells = beat.pattern(id) ?? []
            return Array(cells.prefix(n)) + Array(repeating: nil, count: max(0, n - cells.count))
        }
        dayan = lane(.dayan)
        bayan = lane(.bayan)
        kartal = lane(.kartal)
        // The beat's own grouping, verbatim: a beat like matan (16 groups of 3) must not be
        // re-metered by an edit that never touched the meter.
        let groups = groupsFor(beat)
        if groups.reduce(0, +) == n {
            shape = EditorShape.seeded(groups: groups, cpq: cpqFor(beat))
        } else {
            shape = EditorShape.seeded(groups: [n], cpq: cpqFor(beat))
        }
    }

    // MARK: header

    private var header: some View {
        HStack {
            Text(mode.title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Palette.syahi)
            Spacer()
            Button {
                if previewing { stopPreview() }
                onDone()
            } label: {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Palette.syahiSoft)
                    .frame(width: 32, height: 32)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    // MARK: controls

    private var nameField: some View {
        TextField(String(localized: "Name", bundle: .strings), text: $name)
            .textFieldStyle(.roundedBorder)
            .font(.body)
            // Typing stops at the limit rather than failing later on save.
            .onChange(of: name) { _, value in
                let clamped = Names.clamp(value)
                if clamped != value { name = clamped }
            }
    }

    private var meterRow: some View {
        HStack(spacing: 12) {
            control(String(localized: "Meter", bundle: .strings)) {
                Picker(String(localized: "Meter", bundle: .strings), selection: meterBinding) {
                    if EditorMeter.matching(shape) == nil {
                        Text(String(localized: "As written (\(steps))", bundle: .strings)).tag(EditorMeter?.none)
                    }
                    ForEach(EditorMeter.all) { m in Text(m.label).tag(EditorMeter?.some(m)) }
                }
                .pickerStyle(.menu)
            }
            control(String(localized: "Tempo", bundle: .strings)) {
                HStack(spacing: 6) {
                    Stepper("\(bpm)", onIncrement: {
                        if !brahmaloka && bpm >= NORMAL_MAX_BPM {
                            brahmaloka = true
                            container.transport.celebrateBrahmaloka()
                        } else {
                            bpm = min(bpm + 1, brahmaloka ? MAX_BPM : NORMAL_MAX_BPM)
                        }
                    }, onDecrement: {
                        bpm = max(bpm - 1, MIN_BPM)
                        brahmaloka = brahmalokaFor(bpm: bpm, was: brahmaloka)
                    })
                    .font(.footnote.monospacedDigit())
                    if brahmaloka {
                        BrahmalokaBadge {
                            brahmaloka = false
                            bpm = min(bpm, NORMAL_MAX_BPM)
                        }
                    }
                }
            }
        }
    }

    private func control(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(Palette.syahiSoft)
            content().fixedSize()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 10))
    }

    private var meterBinding: Binding<EditorMeter?> {
        Binding(
            get: { EditorMeter.matching(shape) },
            set: { choice in
                guard let preset = choice else { return }
                setShape(preset.shape)
            }
        )
    }

    /// Lever one: how many beats. ± adds or removes one beat (a whole cycle in 6/8, 7/8).
    private var lengthRow: some View {
        control(String(localized: "Length", bundle: .strings)) {
            HStack(spacing: 14) {
                Button { setShape(shape.removingBeat()) } label: { Image(systemName: "minus.circle") }
                    .disabled(!shape.canRemoveBeat)
                    .accessibilityLabel(String(localized: "Remove a beat", bundle: .strings))
                Text(String(localized: "\(shape.beatCount) beats in the bar", bundle: .strings))
                    .font(.footnote.monospacedDigit())
                Button { setShape(shape.addingBeat()) } label: { Image(systemName: "plus.circle") }
                    .disabled(!shape.canAddBeat)
                    .accessibilityLabel(String(localized: "Add a beat", bundle: .strings))
            }
        }
    }

    /// Lever two: how finely each beat is divided. Cells per beat in a simple meter, a
    /// multiplier in an odd one (7/8 stays 2+2+3). Keeps the rhythm; options past 64
    /// cells are disabled.
    private var resolutionRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(shape.isMultiplier ? String(localized: "Resolution", bundle: .strings) : String(localized: "Cells per beat", bundle: .strings))
                .font(.caption).foregroundStyle(Palette.syahiSoft)
            HStack(spacing: 8) {
                ForEach(shape.resolutionOptions, id: \.self) { option in
                    let selected = option == shape.resolution
                    Button { changeResolution(to: option) } label: {
                        Text(shape.isMultiplier ? "×\(option)" : "\(option)")
                            .font(.footnote.weight(.bold))
                            .frame(minWidth: 40, minHeight: 34)
                            .foregroundStyle(selected ? Palette.onClay : Palette.syahi)
                            .background(selected ? Palette.clay : Color.clear, in: Capsule())
                            .overlay(Capsule().strokeBorder(selected ? Palette.clay : Palette.rule, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .disabled(!selected && shape.atResolution(option) == nil)
                    .opacity(selected || shape.atResolution(option) != nil ? 1 : 0.35)
                }
            }
            if let last = lastResample {
                HStack(alignment: .firstTextBaseline) {
                    Text(resampleMessage(last.report))
                        .font(.caption).foregroundStyle(Palette.syahiSoft)
                    Spacer()
                    Button(String(localized: "Undo", bundle: .strings)) {
                        shape = last.shape
                        dayan = last.lanes[0]; bayan = last.lanes[1]; kartal = last.lanes[2]
                        lastResample = nil
                        setCursor(lane: cursor.lane, step: cursor.step)
                    }
                    .font(.caption.weight(.bold))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 10))
    }

    private func resampleMessage(_ report: ResampleReport) -> String {
        var parts: [String] = []
        if report.moved > 0 { parts.append(String(localized: "\(report.moved) hits moved to the nearest cell.", bundle: .strings)) }
        if report.dropped > 0 { parts.append(String(localized: "\(report.dropped) dropped where two landed together.", bundle: .strings)) }
        return parts.joined(separator: " ")
    }

    /// Re-grid at a new resolution, keeping every hit at its moment in the bar.
    private func changeResolution(to option: Int) {
        guard option != shape.resolution, let next = shape.atResolution(option) else { return }
        var report = ResampleReport(moved: 0, dropped: 0)
        let before = (shape: shape, lanes: [dayan, bayan, kartal])
        dayan = resample(dayan, fromCpq: shape.cpq, toCpq: next.cpq, steps: next.steps, report: &report)
        bayan = resample(bayan, fromCpq: shape.cpq, toCpq: next.cpq, steps: next.steps, report: &report)
        kartal = resample(kartal, fromCpq: shape.cpq, toCpq: next.cpq, steps: next.steps, report: &report)
        cursor.step = min(cursor.step * next.cpq / shape.cpq, next.steps - 1)
        shape = next
        lastResample = report == ResampleReport(moved: 0, dropped: 0) ? nil : (before.shape, before.lanes, report)
    }

    // MARK: the grid

    private var grid: some View {
        ScrollViewReader { proxy in
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 8) {
                VStack(spacing: 8) {
                    ForEach(LaneId.ordered.indices, id: \.self) { i in
                        Text(LaneId.ordered[i].label)
                            .font(.caption2)
                            .frame(height: cellSize, alignment: .leading)
                            .foregroundStyle(Palette.lane(LaneId.ordered[i]))
                    }
                }
                .frame(width: 46, alignment: .leading)

                // One row per lane, stacked beside the label column (they were in the
                // outer HStack, which laid bayan and kartal out to the RIGHT of dayan).
                VStack(spacing: 8) {
                ForEach(LaneId.ordered.indices, id: \.self) { laneIndex in
                    let lane = LaneId.ordered[laneIndex]
                    HStack(spacing: 8) {
                        ForEach(0..<steps, id: \.self) { s in
                            EditorCell(
                                stroke: patternValue(laneIndex, s),
                                lane: lane,
                                isCursor: cursor.lane == laneIndex && cursor.step == s,
                                sameMoment: cursor.lane != laneIndex && cursor.step == s,
                                size: max(16, cellSize - 2),
                            ) {
                                setCursor(lane: laneIndex, step: s)
                            }
                            .id(laneIndex * 1000 + s)
                        }
                    }
                    .frame(height: cellSize)
                    .background(Palette.headWorn.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
                }
                }
            }
            .padding(4)
        }
        // Keep the cell being typed into on screen as entry walks along the row.
        .onChange(of: cursor.step) { _, step in
            withAnimation { proxy.scrollTo(cursor.lane * 1000 + step) }
        }
        }
        .simultaneousGesture(magnify)
    }

    /// Pinch the grid to rescale its cells. The base is frozen at the gesture's start so the
    /// live magnification is relative, and committed on end — a plain `scaleEffect` wouldn't
    /// persist once the fingers lift.
    private var magnify: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let base = magnifyBase ?? cellSize
                magnifyBase = base
                cellSize = min(56, max(18, base * value.magnification))
            }
            .onEnded { _ in magnifyBase = nil }
    }

    private func patternValue(_ laneIndex: Int, _ step: Int) -> Stroke? {
        let lane = LaneId.ordered[laneIndex]
        switch lane {
        case .dayan:  return step < dayan.count ? dayan[step] : nil
        case .bayan:  return step < bayan.count ? bayan[step] : nil
        case .kartal: return step < kartal.count ? kartal[step] : nil
        }
    }

    /// Write the pad into the selected cell, sound it, and step along the same row.
    private func applyPad(_ pad: EditorPad) {
        setPatternValue(lane: cursor.lane, step: cursor.step, value: pad.stroke)
        if let stroke = pad.stroke, !previewing {
            transport.engineHandle.playStroke(pad.lane, open: stroke == .open)
        }
        cursor.step = EditorPads.nextStep(after: cursor.step, steps: steps)
    }

    private var cursorLane: LaneId { LaneId.ordered[cursor.lane] }

    private func setPatternValue(lane: Int, step: Int, value: Stroke?) {
        switch LaneId.ordered[lane] {
        case .dayan:  if step < dayan.count { dayan[step] = value }
        case .bayan:  if step < bayan.count { bayan[step] = value }
        case .kartal: if step < kartal.count { kartal[step] = value }
        }
    }

    private func setCursor(lane: Int, step: Int) {
        cursor = (lane: min(max(0, lane), LaneId.ordered.count - 1), step: min(max(0, step), steps - 1))
    }

    /// The selected cell's row's palette, with a heading saying which cell it writes.
    private var pads: some View {
        VStack(alignment: .leading, spacing: 8) {
        HStack(spacing: 4) {
            Text(cursorLane.label.uppercased())
                .font(.caption.weight(.heavy))
                .foregroundStyle(Palette.lane(cursorLane))
            Text("· " + String(localized: "Cell \(cursor.step + 1)", bundle: .strings)
                 + " · " + String(localized: "tap any cell to change row", bundle: .strings))
                .font(.caption)
                .foregroundStyle(Palette.syahiSoft)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        HStack(spacing: 10) {
            ForEach(EditorPads.forLane(cursorLane), id: \.label) { pad in
                padButton(pad)
            }
            Spacer()
            Button {
                dayan = Array(repeating: nil, count: steps)
                bayan = Array(repeating: nil, count: steps)
                kartal = Array(repeating: nil, count: steps)
            } label: {
                // Icon only: the pads need the row's width in longer languages.
                Label("Clear", systemImage: "trash")
                    .labelStyle(.iconOnly)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.danger)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 8)
            }
        }
        }
    }

    private func padButton(_ pad: EditorPad) -> some View {
        Button { applyPad(pad) } label: {
            VStack(spacing: 4) {
                Text(pad.isRest ? String(localized: "Rest", bundle: .strings) : pad.label)
                    .font(.subheadline.weight(.bold))
                    .lineLimit(1).minimumScaleFactor(0.7)
                Image(systemName: pad.stroke == .open ? "circle.fill" : pad.stroke == .closed ? "xmark" : "circle.dotted")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(pad.isRest ? Palette.faint : Palette.lane(pad.lane))
            }
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(pad.isRest ? Palette.headSunken : Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.rule, lineWidth: 1))
            .foregroundStyle(Palette.syahi)
        }
        .buttonStyle(.plain)
    }

    private var saveRow: some View {
        HStack(spacing: 12) {
            Button {
                previewing ? stopPreview() : startPreview()
            } label: {
                Label(previewing ? String(localized: "Stop", bundle: .strings) : String(localized: "Preview", bundle: .strings),
                      systemImage: previewing ? "stop.fill" : "play.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.onClay)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(previewing ? Palette.danger : Palette.strap)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            // Your own beat can also be saved as a NEW one, leaving the original as it
            // was — how a variation is made. (Built-ins always fork.)
            if case .edit = mode {
                Button {
                    save(asNew: true)
                } label: {
                    Text(String(localized: "Save as new beat", bundle: .strings))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Palette.syahi)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.rule, lineWidth: 1))
                }
                .disabled(saving || name.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            Button {
                save()
            } label: {
                // A fork never changes the built-in: say so, or Save looks inert.
                Label(isFork ? String(localized: "Save as my version", bundle: .strings) : String(localized: "Save", bundle: .strings),
                      systemImage: "checkmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.onClay)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Palette.clay)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .disabled(saving || name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    // MARK: preview + save

    private func currentBeat() -> Beat {
        var patterns: [LaneId: [Stroke?]] = [
            .dayan: Array(dayan.prefix(steps)),
            .bayan: Array(bayan.prefix(steps)),
        ]
        if kartal.prefix(steps).contains(where: { $0 != nil }) {
            patterns[.kartal] = Array(kartal.prefix(steps))
        }
        // Editing in place keeps the id (playlists point at it) and the source; a fork and
        // a new beat have none, so the library mints one.
        let keepsIdentity: Bool
        switch mode {
        case .edit, .everyone: keepsIdentity = true
        case .new, .fork: keepsIdentity = false
        }
        let seed = mode.seed
        var beat = Beat(
            id: keepsIdentity ? seed?.id : nil,
            name: name.trimmingCharacters(in: .whitespaces) == "" ? String(localized: "New beat", bundle: .strings) : Names.clamp(name),
            note: keepsIdentity ? (seed?.note ?? "") : "",
            bpm: bpm,
            steps: steps,
            beatsPerBar: shape.beatsPerBar,
            cellsPerGroup: shape.groups.allSatisfy { $0 == shape.groups.first } ? (shape.groups.first ?? shape.cpq) : shape.cpq,
            groups: shape.groups,
            description: keepsIdentity ? seed?.description : nil,
            lanePatterns: patterns,
            group: keepsIdentity ? seed?.group : nil
        )
        if case .edit(let original) = mode { beat.sourceItemId = original.sourceItemId }
        beat.tags = tags
        // A copy of a community beat (or of a copy) remembers where it came from: credit,
        // and what keeps it from being shared until the rhythm actually differs.
        if case .fork(let original) = mode { beat.forkedFrom = original.sourceItemId ?? original.forkedFrom }
        return beat
    }

    /// "Tags: Keherwa · Kirtan  Edit", and for a copy of a community beat, what saving does.
    private var tagsLine: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(String(localized: "Tags", bundle: .strings) + ": " + (TagVocabulary.line(tags) ?? String(localized: "none", bundle: .strings)))
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
                    .lineLimit(2)
                Spacer()
                Button(String(localized: "Edit", bundle: .strings)) { pickingTags = true }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.clay)
            }
            if case .fork(let original) = mode, original.isCommunity {
                Text(String(localized: "The community beat stays as it is. Saving adds your version to Your beats; you can share it once the rhythm differs.", bundle: .strings))
                    .font(.caption)
                    .foregroundStyle(Palette.syahiSoft)
            }
        }
        .sheet(isPresented: $pickingTags) {
            TagPickerSheet(
                title: String(localized: "Tags", bundle: .strings),
                hint: String(localized: "Published with your beat, so others can find it.", bundle: .strings),
                initial: tags,
            ) { tags = $0 }
        }
    }

    private func startPreview() {
        transport.startPreview(currentBeat(), previewBpm: bpm)
        previewing = true
    }

    private func stopPreview() {
        transport.stopPreview()
        previewing = false
    }

    private var isFork: Bool { if case .fork = mode { return true } else { return false } }

    private func save(asNew: Bool = false) {
        var beat = currentBeat()
        if asNew {
            // No id: the library mints one, and the original stays as it was.
            beat.id = nil
            beat.sourceItemId = nil
            // A variation left with the original's name would be two identical rows.
            if beat.name == mode.seed?.name {
                beat.name = Names.clamp(String(format: String(localized: "%@ (copy)", bundle: .strings), beat.name))
            }
        }
        saving = true
        Task {
            defer { saving = false }
            if case .everyone = mode {
                // The served set, for every install. Refusals (RLS, a row the read path
                // would reject, a beat retired meanwhile) come back as a sentence.
                do {
                    try await container.shippedBeats?.updateShippedBeat(beat)
                } catch {
                    saveError = storageErrorMessage(error)
                    return
                }
                if previewing { transport.stopPreview() }
                container.transport.loadBeat(beat)
                onDone()
                return
            }
            let saved = await container.library.saveBeat(beat)
            if previewing { transport.stopPreview() }
            guard let saved else {
                saveError = container.library.state.error ?? StorageError.fallbackMessage
                return
            }
            container.transport.loadBeat(saved)
            onDone()
        }
    }

    /// Change the shape by length or meter: cells are kept from the start and padded or
    /// cut at the end (only a resolution change re-times them).
    private func setShape(_ next: EditorShape) {
        shape = next
        lastResample = nil
        let n = steps
        setCursor(lane: cursor.lane, step: cursor.step)
        dayan = resized(dayan, to: n)
        bayan = resized(bayan, to: n)
        kartal = resized(kartal, to: n)
    }

    private func resized(_ array: [Stroke?], to n: Int) -> [Stroke?] {
        let head = Array(array.prefix(n))
        return head + Array(repeating: nil, count: max(0, n - head.count))
    }
}

/// A single paintable cell of the grid.
private struct EditorCell: View {
    let stroke: Stroke?
    let lane: LaneId
    let isCursor: Bool
    /// The cursor's column on another row: where the other hands fall at that moment.
    var sameMoment = false
    var size: CGFloat = 30
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            RoundedRectangle(cornerRadius: size * 0.2)
                .fill(stroke == nil ? (sameMoment ? Palette.headSunken : Palette.headWorn) : Palette.lane(lane).opacity(0.18))
                .frame(width: size, height: size)
                .overlay {
                    if let stroke {
                        mark(stroke)
                    }
                }
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.2)
                        .strokeBorder(isCursor ? Palette.clay : Palette.rule,
                                      lineWidth: isCursor ? 2.5 : 1)
                )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func mark(_ stroke: Stroke) -> some View {
        let d = size / 2
        switch stroke {
        case .open:
            Circle().fill(Palette.lane(lane)).frame(width: d, height: d)
        case .closed:
            CrossMark().stroke(Palette.strap, style: StrokeStyle(lineWidth: max(2, d * 0.18), lineCap: .round))
                .frame(width: d, height: d)
        }
    }
}

/// A meter the picker offers. Each is seeded at its plainest resolution; the
/// resolution lever refines it afterwards.
private struct EditorMeter: Identifiable, Hashable {
    let label: String
    let shape: EditorShape
    var id: String { label }

    static func == (a: EditorMeter, b: EditorMeter) -> Bool { a.label == b.label }
    func hash(into h: inout Hasher) { h.combine(label) }

    static let all: [EditorMeter] = [
        .init(label: "4/4", shape: EditorShape(groups: [2, 2, 2, 2], cpq: 2, unit: [2])),
        .init(label: "3/4", shape: EditorShape(groups: [2, 2, 2], cpq: 2, unit: [2])),
        .init(label: "6/8 · 3+3", shape: EditorShape(groups: [3, 3], cpq: 2, unit: [3, 3])),
        .init(label: "7/8 · 2+2+3", shape: EditorShape(groups: [2, 2, 3], cpq: 2, unit: [2, 2, 3])),
    ]

    /// The preset this shape is, at any resolution — or nil for a shape no preset
    /// produces (a longer bar, matan's 16 groups of 3…).
    static func matching(_ shape: EditorShape) -> EditorMeter? {
        all.first { m in
            m.shape.resolutionOptions.contains { m.shape.atResolution($0)?.groups == shape.groups && m.shape.atResolution($0)?.cpq == shape.cpq }
                || (!m.shape.isMultiplier && !shape.isMultiplier && shape.groups.count == m.shape.groups.count)
        }
    }
}
