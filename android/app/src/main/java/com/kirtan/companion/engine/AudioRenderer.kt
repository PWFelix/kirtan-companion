package com.kirtan.companion.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.tanh

/** Fills one block of audio. Called from the audio thread, never from the UI. */
fun interface BlockRenderer {
    /**
     * Write [count] frames of mono float into [out], beginning at the absolute
     * stream position [fromFrame]. Implementations place triggers at their exact
     * offset within the block, which is what makes timing sample-accurate.
     */
    fun render(fromFrame: Long, count: Int, out: FloatArray)
}

/**
 * The audio thread and its output device.
 *
 * This class has no counterpart in the web app — there, `Tone.start()` hands the
 * graph to Web Audio and the browser owns the thread, the buffer and the sound
 * card. On Android we own all three, so this is the piece that:
 *
 *   - opens an [AudioTrack] in streaming mode at the device's native rate,
 *   - runs a dedicated thread at audio priority,
 *   - pulls one block at a time from a [BlockRenderer],
 *   - converts float to 16-bit PCM and blocks on the write, which is what paces
 *     the loop to real time (no sleep, no timer — the device is the clock),
 *   - and keeps the authoritative frame count that [MusicalClock] converts to
 *     musical time.
 *
 * WHY A BLOCK PULL RATHER THAN CALLBACKS. The blocking `write` is the only
 * timing source, and it is the hardware's. Everything musical is derived from
 * the frame position it implies, so the sequencer can never drift against the
 * audio: there is exactly one clock, and it is the sample count. The web engine
 * needs its epsilon nudge and its same-step guard precisely because its JS
 * callbacks are a second, independent clock.
 *
 * LATENCY. [blockSize] frames of render plus the track's internal buffer is the
 * delay between a trigger and hearing it. It is CONSTANT, so a running loop is
 * unaffected — the beat is exactly in time with itself. It is audible in two
 * places only: tapping a pad in the editor, and tap-tempo. Both are tolerable at
 * the buffer sizes used here, and both are the reason [AudioTrack] is opened in
 * `PERFORMANCE_MODE_LOW_LATENCY` rather than at a comfortable default.
 */
