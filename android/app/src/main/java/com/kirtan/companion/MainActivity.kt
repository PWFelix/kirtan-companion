package com.kirtan.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirtan.companion.data.ShareIntake
import com.kirtan.companion.data.shareTextFrom
import com.kirtan.companion.ui.KirtanApp
import com.kirtan.companion.ui.library.LibraryViewModel
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.transport.TransportViewModel
import kotlinx.coroutines.launch

/**
 * The single activity.
 *
 * Its only real job beyond hosting Compose is INBOUND LINKS, and the reason that
 * lives here rather than in a ViewModel is the platform's intent lifecycle:
 *
 *  - `launchMode="singleTask"` (see the manifest) means re-tapping a link while
 *    the app is already open delivers `onNewIntent`, NOT a new activity. Handle it
 *    only in `onCreate` and the second tap does nothing.
 *  - Both paths hand the text to [ShareIntake], which decodes it and holds it for
 *    exactly one collection. That consume-once rule is what stops a poisoned link
 *    from re-triggering on every launch — the Android equivalent of the web app
 *    clearing `location.hash` BEFORE it decodes.
 *
 * The activity deliberately does NOT own the audio. Playback lives in
 * [com.kirtan.companion.playback.PlaybackService] so that swiping the app away,
 * or rotating the phone, does not stop a kirtan.
 */
class MainActivity : ComponentActivity() {

    /**
     * Whether the bars should currently be hidden, so [onWindowFocusChanged] can
     * re-apply it after a system event cleared it. Without remembering intent we
     * could not tell "the system revealed the bars" from "we never hid them",
     * which is the splash state.
     */
    private var immersiveWanted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // EDGE-TO-EDGE. The default leaves the system status and navigation bars
        // as opaque bands the app cannot draw into, which on a short phone reads
        // as dead whitespace above and below the content — most visibly under the
        // bottom nav. `enableEdgeToEdge` lets the window draw behind them and is
        // the supported replacement for the deprecated statusBarColor /
        // navigationBarColor setters. Combined with immersive mode (below) the
        // whole canvas is ours; the splash pads by the insets while the bars are
        // visible there.
        enableEdgeToEdge()

        offerShareLink(intent)
        offerAuthCallback(intent)

        val container = (application as KirtanApplication).container

        setContent {
            KirtanTheme {
                val transport: TransportViewModel = viewModel(
                    factory = TransportViewModel.factory(container.eqPrefs),
                )
                val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)

                // ── Whole-interface scale ──────────────────────────────────
                // Every dp→px conversion in Compose passes through LocalDensity,
                // so providing a scaled Density HERE resizes the entire interface
                // — strip, transport, sheets, dialogs, nav, type — in proportion,
                // from the one persisted value in Settings → Interface size.
                // Applied at the root rather than per screen so no screen can
                // forget it, and so a sheet opened from a scaled screen is scaled
                // too. See EqPrefsSnapshot.uiScale.
                val transportState by transport.state.collectAsState()
                val uiScale = transportState.settings.uiScale
                val baseDensity = LocalDensity.current
                val scaledDensity = remember(baseDensity, uiScale) {
                    Density(baseDensity.density * uiScale, baseDensity.fontScale)
                }

                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    KirtanApp(
                        transport = transport,
                        library = library,
                        onImmersiveChange = ::applyImmersive,
                    )
                }
            }
        }
    }

    /**
     * Hide or reveal the system bars.
     *
     * STICKY-with-transient-reveal, the standard media-app pattern: while hidden,
     * a swipe from any screen edge brings the bars back as an overlay and they
     * retire on their own, so Back/Home/Recents stay one gesture away without
     * costing a permanent ~46dp band — on this phone that band is a whole row of
     * the beat strip.
     *
     * Deliberately NOT tied to playback state. Hiding only while playing would
     * make the layout jump every time the transport starts or stops, and would
     * leave the bars visible during the exact browsing (mixer, library) where the
     * space is wanted just as much. The splash keeps them visible — see KirtanApp.
     *
     * Named `applyImmersive` rather than `setImmersive`: `Activity` grew its own
     * `setImmersive(boolean)` in API 30, and shadowing a platform member with a
     * different meaning is how you get a call site that silently does the wrong
     * thing.
     */
    private fun applyImmersive(immersive: Boolean) {
        immersiveWanted = immersive
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Re-hide the bars whenever the window regains focus.
     *
     * Immersive is best-effort and the system clears it on events we do not
     * control — a permission dialog, the IME, an incoming-call banner, an
     * accessibility service. Without this, one such event leaves the bars up for
     * the rest of the session and the user has to discover that restarting the
     * app fixes it. Re-applying on focus regain is the standard remedy and costs
     * nothing when the bars are already hidden.
     *
     * It does NOT fight a deliberate edge swipe: that reveal happens while we
     * hold focus, so this never fires for it, and the sticky behaviour retires
     * the bars on its own.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveWanted) applyImmersive(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Also update the activity's intent, or a later configuration change
        // would replay the ORIGINAL one and re-import the same beat.
        setIntent(intent)
        offerShareLink(intent)
        offerAuthCallback(intent)
    }

    private fun offerShareLink(intent: Intent?) {
        shareTextFrom(intent)?.let { ShareIntake.offer(it) }
    }

    /**
     * Hand an OAuth redirect to the client, exactly once.
     *
     * Same one-chance rule as the share link: the callback carries the
     * authorization code, and a replayed intent (configuration change, task
     * relaunch) would attempt the exchange twice — the second attempt fails and
     * would surface as a spurious sign-in error. Clearing the activity's intent
     * after consuming it is what makes the replay impossible.
     */
    private fun offerAuthCallback(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (!url.startsWith(AUTH_CALLBACK_PREFIX)) return
        val client = (application as KirtanApplication).container.supabase ?: return

        setIntent(Intent())
        (application as KirtanApplication).container.scope.launch {
            // A failed exchange (expired code, clock skew beyond the retry) must
            // not take the app down; the user simply stays signed out.
            runCatching { client.completeOAuth(url) }
        }
    }

    companion object {
        private const val AUTH_CALLBACK_PREFIX = "kirtan://auth/callback"
    }
}
