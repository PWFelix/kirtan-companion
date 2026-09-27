import SwiftUI

// The community library: published beats and playlists from every kirtan companion.
//
// The public face of the cloud, and the part anyone can read — RLS opens the table to the
// world — but copying and publishing need a session, so the screen leads with the account
// and only then shows the list. Ported from the "Browse" page inside the web's Beats view
// (src/views/BeatsView.jsx's `renderBrowse`). It is content, not a screen of its own: it is
// drawn under Beats' "Community" segment, so it carries no title of its own.
//
// The web's maintainer-only "promote into the shipped set" action is deliberately not
// ported — it writes to what the app ships and belongs to its two maintainers, not to a
// general build.
struct BrowseView: View {
    let container: AppContainer

    @State private var query = ""
    @State private var items: [PublishedItem] = []
    @State private var loading = false
    @State private var errorMessage: String?
    /// The card that just imported, for the brief "Added" flash on its button.
    @State private var copiedId: String?

    private var signedIn: Bool { container.account != nil }
    /// Re-keys the loader when the query changes or the session appears/disappears.
    private var loadKey: String { "\(query)~\(signedIn)" }

    var body: some View {
        VStack(spacing: 12) {
            accountGate

            if signedIn {
                searchField
                list
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .task(id: loadKey) {
            guard container.cloudConfigured, signedIn else { return }
            if !query.isEmpty {
                try? await Task.sleep(for: .milliseconds(300))
                if Task.isCancelled { return }
            }
            await load()
        }
    }

    // ── Account gate ────────────────────────────────────────────────────────

    @ViewBuilder
    private var accountGate: some View {
        if !container.cloudConfigured {
            // No cloud in this build: there is nothing to browse. The segment shouldn't
            // be drawn either, but this keeps the view self-contained if it is shown.
            Text("The community library isn't available in this build.")
                .font(.footnote)
                .foregroundStyle(Palette.syahiSoft)
        } else if let account = container.account {
            HStack {
                Text("Signed in as \(container.auth.displayName)")
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
                Spacer()
                Button("Sign out") {
                    Task { await container.signOut() }
                }
                .font(.footnote)
                .foregroundStyle(Palette.clay)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        } else {
            VStack(spacing: 10) {
                SignInButton(container: container, title: "Sign in to browse & share")
                Text("Copy a shared beat into your library, or publish one of your own.")
                    .font(.footnote)
                    .foregroundStyle(Palette.syahiSoft)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
            .padding(.horizontal, 12)
            .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    // ── Search ──────────────────────────────────────────────────────────────

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Palette.faint)
            TextField("Search the community", text: $query)
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

    @ViewBuilder
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

                ForEach(items) { item in
                    card(item)
                }
            }
            .padding(8)
        }
    }

    private var emptyHint: String {
        query.trimmingCharacters(in: .whitespaces).isEmpty
            ? "Nothing shared yet — open any beat, tap Share, and choose “Publish to community”."
            : "Nothing shared matches “\(query.trimmingCharacters(in: .whitespaces))”."
    }

    // One published row as a card: name, author, copy count, an Add button, and — for a
    // single beat — a mini preview of the pattern.
    private func card(_ item: PublishedItem) -> some View {
        let added = copiedId == item.id
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Palette.syahi)
                    Text(meta(item))
                        .font(.caption)
                        .foregroundStyle(Palette.syahiSoft)
                }
                Spacer(minLength: 8)
                Button {
                    Task { await add(item) }
                } label: {
                    Text(added ? "Added" : "+ Add")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(added ? Palette.syahiSoft : Palette.clay)
                }
                .buttonStyle(.plain)
                .disabled(added)
            }
            if let preview = previewBeat(item) {
                BeatStripView(beat: preview, mini: true)
                    .padding(.top, 2)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
    }

    private func meta(_ item: PublishedItem) -> String {
        let kind = item.kind == .playlist
            ? "Playlist · \(item.payload.element("beats")?.asArray()?.count ?? 0) beats"
            : "Beat"
        var text = "\(kind) · by \(item.authorName ?? "A devotee")"
        if item.copies > 0 {
            text += " · \(item.copies) \(item.copies == 1 ? "copy" : "copies")"
        }
        return text
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /// The card's Add: import the snapshot into the user's library, then best-effort bump the
    /// copy counter and flash the button. Importing never fails the tap even if the counter
    /// doesn't move — the beat is in the library by then.
    private func add(_ item: PublishedItem) async {
        guard let community = container.community else { return }
        let result = await container.library.importShared(community.toImportPayload(item))
        guard !result.beats.isEmpty else { return }
        copiedId = item.id
        Task {
            await community.incrementCopies(item.id)
            try? await Task.sleep(for: .seconds(1.6))
            if copiedId == item.id { copiedId = nil }
        }
    }

    private func load() async {
        guard let community = container.community, signedIn else { return }
        loading = true
        errorMessage = nil
        do {
            items = try await community.browse(query: query)
        } catch {
            items = []
            errorMessage = storageErrorMessage(error)
        }
        loading = false
    }

    private func previewBeat(_ item: PublishedItem) -> Beat? {
        guard item.kind == .beat else { return nil }
        return LibraryJson.decodeBeat(item.payload.element("beat"))
    }
}
