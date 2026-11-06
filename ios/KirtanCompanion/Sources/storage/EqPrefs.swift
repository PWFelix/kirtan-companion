import Foundation

// Persistence for the mixer and the notation display — the deliberately-NOT-a-provider path.
//
// Ported from storage/EqPrefs.kt, which ports src/storage/eqPrefs.js. On iOS it shares the
// one KirtanStore file with the beat library (see KirtanStore's header) — the asymmetry
// lives in behaviour, not file count: the beat provider throws and EqPrefs never does.
//
// ── THE POSTURE IS "NEVER THROW" ──
// Reading fails whenever the store is unavailable, and decoding fails whenever something
// unexpected lands under the key — a future schema change, a backup restore from an older
// build. A corrupt mixer setting must degrade to FLAT, not brick the transport on launch,
// so every failure path arrives at EqPrefsSnapshot.default. By the same reasoning a failed
// SAVE is only a log line: the sliders keep working for this session, the device just won't
// remember. This is deliberately NOT the beat provider's "errors are the point" stance: a
// lost EQ setting is re-set in seconds, a lost beat is not.
//
// ── THE SHAPE CARRIES DAYAN AND BAYAN ONLY ──
// Kartal is the mixer's third lane — faders and a mute, but no equaliser and no tuning — so
// it gets no EndSoundPrefs, and the snapshot names the two ends as FIELDS rather than
// holding a map, which makes "do not add a kartal EQ" structural instead of a comment.
//
// ── SANITISE ON LOAD ──
// Every band is clamped to the shared dB range and every tuning offset to the shared cents
// range, reading the SAME bounds the engine and the sliders use, so a hand-edited or stale
// value cannot push the DSP out of range through launch hydration.

/// One mridanga end's sound-shaping settings, and its two panels' disclosure state.
struct EndSoundPrefs: Equatable {
    /// EQ_BAND_COUNT gains in dB, in the shared band order.
    var bands: [Double]
    /// The tuning offset in cents, within the shared range.
    var tuneCents: Int
    /// Whether the EQ panel is open. Cosmetic, but worth remembering.
    var eqPanelOpen: Bool
    /// Whether the tuning panel is open.
    var tunePanelOpen: Bool

    /// Flat, centred, closed.
    static let `default` = EndSoundPrefs(
        bands: flatEqBands(),
        tuneCents: 0,
        eqPanelOpen: false,
        tunePanelOpen: false,
    )
}

/// Everything the mixer and the notation display remember.
///
/// Immutable, and the unit both EqPrefs.load returns and EqPrefs.save takes — copy one
/// field and save the result, rather than mutating something shared.
struct EqPrefsSnapshot: Equatable {
    var masterVolume: Double
    /// Per-lane faders, 0…1. A lane absent from the map reads as 1.
    var endVolumes: [LaneId: Double]
    /// Lanes currently muted. Kept apart from endVolumes so unmuting restores the fader.
    var mutedEnds: Set<LaneId>
    /// Whether the tempo controls are locked against an accidental drag.
    var tempoLocked: Bool
    /// Whether the strip prints the bol syllable under each mark (Settings toggle).
    var showBols: Bool
    /// Whether the strip prints the guided step labels above it — on by default.
    var showStepLabels: Bool
    /// Interface scale: a multiplier applied to every point in the app. See the UI layer.
    var uiScale: Double
    /// How much human variation the drum plays with (Mixer → Feel). Off on a fresh install.
    var feel: Feel = .off
    /// The Learn highway mirrored for a left-handed player (dayan columns on the left).
    var learnLeftHanded: Bool = false
    var dayan: EndSoundPrefs
    var bayan: EndSoundPrefs

    /// A lane's EQ/tuning settings, or nil for a lane that has none (kartal).
    func end(_ lane: LaneId) -> EndSoundPrefs? {
        switch lane {
        case .dayan: return dayan
        case .bayan: return bayan
        case .kartal: return nil
        }
    }

    /// A lane's fader, defaulting to unity — the engine's own default.
    func volume(_ lane: LaneId) -> Double { endVolumes[lane] ?? 1 }

    func isMuted(_ lane: LaneId) -> Bool { mutedEnds.contains(lane) }

    static let defaultMasterVolume = 0.9

    /// Bounds for uiScale — wide enough to matter at both ends, narrow enough that no
    /// preset can push a layout into a degenerate state.
    static let uiScaleMin = 0.75
    static let uiScaleMax = 1.5

