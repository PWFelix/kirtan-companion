import SwiftUI

/// The playing screen: the beat's name, the transport, and the tempo.
///
/// Ported from `src/views/HomeView.jsx`. For now this is the core transport only;
/// the beat strip, the mixer sheet and the quick-pick are added around it. Every
/// read and write goes through the container's [TransportViewModel] — the one
/// place that calls the engine.
struct HomeView: View {
    let container: AppContainer

    private var transport: TransportViewModel { container.transport }
    private var beat: Beat { beatForTransport() }

    @State private var mixerOpen = false
    @State private var pickerOpen = false
    @State private var tempoOpen = false

    private let rowHeight: CGFloat = 46

    var body: some View {
        // Captured here (on the main thread) so the Canvas's per-frame closure can
        // read the engine's phase without an actor hop; the engine is a plain,
        // lock-synchronised object.
        let engine = transport.engineHandle
        let muted = transport.settings.mutedEnds
        let lanes = stripLanes(for: beat)

        VStack(spacing: 22) {
            header

            stripBlock(engine: engine, muted: muted, lanes: lanes)

            Spacer(minLength: 0)
            playButton
            Spacer(minLength: 0)
            tempo
        }
        .padding(24)
        // The shell floats its bottom bar OVER the content; without this the tempo
        // buttons sat underneath it, unreachable. 96 is what the shell's banners clear.
        .padding(.bottom, 72)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sheet(isPresented: $mixerOpen) {
            MixerSheet(container: container, beat: beat)
                .presentationDetents([.medium, .large])
        }
        #if DEBUG
        // DEBUG: `-kirtanTempo` opens the tempo sheet, for screenshots.
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-kirtanTempo") { tempoOpen = true } }
        #endif
        .sheet(isPresented: $tempoOpen) {
            TempoSheet(container: container)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $pickerOpen) {
            BeatPickerSheet(container: container) { pickerOpen = false }
                .presentationDetents([.medium, .large])
        }
    }

    private func stripBlock(engine: KirtanEngine, muted: Set<LaneId>, lanes: [LaneId]) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                ForEach(lanes, id: \.self) { lane in
                    Text(lane.label)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(muted.contains(lane) ? Palette.faint : Palette.lane(lane))
                        .frame(height: rowHeight, alignment: .leading)
                }
            }
            .frame(width: 50, alignment: .leading)

            BeatStripView(
                beat: beat,
                step: transport.step,
                playing: transport.playing,
                getPhase: { engine.getPhase() },
                mutedEnds: muted,
                showBols: transport.settings.showBols,
            )
            .frame(height: rowHeight * CGFloat(lanes.count))
            .background(Palette.headWorn, in: RoundedRectangle(cornerRadius: 12))
            .overlay(alignment: .topTrailing) {
                Button {
                    mixerOpen = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                        .font(.footnote)
                        .foregroundStyle(Palette.syahiSoft)
                        .padding(8)
                }
                .padding(6)
            }
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 6) {
                // Tap the name to choose from built-ins, your beats or a list.
                Button { pickerOpen = true } label: {
                    HStack(spacing: 6) {
                        Text(transport.beatName ?? String(localized: "No beat", bundle: .strings))
                            .font(.system(size: 30, weight: .semibold, design: .serif))
                            .foregroundStyle(Palette.syahi)
                        Image(systemName: "chevron.down").font(.callout).foregroundStyle(Palette.syahiSoft)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Choose a beat", bundle: .strings))
                // Tap the tempo to type it, or jump it by ten (TempoSheet).
                Button { tempoOpen = true } label: {
                    HStack(spacing: 4) {
                        Text("\(transport.bpm) bpm")
                            .monospacedDigit()
                        Image(systemName: "pencil").font(.caption)
                    }
                    .font(.callout)
                    .foregroundStyle(Palette.syahiSoft)
                }
                .buttonStyle(.plain)
                .disabled(transport.settings.tempoLocked)
                .accessibilityLabel(String(localized: "Set tempo", bundle: .strings))
            }
            Spacer()
        }
    }

    @ViewBuilder
    private var playButton: some View {
        let enabled = transport.ready && !transport.outputFailed
        Button {
            transport.togglePlay(beat)
        } label: {
            Image(systemName: transport.playing ? "pause.circle.fill" : "play.circle.fill")
                .font(.system(size: 96))
                .foregroundStyle(enabled ? Palette.clay : Palette.faint)
        }
        .disabled(!enabled)

        if !transport.ready {
            Text(String(localized: "Loading sounds…", bundle: .strings))
                .font(.footnote)
                .foregroundStyle(Palette.syahiSoft)
        } else if transport.outputFailed {
            Text(String(localized: "Audio output unavailable", bundle: .strings))
                .font(.footnote)
                .foregroundStyle(Palette.danger)
        }
    }

    private var tempo: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                BpmSlider(
                    bpm: transport.bpm,
                    brahmaloka: transport.brahmaloka,
                    enabled: !transport.settings.tempoLocked,
                    onChange: { transport.changeBpm($0) },
                    onUnlock: { transport.unlockBrahmaloka() },
                )
                if transport.brahmaloka {
                    BrahmalokaBadge { transport.leaveBrahmaloka() }
                }
            }
            tempoButtons
        }
    }

    private var tempoButtons: some View {
        HStack(spacing: 16) {
            Button { transport.nudgeBpm(-2) } label: { minus }
            Button { transport.nudgeBpm(2) } label: { plus }
            Button {
                transport.tapTempo()
            } label: {
                Label("Tap", systemImage: "hand.tap")
                    .font(.footnote.weight(.medium))
            }
        }
    }

    private var minus: some View {
        Image(systemName: "minus")
            .frame(width: 44, height: 44)
            .background(Palette.headWorn, in: Circle())
            .foregroundStyle(Palette.syahi)
    }

    private var plus: some View {
        Image(systemName: "plus")
            .frame(width: 44, height: 44)
            .background(Palette.headWorn, in: Circle())
            .foregroundStyle(Palette.syahi)
    }

    private func beatForTransport() -> Beat {
        // The selected beat, or the default. A fresh Home has already loadBeat'd a
        // beat in the container's begin(), so this is a fallback.
        transport.loadedBeat
            ?? container.library.allBeats.first
            ?? container.library.shippedBeats.defaultBeat
    }
}
