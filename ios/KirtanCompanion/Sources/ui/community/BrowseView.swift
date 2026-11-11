import SwiftUI

// The community library: published beats and playlists from every kirtan companion.
//
// Ported from the "Browse" page inside the web's Beats view and from Android's
// CommunityScreen/CommunityViewModel. Drawn under Beats' "Community" segment, so it carries
// no title of its own.
//
// Two rules from the client shape this screen:
//  - Browsing needs NO identity (the select policy is `using (true)`), so a signed-out user
//    can browse and even copy into device storage. Rating and publishing need a session.
//  - Copying goes through LibraryRepository.importShared — the SAME door a share link uses —
//    stamped with the item's id, so the card can say "In your library" (issue #11). That
//    fact is read from the library itself, not remembered at add time: deleting your copy
//    brings Add back, and a copy synced from another device counts too.
struct BrowseView: View {
    let container: AppContainer

    @State private var query = ""
    @State private var scope: CommunityClient.BrowseScope = .everything
    /// Show only beats the author tagged with this.
    @State private var tag: String?
    @State private var items: [PublishedItem] = []
    /// Item ids the signed-in user has upvoted. Empty when signed out.
    @State private var myVotes: Set<String> = []
    /// Browse order; Top (votes decayed by age) by default.
    @State private var sort: CommunityClient.BrowseSort = .top
    @State private var loading = false
    @State private var errorMessage: String?
    /// Ids an Add is in flight for: guards a double tap that lands before the library
    /// state has caught up with the first.
    @State private var adding: Set<String> = []
    @State private var pendingUnpublish: PublishedItem?
    @State private var promoting: PublishedItem?
    @State private var promoteHeading = ""
    /// The card whose beat is sounding as a preview, if any. Listen before adding:
    /// nothing is saved or selected, and ending it hands the engine back to Home's beat.
    @State private var previewingId: String?
    /// When it started: stopping a playing Home beat to make room reports playing=false
    /// a moment later, which must not read as "stopped elsewhere".
    @State private var previewStarted = Date.distantPast

    private var auth: AuthViewModel { container.auth }
    private var session: AuthSession? { auth.session }
    /// Re-keys the loader when the query, the scope or the session changes.
    private var loadKey: String { "\(query)~\(scope)~\(sort)~\(tag ?? "-")~\(session?.userId ?? "-")" }

    /// Community item ids the library already holds a copy of.
    private var inLibrary: Set<String> {
        Set(container.library.state.customBeats.compactMap(\.sourceItemId))
    }

