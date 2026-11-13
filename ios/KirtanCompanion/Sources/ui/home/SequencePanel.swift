import SwiftUI

/// Home's running order: beats in order, each for a number of bars, as a row of
/// chips under the strip. Tapping a chip opens its sheet (bars, move, change
/// beat, remove); Add opens the beat picker aimed at a new entry. Loop, Open and
/// Save sit in the header.
///
/// Owns only which of its sheets is open. The sequence itself is the
/// transport's, because it feeds the engine — the same split as Android's
/// SequencePanel.kt and the web's SequencePanel.jsx.
struct SequencePanel: View {
    let container: AppContainer

    private var transport: TransportViewModel { container.transport }
    private var seq: SequenceState { transport.sequence }

    /// Which entry's sheet is open.
    @State private var entrySheet: EntryRef?
    @State private var saveOpen = false
    @State private var openOpen = false
    /// What the beat picker is choosing FOR: a new entry, or entry n's beat.
    @State private var pick: PickTarget?

    struct EntryRef: Identifiable { let id: Int }
    enum PickTarget: Identifiable {
        case add, replace(Int)
        var id: Int { if case .replace(let i) = self { return i }; return -1 }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            chips
        }
        .sheet(item: $entrySheet) { ref in
            EntrySheet(container: container, index: ref.id,
                       onChangeBeat: { entrySheet = nil; pick = .replace(ref.id) },
                       onMoved: { entrySheet = EntryRef(id: $0) },
                       onDone: { entrySheet = nil })
                .presentationDetents([.medium])
        }
        .sheet(item: $pick) { target in
            BeatPickerSheet(
                container: container,
                selectedId: { if case .replace(let i) = target { return seq.working.entries[safe: i]?.beatId }; return .some(nil) }(),
                onPick: { beat in
                    guard let id = beat.id else { return }
                    switch target {
                    case .add: transport.editSequence { Sequences.add($0, id) }
                    case .replace(let i): transport.editSequence { Sequences.setBeat($0, i, id) }
                    }
                },
                onDone: { pick = nil },
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $saveOpen) {
            SaveSequenceSheet(container: container) { saveOpen = false }
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $openOpen) {
            OpenSequenceSheet(container: container) { openOpen = false }
                .presentationDetents([.medium, .large])
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "Sequence", bundle: .strings).uppercased())
                    .font(.caption2.weight(.bold))
                    .tracking(1)
                    .foregroundStyle(Palette.syahiSoft)
                let name = seq.working.name.isEmpty
                    ? (seq.working.entries.isEmpty ? "" : String(localized: "Unsaved sequence", bundle: .strings))
                    : seq.working.name
                Text(name)
                    .font(.system(size: 17, weight: .semibold, design: .serif))
                    .foregroundStyle(Palette.syahi)
                    .lineLimit(1)
            }
            Spacer()
            let loop = seq.working.loop
            iconButton("repeat", filled: loop,
                       label: loop ? String(localized: "Loop is on. Tap to stop at the end", bundle: .strings)
                                   : String(localized: "Loop the sequence", bundle: .strings)) {
                transport.setSequenceLoop(!loop)
            }
            iconButton("folder", label: String(localized: "Open a sequence", bundle: .strings)) { openOpen = true }
            iconButton("square.and.arrow.down", label: String(localized: "Save sequence", bundle: .strings)) { saveOpen = true }
                .disabled(seq.working.entries.isEmpty)
                .opacity(seq.working.entries.isEmpty ? 0.35 : 1)
        }
    }

    private func iconButton(_ symbol: String, filled: Bool = false, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body)
                .frame(width: 40, height: 40)
                .foregroundStyle(filled ? Palette.onClay : Palette.syahiSoft)
                .background(RoundedRectangle(cornerRadius: 12).fill(filled ? Palette.clay : Color.clear))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(filled ? Palette.clay : Palette.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if seq.working.entries.isEmpty {
                    Text(String(localized: "Add beats to play them in order, each for a number of bars.", bundle: .strings))
                        .font(.footnote)
                        .foregroundStyle(Palette.syahiSoft)
                        .frame(maxWidth: 220, alignment: .leading)
                }
                ForEach(Array(seq.working.entries.enumerated()), id: \.offset) { i, e in
                    chip(i, e)
                }
                Button { pick = .add } label: {
                    Label(String(localized: "Add beat", bundle: .strings), systemImage: "plus")
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(Palette.clay)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.rule, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func chip(_ i: Int, _ e: SequenceEntry) -> some View {
        let beat = transport.sequenceBeat(e.beatId)
        let live = seq.playingEntry == i
        return Button { entrySheet = EntryRef(id: i) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(beat?.name ?? String(localized: "Beat not found", bundle: .strings))
                    .font(.footnote.weight(.bold))
                if live, let bar = seq.playingBar {
                    Text(String(localized: "Bar \(bar) of \(e.bars)", bundle: .strings)).font(.caption2)
                } else {
                    Text(String(localized: "\(e.bars) bars", bundle: .strings)).font(.caption2)
                }
            }
            .lineLimit(1)
            .foregroundStyle(live ? Palette.onClay : Palette.syahi)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 14).fill(live ? Palette.clay : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(live ? Palette.clay : Palette.rule, lineWidth: 1))
            .opacity(beat == nil ? 0.5 : 1)
        }
        .buttonStyle(.plain)
    }
}

/// One entry: its bars, its place, its beat.
private struct EntrySheet: View {
    let container: AppContainer
    let index: Int
    let onChangeBeat: () -> Void
    let onMoved: (Int) -> Void
    let onDone: () -> Void

    private var transport: TransportViewModel { container.transport }