class AudioRenderer(
    val sampleRate: Int,
    val blockSize: Int,
    private val renderer: BlockRenderer,
) {

    companion object {
        private const val TAG = "AudioRenderer"

        /**
         * Track buffer as a multiple of the block size. Larger is more tolerant
         * of the thread being preempted (a background app competes for CPU) and
         * costs latency; smaller is the reverse. Eight blocks is ~43 ms at
         * 48 kHz, which survives scheduler noise without the pad-trigger delay
         * becoming obvious.
         */
        private const val BUFFER_BLOCKS = 8
    }

    /**
     * Frames handed to the track so far. THE authoritative position: the clock,
     * the playhead and the trigger placement all read it. Volatile because the
     * UI thread reads it to draw the playhead while the audio thread writes it.
     */
    @Volatile
    var framesRendered: Long = 0
        private set

    /** Count of short writes — the track not accepting a whole block. */
    @Volatile
    var underruns: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /**
     * Open the track and start pulling blocks. Idempotent.
     * @return false if no output device could be opened; the engine stays usable
     *   and silent rather than crashing, so a locked-down or broken audio path
     *   degrades the way a failed sample load does.
     */
    fun start(): Boolean {
        if (isRunning) return true

        val newTrack = try {
            createTrack()
        } catch (e: Exception) {
            Log.e(TAG, "could not open an AudioTrack", e)
            null
        } ?: return false

        track = newTrack
        framesRendered = 0
        underruns = 0

        try {
            newTrack.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack refused to play", e)
            newTrack.release()
            track = null
            return false
        }

        val t = Thread({ loop(newTrack) }, "kirtan-audio").apply {
            // Not a daemon: audio must survive the UI thread doing something
            // foolish, and the service that owns us decides when to stop.
            priority = Thread.MAX_PRIORITY
        }
        thread = t
        isRunning = true
        t.start()
        return true
    }

    /** Stop pulling and release the device. Safe to call twice. */
    fun stop() {
        isRunning = false
        thread?.let { t ->
            try {
                t.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null

        track?.let {
            try {
                it.pause()
                it.flush()
                it.stop()
            } catch (_: IllegalStateException) {
                // Already stopped, or never started.
            }
            it.release()
        }
        track = null
    }

    private fun createTrack(): AudioTrack {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBytes <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBytes for ${sampleRate}Hz")
            return throw IllegalStateException("no viable output buffer size")
        }
        // Two bytes per mono sample; hold at least BUFFER_BLOCKS of our own.
        val bufferBytes = maxOf(minBytes, blockSize * 2 * BUFFER_BLOCKS)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA puts us on the media stream and, with the
                    // foreground service, is what lets playback continue with
                    // the screen off. CONTENT_TYPE_MUSIC keeps the platform from
                    // applying speech processing to a drum.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun loop(track: AudioTrack) {
        // URGENT_AUDIO is the priority the platform's own audio threads run at;
        // setting it from inside the thread is required, as it maps onto a
        // cgroup that the Java thread priority alone does not reach.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val floatBuffer = FloatArray(blockSize)
        val pcm = ShortArray(blockSize)

        while (isRunning) {
            renderer.render(framesRendered, blockSize, floatBuffer)

            for (i in 0 until blockSize) {
                pcm[i] = (softClip(floatBuffer[i]) * 32767f).toInt().toShort()
            }
            // The write blocks until the track has room, which is what paces the
            // whole loop. Retry a partial write rather than dropping frames: a
            // dropped block is a gap in the rhythm, which is far more noticeable
            // than the same block arriving late.
            var offset = 0
            while (offset < blockSize && isRunning) {
                val written = track.write(pcm, offset, blockSize - offset)
                if (written > 0) {
                    offset += written
                    framesRendered += written
                } else if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                    Log.e(TAG, "AudioTrack died; stopping")
                    isRunning = false
                    break
                } else if (written < 0) {
                    Log.w(TAG, "AudioTrack.write returned $written")
                    underruns++
                    break
                }
                // written == 0: non-blocking would spin, but MODE_STREAM blocks,
                // so treat it as a stall and let the next iteration retry.
            }
            if (offset < blockSize) underruns++
        }
    }
}

/** Level above which [softClip] starts shaping. */
internal const val SOFT_CLIP_KNEE = 0.80f

/**
 * A soft knee above [SOFT_CLIP_KNEE], asymptotically approaching 1.0.
 *
 * This is a deliberate, documented divergence from the web engine, which simply
 * lets Web Audio clip. Three lanes summed — and the bayan carries a fixed 1.25
 * makeup gain — can exceed full scale on a coincident downbeat, and hard clipping
 * a drum transient is the harshest distortion there is.
 *
 * `tanh` rather than a polynomial because of the knee's continuity. A cubic that
 * starts at the knee and lands on 1.0 necessarily leaves it with slope n (for a
 * `(1-a)^n` shape), so a polynomial knee has a DERIVATIVE discontinuity — audible
 * as exactly the harshness we were trying to avoid. `tanh` gives f(knee) = knee
 * and f'(knee) = 1 exactly, so the curve leaves the linear region smoothly, and
 * it is bounded by 1.0 for any input however large.
 *
 * Below the knee this is exactly unity, so ordinary playing is untouched and a
 * mixer setting still means what it means on the web; only peaks that would
 * otherwise clip are shaped. Full scale maps to ~0.95, a level loss far too
 * small to hear.
 *
 * Top-level and internal rather than a private method so the limiter can be
 * tested directly — it is the last thing standing between a summed mix and the
 * speaker, and it is the one stage whose failure mode is audible distortion.
 */
internal fun softClip(x: Float): Float {
    val a = if (x < 0f) -x else x
    if (a <= SOFT_CLIP_KNEE) return x
    val sign = if (x < 0f) -1f else 1f
    val headroom = 1f - SOFT_CLIP_KNEE
    return sign * (SOFT_CLIP_KNEE + headroom * tanh(((a - SOFT_CLIP_KNEE) / headroom).toDouble())).toFloat()
}
