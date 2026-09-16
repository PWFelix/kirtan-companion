package com.kirtan.companion.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kirtan.companion.R
import com.kirtan.companion.engine.KirtanEngine

/**
 * A Media3 [Player] that plays our engine.
 *
 * WHY A CUSTOM PLAYER RATHER THAN EXOPLAYER. ExoPlayer decodes media; there is
 * no media here. The sound is mixed from decoded samples by
 * [com.kirtan.companion.engine.SoundPlayer] on our own audio thread. What we want
 * from Media3 is everything AROUND playback: the foreground service that keeps
 * audio alive with the screen off, the media notification, lock-screen controls,
 * and headset/Bluetooth transport buttons. [SimpleBasePlayer] is precisely the
 * seam Media3 offers for that — implement a Player on top of your own playback
 * and the session machinery works unchanged.
 *
 * WHY THIS IS THE PIECE THAT JUSTIFIES GOING NATIVE. A browser tab cannot hold a
 * foreground service. Chromium throttles and then pauses JS when the page is
 * hidden, and — per Chromium's own rules — silent audio does not earn the
 * exemption that audible audio does. Android hardened this further: audio started
 * from the background requires a `mediaPlayback` foreground service, and a
 * violation FAILS SILENTLY, with no exception, the drum just stops. For an app
 * whose primary user chants at morning arti with the phone face-down, that is the
 * product requirement, and it is unreachable from the web.
 *
 * ── THE SHAPE OF THIS CLASS ──
 * [SimpleBasePlayer] is a state machine read entirely through [getState]: there
 * is no `handlePlay`/`handlePause`, because play and pause are both just
 * `setPlayWhenReady`. All routing therefore goes through
 * [handleSetPlayWhenReady], which is the single place the engine is started or
 * stopped and audio focus is taken or given back. Mutating a field and calling
 * [invalidateState] is how a change reaches the notification.
 *
 * POSITION AND DURATION ARE MEANINGLESS HERE, deliberately. A looping drum
 * pattern has no end and nothing to seek to, so the item reports no duration,
 * is marked unseekable, and [handleSeek] fails. Pretending otherwise would give
 * the notification a scrubber that does nothing, which is worse than none.
 */
@OptIn(UnstableApi::class)
class KirtanPlayer(
    context: Context,
    private val engine: KirtanEngine,
    private val focus: AudioFocusHandler,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appLabel = context.getString(R.string.app_name)

    /**
     * A stable media id and a URI that is never fetched. MediaItem wants a URI
     * for routing and for the notification's artwork lookup; the `kirtan://`
     * scheme makes it obvious in logs that nothing network-backed is involved.
     */
    private val mediaItem = MediaItem.Builder()
        .setMediaId(MEDIA_ID)
        .setUri("kirtan://loop")
        .build()

    private var playWhenReady = false
    private var beatName: String? = null

    /** Reflect a beat change into the notification without touching playback. */
    fun setBeatTitle(name: String) {
        if (beatName == name) return
        beatName = name
        invalidateState()
    }

    /** The engine's own view of whether it is producing sound. */
    fun isEnginePlaying(): Boolean = engine.isPlaying

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAllCommands()
                // Seeking and playlist editing are both meaningless for one
                // looping pattern; removing them stops the notification drawing
                // controls that cannot work.
                .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
        )
        .setPlaylist(listOf(buildItemData()))
        .setCurrentMediaItemIndex(0)
        .setPlayWhenReady(
            playWhenReady,
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        .setPlaybackState(Player.STATE_READY)
        .build()

    private fun buildItemData(): MediaItemData = MediaItemData.Builder(MEDIA_ID)
        .setMediaItem(mediaItem)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(beatName ?: appLabel)
                .setArtist(appLabel)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        // No setDurationUs: a loop does not end, so the duration stays
        // TIME_UNSET and the notification draws no progress bar.
        .setIsSeekable(false)
        .setIsDynamic(false)
        .build()

    /**
     * The single entry point for play and pause — from the notification, a headset
     * button, or the UI. Media3 routes all of them here.
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            // Focus FIRST: if another app owns the output we must not start over
            // it, and we must not arm the device only to abandon it immediately.
            if (!focus.requestFocus()) return Futures.immediateVoidFuture()

            // The device must be open before the clock starts, or the first
            // block renders into nothing. arm() is idempotent.
            if (!engine.arm()) {
                focus.abandonFocus()
                return Futures.immediateVoidFuture()
            }

            engine.start()
            this.playWhenReady = true
        } else {
            engine.stop()
            this.playWhenReady = false
            focus.abandonFocus()
            // Leave the master duck alone: a pause is not a focus loss, and the
            // next play should come back at the user's own level.
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Refused, not ignored. A looping pattern has no position to move to; failing
     * the future is what tells a controller the command was not honoured, rather
     * than letting it believe a seek happened.
     *
     * A [SettableFuture] rather than `Futures.immediateFailedFuture`, because
     * that helper returns a plain `Future` and the override must hand back a
     * [ListenableFuture].
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = SettableFuture.create<Void>().apply {
        setException(
            UnsupportedOperationException("a looping drum pattern has no position to seek to")
        )
    }

    companion object {
        const val MEDIA_ID = "kirtan-loop"
    }
}