    var body: some View {
        let entries = transport.sequence.working.entries
        if let e = entries[safe: index] {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text(transport.sequenceBeat(e.beatId)?.name ?? String(localized: "Beat not found", bundle: .strings))
                        .font(.system(size: 26, weight: .semibold, design: .serif))
                        .foregroundStyle(Palette.syahi)
                    Spacer()
                }
                Text(String(localized: "Bars", bundle: .strings).uppercased())
                    .font(.caption2.weight(.bold)).tracking(1).foregroundStyle(Palette.syahiSoft)
                HStack(spacing: 24) {
                    stepButton("chevron.left", String(localized: "Fewer bars", bundle: .strings), enabled: e.bars > 1) {
                        transport.editSequence { Sequences.setBars($0, index, e.bars - 1) }
                    }
                    Text(String(localized: "\(e.bars) bars", bundle: .strings))
                        .font(.title3.weight(.semibold)).monospacedDigit()
                        .frame(minWidth: 110)
                    stepButton("chevron.right", String(localized: "More bars", bundle: .strings), enabled: e.bars < Sequences.maxBars) {
                        transport.editSequence { Sequences.setBars($0, index, e.bars + 1) }
                    }
                }
                .frame(maxWidth: .infinity)
                HStack(spacing: 12) {
                    Button(String(localized: "Move earlier", bundle: .strings)) {
                        transport.editSequence { Sequences.move($0, index, index - 1) }
                        onMoved(index - 1)
                    }
                    .disabled(index == 0)
                    .frame(maxWidth: .infinity)
                    Button(String(localized: "Move later", bundle: .strings)) {
                        transport.editSequence { Sequences.move($0, index, index + 1) }
                        onMoved(index + 1)
                    }
                    .disabled(index == entries.count - 1)
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button(String(localized: "Change beat", bundle: .strings), action: onChangeBeat)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button(role: .destructive) {
                    transport.editSequence { Sequences.remove($0, index) }
                    onDone()
                } label: {
                    Label(String(localized: "Remove from sequence", bundle: .strings), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            }
            .tint(Palette.clay)
            .padding(24)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Palette.head.ignoresSafeArea())
        }
    }

    private func stepButton(_ symbol: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .frame(width: 48, height: 48)
                .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 14))
                .foregroundStyle(Palette.syahi)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(label)
    }
}

/// Naming is the whole job of this sheet, so the field takes focus on open.
/// Opened from a saved sequence, it offers both overwrite and "as new".
private struct SaveSequenceSheet: View {
    let container: AppContainer
    let onDone: () -> Void

    @State private var name = ""
    @FocusState private var focused: Bool

    var body: some View {
        let transport = container.transport
        let canOverwrite = transport.sequence.saved.contains { $0.id == transport.sequence.working.savedId }
        let ok = !name.trimmingCharacters(in: .whitespaces).isEmpty
        VStack(alignment: .leading, spacing: 16) {
            Text(String(localized: "Save sequence", bundle: .strings))
                .font(.system(size: 26, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
            TextField(String(localized: "Name", bundle: .strings), text: $name)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .submitLabel(.done)
                .onSubmit { if ok { save(asNew: false) } }
            Button { save(asNew: false) } label: {
                Text(canOverwrite ? String(localized: "Save changes", bundle: .strings) : String(localized: "Save sequence", bundle: .strings))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!ok)
            if canOverwrite {
                Button { save(asNew: true) } label: {
                    Text(String(localized: "Save as new", bundle: .strings)).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(!ok)
            }
        }
        .tint(Palette.clay)
        .padding(24)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.head.ignoresSafeArea())
        .onAppear {
            name = transport.sequence.working.name
            focused = true
        }
    }

    private func save(asNew: Bool) {
        container.transport.saveSequence(name: name, asNew: asNew)
        onDone()
    }
}

/// The saved sequences: open one, delete one, or start a new one.
private struct OpenSequenceSheet: View {
    let container: AppContainer
    let onDone: () -> Void

    var body: some View {
        let transport = container.transport
        let seq = transport.sequence
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "Sequences", bundle: .strings))
                .font(.system(size: 26, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
            if seq.working.isDirty(seq.saved) {
                Text(String(localized: "Your current sequence has unsaved changes; opening another replaces them.", bundle: .strings))
                    .font(.footnote).foregroundStyle(Palette.syahiSoft)
            }
            ScrollView {
                VStack(spacing: 8) {
                    if seq.saved.isEmpty {
                        Text(String(localized: "No saved sequences yet.", bundle: .strings))
                            .font(.callout).foregroundStyle(Palette.syahiSoft)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(seq.saved) { s in
                        let selected = s.id == seq.working.savedId
                        HStack {
                            Button {
                                transport.openSequence(s.id)
                                onDone()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.name.isEmpty ? String(localized: "Unsaved sequence", bundle: .strings) : s.name)
                                        .font(.body.weight(.semibold)).foregroundStyle(Palette.syahi)
                                    Text("\(String(localized: "\(s.entries.count) beats", bundle: .strings)) · \(String(localized: "\(s.totalBars) bars", bundle: .strings))")
                                        .font(.footnote).foregroundStyle(Palette.syahiSoft)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                            Button(role: .destructive) { transport.deleteSequence(s.id) } label: {
                                Image(systemName: "trash").frame(width: 40, height: 40)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(Palette.syahiSoft)
                            .accessibilityLabel(String(localized: "Delete", bundle: .strings))
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.headWorn))
                        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? Palette.clay : Palette.rule, lineWidth: 1))
                    }
                }
            }
            Button {
                transport.newSequence()
                onDone()
            } label: {
                Label(String(localized: "New sequence", bundle: .strings), systemImage: "plus").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(Palette.clay)
        }
        .padding(24)
        .background(Palette.head.ignoresSafeArea())
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
