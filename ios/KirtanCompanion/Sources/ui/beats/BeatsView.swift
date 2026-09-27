import SwiftUI

/// The beat library: the user's beats, and — when a cloud is compiled in — the
/// community library.
///
/// Ported from `src/views/BeatsView.jsx`, which is the app's most sprawling screen — a hub
/// of cards that drill into per-category pages with a live search and drag-reorder. That
/// desktop layout doesn't fit a phone, so this draws the same content as a single scroll:
/// a search box, then a section per collection (built-in, your beats, each progression a
/// beat belongs to). A beat may appear in several sections — that overlap is intentional
/// and matches the web. The one deliberate omission is drag-reorder: the repository only
/// reorders real user categories (`reorderCategory` guards on a stored category), not the
/// virtual "Your beats" list, so it can't be offered uniformly; a tap to play is the
/// primary action here anyway.
struct BeatsView: View {
    let container: AppContainer

    private var library: LibraryRepository { container.library }

    private enum Segment: String, CaseIterable, Identifiable {
        case library = "My beats"
        case community = "Community"
        var id: String { rawValue }
    }

    @State private var segment: Segment = .library
    @State private var query = ""
    /// The beat id whose delete is awaiting confirmation (drives a confirmation dialog).
    @State private var pendingDelete: String?

    var body: some View {
        VStack(spacing: 12) {
            Text("Beats")
                .font(.system(size: 26, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)

            if container.cloudConfigured {
                Picker("Library", selection: $segment) {
                    ForEach(Segment.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .tint(Palette.clay)
                .padding(.horizontal, 20)
            }

            if container.cloudConfigured && segment == .community {
                BrowseView(container: container)
                    .padding(.horizontal, 4)
            } else {
                libraryScreen
            }
        }
        .confirmationDialog(
            "Delete \(pendingDeleteName)?",
            isPresented: Binding(get: { pendingDelete != nil },
                                 set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) { confirmDelete() }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        }
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }

    private var pendingDeleteName: String {
        library.allBeats.first(where: { $0.id == pendingDelete })?.name ?? "this beat"
    }

    // ── Search ──────────────────────────────────────────────────────────────

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Palette.faint)
            TextField("Search your beats", text: $query)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .foregroundStyle(Palette.syahi)
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Palette.faint)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.rule, lineWidth: 1))
        .padding(.horizontal, 16)
    }

    // ── The library, sectioned (or a search over it) ───────────────────────

    @ViewBuilder
    private var libraryScreen: some View {
        if library.state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = library.state.error {
            Text(error).font(.footnote).foregroundStyle(Palette.danger)
                .frame(maxWidth: .infinity).padding(8)
        } else {
            searchField
            if trimmedQuery.isEmpty {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        section("Built in", library.shippedBeats.effective, deletable: false)
                        section("Your beats", library.state.customBeats, deletable: true)
                        ForEach(library.state.categories, id: \.id) { category in
                            section(category.name, library.categoryBeats(category.id), deletable: false)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            } else {
                let results = library.allBeats.filter {
                    $0.name.localizedCaseInsensitiveContains(trimmedQuery)
                }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if results.isEmpty {
                            Text("No beats match “\(trimmedQuery)”.")
                                .font(.footnote).foregroundStyle(Palette.syahiSoft)
                                .frame(maxWidth: .infinity).padding(.vertical, 24)
                        }
                        ForEach(results, id: \.id) { beatRow($0, deletable: library.isCustomBeat($0.id)) }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    // A labelled run of beat rows. An empty collection is skipped entirely (no "Your beats"
    // header hovering over nothing).
    @ViewBuilder
    private func section(_ title: String, _ beats: [Beat], deletable: Bool) -> some View {
        if !beats.isEmpty {
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Palette.syahiSoft)
                .padding(.top, 12)
                .padding(.bottom, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            ForEach(beats, id: \.id) { beatRow($0, deletable: deletable) }
        }
    }

    private func beatRow(_ beat: Beat, deletable: Bool) -> some View {
        HStack {
            Button {
                container.transport.loadBeat(beat)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(beat.name)
                            .font(.body.weight(.medium))
                            .foregroundStyle(Palette.syahi)
                        if !beat.note.isEmpty {
                            Text(beat.note)
                                .font(.caption)
                                .foregroundStyle(Palette.syahiSoft)
                        }
                    }
                    Spacer()
                    Text("\(beat.bpm) bpm")
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(Palette.faint)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if deletable {
                Button {
                    pendingDelete = beat.id
                } label: {
                    Image(systemName: "xmark")
                        .font(.footnote)
                        .foregroundStyle(Palette.faint)
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        .padding(.vertical, 2)
    }

    private func confirmDelete() {
        guard let id = pendingDelete else { return }
        pendingDelete = nil
        Task { await container.library.deleteBeat(id) }
    }
}
