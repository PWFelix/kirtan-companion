import AVFoundation

/// Which output the drum is playing through, as far as display sync cares.
///
/// The display delay is stored PER ROUTE, because a Bluetooth speaker and a pair
/// of earbuds lag by different amounts while the phone speaker barely lags at
/// all. [key] is what the delay is stored under. It matches the Android port's
/// keys (`speaker`, `wired`, `bt:<name>`), though delays are not synced between
/// devices: they belong to the hardware pairing, not the person.
struct OutputRoute: Equatable {
    let key: String
    /// The device's own name ("JBL Flip 5"), or "" for outputs we name ourselves.
    let name: String
    let bluetooth: Bool

    init(key: String, label name: String, bluetooth: Bool) {
        self.key = key
        self.name = name
        self.bluetooth = bluetooth
    }

    /// What to call it on screen. Resolved on each read, not stored: routes outlive
    /// a language switch in Settings, and a stored "Phone speaker" would stay English.
    var label: String {
        if !name.isEmpty { return name }
        return key == "wired" ? String(localized: "Headphones", bundle: .strings)
                              : String(localized: "Phone speaker", bundle: .strings)
    }

    static let speaker = OutputRoute(key: "speaker", label: "", bluetooth: false)

    /// Bluetooth ports, by name. A2DP is the usual music link; LE is newer
    /// hearing aids and LE Audio earbuds; HFP is a headset that only offered
    /// the call profile.
    private static let bluetoothPorts: Set<AVAudioSession.Port> = [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP]
    private static let wiredPorts: Set<AVAudioSession.Port> = [.headphones, .usbAudio, .lineOut]

    /// Classify a session port. Pure, so it is unit-tested without a session.
    static func from(portType: AVAudioSession.Port, portName: String) -> OutputRoute {
        if bluetoothPorts.contains(portType) {
            let name = portName.isEmpty ? "Bluetooth" : portName
            return OutputRoute(key: "bt:\(name)", label: name, bluetooth: true)
        }
        if wiredPorts.contains(portType) {
            return OutputRoute(key: "wired", label: "", bluetooth: false)
        }
        if portType == .airPlay {
            let name = portName.isEmpty ? "AirPlay" : portName
            // AirPlay lags like Bluetooth (worse, often) and wants the same prompt.
            return OutputRoute(key: "airplay:\(name)", label: name, bluetooth: true)
        }
        return .speaker
    }

    /// The route the session is using now.
    static func current(_ session: AVAudioSession = .sharedInstance()) -> OutputRoute {
        guard let port = session.currentRoute.outputs.first else { return .speaker }
        return from(portType: port.portType, portName: port.portName)
    }
}