    var body: some View {
        VStack(spacing: 10) {
            accountGate
            if container.cloudConfigured {
                searchField
                Picker(String(localized: "Search in", bundle: .strings), selection: $scope) {
                    Text(String(localized: "Everything", bundle: .strings)).tag(CommunityClient.BrowseScope.everything)
                    Text(String(localized: "Names", bundle: .strings)).tag(CommunityClient.BrowseScope.names)
                    Text(String(localized: "Authors", bundle: .strings)).tag(CommunityClient.BrowseScope.authors)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 12)
                Picker(String(localized: "Sort by", bundle: .strings), selection: $sort) {
                    Text(String(localized: "Top", bundle: .strings)).tag(CommunityClient.BrowseSort.top)
                    Text(String(localized: "New", bundle: .strings)).tag(CommunityClient.BrowseSort.new)
                    Text(String(localized: "Most added", bundle: .strings)).tag(CommunityClient.BrowseSort.copies)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 12)
                // Every vocabulary tag: the server filters, so this page can't tell which
                // tags exist out there.
                TagFilterRow(tags: TagVocabulary.allTags.map(\.id), selected: $tag)
                list
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
        // Leaving the page, or the sound being stopped elsewhere, ends the preview.
        .onDisappear { endPreview() }
        .onChange(of: container.transport.playing) { _, playing in
            if !playing, previewingId != nil, Date().timeIntervalSince(previewStarted) > 1 { endPreview() }
        }
        .task(id: loadKey) {
            guard container.cloudConfigured else { return }
            if !query.isEmpty {
                try? await Task.sleep(for: .milliseconds(300))
                if Task.isCancelled { return }
            }
            await load()
        }
        .confirmationDialog(
            String(localized: "Remove “\(pendingUnpublish?.name ?? "")” from the community library?", bundle: .strings),
            isPresented: Binding(get: { pendingUnpublish != nil }, set: { if !$0 { pendingUnpublish = nil } }),
            titleVisibility: .visible,
            presenting: pendingUnpublish,
        ) { item in
            Button(String(localized: "Unpublish", bundle: .strings), role: .destructive) { Task { await unpublish(item) } }
            Button(String(localized: "Cancel", bundle: .strings), role: .cancel) { pendingUnpublish = nil }
        } message: { _ in
            Text(String(localized: "Copies people already made stay in their libraries.", bundle: .strings))
        }
        .alert(String(localized: "Make it a built-in beat", bundle: .strings), isPresented: Binding(get: { promoting != nil }, set: { if !$0 { promoting = nil } })) {
            TextField(String(localized: "Section heading", bundle: .strings), text: $promoteHeading)
            Button(String(localized: "Cancel", bundle: .strings), role: .cancel) { promoting = nil }
            Button(String(localized: "Promote", bundle: .strings)) {
                if let item = promoting { Task { await promote(item) } }
                promoting = nil
            }
        } message: {
            Text(String(localized: "Every install gets this beat on its next launch. Which section of Built in should it appear under?", bundle: .strings))
        }
    }

    // ── Account ─────────────────────────────────────────────────────────────

    @ViewBuilder
    private var accountGate: some View {
        if !container.cloudConfigured {
            Text(String(localized: "The community library isn't available in this build.", bundle: .strings))
                .font(.footnote)
                .foregroundStyle(Palette.syahiSoft)
        } else if session != nil {
            HStack {
                Text(String(localized: "Signed in as \(auth.displayName)", bundle: .strings))
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
                Spacer()
                Button(String(localized: "Sign out", bundle: .strings)) {
                    Task { await container.signOut() }
                }
                .font(.footnote)
                .foregroundStyle(Palette.clay)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        } else {
            // Browsing and copying work signed out; only rating and publishing need an
            // account, so the gate is an invitation rather than a wall.
            HStack(spacing: 10) {
                Text(String(localized: "Sign in to upvote beats and publish your own.", bundle: .strings))
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
                Spacer(minLength: 4)
                SignInButton(container: container, title: String(localized: "Sign in", bundle: .strings))
                    .fixedSize()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    // ── Search ──────────────────────────────────────────────────────────────

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Palette.faint)
            TextField(scope == .authors ? String(localized: "Search by author", bundle: .strings) : String(localized: "Search the community", bundle: .strings), text: $query)
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
    }

    // ── List ────────────────────────────────────────────────────────────────

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                if loading && items.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                }
                if let errorMessage {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(Palette.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                }
                if !loading && errorMessage == nil && items.isEmpty {
                    Text(emptyHint)
                        .font(.footnote)
                        .foregroundStyle(Palette.syahiSoft)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                }
                ForEach(items) { card($0) }
            }
            .padding(8)
            .padding(.bottom, 100)
        }
        .refreshable { await load() }
    }

    private var emptyHint: String {
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty
            ? String(localized: "Nothing shared yet — open any of your beats' … menu and choose “Publish to community”.", bundle: .strings)
            : String(localized: "Nothing shared matches “\(q)”.", bundle: .strings)
    }

    private func card(_ item: PublishedItem) -> some View {
        let owned = item.authorId != nil && item.authorId == session?.userId
        let held = inLibrary.contains(item.id)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Palette.syahi)
                    Text(meta(item))
                        .font(.caption)
                        .foregroundStyle(Palette.syahiSoft)
                    // The author's tags, straight off the snapshot.
                    if let line = TagVocabulary.line(authorTags(item)) {
                        Text(line)
                            .font(.caption)
                            .foregroundStyle(Palette.syahiSoft)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                if held {
                    Label("In your library", systemImage: "checkmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Palette.syahiSoft)
                } else {
                    Button {
                        Task { await add(item) }
                    } label: {
                        Text(adding.contains(item.id) ? String(localized: "Adding…", bundle: .strings) : String(localized: "+ Add", bundle: .strings))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(Palette.clay)
                    }
                    .buttonStyle(.plain)
                    .disabled(adding.contains(item.id) || container.community?.toImportPayload(item) == nil)
                }
                if owned || auth.isMaintainer {
                    Menu {
                        if auth.isMaintainer && item.kind == .beat {
                            Button { promoteHeading = "Community"; promoting = item } label: {
                                Label(String(localized: "Make it a built-in beat", bundle: .strings), systemImage: "star.circle")
                            }
                        }
                        if owned {
                            Button(role: .destructive) { pendingUnpublish = item } label: {
                                Label(String(localized: "Unpublish", bundle: .strings), systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .foregroundStyle(Palette.syahiSoft)
                            .frame(width: 32, height: 24)
                    }
                    .accessibilityLabel(String(localized: "More for \(item.name)", bundle: .strings))
                }
            }
            if let preview = previewBeat(item) {
                BeatStripView(beat: preview, mini: true)
                    .padding(.top, 2)
                let on = previewingId == item.id
                Button {
                    if on {
                        endPreview()
                    } else {
                        container.transport.startPreview(preview, previewBpm: preview.bpm)
                        previewingId = item.id
                        previewStarted = Date()
                    }
                } label: {
                    Label(on ? String(localized: "Stop", bundle: .strings) : String(localized: "Preview", bundle: .strings),
                          systemImage: on ? "stop.fill" : "play.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Palette.clay)
                }
                .buttonStyle(.plain)
            }
            voteButton(item)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
    }

    /// The upvote pill: arrow plus count, clay once you've voted; tapping again takes it
    /// back. Signed out it still shows the count, disabled. Hidden when the database
    /// predates votes (no count to show).
    @ViewBuilder
    private func voteButton(_ item: PublishedItem) -> some View {
        if let count = item.voteCount {
            let voted = myVotes.contains(item.id)
            Button {
                Task { await toggleVote(item) }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: voted ? "arrow.up.circle.fill" : "arrow.up.circle")
                    Text("\(count)").monospacedDigit()
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(voted ? Palette.clay : Palette.syahiSoft)
                .padding(.horizontal, 10)
                .frame(minHeight: 32)
                .overlay(Capsule().stroke(voted ? Palette.clay : Palette.faint, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .disabled(session == nil)
            .accessibilityLabel(voted
                ? String(localized: "Take back your upvote (\(count) votes)", bundle: .strings)
                : String(localized: "Upvote (\(count) votes)", bundle: .strings))
        }
    }

    private func authorTags(_ item: PublishedItem) -> [String] {
        (item.payload.element("beat")?.element("tags")?.asArray() ?? []).compactMap { $0.asString() }
    }

    private func meta(_ item: PublishedItem) -> String {
        let count = item.payload.element("beats")?.asArray()?.count ?? 0
        let kind = item.kind == .playlist
            ? String(localized: "Playlist · \(count) beats", bundle: .strings)
            : String(localized: "Beat", bundle: .strings)
        let author = item.authorName ?? String(localized: "A devotee", bundle: .strings)
        var text = "\(kind) · " + String(localized: "by \(author)", bundle: .strings)
        if item.copies > 0 {
            text += " · " + String(localized: "\(item.copies) copies", bundle: .strings)
        }
        return text
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /// Copy into the library, stamped with the item id, then best-effort bump the counter:
    /// the beat is safely in the library by then, and a count that didn't move is not
    /// worth an error.
    private func add(_ item: PublishedItem) async {
        guard let community = container.community else { return }
        if inLibrary.contains(item.id) || adding.contains(item.id) {
            auth.notice = String(localized: "“\(item.name)” is already in your library.", bundle: .strings)
            return
        }
        guard let payload = community.toImportPayload(item) else {
            auth.notice = String(localized: "That item couldn't be read — it may have been published by a newer version of the app.", bundle: .strings)
            return
        }
        adding.insert(item.id)
        let result = await container.library.importShared(payload, sourceItemId: item.id)
        adding.remove(item.id)
        if result.beats.isEmpty {
            auth.notice = container.library.state.error ?? String(localized: "Nothing was added.", bundle: .strings)
            return
        }
        auth.notice = result.categoryId != nil
            ? String(localized: "Added \(result.beats.count) beats as the list “\(item.name)”.", bundle: .strings)
            : String(localized: "Added “\(item.name)” to Your beats.", bundle: .strings)
        Task { await community.incrementCopies(item.id) }
    }

    /// Optimistic: the arrow and count move now and go back if the write fails. No reload,
    /// so the list doesn't re-sort under your finger.
    private func toggleVote(_ item: PublishedItem) async {
        guard let community = container.community, let me = session else {
            auth.notice = String(localized: "Sign in to upvote.", bundle: .strings)
            return
        }
        let on = !myVotes.contains(item.id)
        func apply(_ yes: Bool) {
            if yes { myVotes.insert(item.id) } else { myVotes.remove(item.id) }
            if let i = items.firstIndex(where: { $0.id == item.id }), let c = items[i].voteCount {
                items[i].voteCount = max(0, c + (yes ? 1 : -1))
            }
        }
        apply(on)
        do {
            try await community.vote(itemId: item.id, userId: me.userId, on: on)
        } catch {
            apply(!on)
            auth.notice = storageErrorMessage(error)
        }
    }

    private func unpublish(_ item: PublishedItem) async {
        pendingUnpublish = nil
        guard let community = container.community else { return }
        do {
            try await community.unpublish(item.id)
            items.removeAll { $0.id == item.id }
            auth.notice = String(localized: "Removed “\(item.name)” from the community library.", bundle: .strings)
        } catch {
            auth.notice = storageErrorMessage(error)
        }
    }

    /// Maintainers only: write a published single beat into `shipped_beats`. The server's
    /// `is_maintainer()` policy has the last word; the menu only decides what's on screen.
    private func promote(_ item: PublishedItem) async {
        guard let shipped = container.shippedBeats, let beat = previewBeat(item) else {
            auth.notice = String(localized: "Only a single beat can become a built-in — not a list.", bundle: .strings)
            return
        }
        let heading = promoteHeading.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try await shipped.promote(item: item, beat: beat, heading: heading.isEmpty ? "Community" : heading)
            auth.notice = String(localized: "“\(beat.name)” is now one of the built-in beats.", bundle: .strings)
        } catch {
            auth.notice = storageErrorMessage(error)
        }
    }

    private func load() async {
        guard let community = container.community else { return }
        loading = true
        errorMessage = nil
        do {
            let loaded = try await community.browse(query: query, scope: scope, tag: tag, sort: sort)
            items = loaded
            if let me = session?.userId {
                myVotes = (try? await community.myVotes(userId: me, itemIds: loaded.map(\.id))) ?? []
            } else {
                myVotes = []
            }
        } catch {
            items = []
            errorMessage = storageErrorMessage(error)
        }
        loading = false
    }

    private func endPreview() {
        guard previewingId != nil else { return }
        previewingId = nil
        container.transport.endPreview()
    }

    private func previewBeat(_ item: PublishedItem) -> Beat? {
        guard item.kind == .beat else { return nil }
        return LibraryJson.decodeBeat(item.payload.element("beat"))
    }
}
