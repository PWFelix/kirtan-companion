import Foundation
import Observation

/// The one object that builds the whole object graph and holds it together: the
/// store, the library, the engine, playback, and the transport view model.
///
/// Views never construct these — they receive the pieces from here. A single
/// process-scoped `@MainActor` object so the app has exactly one engine, one
/// store and one transport for its lifetime (a rotation re-creates views, never
/// these).
@MainActor
final class AppContainer {

    static let shared = AppContainer()

    let store: KirtanStore
    let prefs: EqPrefs
    let library: LibraryRepository
    let engine: KirtanEngine
    let playback: PlaybackController
    let transport: TransportViewModel

    // The cloud: nil in a build compiled without a project, in which case every cloud
    // affordance hides itself. Kept for the lifetime of the process, next to the local store
    // it is a sibling of.
    let supabase: SupabaseClient?
    let community: CommunityClient?
    /// The served built-in set and the maintainer writes. Nil without a cloud.
    let shippedBeats: ShippedBeatsClient?
    /// The observable account slice views read; `supabase` is the engine underneath.
    let auth: AuthViewModel
    private let localProvider: BeatsProvider

    private init() {
        let store = KirtanStore()
        let prefs = EqPrefs(store: store)
        let localProvider = LocalBeatsProvider(store: store)
        let library = LibraryRepository(initialProvider: localProvider)
        let engine = KirtanEngine(sampleRate: AudioDeviceConfig.sampleRate, loader: BundleSampleLoader())
        let playback = PlaybackController(engine: engine)
        let transport = TransportViewModel(engine: engine, playback: playback, prefs: prefs)

        let supabase = SupabaseClient.fromBundle(store: store)
        let community = supabase.map { CommunityClient(client: $0) }
        let auth = AuthViewModel(configured: supabase != nil)
        let shippedBeats = supabase.map { ShippedBeatsClient(client: $0, store: store) }
        // The cached set goes in before anything reads the built-ins, so a returning user
        // never sees the compiled set disagree with their playlists for a moment.
        shippedBeats?.loadCached()

        self.store = store
        self.prefs = prefs
        self.library = library
        self.localProvider = localProvider
        self.engine = engine
        self.playback = playback
        self.transport = transport
        self.supabase = supabase
        self.community = community
        self.auth = auth
        self.shippedBeats = shippedBeats
    }

    /// The splash's Begin: decode the samples once, open the output and arm, hydrate
    /// the persisted mixer, and point the transport at a beat so Play has something
    /// to play. Idempotent in its effects (loadSounds guards on `isReady`).
    func begin() async {
        await transport.begin()
        await transport.loadSettings()
        if transport.beatName == nil {
            let beat = library.allBeats.first ?? ShippedBeats.shared.defaultBeat
            transport.loadBeat(beat)
        }
        // Restore the session and re-point the library at the cloud if there is one. Done as a
        // background task, never gating the splash: a slow or absent network must not hold up
        // the moment the user has said they want sound.
        if supabase != nil {
            Task { await self.restoreCloud() }
            Task { await self.shippedBeats?.checkForUpdates(manual: false) }
        }
    }

    // ── Cloud / auth ─────────────────────────────────────────────────────────
    // Views call these and then re-read `account`; the library re-points itself.

    /// Whether the cloud is compiled into this build at all. UI hides cloud affordances when
    /// false — the degraded, no-project mode.
    var cloudConfigured: Bool { auth.configured }

    /// The signed-in account, or nil.
    var account: AuthSession? { auth.session }

    /// Restore the stored session and point the library at the cloud if there is one.
    func restoreCloud() async {
        guard let supabase else { return }
        await supabase.restore()
        // A RESTORED session is not a fresh sign-in: no migration offer on every cold start.
        await syncProviderToSession(freshSignIn: false)
    }

    /// The OAuth round trip came back through the `kirtan://` deep link. Finish it and
    /// re-point the library. A failure is thrown to the caller, not swallowed here.
    func handleAuthCallback(_ url: URL) async throws {
        guard let supabase else { return }
        let wasSignedIn = supabase.session != nil
        _ = try await supabase.completeOAuth(callbackUrl: url.absoluteString)
        await syncProviderToSession(freshSignIn: !wasSignedIn)
    }

    /// The URL to open in an in-app browser to begin an OAuth sign-in.
    func beginSignIn() async -> String? {
        await supabase?.beginOAuth()
    }

    func signOut() async {
        await supabase?.signOut()
        await syncProviderToSession(freshSignIn: false)
    }

    // The library follows the session: cloud beats for a signed-in user, the device store for
    // everyone else. `attachProvider` reloads from the new store.
    //
    // A FRESH sign-in captures the device library BEFORE the swap — afterwards it is
    // invisible — and, when the account is empty, offers to move it up (MigrationSheet).
    // Only offered into an empty account: migration mints fresh ids, so pushing into one
    // that already holds beats would duplicate them.
    private func syncProviderToSession(freshSignIn: Bool) async {
        auth.setSession(supabase?.session)
        auth.pendingMigration = nil
        if let session = supabase?.session, let cloud = SupabaseBeatsProvider.create(client: supabase, session: session) {
            let local = freshSignIn ? (try? await localProvider.loadAll()) : nil
            library.attachProvider(cloud)
            if let local, !(local.beats.isEmpty && local.categories.isEmpty) {
                await library.reload()
                if library.state.error == nil && library.state.customBeats.isEmpty && library.state.categories.isEmpty {
                    auth.pendingMigration = local
                }
            }
        } else {
            library.attachProvider(localProvider)
        }
        auth.isMaintainer = auth.session != nil ? (await shippedBeats?.isMaintainer() ?? false) : false
    }

    /// Write a confirmed import and say what happened. The ONE door for pasted codes and
    /// deep links alike, so the two cannot drift in how they mint and de-duplicate.
    func acceptImport(_ payload: ShareCodec.SharePayload) async {
        let result = await library.importShared(payload)
        if result.beats.isEmpty {
            auth.notice = library.state.error ?? String(localized: "Nothing was imported.", bundle: .strings)
        } else if result.categoryId != nil {
            auth.notice = String(localized: "Added \(result.beats.count) beats as a new list.", bundle: .strings)
        } else {
            auth.notice = String(localized: "Added “\(result.beats[0].name)” to Your beats.", bundle: .strings)
        }
    }

    /// The migration sheet's "Move them": copy the captured device library up.
    func acceptMigration() async -> MigrationResult? {
        guard let local = auth.pendingMigration else { return nil }
        let result = await library.importLibrary(local)
        auth.pendingMigration = nil
        auth.notice = result.failed
            ? (library.state.error ?? String(localized: "Your beats could not be moved. They are still on this device.", bundle: .strings))
            : String(localized: "Moved \(result.beats) beats to your account.", bundle: .strings)
        return result
    }

    /// Tear the output down. The engine and its device are left in a clean stopped
    /// state; the samples stay decoded.
    func release() {
        playback.release()
    }
}
