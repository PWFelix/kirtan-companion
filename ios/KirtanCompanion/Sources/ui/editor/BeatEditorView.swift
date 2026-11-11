import SwiftUI

/// The custom-beat editor: a paintable grid of the two mridanga ends (and cymbals)
/// that saves a new beat into the library.
///
/// Ported in spirit from `src/BeatEditor.jsx`. That editor is the most stateful view
/// in the app; this keeps the same *contract* — a beat in, `onSave(beat)` out, takes
/// the transport over for preview while open — but with a deliberately simpler paint
/// model (pick a pad, paint cells) rather than the web's zoom/selection apparatus.
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
    @State private var beatsPerBar = 4.0
    @State private var cellsPerGroup = 2
    @State private var dayan: [Stroke?] = Array(repeating: nil, count: 8)
    @State private var bayan: [Stroke?] = Array(repeating: nil, count: 8)
    @State private var kartal: [Stroke?] = Array(repeating: nil, count: 8)
    @State private var cursor: (lane: Int, step: Int) = (0, 0)
    @State private var activePad: Stroke? = .open
    @State private var previewing = false
    /// Grid cell size, rescaled by a pinch. Bounded so it stays tappable on a phone.
    @State private var cellSize: CGFloat = 32
    /// The cell size when the current pinch began, so `magnification` scales relative to it.
    @State private var magnifyBase: CGFloat?
    /// A seeded beat's own meter, kept verbatim until the user picks a preset. The presets
    /// are uniform bars; a beat like matan (16 groups of 3) would be silently re-metered by
    /// the nearest preset, so an edit that never touched the meter must not change it.
    @State private var keptMeter: KeptMeter?
    @State private var seeded = false
    @State private var saving = false
    @State private var saveError: String?
    /// The author's tags, published with the beat.
    @State private var tags: [String] = []
    @State private var pickingTags = false

    private struct KeptMeter: Equatable {
        let groups: [Int]
        let beatsPerBar: Double
        let steps: Int
        let cellsPerGroup: Int
    }

    /// Whole cells in the bar: the meter (beats) × the subdivision (cells per beat).
    private var steps: Int { keptMeter?.steps ?? max(1, Int(beatsPerBar.rounded()) * cellsPerGroup) }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().background(Palette.rule)
            ScrollView {
                VStack(spacing: 18) {
                    nameField
                    tagsLine
                    meterRow
                    grid
                    pads
                    saveRow
                }
                .padding(18)
            }
            .background(Palette.head.ignoresSafeArea())
        }
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
        let n = beat.steps
        func lane(_ id: LaneId) -> [Stroke?] {
            let cells = beat.pattern(id) ?? []
            return Array(cells.prefix(n)) + Array(repeating: nil, count: max(0, n - cells.count))
        }
        dayan = lane(.dayan)
        bayan = lane(.bayan)
        kartal = lane(.kartal)
        let groups = groupsFor(beat)
        let preset = MeterPreset.allCases.first {
            $0.beatsPerBar == beat.beatsPerBar && $0.cellsPerGroup == beat.cellsPerGroup
                && Int($0.beatsPerBar.rounded()) * $0.cellsPerGroup == n
                && groups == Array(repeating: $0.cellsPerGroup, count: Int($0.beatsPerBar.rounded()))
        }
        if let preset {
            beatsPerBar = preset.beatsPerBar
            cellsPerGroup = preset.cellsPerGroup
        } else {
            beatsPerBar = beat.beatsPerBar
            cellsPerGroup = beat.cellsPerGroup
            keptMeter = KeptMeter(groups: groups, beatsPerBar: beat.beatsPerBar, steps: n, cellsPerGroup: beat.cellsPerGroup)
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
    }

    private var meterRow: some View {
        HStack(spacing: 12) {
            control(String(localized: "Meter", bundle: .strings)) {
                Picker(String(localized: "Meter", bundle: .strings), selection: meterBinding) {
                    if let keptMeter {
                        Text(String(localized: "As written (\(keptMeter.steps))", bundle: .strings)).tag(MeterPreset?.none)
                    }
                    ForEach(meters, id: \.label) { m in Text(m.label).tag(MeterPreset?.some(m)) }
                }
                .pickerStyle(.menu)
            }
            control(String(localized: "Tempo", bundle: .strings)) {
                Stepper("\(bpm)", value: $bpm, in: 40...240, step: 1)
                    .font(.footnote.monospacedDigit())
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

    private var meters: [MeterPreset] { MeterPreset.allCases }

    private var meterBinding: Binding<MeterPreset?> {
        Binding(
            get: { keptMeter != nil ? nil : MeterPreset.matching(beatsPerBar: beatsPerBar, cellsPerGroup: cellsPerGroup) },
            set: { choice in
                guard let preset = choice else { return }
                keptMeter = nil
                beatsPerBar = preset.beatsPerBar
                setCellsPerGroup(preset.cellsPerGroup)
            }
        )
    }

    // MARK: the paintable grid

    private var grid: some View {
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

                ForEach(LaneId.ordered.indices, id: \.self) { laneIndex in
                    let lane = LaneId.ordered[laneIndex]
                    HStack(spacing: 8) {
                        ForEach(0..<steps, id: \.self) { s in
                            EditorCell(
                                stroke: patternValue(laneIndex, s),
                                lane: lane,
                                isCursor: cursor.lane == laneIndex && cursor.step == s,
                                size: max(16, cellSize - 2),
                            ) {
                                applyPad(lane: laneIndex, step: s)
                            }
                        }
                    }
                    .frame(height: cellSize)
                    .background(Palette.headWorn.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(4)
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

    private func applyPad(lane: Int, step: Int) {
        setCursor(lane: lane, step: step)
        setPatternValue(lane: lane, step: step, value: activePad)
    }

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

    private var pads: some View {
        HStack(spacing: 10) {
            padButton(.open, String(localized: "Open", bundle: .strings), symbol: "circle.fill")
            padButton(.closed, String(localized: "Closed", bundle: .strings), symbol: "square.fill")
            padButton(nil, String(localized: "Rest", bundle: .strings), symbol: "circle.dotted")
            Spacer()
            Button {
                dayan = Array(repeating: nil, count: steps)
                bayan = Array(repeating: nil, count: steps)
                kartal = Array(repeating: nil, count: steps)
            } label: {
                Label("Clear", systemImage: "trash")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Palette.danger)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
        }
    }

    private func padButton(_ stroke: Stroke?, _ title: String, symbol: String) -> some View {
        let selected = activePad == stroke
        return Button {
            activePad = stroke
        } label: {
            HStack(spacing: 6) {
                Image(systemName: symbol)
                    .foregroundStyle(stroke.map { Palette.stroke($0, laneId: .dayan) } ?? Palette.faint)
                Text(title).font(.footnote.weight(selected ? .semibold : .regular))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(selected ? Palette.clay.opacity(0.2) : Palette.headWorn)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(selected ? Palette.clay : Palette.rule, lineWidth: selected ? 2 : 1)
            )
            .foregroundStyle(selected ? Palette.clayDeep : Palette.syahi)
        }
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

            Button {
                save()
            } label: {
                // A fork never changes the built-in: say so, or Save looks inert.
                Label(isFork ? "Save as my version" : "Save", systemImage: "checkmark")
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
            name: name.trimmingCharacters(in: .whitespaces) == "" ? String(localized: "New beat", bundle: .strings) : name,
            note: keepsIdentity ? (seed?.note ?? "") : "",
            bpm: bpm,
            steps: steps,
            beatsPerBar: keptMeter?.beatsPerBar ?? beatsPerBar,
            cellsPerGroup: keptMeter?.cellsPerGroup ?? cellsPerGroup,
            groups: keptMeter?.groups ?? Array(repeating: cellsPerGroup, count: max(1, Int(beatsPerBar.rounded()))),
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

    private func save() {
        let beat = currentBeat()
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

    private func setCellsPerGroup(_ value: Int) {
        cellsPerGroup = value
        let n = steps
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
    var size: CGFloat = 30
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            RoundedRectangle(cornerRadius: size * 0.2)
                .fill(stroke == nil ? Palette.headWorn : Palette.lane(lane).opacity(0.18))
                .frame(width: size, height: size)
                .overlay {
                    if let stroke {
                        mark(stroke)
                    }
                }
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.2)
                        .strokeBorder(isCursor ? Palette.syahi : Palette.rule,
                                      lineWidth: isCursor ? 2 : 1)
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
            RoundedRectangle(cornerRadius: size * 0.1).fill(Palette.strap).frame(width: d, height: d)
        }
    }
}

/// A named meter: the pulse count per bar and the subdivision within each pulse.
private struct MeterPreset: Identifiable, Hashable {
    let beatsPerBar: Double
    let cellsPerGroup: Int
    let label: String
    var id: String { "\(beatsPerBar)-\(cellsPerGroup)" }

    static let allCases: [MeterPreset] = [
        .init(beatsPerBar: 4, cellsPerGroup: 1, label: "4/4"),
        .init(beatsPerBar: 4, cellsPerGroup: 2, label: String(localized: "8 (eighths)", bundle: .strings)),
        .init(beatsPerBar: 4, cellsPerGroup: 3, label: String(localized: "12 (triplets)", bundle: .strings)),
        .init(beatsPerBar: 4, cellsPerGroup: 4, label: String(localized: "16 (sixteenths)", bundle: .strings)),
        .init(beatsPerBar: 3, cellsPerGroup: 2, label: "6 (3/4)"),
    ]

    static func matching(beatsPerBar: Double, cellsPerGroup: Int) -> MeterPreset {
        allCases.first { $0.beatsPerBar == beatsPerBar && $0.cellsPerGroup == cellsPerGroup }
            ?? allCases[1]
    }
}
