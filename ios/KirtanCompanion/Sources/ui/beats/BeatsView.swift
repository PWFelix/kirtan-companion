import SwiftUI

/// The beat library: the user's beats, and — when a cloud is compiled in — the
/// community library.
///
/// Ported from `src/views/BeatsView.jsx`, which is the app's most sprawling screen — a hub
/// of cards that drill into per-category pages with a live search and drag-reorder. That
/// desktop layout doesn't fit a phone, so this draws the same content as a single scroll:
/// a search box, then a section per collection (built-in, your beats, each progression a
/// beat belongs to). A beat may appear in several sections — that overlap is intentional
/// and matches the web.
///
/// Every per-beat action lives in one "…" menu on the row (edit, share, publish, add to a
/// list, delete), and every per-list action in one on the list's heading (rename, share,
/// publish, delete), so a tap on the row itself always means the same thing: play it.
/// Drag-reorder is still omitted: a tap to play is the primary action on a phone.
struct BeatsView: View {
    let container: AppContainer
    /// Open the editor in a mode (owned by NavigationShell, which draws it over every tab).
    var onEdit: (BeatEditorView.EditorMode) -> Void = { _ in }

    private var library: LibraryRepository { container.library }
    private var auth: AuthViewModel { container.auth }

    private enum Segment: String, CaseIterable, Identifiable {
        case library = "My beats"
        case community = "Community"
        var id: String { rawValue }
    }

    /// Which modal is up. One enum, so two sheets can never fight over the screen.
    private enum Sheet: Identifiable {
        case share(ShareTarget)
        case importCode
        case confirm(ShareCodec.SharePayload)
        var id: String {
            switch self {
            case .share(let t): return "share:\(t.id)"
            case .importCode: return "import"
            case .confirm: return "confirm"
            }
        }
    }

    /// A text prompt: naming a new list, or renaming one.
    private enum NamePrompt: Equatable {
        case newList
        case rename(id: String, current: String)
    }

    /// A destructive action awaiting its confirmation.
    private enum Pending: Equatable {
        case deleteBeat(id: String, name: String)
        case deleteList(id: String, name: String)
        case removeForEveryone(Beat)

        var title: String {
            switch self {
            case .deleteBeat(_, let name): return String(localized: "Delete \(name)?", bundle: .strings)
            case .deleteList(_, let name): return String(localized: "Delete the list “\(name)”?", bundle: .strings)
            case .removeForEveryone(let beat): return String(localized: "Remove \(beat.name) for everyone?", bundle: .strings)
            }
        }

        var message: String {
            switch self {
            case .deleteBeat: return String(localized: "It is also taken out of every list that uses it.", bundle: .strings)
            case .deleteList: return String(localized: "The beats in it stay in your library.", bundle: .strings)
            case .removeForEveryone:
                return String(localized: "It disappears from the Beats screen for everyone on their next launch. Progressions that use it keep working — its id does not change, only its visibility.", bundle: .strings)
            }
        }
    }

    @State private var segment: Segment = BeatsView.defaultSegment()
    @State private var query = ""
    @State private var sheet: Sheet?
    @State private var pending: Pending?
    @State private var namePrompt: NamePrompt?
    @State private var nameDraft = ""

    /// DEBUG: `-kirtanCommunity` opens on the Community segment, for screenshots.
    private static func defaultSegment() -> Segment {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-kirtanCommunity") { return .community }
        #endif
        return .library
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text(String(localized: "Beats", bundle: .strings))
                    .font(.system(size: 26, weight: .semibold, design: .serif))
                    .foregroundStyle(Palette.syahi)
                Spacer()
                if segment == .library {
                    Menu {
                        Button { sheet = .importCode } label: { Label(String(localized: "Import a code or link", bundle: .strings), systemImage: "square.and.arrow.down") }
                        Button { nameDraft = ""; namePrompt = .newList } label: { Label(String(localized: "New list", bundle: .strings), systemImage: "text.badge.plus") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.title3)
                            .foregroundStyle(Palette.clay)
                            .frame(width: 40, height: 40)
                    }
                    .accessibilityLabel(String(localized: "Library actions", bundle: .strings))
                }
            }
            .padding(.horizontal, 24)