    /// The choices Settings offers. Discrete on purpose: a scale is a per-device decision
    /// made once, presets are sanity-checkable, and a slider would invite values nobody
    /// has ever seen laid out.
    static let uiScalePresets: [Double] = [0.85, 1.0, 1.15, 1.3]

    /// The state a fresh install, and every failure path, arrives at.
    static let `default` = EqPrefsSnapshot(
        masterVolume: defaultMasterVolume,
        endVolumes: [:],
        mutedEnds: [],
        tempoLocked: false,
        showBols: false,
        showStepLabels: true,
        uiScale: 1.0,
        dayan: .default,
        bayan: .default,
    )
}

/// Persistence for the snapshot above. Never throws — see the header.
final class EqPrefs {

    /// The idle period a caller should coalesce writes over. React state and the DSP still
    /// update immediately, only the write waits. A slider drag fires many changes a second
    /// and a disk write per event would jank it, so the caller debounces on this.
    static let saveDebounceMs = 250

    private let store: KirtanStore
    private let log: StorageLog

    init(store: KirtanStore, log: StorageLog = OSStorageLog()) {
        self.store = store
        self.log = log
    }

    /// The settings as one snapshot, read ONCE at launch and hydrated into the transport.
    /// Two reads double the disk access and can disagree if the store changed between them.
    func load() async -> EqPrefsSnapshot {
        // decode never throws, so nothing here can: a store that refuses to read hands back
        // its missing key, and the codec turns that into the default snapshot.
        return await store.read { EqPrefsCodec.decode($0.string(KirtanStore.Keys.mixerPrefs)) }
    }

    /// Write the WHOLE snapshot. Whole-object rather than per-field, because a per-field
    /// write computed from a stale snapshot resurrects the value the user changed a moment
    /// ago. Failures are logged, never thrown — a slider change must not take the mixer down.
    func save(_ snapshot: EqPrefsSnapshot) async {
        do {
            try await store.perform { ctx in
                ctx.setString(KirtanStore.Keys.mixerPrefs, EqPrefsCodec.encode(snapshot))
            }
        } catch {
            log.warn("[storage] mixer settings could not be saved", error)
        }
    }
}

// The snapshot's JSON, and the sanitiser that reads it back.
//
// Pure and never-throwing, so the "a poisoned value cannot reach the DSP" rule can be
// tested without a disk or a store. The per-end field names — bands, tune, open, tuneOpen
// — are the web eqPrefs.js blob's, so a settings file carried across still reads.
enum EqPrefsCodec {

    private static let keyVolume = "volume"
    private static let keyEndVolumes = "endVolumes"
    private static let keyMutedEnds = "mutedEnds"
    private static let keyTempoLocked = "tempoLocked"
    private static let keyShowBols = "showBols"
    private static let keyShowStepLabels = "showStepLabels"
    private static let keyUiScale = "uiScale"
    private static let keyFeel = "feel"
    private static let keyLearnLeftHanded = "learnLeftHanded"
    private static let keyDayan = "dayan"
    private static let keyBayan = "bayan"

    static func encode(_ snapshot: EqPrefsSnapshot) -> String {
        var endVolumes: [JPair] = []
        for lane in LaneId.ordered {
            if let volume = snapshot.endVolumes[lane] {
                endVolumes.append(JPair(key: lane.wireId, value: .number(volume)))
            }
        }
        let mutedEnds = JValue.arr(
            LaneId.ordered.filter(snapshot.mutedEnds.contains).map { .string($0.wireId) }
        )
        return JValue.obj(
            (keyVolume, .number(snapshot.masterVolume)),
            (keyEndVolumes, .object(endVolumes)),
            (keyMutedEnds, mutedEnds),
            (keyTempoLocked, .bool(snapshot.tempoLocked)),
            (keyShowBols, .bool(snapshot.showBols)),
            (keyShowStepLabels, .bool(snapshot.showStepLabels)),
            (keyUiScale, .number(snapshot.uiScale)),
            (keyFeel, .string(snapshot.feel.rawValue)),
            (keyLearnLeftHanded, .bool(snapshot.learnLeftHanded)),
            (keyDayan, encodeEnd(snapshot.dayan)),
            (keyBayan, encodeEnd(snapshot.bayan)),
        ).write()
    }

