package com.kirtan.companion.engine

/**
 * Which frame is coming out of the speaker RIGHT NOW: the position the
 * playhead is drawn at.
 *
 * WHY THIS EXISTS. [AudioRenderer.framesRendered] counts frames handed to the
 * device, not frames heard. Between the two sit our own track buffer (16 blocks,
 * ~86 ms), the platform mixer and, on a Bluetooth speaker, the radio link and
 * the speaker's own buffer, which together add 150–300 ms more. Drawn from the
 * rendered count, the strip and the Learn highway run visibly AHEAD of the
 * drum, and over Bluetooth by a third of a second, which at kirtan tempo is a
 * whole beat.
 *
 * THE MEASUREMENT. `AudioTrack.getTimestamp` reports a (frame, time) pair: this
 * frame was being presented at this CLOCK_MONOTONIC instant. Extrapolating from
 * it at the sample rate gives the frame being presented now, with the output
 * path's latency already in it. That includes Bluetooth on most phones from
 * Android 9, whose A2DP stack folds the speaker's reported delay into the
 * timestamp. Where a device under-reports (cheap speakers, car stereos), the
 * user's per-device adjustment, [KirtanEngine.setDisplayDelayMs], covers the
 * rest.
 *
 * Pure functions and an immutable stamp, so the arithmetic is testable on the
 * JVM without an AudioTrack.
 */
internal object OutputClock {

    /** One `getTimestamp` reading. Immutable, so the UI thread never sees half of one. */
    data class Stamp(val frame: Long, val nanos: Long)

    /**
     * A stamp older than this is not trusted. The audio thread refreshes it every
     * few blocks while playing, so a stale stamp means the track stalled or has
     * just restarted, and extrapolating across that gap would race ahead of what
     * is actually being heard.
     */
    const val STALE_NANOS = 1_000_000_000L

    /**
     * The frame being presented at [nowNanos], or null when [stamp] cannot be
     * trusted (absent or stale). Never ahead of [rendered]: a frame cannot be
     * heard before it was written, so an underrun, where extrapolation would
     * overshoot, clamps to what exists.
     */
    fun presentedFrame(stamp: Stamp?, nowNanos: Long, sampleRate: Int, rendered: Long): Long? {
        if (stamp == null || stamp.frame <= 0) return null
        val age = nowNanos - stamp.nanos
        if (age > STALE_NANOS) return null
        val frame = stamp.frame + age * sampleRate / 1_000_000_000L
        return frame.coerceIn(0, rendered)
    }

    /** Milliseconds as frames at [sampleRate]. Signed: a negative delay moves the display earlier. */
    fun msToFrames(ms: Int, sampleRate: Int): Long = ms.toLong() * sampleRate / 1000L
}
