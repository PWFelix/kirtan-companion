import AVFoundation
import MediaPlayer

/// The playback session: the audio route's lifecycle, lock-screen and earbud
/// controls, and the pause-then-resume that an incoming phone call demands.
///
/// Android earns this with a `mediaPlayback` foreground service and a Media3
/// `MediaController`: the browser can hold no foreground service, and the drum
/// silently dies with the screen off, so the native port has to keep it alive.
/// iOS needs neither. A `.playback` audio session plus the `audio` background
/// mode keep the engine running with the screen off, and
/// `MPRemoteCommandCenter` IS the lock-screen control. So this is the one layer
/// that is genuinely SMALLER than its Android twin, and it carries no service.
///
/// The split from the transport is the Android one, unchanged: only the two
/// commands that decide whether sound is playing route through here; the mixer
/// (tempo, volume, EQ, tuning) talks to the engine directly. A slider drag
/// never crosses into this.
@MainActor
final class PlaybackController {

    private let engine: KirtanEngine
    private let session = AVAudioSession.sharedInstance()

    /// Whether the controller believes it should be playing. Drives every
    /// play/pause icon; the transport mirrors it into its state.
    private(set) var playWhenReady = false
    var onPlayWhenReadyChange: ((Bool) -> Void)?

    /// The output in use. Display sync keys its delay on this.
    private(set) var route = OutputRoute.speaker
    var onRouteChange: ((OutputRoute) -> Void)?

    /// True when an interruption (a call) stopped us, so the end of the call
    /// resumes a kirtan the user had deliberately started.
    private var resumeAfterInterruption = false

    private var beatTitle = "Kirtan"
    private var observers: [NSObjectProtocol] = []
    private var configured = false

    init(engine: KirtanEngine) {
        self.engine = engine
    }

    /// Wire the remote commands and the session observers. Idempotent.
    func connect() {
        configureSession()
        guard observers.isEmpty else { return }
        registerRemoteCommands()

        let center = NotificationCenter.default
        observers.append(
            center.addObserver(
                forName: AVAudioSession.interruptionNotification, object: session,
                queue: .main,
            ) { [weak self] note in
                Task { @MainActor in self?.handleInterruption(note) }
            }
        )
        observers.append(
            center.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: session,
                queue: .main,
            ) { [weak self] note in
                Task { @MainActor in self?.handleRouteChange(note) }
            }
        )
    }

    private func configureSession() {
        guard !configured else { return }
        configured = true
        do {
            // `.playback`: audible even with the ring/silent switch on, and it is
            // the category that earns the `audio` background mode.
            try session.setCategory(.playback, mode: .default)
        } catch {
            // A session we cannot configure still lets the engine run to the
            // current output; log and go on rather than bricking playback.
            #if DEBUG
            print("[playback] could not configure session: \(error)")
            #endif
        }
    }

    /// The splash's Begin tap: the user has said they want sound, so open the
    /// output and arm the engine (it renders silence until the clock starts).
    func begin() {
        configureSession()
        activateSession()
        _ = engine.arm()
    }

    /// Activate the session so the output route is live.
    private func activateSession() {
        do {
            // setActive is cheap and idempotent; calling it every play keeps the
            // route live after a call or a background/foreground transition.
            try session.setActive(true)
        } catch {
            #if DEBUG
            print("[playback] could not activate session: \(error)")
            #endif
        }
        refreshRoute()
    }

    /// Re-read the output and its latency. The latency only means anything once
    /// the session is active and routed, so this runs on activation and on every
    /// route change rather than once at launch.
    private func refreshRoute() {
        engine.setOutputLatency(seconds: session.outputLatency + session.ioBufferDuration)
        let next = OutputRoute.current(session)
        if next != route {
            route = next
            onRouteChange?(next)
        }
    }

    /// The single seam for play and pause — from the UI, a lock-screen control,
    /// or an earbud button. All of them end up here.
    func setPlayWhenReady(_ play: Bool) {
        guard play != playWhenReady else { return }
        if play {
            activateSession()
            // The device must be open before the clock starts, or the first block
            // renders into nothing. arm() is idempotent.
            guard engine.arm() else {
                publish(false)
                return
            }
            engine.start()
            publish(true)
        } else {
            engine.stop()
            publish(false)
        }
    }

    private func publish(_ playing: Bool) {
        playWhenReady = playing
        onPlayWhenReadyChange?(playing)
        updateNowPlaying()
    }

    /// Reflect a beat change into the lock-screen title without touching playback.
    func setBeatTitle(_ title: String) {
        beatTitle = title
        updateNowPlaying()
    }

    private func updateNowPlaying() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: beatTitle,
            MPMediaItemPropertyArtist: "Mrdanga Hero",
            MPNowPlayingInfoPropertyPlaybackRate: playWhenReady ? 1.0 : 0.0,
        ]
    }

    private func registerRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.setPlayWhenReady(true) }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.setPlayWhenReady(false) }
            return .success
        }
    }

    /// A call (or alarm) is stealing the output. Pause, and remember to resume a
    /// kirtan the user had started once the call ends.
    private func handleInterruption(_ note: Notification) {
        guard
            let typeRaw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: typeRaw)
        else { return }

        switch type {
        case .began:
            if playWhenReady {
                resumeAfterInterruption = true
                setPlayWhenReady(false)
            }
        case .ended:
            if resumeAfterInterruption {
                resumeAfterInterruption = false
                setPlayWhenReady(true)
            }
        @unknown default:
            break
        }
    }

    /// Headphones unplugged, Bluetooth dropped, and so on. The web app simply
    /// keeps playing on whatever output remains, and so does this. The one thing
    /// that does change is the latency, and with it the display delay: a speaker
    /// connecting mid-kirtan brings its own.
    private func handleRouteChange(_ note: Notification) {
        refreshRoute()
        #if DEBUG
        if let change = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
           let reason = AVAudioSession.RouteChangeReason(rawValue: change) {
            print("[playback] route changed: \(reason)")
        }
        #endif
    }

    /// Tear down. A pause here is not a focus loss: the next play resumes at the
    /// user's own level, and the engine is left in a clean stopped state.
    func release() {
        if playWhenReady { setPlayWhenReady(false) }
        try? session.setActive(false)
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }
}
