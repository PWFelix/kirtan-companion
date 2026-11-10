import SwiftUI
import UIKit

// Sharing out and bringing in: the share sheet, the paste-to-import sheet, and the
// confirmation every inbound payload passes through.
//
// Ported from ui/share/ShareSheet.kt and ImportSheet.kt (themselves from the share and
// import sheets in src/views/BeatsView.jsx). The whole beat travels INSIDE the link —
// nothing is uploaded — so sharing works signed out and with no cloud configured.

/// What is being shared out: one beat, or a whole progression.
enum ShareTarget: Identifiable {
    case beat(Beat)
    case category(name: String, beats: [Beat])

    var id: String {
        switch self {
        case .beat(let b): return "b:\(b.id ?? b.name)"
        case .category(let name, _): return "c:\(name)"
        }
    }

    var title: String {
        switch self {
        case .beat(let b): return b.name
        case .category(let name, _): return name
        }
    }

    var payload: ShareCodec.SharePayload {
        switch self {
        case .beat(let b): return .beat(b)
        case .category(let name, let beats): return .category(name: name, beats: beats)
        }
    }
}

/// The outbound share sheet: a native share, a copyable link, and a copyable bare code —
/// the code survives a messenger that mangles URLs, the link survives a recipient with no app.
struct ShareSheetView: View {
    let target: ShareTarget
    var onDismiss: () -> Void

    @State private var copied: String?

    private var code: String {
        switch target {
        case .beat(let b): return ShareCodec.encodeBeat(b)
        case .category(let name, let beats): return ShareCodec.encodeCategory(name: name, beats: beats)
        }
    }

    var body: some View {
        let code = self.code
        let link = ShareLinks.forCode(code)
        SheetScaffold(title: String(localized: "Share \(target.title)"), onDismiss: onDismiss) {
            Text(blurb).font(.subheadline).foregroundStyle(Palette.syahiSoft)

            if ShareLinks.webBase.isEmpty {
                Text(String(localized: "No web origin is configured, so this link opens the app rather than the site."))
                    .font(.caption).foregroundStyle(Palette.faint)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                Text(link).font(.caption.monospaced()).foregroundStyle(Palette.syahi).textSelection(.enabled)
            }
            .padding(12)
            .background(Palette.headSunken, in: RoundedRectangle(cornerRadius: 10))

            HStack(spacing: 10) {
                SecondaryButton(title: String(localized: "Copy link")) { UIPasteboard.general.string = link; copied = String(localized: "Link copied") }
                SecondaryButton(title: String(localized: "Copy code")) { UIPasteboard.general.string = code; copied = String(localized: "Code copied") }
            }

            if let url = URL(string: link) {
                ShareLink(item: url, subject: Text(target.title)) {
                    Label(String(localized: "Share…"), systemImage: "square.and.arrow.up")
                        .primaryButtonLabel()
                }
            }

            if let copied {
                Text(copied).font(.caption).foregroundStyle(Palette.clay)
            }
        }
    }

    private var blurb: String {
        switch target {
        case .beat: return String(localized: "The whole beat travels inside the link. Nothing is uploaded.")
        case .category(_, let beats): return String(localized: "All \(beats.count) beats in this list travel inside the link. Nothing is uploaded.")
        }
    }
}

/// Paste a code or link, decode it, then hand it to the confirmation. Two steps on
/// purpose: this is the app's first untrusted input, and the user confirms something they
/// can SEE (the decoded strip), not a blob of base64.
struct ImportSheetView: View {
    var onDismiss: () -> Void
    var onDecoded: (ShareCodec.SharePayload) -> Void

    @State private var input = ""
    @State private var error: String?

    var body: some View {
        SheetScaffold(title: String(localized: "Import"), onDismiss: onDismiss) {
            Text(String(localized: "Paste a share code or link. A whole link works — the code is read out of it either way."))
                .font(.subheadline).foregroundStyle(Palette.syahiSoft)
            TextField(String(localized: "Paste a share code or link"), text: $input, axis: .vertical)
                .lineLimit(2...4)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(10)
                .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.rule))
                .onChange(of: input) { error = nil }
            if let error {
                Text(error).font(.subheadline).foregroundStyle(Palette.danger)
            }
            Button {
                let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
                let code = URL(string: trimmed).flatMap(ShareLinks.code(fromDeepLink:)) ?? ShareCodec.codeFromInput(trimmed)
                if let payload = ShareCodec.decodeShare(code), payload != .invalid {
                    onDecoded(payload)
                } else {
                    error = String(localized: "That is not a Kirtan share code or link. Nothing was imported.")
                }
            } label: {
                Text(String(localized: "Preview")).primaryButtonLabel()
            }
            .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty)
            Text(String(localized: "An imported beat is always saved under a NEW id, so it can never overwrite one of yours — a single beat lands in Your beats, a list arrives as its own playlist."))
                .font(.caption).foregroundStyle(Palette.faint)
        }
    }
}

