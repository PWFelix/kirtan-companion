package com.kirtan.companion.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.kirtan.companion.AppContainer
import com.kirtan.companion.KirtanApplication
import com.kirtan.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that owns playback.
 *
 * THIS IS THE REASON THE APP IS NATIVE. Everything else in the port could have
 * shipped as a WebView; this could not. A browser tab cannot hold a foreground
 * service, Chromium pauses JS when the page is hidden, and Android requires a
 * `mediaPlayback` foreground service for audio started from the background —
 * with violations failing SILENTLY. The primary user chants at morning arti with
 * the phone face-down and the screen off, so "the drum keeps playing" is not a
 * polish item, it is the product.
 *
 * [MediaSessionService] supplies the machinery: it promotes itself to a
 * foreground service while [KirtanPlayer] reports `playWhenReady`, builds the
 * media notification, wires lock-screen and headset transport controls, and
 * demotes itself again when playback stops. Implementing a custom [Player] over
 * our own mixer (rather than using ExoPlayer, which has no media to decode) gets
 * all of that for free — which is the whole reason [KirtanPlayer] exists.
 *
 * LIFECYCLE. The service is started by the UI when playback begins and stops
 * itself when the task is swiped away while paused. [onDestroy] releases the
 * audio device: the engine itself is process-scoped (see [AppContainer]) because
 * re-decoding eleven samples on every play would be wasted work, but the
 * AudioTrack is a native resource and this service is the component that knows
 * when playback has truly ended.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "kirtan_playback"

        /**
         * How far the drum drops when another app takes focus transiently.
         * A quarter level is enough to hear a navigation prompt over without
         * losing the beat — stopping outright would drop a congregation out of
         * time, which is the worse failure by a wide margin.
         */
        private const val DUCK_LEVEL = 0.25f
    }

    private var session: MediaSession? = null
    private lateinit var player: KirtanPlayer
    private lateinit var focus: AudioFocusHandler
    private var noisyReceiver: BroadcastReceiver? = null

    /**
     * Main-thread scope for work tied to this service's life.
     *
     * Main because [KirtanPlayer] is a [androidx.media3.common.SimpleBasePlayer],
     * which throws from any other thread; scoped here rather than on the container
     * so it dies with the service instead of outliving it.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container: AppContainer by lazy {
        (application as KirtanApplication).container
    }

    override fun onCreate() {
        super.onCreate()

        val engine = (application as KirtanApplication).container.engine

        focus = AudioFocusHandler(this).apply {
            onDuck = { engine.setMasterDuck(DUCK_LEVEL) }
            onGain = { engine.setMasterDuck(1f) }
            // Permanent loss (a call, another music app) pauses rather than
            // ducks: focus will not come back on its own, and a drum playing
            // over a phone call is not a mistake anyone wants to make twice.
            onLoss = { if (::player.isInitialized) player.pause() }
        }

        player = KirtanPlayer(this, engine, focus)
        session = MediaSession.Builder(this, player).build()

        createNotificationChannel()
        // The default provider builds the notification, but pointed at OUR
        // channel so the name and importance in system settings are the ones we
        // chose rather than Media3's generic defaults.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                DefaultMediaNotificationProvider.NotificationIdProvider {
                    DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
                },
                CHANNEL_ID,
                R.string.notif_channel_name,
            )
        )

        registerBecomingNoisy()

        // Mirror the loaded beat's name into the notification.
        //
        // ON THE MAIN THREAD, and this is not a style choice: SimpleBasePlayer
        // verifies every access — including the invalidateState() that
        // setBeatTitle() triggers — against the application looper and throws
        // IllegalStateException from any other thread. Collecting the flow on the
        // container's Default scope crashed the process the first time a beat was
        // loaded; only running it on a device surfaced that.
        serviceScope.launch(Dispatchers.Main) {
            container.currentBeatName.collect { name ->
                if (name != null) player.setBeatTitle(name)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the app away while paused should stop the service; while playing it
     * must not, or the drum would cut out on a gesture the user did not mean as
     * "stop the kirtan".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        noisyReceiver = null

        serviceScope.cancel()
        focus.abandonFocus()

        session?.let {
            it.player.release()
            it.release()
        }
        session = null

        // Release the AudioTrack. The engine object survives (it is
        // process-scoped) but the native output stream must not.
        (application as KirtanApplication).container.engine.release()

        super.onDestroy()
    }

    /**
     * Unplugging headphones must not blast the room.
     *
     * Media3 does not do this for a custom player, and for this app it matters
     * more than usual: the drum is loud, and someone practising on headphones at
     * a temple would otherwise broadcast the moment they pulled the jack.
     */
    private fun registerBecomingNoisy() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    player.pause()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        // Not exported: this is a system broadcast, and registering it exported
        // would let any app on the device pause our playback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        noisyReceiver = receiver
    }

    /**
     * The channel is created here rather than left to Media3's default so the
     * name is one a person recognises in system settings, and so the importance
     * is LOW: a drum app's notification should be visible and never audible.
     *
     * Created before the provider is handed the same id, so the provider finds it
     * already configured instead of making its own.
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }
}
