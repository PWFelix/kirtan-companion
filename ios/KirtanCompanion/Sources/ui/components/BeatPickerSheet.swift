import SwiftUI

/// The quick-pick sheet, opened by tapping the beat name on Home or Learn.
///
/// A tab row (Built in · Your beats · each of your lists) over the beats of that tab,
/// each with a mini strip. It opens on the ACTIVE category, and picking from a tab makes
/// that tab active, so the ‹ › arrows then step through the same list — the same
/// contract as Android's and the web's quick-pick.
struct BeatPickerSheet: View {
    let container: AppContainer
    /// The beat to mark as chosen; nil means the loaded beat.
    var selectedId: String?? = nil
    /// Where the pick goes. Nil loads it (Home's beat, Learn's beat); Home's
    /// sequencer passes one to add or replace an entry instead.
    var onPick: ((Beat) -> Void)? = nil
    let onDone: () -> Void

    @State private var tab: String = ""

    private var library: LibraryRepository { container.library }

    private var tabs: [String] {
        let community = library.state.customBeats.contains(where: \.isCommunity) ? [COMMUNITY_CATEGORY] : []
        return [BUILTIN_CATEGORY, CUSTOM_CATEGORY] + community + library.state.categories.map(\.id)
    }

    private func tabName(_ id: String) -> String {
        switch id {
        case BUILTIN_CATEGORY: return String(localized: "Built in", bundle: .strings)
        case CUSTOM_CATEGORY: return String(localized: "Your beats", bundle: .strings)
        default: return library.categoryName(id)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(String(localized: "Choose a beat", bundle: .strings))
                .font(.system(size: 22, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.syahi)
                .padding(.horizontal, 20)
                .padding(.top, 20)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs, id: \.self) { id in
                        Button { tab = id } label: {
                            Text(tabName(id))
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .foregroundStyle(id == tab ? Color.white : Palette.syahi)
                                .background(Capsule().fill(id == tab ? Palette.clay : Color.clear))
                                .overlay(Capsule().strokeBorder(id == tab ? Color.clear : Palette.rule, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }

            let beats = library.categoryBeats(tab)
            ScrollView {
                VStack(spacing: 8) {
                    if beats.isEmpty {
                        Text(String(localized: "This category is empty — add beats to it on the Beats page.", bundle: .strings))
                            .font(.callout)
                            .foregroundStyle(Palette.syahiSoft)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 16)
                    }
                    ForEach(beats, id: \.id) { beat in
                        row(beat)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 20)
            }
        }
        .background(Palette.head.ignoresSafeArea())
        .onAppear { if tab.isEmpty { tab = library.state.activeCategoryId } }
    }

    private func row(_ beat: Beat) -> some View {
        let selected = beat.id == (selectedId ?? container.transport.loadedBeat?.id)
        return Button {
            library.setActiveCategory(tab)
            if let onPick { onPick(beat) } else { container.transport.loadBeat(beat) }
            onDone()
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(beat.name).font(.body.weight(.semibold)).foregroundStyle(Palette.syahi)
                    Spacer()
                    Text(BeatText.note(beat.note)).font(.footnote).foregroundStyle(Palette.syahiSoft)
                }
                BeatStripView(beat: beat, mini: true).frame(height: 18)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14).fill(selected ? Palette.clay.opacity(0.12) : Color.white.opacity(0.6)))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? Palette.clay : Palette.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