/// The last stop before anything is written. Pasted codes and deep links both arrive here,
/// so a link can never write to the library without the user seeing it first.
struct ImportConfirmView: View {
    let payload: ShareCodec.SharePayload
    var onDismiss: () -> Void
    var onAccept: () -> Void

    var body: some View {
        switch payload {
        case .invalid:
            SheetScaffold(title: String(localized: "Can't open that link"), onDismiss: onDismiss) {
                Text(String(localized: "The code was damaged, truncated, or made by a newer version of the app. Nothing was imported."))
                    .font(.subheadline).foregroundStyle(Palette.syahiSoft)
                Button(action: onDismiss) { Text(String(localized: "Close")).primaryButtonLabel() }
            }
        case .beat(let beat):
            SheetScaffold(title: String(localized: "Someone shared this"), onDismiss: onDismiss) {
                Text(beat.name).font(.title2.weight(.semibold)).foregroundStyle(Palette.syahi)
                Text(String(localized: "\(beat.steps) cells") + " · " + String(localized: "suggested \(beat.bpm) BPM")).font(.subheadline).foregroundStyle(Palette.syahiSoft)
                BeatStripView(beat: beat).frame(height: 90)
                buttons(accept: String(localized: "Add to my beats"))
            }
        case .category(let name, let beats):
            SheetScaffold(title: String(localized: "Someone shared a list"), onDismiss: onDismiss) {
                Text(name).font(.title2.weight(.semibold)).foregroundStyle(Palette.syahi)
                Text(String(localized: "\(beats.count) beats")).font(.subheadline).foregroundStyle(Palette.syahiSoft)
                ForEach(Array(beats.enumerated()), id: \.offset) { _, beat in
                    HStack {
                        Text(beat.name).foregroundStyle(Palette.syahi)
                        Spacer()
                        Text("\(beat.bpm) bpm").font(.footnote.monospacedDigit()).foregroundStyle(Palette.faint)
                    }
                    .padding(.vertical, 4)
                }
                buttons(accept: String(localized: "Add list"))
            }
        }
    }

    private func buttons(accept: String) -> some View {
        HStack(spacing: 10) {
            SecondaryButton(title: String(localized: "Cancel"), action: onDismiss)
            Button(action: onAccept) { Text(accept).primaryButtonLabel() }
        }
    }
}

/// The first-sign-in offer to copy the device library into the new account. Declining is
/// a real choice: the beats stay on the device and the account starts empty. Only made on
/// a signed-out → signed-in transition, so it does not return on later launches.
struct MigrationSheetView: View {
    let container: AppContainer
    let local: Library
    @State private var busy = false

    var body: some View {
        SheetScaffold(title: String(localized: "Move your beats to your account?"), onDismiss: { container.auth.pendingMigration = nil }) {
            Text(summary).font(.subheadline).foregroundStyle(Palette.syahi)
            Text(String(localized: "Moving copies them up; the copies on this device stay until you delete them. Leaving them here means this account starts empty."))
                .font(.caption).foregroundStyle(Palette.syahiSoft)
            HStack(spacing: 10) {
                SecondaryButton(title: String(localized: "Leave on device")) { container.auth.pendingMigration = nil }
                    .disabled(busy)
                Button {
                    busy = true
                    Task { _ = await container.acceptMigration(); busy = false }
                } label: { Text(busy ? String(localized: "Moving…") : String(localized: "Move them")).primaryButtonLabel() }
                .disabled(busy)
            }
        }
    }

    private var summary: String {
        let b = local.beats.count, c = local.categories.count
        var s = String(localized: "This device holds \(b) beats")
        if c > 0 { s += String(localized: " and \(c) lists") }
        return s + (b + c == 1 ? String(localized: " that is not in your account yet.") : String(localized: " that are not in your account yet."))
    }
}

// ── Shared sheet chrome ────────────────────────────────────────────────────

/// A titled sheet body with a close button: the KcSheet of the Android port.
struct SheetScaffold<Content: View>: View {
    let title: String
    var onDismiss: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text(title).font(.title3.weight(.semibold)).foregroundStyle(Palette.syahi)
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark").font(.body.weight(.semibold))
                            .foregroundStyle(Palette.syahiSoft).frame(width: 32, height: 32)
                    }
                    .accessibilityLabel(String(localized: "Close"))
                }
                content()
            }
            .padding(20)
        }
        .background(Palette.head.ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }
}

struct SecondaryButton: View {
    let title: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Palette.clayDeep)
                .frame(maxWidth: .infinity, minHeight: 44)
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.clay, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

extension View {
    /// The filled clay button face, for labels inside Button/ShareLink.
    func primaryButtonLabel() -> some View {
        self.font(.subheadline.weight(.semibold))
            .foregroundStyle(Palette.onClay)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(Palette.clay, in: RoundedRectangle(cornerRadius: 10))
    }
}
