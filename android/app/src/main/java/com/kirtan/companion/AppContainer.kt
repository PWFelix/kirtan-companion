package com.kirtan.companion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kirtan.companion.data.model.Library
import com.kirtan.companion.engine.KirtanEngine
import com.kirtan.companion.playback.AudioDeviceInfo
import com.kirtan.companion.storage.BeatsProvider
import com.kirtan.companion.storage.CommunityClient
import com.kirtan.companion.storage.EqPrefs
import com.kirtan.companion.storage.LibraryRepository
import com.kirtan.companion.storage.LocalBeatsProvider
import com.kirtan.companion.storage.SupabaseBeatsProvider
import com.kirtan.companion.storage.SupabaseClient
import com.kirtan.companion.storage.kirtanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The composition root — every object the app needs, created once.
 *
 * This stands in for a dependency-injection framework. The app has exactly one
 * graph and no scoping subtleties, so a plain class is both smaller and easier
 * to follow than generated code.
 *
 * ── WHY THE ENGINE LIVES HERE AND NOT IN THE SERVICE ──
 * [KirtanEngine] is process-scoped, but its AUDIO DEVICE is service-scoped, and
 * the distinction is the whole design:
 *
 *   - The engine holds ~2.7 MB of decoded samples. Recreating it per service
 *     start would mean re-decoding eleven recordings on every play, which is
 *     tens of milliseconds of work on the exact path the user is waiting on.
 *   - The AudioTrack is a native resource that must be released, and the
 *     component that knows when playback has truly ended is the service. So
 *     [PlaybackService] calls `arm()` on play and `release()` on destroy.
 *
 * An engine that outlives its AudioTrack is how you leak a native audio buffer;
 * an AudioTrack that outlives the engine is how you get silence. Splitting
 * ownership this way keeps exactly one owner for each.
 *
 * The UI reads the engine directly for the playhead rather than going through
 * the media controller, because bar phase is read once per frame at 60 Hz and
 * cannot survive a round trip through IPC. Transport commands (play, pause) go
 * through the controller so the notification and the UI can never disagree.
 */
class AppContainer(private val appContext: Context) {

    /**
     * Process-wide scope for work that must outlive any single screen — sample
     * decoding and library loading. Not `viewModelScope`, because both of those
     * need to survive the activity being recreated mid-load, which on Android
     * happens on every rotation.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The output rate everything is decoded and rendered at. */
    val sampleRate: Int by lazy { AudioDeviceInfo.sampleRate(appContext) }

    /**
     * The one DataStore for the whole process.
     *
     * Exposed as a single instance on purpose: DataStore throws if two instances
     * are opened over the same file, so every consumer — beats, prefs, the auth
     * session — must share this one rather than each building its own.
     */
    val store: DataStore<Preferences> by lazy { appContext.kirtanStore }

    val engine: KirtanEngine by lazy { KirtanEngine(appContext.assets, sampleRate) }

    /** The on-device library. The default provider; the cloud swaps in over it. */
    val localBeats: BeatsProvider by lazy { LocalBeatsProvider(store) }

    /**
     * The cloud client, or null when `SUPABASE_URL` is blank.
     *
     * Null IS the feature: an unconfigured build offers no cloud at all and runs
     * entirely on device storage, exactly like the web build with no
     * `VITE_SUPABASE_*`. Nothing downstream needs a null check beyond "is it
     * there", because [library] falls back to [localBeats] when it isn't.
     */
    val supabase: SupabaseClient? by lazy { SupabaseClient.fromBuildConfig(store) }

    val community: CommunityClient? by lazy { supabase?.let { CommunityClient(it) } }

    /**
     * The mixer's persisted settings.
     *
     * Separate from [library] on purpose, and the separation is load-bearing:
     * a beat is the user's WORK, so a failed save must surface; a mixer position
     * is a PREFERENCE, so [EqPrefs] never throws and quietly falls back to
     * defaults. One error posture cannot serve both.
     */
    val eqPrefs: EqPrefs by lazy { EqPrefs(store) }

    /**
     * Everything the user has saved, plus the compiled-in beats merged above it.
     * Given the process [scope] so an in-flight optimistic write survives a
     * rotation instead of being cancelled halfway and leaving the store and the
     * UI disagreeing.
     */
    val library: LibraryRepository by lazy { LibraryRepository(scope, localBeats) }

    /**
     * The device library captured at the moment of a FRESH sign-in, waiting for
     * the user to decide whether it moves to their account.
     *
     * Captured BEFORE the provider swaps, because once the repository reads the
     * cloud the device copy is invisible to it — and with it, any offer to migrate
     * it. Null means nothing is pending.
     */
    val pendingMigration = MutableStateFlow<Library?>(null)

    init {
        // Follow the auth session: signed in, the library becomes the cloud one;
        // signed out (or never signed in), it is the local one. Collecting rather
        // than checking once means signing out mid-session drops back to device
        // storage without a restart.
        scope.launch {
            val client = supabase ?: return@launch
            client.restore()
            // Seeded from the RESTORED session so a returning user does not get a
            // migration offer on every cold start: only a transition from signed
            // out to signed in counts as a fresh sign-in.
            var wasSignedIn = client.session.value != null

            client.session.collect { session ->
                val signedIn = session != null
                val freshSignIn = signedIn && !wasSignedIn
                wasSignedIn = signedIn

                if (freshSignIn) {
                    val local = with(library.state.value) {
                        Library(customBeats, categories, activeCategoryId)
                    }
                    library.attachProvider(
                        SupabaseBeatsProvider.create(client, session) ?: localBeats
                    )
                    library.reload()
                    // Only offer when the account is empty: pushing into an
                    // account that already holds beats would duplicate them,
                    // because migration mints fresh ids by design.
                    val cloudEmpty = with(library.state.value) {
                        customBeats.isEmpty() && categories.isEmpty()
                    }
                    val worthMoving = local.beats.isNotEmpty() || local.categories.isNotEmpty()
                    if (cloudEmpty && worthMoving) pendingMigration.value = local
                } else {
                    library.attachProvider(
                        SupabaseBeatsProvider.create(client, session) ?: localBeats
                    )
                }
            }
        }
    }

    /**
     * The loaded beat's name, for the media notification's title.
     *
     * A flow rather than a direct call into the player because the two live on
     * opposite sides of a service boundary: the UI knows which beat was chosen,
     * and [KirtanPlayer] owns the notification. Routing it through here keeps the
     * UI from needing a bound controller just to rename a notification, and keeps
     * the service from needing to know the library exists.
     */
    val currentBeatName: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Decoding is idempotent-once: guarded by a flag rather than by the caller
     * remembering, because two screens want the samples ready (the splash's
     * Begin gate and the service's first play) and neither should have to know
     * whether the other got there first.
     */
    private val soundsLoaded = AtomicBoolean(false)

    /** Kick off sample decoding if it has not already started. */
    fun ensureSoundsLoaded() {
        if (!soundsLoaded.compareAndSet(false, true)) return
        scope.launch { engine.loadSounds() }
    }
}