            if container.cloudConfigured {
                Picker(String(localized: "Library", bundle: .strings), selection: $segment) {
                    ForEach(Segment.allCases) { seg in
                        Text(seg == .library ? String(localized: "My beats", bundle: .strings) : String(localized: "Community", bundle: .strings)).tag(seg)
                    }
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
        .sheet(item: $sheet) { which in
            switch which {
            case .share(let target):
                ShareSheetView(target: target) { sheet = nil }
            case .importCode:
                ImportSheetView(onDismiss: { sheet = nil }) { payload in sheet = .confirm(payload) }
            case .confirm(let payload):
                ImportConfirmView(payload: payload, onDismiss: { sheet = nil }) {
                    sheet = nil
                    Task { await container.acceptImport(payload) }
                }
            }
        }
        .confirmationDialog(
            pending?.title ?? "",
            isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
            titleVisibility: .visible,
            presenting: pending,
        ) { which in
            Button(confirmLabel(which), role: .destructive) { confirm(which) }
            Button(String(localized: "Cancel", bundle: .strings), role: .cancel) { pending = nil }
        } message: { which in
            Text(which.message)
        }
        .alert(namePromptTitle, isPresented: Binding(get: { namePrompt != nil }, set: { if !$0 { namePrompt = nil } })) {
            TextField(String(localized: "List name", bundle: .strings), text: $nameDraft)
            Button(String(localized: "Cancel", bundle: .strings), role: .cancel) { namePrompt = nil }
            Button(namePrompt == .newList ? String(localized: "Create", bundle: .strings) : String(localized: "Rename", bundle: .strings)) { commitName() }
        }
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }

    // ── Search ──────────────────────────────────────────────────────────────

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Palette.faint)
            TextField(String(localized: "Search your beats", bundle: .strings), text: $query)
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
        } else {
            if let error = library.state.error {
                Button { library.dismissError() } label: {
                    Text(error).font(.footnote).foregroundStyle(Palette.danger)
                        .frame(maxWidth: .infinity).padding(8)
                }
                .buttonStyle(.plain)
            }
            searchField
            if trimmedQuery.isEmpty {
                // A List rather than a ScrollView, for the native swipe actions on each
                // beat row; styled flat so it reads exactly as the stack did.
                List {
                    Group {
                        builtInHeader
                        ForEach(library.shippedBeats.effective, id: \.id) { beatRow($0) }
                        section(String(localized: "Your beats", bundle: .strings), library.state.customBeats)
                        ForEach(library.state.categories, id: \.id) { category in
                            categoryHeader(category)
                            let beats = library.categoryBeats(category.id)
                            if beats.isEmpty {
                                Text(String(localized: "Empty — add beats from any row's … menu.", bundle: .strings))
                                    .font(.caption).foregroundStyle(Palette.faint)
                                    .padding(.vertical, 6)
                            }
                            ForEach(beats, id: \.id) { beatRow($0) }
                        }
                    }
                    .libraryListRow()
                }
                .libraryList()
            } else {
                let results = library.allBeats.filter {
                    $0.name.localizedCaseInsensitiveContains(trimmedQuery)
                }
                List {
                    Group {
                        if results.isEmpty {
                            Text(String(localized: "No beats match “\(trimmedQuery)”.", bundle: .strings))
                                .font(.footnote).foregroundStyle(Palette.syahiSoft)
                                .frame(maxWidth: .infinity).padding(.vertical, 24)
                        }
                        ForEach(results, id: \.id) { beatRow($0) }
                    }
                    .libraryListRow()
                }
                .libraryList()
            }
        }
    }

    /// "Built in" plus the one line about where the set came from, with a manual check —
    /// the Beats screen's ShippedStatus row on Android.
    private var builtInHeader: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                sectionTitle(String(localized: "Built in", bundle: .strings))
                Spacer()
                if let shipped = container.shippedBeats {
                    Button {
                        Task { await shipped.checkForUpdates(manual: true) }
                    } label: {
                        if shipped.status.checking {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "arrow.clockwise").font(.footnote)
                        }
                    }
                    .foregroundStyle(Palette.clay)
                    .accessibilityLabel(String(localized: "Check for updated built-in beats", bundle: .strings))
                    .padding(.top, 12)
                }
            }
            if let notice = container.shippedBeats?.status.notice {
                Text(notice).font(.caption).foregroundStyle(Palette.syahiSoft)
            }
        }
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Palette.syahiSoft)
            .padding(.top, 12)
            .padding(.bottom, 4)
    }

    // A labelled run of beat rows. An empty collection is skipped entirely (no "Your beats"
    // header hovering over nothing).
    @ViewBuilder
    private func section(_ title: String, _ beats: [Beat]) -> some View {
        if !beats.isEmpty {
            sectionTitle(title)
            ForEach(beats, id: \.id) { beatRow($0) }
        }
    }

    private func categoryHeader(_ category: Category) -> some View {
        HStack {
            sectionTitle(category.name)
            Spacer()
            Menu {
                Button { nameDraft = category.name; namePrompt = .rename(id: category.id, current: category.name) } label: {
                    Label(String(localized: "Rename", bundle: .strings), systemImage: "pencil")
                }
                let beats = library.categoryBeats(category.id)
                if !beats.isEmpty {
                    if p2pSharing {
                        Button { sheet = .share(.category(name: category.name, beats: beats)) } label: {
                            Label(String(localized: "Share list…", bundle: .strings), systemImage: "square.and.arrow.up")
                        }
                        if canPublish {
                            Button { publish(.category(name: category.name, beats: beats), name: category.name) } label: {
                                Label(String(localized: "Publish to community", bundle: .strings), systemImage: "globe")
                            }
                        }
                    } else if container.community != nil {
                        Button { publish(.category(name: category.name, beats: beats), name: category.name) } label: {
                            Label(String(localized: "Share list…", bundle: .strings), systemImage: "square.and.arrow.up")
                        }
                    }
                }
                Button(role: .destructive) { pending = .deleteList(id: category.id, name: category.name) } label: {
                    Label(String(localized: "Delete list", bundle: .strings), systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(Palette.syahiSoft)
                    .frame(width: 36, height: 30)
                    .padding(.top, 12)
            }
            .accessibilityLabel(String(localized: "Actions for \(category.name)", bundle: .strings))
        }
    }

    private func beatRow(_ beat: Beat) -> some View {
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
                            Text(BeatText.note(beat.note))
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

            beatMenu(beat)
        }
        .padding(.leading, 14)
        .padding(.vertical, 8)
        .padding(.trailing, 4)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        .padding(.vertical, 2)
        // Swipe left for the two everyday actions, as on Android. Delete still goes
        // through the confirm dialog — a swipe is too easy to make by accident — and
        // is offered only on the user's own beats; built-ins get Share alone.
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if library.isCustomBeat(beat.id), let id = beat.id {
                Button { pending = .deleteBeat(id: id, name: beat.name) } label: {
                    Label(String(localized: "Delete", bundle: .strings), systemImage: "trash")
                }
                .tint(Palette.danger)
            }
            if p2pSharing || container.community != nil {
                Button {
                    if p2pSharing { sheet = .share(.beat(beat)) } else { publish(.beat(beat), name: beat.name) }
                } label: {
                    Label(String(localized: "Share", bundle: .strings), systemImage: "square.and.arrow.up")
                }
                .tint(Palette.strap)
            }
        }
    }

    private func beatMenu(_ beat: Beat) -> some View {
        let custom = library.isCustomBeat(beat.id)
        return Menu {
            if custom {
                Button { onEdit(.edit(beat)) } label: { Label(String(localized: "Edit", bundle: .strings), systemImage: "pencil") }
            } else {
                Button { onEdit(.fork(beat)) } label: { Label(String(localized: "Edit a copy", bundle: .strings), systemImage: "doc.on.doc") }
                if auth.isMaintainer {
                    Button { onEdit(.everyone(beat)) } label: { Label(String(localized: "Edit for everyone", bundle: .strings), systemImage: "person.3") }
                }
            }
            if p2pSharing {
                Button { sheet = .share(.beat(beat)) } label: { Label(String(localized: "Share…", bundle: .strings), systemImage: "square.and.arrow.up") }
                if custom && canPublish {
                    Button { publish(.beat(beat), name: beat.name) } label: { Label(String(localized: "Publish to community", bundle: .strings), systemImage: "globe") }
                }
            } else if container.community != nil {
                Button { publish(.beat(beat), name: beat.name) } label: { Label(String(localized: "Share…", bundle: .strings), systemImage: "square.and.arrow.up") }
            }
            if !library.state.categories.isEmpty, let id = beat.id {
                Menu {
                    ForEach(library.state.categories, id: \.id) { category in
                        Button {
                            Task { await library.toggleBeatInCategory(category.id, id) }
                        } label: {
                            if category.beatIds.contains(id) {
                                Label(category.name, systemImage: "checkmark")
                            } else {
                                Text(category.name)
                            }
                        }
                    }
                } label: {
                    Label(String(localized: "Lists", bundle: .strings), systemImage: "text.badge.plus")
                }
            }
            if custom, let id = beat.id {
                Button(role: .destructive) { pending = .deleteBeat(id: id, name: beat.name) } label: {
                    Label(String(localized: "Delete", bundle: .strings), systemImage: "trash")
                }
            } else if auth.isMaintainer {
                Button(role: .destructive) { pending = .removeForEveryone(beat) } label: {
                    Label(String(localized: "Remove for everyone", bundle: .strings), systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.body)
                .foregroundStyle(Palette.syahiSoft)
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(String(localized: "Actions for \(beat.name)", bundle: .strings))
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /// Publishing needs a cloud and a session; signed out, the item is simply not offered.
    private var canPublish: Bool { container.community != nil && auth.session != nil }

    /// Peer-to-peer sharing (codes, links, the share sheet) is soft-retired: "Share"
    /// now publishes to the community. Imports of codes and links still work, and
    /// flipping this brings the old menu entries back.
    private let p2pSharing = false

    private func publish(_ payload: ShareCodec.SharePayload, name: String) {
        guard let community = container.community else { return }
        guard let session = auth.session else {
            auth.notice = String(localized: "Sign in to rate beats and publish your own.", bundle: .strings)
            return
        }
        Task {
            do {
                _ = try await community.publish(payload, authorId: session.userId, authorName: session.displayName)
                auth.notice = String(localized: "Published “\(name)” to the community library.", bundle: .strings)
            } catch {
                auth.notice = storageErrorMessage(error)
            }
        }
    }

    private func confirmLabel(_ which: Pending) -> String {
        switch which {
        case .deleteBeat, .deleteList: return String(localized: "Delete", bundle: .strings)
        case .removeForEveryone: return String(localized: "Remove for everyone", bundle: .strings)
        }
    }

    private func confirm(_ which: Pending) {
        pending = nil
        switch which {
        case .deleteBeat(let id, _):
            Task { await library.deleteBeat(id) }
        case .deleteList(let id, _):
            Task { await library.deleteCategory(id) }
        case .removeForEveryone(let beat):
            Task {
                do {
                    try await container.shippedBeats?.removeShippedBeat(beat)
                    auth.notice = String(localized: "Removed “\(beat.name)” from the built-in beats.", bundle: .strings)
                } catch {
                    auth.notice = storageErrorMessage(error)
                }
            }
        }
    }

    private var namePromptTitle: String {
        switch namePrompt {
        case .rename(_, let current): return String(localized: "Rename “\(current)”", bundle: .strings)
        default: return String(localized: "New list", bundle: .strings)
        }
    }

    private func commitName() {
        let name = nameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        let prompt = namePrompt
        namePrompt = nil
        guard !name.isEmpty else { return }
        switch prompt {
        case .newList:
            Task { _ = await library.createCategory(name) }
        case .rename(let id, _):
            Task { await library.renameCategory(id, name) }
        case nil:
            break
        }
    }
}

private extension View {
    /// Flattens a List row to look like the old stacked layout: no separator, no row
    /// background, the stack's 16pt side margins.
    func libraryListRow() -> some View {
        self.listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }

    /// The List itself: plain, transparent, with room under the last row for the
    /// floating bottom bar.
    func libraryList() -> some View {
        self.listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .contentMargins(.bottom, 110, for: .scrollContent)
    }
}
