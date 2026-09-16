package com.kirtan.companion.ui.transport

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kirtan.companion.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The UI's handle on the playback service.
 *
 * WHY THE UI DOES NOT CALL `engine.start()` DIRECTLY. It could — the engine is
 * process-scoped and reachable from [com.kirtan.companion.AppContainer] — and the
 * drum would play. But the notification would still say "paused", the lock-screen
 * controls would do nothing, and Media3 would not promote the service to a
 * foreground service, so the audio would die with the screen off. That last one is
 * the entire reason this app is native, so transport commands go through the
 * controller and let Media3 own the consequences.
 *
 * Everything else — tempo, volumes, EQ, tuning, which beat is loaded — goes
 * straight to the engine. Those are mixer state, not transport state: the
 * notification has no opinion about an EQ band, and routing a slider drag through
 * IPC at 60 Hz would be both slow and pointless.
 *
 * The asymmetry is the design: ONE path for the two commands that change whether
 * sound is playing, a direct path for everything that changes what it sounds like.
 */
@OptIn(UnstableApi::class)
class PlaybackConnection(private val context: Context) {

    companion object {
        private const val TAG = "PlaybackConnection"
    }

    private var controller: MediaController? = null

    private val _playWhenReady = MutableStateFlow(false)

    /** Whether the service believes it should be playing. Drives every play/pause icon. */
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    val isConnected: Boolean get() = controller != null

    /**
     * Bind to [PlaybackService], starting it if necessary.
     *
     * Idempotent: a second call returns the existing controller. The blocking
     * `get()` is confined to the IO dispatcher — `buildAsync` hands back a Guava
     * future, and awaiting it with a callback would need a coroutines-Guava
     * bridge that is not worth a dependency for one call made once per process.
     */
    suspend fun connect(): MediaController? {
        controller?.let { return it }
        return try {
            val token = SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java),
            )
            val built = withContext(Dispatchers.IO) {
                MediaController.Builder(context, token).buildAsync().get()
            }
            controller = built
            _playWhenReady.value = built.playWhenReady
            // Media3's Player.addListener takes no executor: callbacks always
            // arrive on the application looper, which is the main thread here.
            built.addListener(
                object : Player.Listener {
                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        _playWhenReady.value = playWhenReady
                    }
                }
            )
            built
        } catch (e: Exception) {
            // A failed bind must not wedge the app: the engine can still be
            // driven locally, and the user gets sound without a notification.
            Log.e(TAG, "could not connect to PlaybackService", e)
            null
        }
    }

    /**
     * Ask the service to play or pause. Falls back to nothing if unbound — the
     * caller is responsible for having called [connect], and a dropped command is
     * recoverable by tapping again, whereas throwing would take the screen down.
     */
    fun setPlayWhenReady(play: Boolean) {
        val c = controller
        if (c == null) {
            Log.w(TAG, "setPlayWhenReady($play) with no controller; ignoring")
            return
        }
        // Optimistic: reflect the intent immediately so the button doesn't feel
        // dead, then let the listener correct it if the service refuses.
        _playWhenReady.value = play
        c.playWhenReady = play
    }

    fun release() {
        controller?.release()
        controller = null
        _playWhenReady.value = false
    }
}
