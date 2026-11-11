import SwiftUI

// TagPicker.swift
//
// The tag UI shared by the library, the editor and Community: one pill, a picker sheet
// over the curated vocabulary (TagVocabulary), and a filter row. Mirrors Android's
// ui/components/TagPicker.kt.

/// One selectable tag pill.
struct TagPill: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.footnote.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? Palette.onClay : Palette.syahi)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Palette.clay : Palette.headWorn, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? Palette.clay : Palette.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Lays children out left to right, wrapping onto new lines.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width { y += line + spacing; x = 0; line = 0 }
            x += size.width + spacing
            line = max(line, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, width), height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, line: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX { y += line + spacing; x = bounds.minX; line = 0 }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}

/// Pick tags from the vocabulary. Unknown ids already on the beat (from a newer app) are
/// kept as they are: this build can't show them, but must not drop them.
struct TagPickerSheet: View {
    let title: String
    let hint: String
    let initial: [String]
    let onDone: ([String]) -> Void

    @State private var chosen: Set<String> = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(hint).font(.footnote).foregroundStyle(Palette.syahiSoft)
                    ForEach(TagVocabulary.groups) { group in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.label)
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Palette.syahiSoft)
                            FlowLayout {
                                ForEach(group.tags) { tag in
                                    TagPill(label: tag.label, selected: chosen.contains(tag.id)) {
                                        if chosen.contains(tag.id) { chosen.remove(tag.id) } else { chosen.insert(tag.id) }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(20)
            }
            .background(Palette.head)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel", bundle: .strings)) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done", bundle: .strings)) {
                        let knownIds = Set(TagVocabulary.allTags.map(\.id))
                        // Vocabulary order for known tags, then unknown ones as they were.
                        let ordered = TagVocabulary.allTags.map(\.id).filter { chosen.contains($0) }
                            + initial.filter { !knownIds.contains($0) }
                        onDone(ordered)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { chosen = Set(initial) }
    }
}

/// "All" plus one pill per tag in `tags` (vocabulary order). Draws nothing when none of
/// them are known, so a library without tags shows no empty filter row.
struct TagFilterRow: View {
    let tags: [String]
    @Binding var selected: String?

    var body: some View {
        let known = TagVocabulary.known(tags)
        if !known.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    TagPill(label: String(localized: "All", bundle: .strings), selected: selected == nil) { selected = nil }
                    ForEach(known) { tag in
                        TagPill(label: tag.label, selected: selected == tag.id) {
                            selected = selected == tag.id ? nil : tag.id
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}