    private static func encodeEnd(_ end: EndSoundPrefs) -> JValue {
        .obj(
            ("bands", .arr(end.bands.map { .number($0) })),
            ("tune", .int(end.tuneCents)),
            ("open", .bool(end.eqPanelOpen)),
            ("tuneOpen", .bool(end.tunePanelOpen)),
        )
    }

    /// The snapshot held in raw, or EqPrefsSnapshot.default for anything at all that is
    /// wrong with it. Per-FIELD fallback rather than all-or-nothing: a half-written blob
    /// still yields a complete, valid snapshot, with the damaged fields at their defaults.
    static func decode(_ raw: String?) -> EqPrefsSnapshot {
        guard let raw else { return .default }
        guard let parsed = JsonEl.parse(raw), case .object = parsed else { return .default }
        let obj = parsed
        return EqPrefsSnapshot(
            masterVolume: volumeOf(obj.element(keyVolume), EqPrefsSnapshot.defaultMasterVolume),
            endVolumes: endVolumesOf(obj.element(keyEndVolumes)),
            mutedEnds: mutedEndsOf(obj.element(keyMutedEnds)),
            tempoLocked: boolOf(obj.element(keyTempoLocked), false),
            showBols: boolOf(obj.element(keyShowBols), false),
            showStepLabels: boolOf(obj.element(keyShowStepLabels), true),
            uiScale: scaleOf(obj.element(keyUiScale)),
            feel: obj.element(keyFeel)?.asString().flatMap(Feel.init(rawValue:)) ?? .off,
            learnLeftHanded: boolOf(obj.element(keyLearnLeftHanded), false),
            dayan: endOf(obj.element(keyDayan)),
            bayan: endOf(obj.element(keyBayan)),
        )
    }

    private static func endOf(_ element: JValue?) -> EndSoundPrefs {
        guard let obj = element, case .object = obj else { return .default }
        return EndSoundPrefs(
            bands: bandsOf(obj.element("bands")),
            tuneCents: tuneOf(obj.element("tune")),
            eqPanelOpen: boolOf(obj.element("open"), false),
            tunePanelOpen: boolOf(obj.element("tuneOpen"), false),
        )
    }

    /// Exactly EQ_BAND_COUNT gains, each coerced to a finite number and clamped into the
    /// shared dB range. A short array pads with 0 dB and a long one is truncated, so the
    /// engine's five-filter chain always gets five values.
    private static func bandsOf(_ element: JValue?) -> [Double] {
        let array = element?.asArray() ?? []
        return (0..<EQ_BAND_COUNT).map { index in
            guard index < array.count, let value = array[index].asNumber(), value.isFinite else {
                return 0   // a short or poisoned array flattens to 0 dB, matching the web's bandOf
            }
            return clampEqDb(value)
        }
    }

    private static func tuneOf(_ element: JValue?) -> Int {
        let raw = element?.asNumber()
        let cents = (raw?.isFinite == true ? Int(raw!.rounded()) : 0)
        return clampCents(cents)
    }

    private static func volumeOf(_ element: JValue?, _ fallback: Double) -> Double {
        let raw = element?.asNumber()
        guard let raw, raw.isFinite else { return fallback }
        return min(max(raw, 0), 1)
    }

    private static func endVolumesOf(_ element: JValue?) -> [LaneId: Double] {
        guard let obj = element, case .object = obj else { return [:] }
        var volumes: [LaneId: Double] = [:]
        // Iterated by the KNOWN lanes, so an invented key cannot introduce a lane the
        // engine has no channel for.
        for lane in LaneId.ordered {
            if let raw = obj.element(lane.wireId)?.asNumber(), raw.isFinite {
                volumes[lane] = min(max(raw, 0), 1)
            }
        }
        return volumes
    }

    private static func mutedEndsOf(_ element: JValue?) -> Set<LaneId> {
        guard let array = element?.asArray() else { return [] }
        var muted: Set<LaneId> = []
        for item in array {
            if let wireId = item.asString(), let lane = LaneId.fromWire(wireId) {
                muted.insert(lane)
            }
        }
        return muted
    }

    private static func boolOf(_ element: JValue?, _ fallback: Bool) -> Bool {
        element?.asBool() ?? fallback
    }

    private static func scaleOf(_ element: JValue?) -> Double {
        let raw = element?.asNumber()
        guard let raw, raw.isFinite else { return 1.0 }
        return min(max(raw, EqPrefsSnapshot.uiScaleMin), EqPrefsSnapshot.uiScaleMax)
    }
}
