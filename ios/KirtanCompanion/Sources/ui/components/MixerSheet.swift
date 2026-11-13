import SwiftUI

/// The sound panel: master volume, then per-instrument volume/mute, and — for the
/// two mridanga ends — a five-band EQ and a tuning control.
///
/// Ported from `src/views/HomeView.jsx`'s mixer sheet. Every knob writes through the
/// transport (which mirrors into the engine and persists); every knob reads back from
/// the persisted snapshot, so reopening the sheet shows exactly what is sounding.
struct MixerSheet: View {
    let container: AppContainer
    let beat: Beat

    private var transport: TransportViewModel { container.transport }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    masterSection
                    feelSection
                SwingControl(
                    value: transport.swing,
                    onChange: { transport.setSwing($0) },
                    hint: String(localized: "Plays every second cell late, for a long-short lilt. Starts at the beat’s own swing; choosing a beat brings that back.", bundle: .strings)
                )
                .sectionCard()

                    ForEach(LaneId.ordered, id: \.self) { lane in
                        laneSection(lane)
                    }
                }
                .padding(20)
            }
            .background(Palette.head.ignoresSafeArea())
            .navigationTitle(String(localized: "Sound", bundle: .strings))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "Done", bundle: .strings)) { dismiss() }
                }
            }
        }
    }

    @Environment(\.dismiss) private var dismiss

    private var masterSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Master", bundle: .strings))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Palette.syahiSoft)
            Slider(
                value: Binding(
                    get: { transport.settings.masterVolume },
                    set: { transport.changeVolume($0) }
                ),
                in: 0...1
            ) {
                Text(String(localized: "Master", bundle: .strings))
            } minimumValueLabel: {
                Image(systemName: "speaker").foregroundStyle(Palette.faint)
            } maximumValueLabel: {
                Image(systemName: "speaker.wave.3.fill").foregroundStyle(Palette.faint)
            }
            .tint(Palette.clay)
        }
        .sectionCard()
    }

    /// EXPERIMENT: human timing and loudness variation (engine/Humanizer.swift).
    /// Four fixed levels rather than a slider, so it is easy to A/B by ear.
    private var feelSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Feel", bundle: .strings))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Palette.syahiSoft)
            Picker(String(localized: "Feel", bundle: .strings), selection: Binding(
                get: { transport.settings.feel },
                set: { transport.setFeel($0) }
            )) {
                ForEach(Feel.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            Text(transport.settings.feel == .drag
                 ? String(localized: "Laid back, the Bengali way: the drum sits just behind the beat, least on sam, so the \"1\" stays solid for singing.", bundle: .strings)
                 : String(localized: "Small changes in timing and loudness, so the drum sounds played rather than programmed.", bundle: .strings))
                .font(.caption)
                .foregroundStyle(Palette.syahiSoft)
        }
        .sectionCard()
    }

    private func laneSection(_ lane: LaneId) -> some View {
        let hasSound = settings().end(lane) != nil   // kartal has no EQ/tune

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(lane.label)
                    .font(.headline)
                    .foregroundStyle(Palette.lane(lane))
                Spacer()
                Button {
                    transport.toggleMute(lane)
                } label: {
                    Image(systemName: transport.settings.isMuted(lane) ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .foregroundStyle(transport.settings.isMuted(lane) ? Palette.danger : Palette.syahiSoft)
                }
            }

            Slider(
                value: Binding(
                    get: { transport.settings.volume(lane) },
                    set: { transport.changeEndVolume(lane, $0) }
                ),
                in: 0...1
            )
            .tint(Palette.lane(lane))

            if hasSound, let end = settings().end(lane) {
                eqSection(lane, end: end)
                tuneSection(lane, end: end)
            }
        }
        .sectionCard()
    }

    private func eqSection(_ lane: LaneId, end: EndSoundPrefs) -> some View {
        let open = bindingForEqPanel(lane)
        return VStack(alignment: .leading, spacing: 10) {
            Button {
                transport.toggleEqPanel(lane)
            } label: {
                HStack {
                    Text(String(localized: "Equaliser", bundle: .strings))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Palette.syahi)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .rotationEffect(.degrees(open ? 180 : 0))
                        .foregroundStyle(Palette.faint)
                }
            }
            if open {
                ForEach(0..<EQ_BAND_COUNT, id: \.self) { band in
                    HStack {
                        Text(EQ_BANDS[band].label)
                            .font(.caption.monospacedDigit())
                            .frame(width: 52, alignment: .leading)
                            .foregroundStyle(Palette.syahiSoft)
                        Slider(
                            value: Binding(
                                get: { transport.settings.end(lane)?.bands[band] ?? 0 },
                                set: { transport.changeEqBand(lane, bandIndex: band, db: $0) }
                            ),
                            in: EQ_MIN_DB...EQ_MAX_DB
                        )
                        .tint(Palette.lane(lane))
                    }
                }
            }
        }
    }

    private func tuneSection(_ lane: LaneId, end: EndSoundPrefs) -> some View {
        let open = bindingForTunePanel(lane)
        return VStack(alignment: .leading, spacing: 10) {
            Button {
                transport.toggleTunePanel(lane)
            } label: {
                HStack {
                    Text(String(localized: "Tune", bundle: .strings) + "  \(end.tuneCents >= 0 ? "+" : "")\(end.tuneCents)¢")
                        .font(.subheadline.weight(.medium))
                        .monospacedDigit()
                        .foregroundStyle(Palette.syahi)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .rotationEffect(.degrees(open ? 180 : 0))
                        .foregroundStyle(Palette.faint)
                }
            }
            if open {
                Slider(
                    value: Binding(
                        get: { Double(transport.settings.end(lane)?.tuneCents ?? 0) },
                        set: { transport.changeTune(lane, Int($0.rounded())) }
                    ),
                    in: Double(TUNE_MIN_CENTS)...Double(TUNE_MAX_CENTS)
                )
                .tint(Palette.lane(lane))
            }
        }
    }

    private func settings() -> EqPrefsSnapshot { transport.settings }

    private func bindingForEqPanel(_ lane: LaneId) -> Bool {
        transport.settings.end(lane)?.eqPanelOpen ?? false
    }

    private func bindingForTunePanel(_ lane: LaneId) -> Bool {
        transport.settings.end(lane)?.tunePanelOpen ?? false
    }
}

/// The raised card a mixer section sits in.
private extension View {
    func sectionCard() -> some View {
        self
            .padding(16)
            .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 14))
    }
}
