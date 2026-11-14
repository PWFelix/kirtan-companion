import SwiftUI

// A rhythm's notes: the author's prose about it — what it is, where it comes from,
// when to play it, how, with which fingers. Stored as the beat's `description` (the
// field the built-ins always carried), so it saves, syncs and publishes with the beat;
// share LINKS leave it out to stay short. Written in the editor, read on Home and Learn.
// Mirrors Android ui/components/RhythmNotes.kt and web src/ui/RhythmNotes.jsx.

/// The small link under a beat's name; nothing at all when the beat has no notes.
struct NotesLink: View {
    let notes: String?
    let onOpen: () -> Void

    var body: some View {
        if let notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Button(String(localized: "About this rhythm", bundle: .strings), action: onOpen)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Palette.clay)
                .buttonStyle(.plain)
        }
    }
}

/// Reading the notes.
struct NotesSheet: View {
    let title: String
    let notes: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(notes)
                    .font(.body)
                    .foregroundStyle(Palette.syahi)
                    .lineSpacing(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }
            .background(Palette.head)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done", bundle: .strings)) { dismiss() }
                }
            }
        }
    }
}

/// Writing the notes. Done hands back the trimmed text; Cancel keeps the old.
struct NotesEditorSheet: View {
    static let maxNotes = 4000
    let initial: String
    let onDone: (String) -> Void
    @State private var text = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "About the rhythm: its history, when to use it, how to play it, which fingers to use. Saved and published with the beat; share links leave it out.", bundle: .strings))
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
                TextEditor(text: $text)
                    .scrollContentBackground(.hidden)
                    .foregroundStyle(Palette.syahi)
                    .padding(8)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.syahiSoft.opacity(0.3)))
                    .onChange(of: text) { _, v in if v.count > Self.maxNotes { text = String(v.prefix(Self.maxNotes)) } }
            }
            .padding(20)
            .background(Palette.head)
            .navigationTitle(String(localized: "Notes", bundle: .strings))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel", bundle: .strings)) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "Done", bundle: .strings)) {
                        onDone(text.trimmingCharacters(in: .whitespacesAndNewlines))
                        dismiss()
                    }
                }
            }
            .onAppear { text = initial }
        }
    }
}
