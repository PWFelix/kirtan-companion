package com.kirtan.companion.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Audio focus, and the "becoming noisy" rule.
 *
 * A custom [androidx.media3.common.SimpleBasePlayer] gets none of this for free
 * — ExoPlayer handles focus internally, but we are not using ExoPlayer, because
 * there is no media to decode: the sound is synthesised from samples by our own
 * mixer. So focus is ours to manage.
 *
 * Why it matters for THIS app specifically: a kirtan is often accompanied by
 * someone else's recording, a phone call, or a temple's own sound system. Taking
 * focus transiently and ducking on loss is the difference between the drum
 * politely dropping out and two audio sources fighting.
 *
 * The attributes must match the ones [com.kirtan.companion.engine.AudioRenderer]
 * opens its AudioTrack with (USAGE_MEDIA / CONTENT_TYPE_MUSIC), or the platform
 * treats the focus request and the actual stream as belonging to two different
 * apps.
 */
class AudioFocusHandler(context: Context) {

    companion object {
        private const val TAG = "AudioFocusHandler"
    }

    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Called when focus is lost transiently — e.g. a navigation prompt. */
    var onDuck: () -> Unit = {}

    /** Called when focus comes back, so a duck can be undone. */
    var onGain: () -> Unit = {}

    /** Called when focus is lost for good — e.g. an incoming call. */
    var onLoss: () -> Unit = {}

    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "focus lost; stopping")
                onLoss()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "focus lost transiently ($change); ducking")
                onDuck()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "focus regained")
                onGain()
            }
        }
    }

    /**
     * Ask for focus. minSdk is 26, so the [AudioFocusRequest] API is always
     * available and the deprecated `requestAudioFocus(listener, stream, hint)`
     * overload is not needed.
     *
     * @return true if focus was granted. A refusal means something else owns the
     *   output and we should not start over it.
     */
    fun requestFocus(): Boolean {
        val req = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            // Accept a transient duck rather than failing: a navigation prompt
            // should lower the drum, not stop a kirtan.
            .setWillPauseWhenDucked(false)
            .build()
            .also { request = it }

        val result = manager.requestAudioFocus(req)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonFocus() {
        request?.let { manager.abandonAudioFocusRequest(it) }
    }
}
