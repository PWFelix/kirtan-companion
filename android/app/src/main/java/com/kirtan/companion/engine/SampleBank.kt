package com.kirtan.companion.engine

import android.content.res.AssetManager
import android.util.Log
import com.kirtan.companion.data.model.LaneId
import kotlin.random.Random

/**
 * Which recording to sound: a lane, and whether the head is open or closed.
 *
 * The web app keys samples by the STRING `"dayan_open"` and routes them by
 * NAME PREFIX (`name.startsWith("dayan")`) — a convention the SoundPlayer
 * header warns you must honour when adding an instrument. Here the lane is a
 * typed [LaneId], so a mis-spelled or unroutable stroke is a compile error
 * rather than a silent fall-through to master. The [wireName] is kept only for
 * log messages and asset paths, where the string form is genuinely the key.
 */
data class StrokeKey(val lane: LaneId, val open: Boolean) {
    val wireName: String get() = "${lane.wireId}_${if (open) "open" else "closed"}"

    companion object {
        /** Every stroke the app can sound, in load order. */
        val ALL: List<StrokeKey> = LaneId.ORDERED.flatMap { lane ->
            listOf(StrokeKey(lane, true), StrokeKey(lane, false))
        }
    }
}

/**
 * Loads and decodes the bundled recordings.
 *
 * This class owns asset I/O and decoding, and nothing else. Playback, the
 * round-robin cursor and the per-lane routing all live in [SoundPlayer], which
 * receives a plain map through [decoded]. That split is deliberate: it is what
 * lets the entire DSP path be exercised offline on a JVM with synthesised
 * samples, without an [AssetManager] or a sound card.
 *
 * MISSING FILES ARE NOT FATAL, and this is load-bearing rather than defensive.
 * The bundled karatalas ship two open variants while [STROKE_FILES] names three,
 * and the web loader resolves a failed fetch to null and filters it out so that
 * one absent recording cannot leave `"ready"` unfired and the app stuck on its
 * loading screen. That rule is what lets a stroke's entry ship BEFORE its
 * recordings exist, so it is reproduced here exactly: a file that cannot be
 * opened is skipped with a warning, and a stroke is registered only if at least
 * one sample decoded.
 */
class SampleBank(private val assets: AssetManager) {

    companion object {
        private const val TAG = "SampleBank"
        private const val ASSET_ROOT = "sounds"

        /**
         * Stroke → the recordings that voice it, in preference order. Mirrors
         * `STROKE_SAMPLES` in SoundPlayer.js, including the three kartal opens of
         * which only two are currently bundled.
         */
        val STROKE_FILES: Map<StrokeKey, List<String>> = buildMap {
            for (lane in LaneId.ORDERED) {
                put(StrokeKey(lane, true), listOf("open_1.wav", "open_2.wav", "open_3.wav"))
                put(StrokeKey(lane, false), listOf("closed_1.wav"))
            }
        }
    }

    private val pools = LinkedHashMap<StrokeKey, List<PcmSample>>()

    /** The rate every sample was decoded to, so voices need no further conversion. */
    var sampleRate: Int = 0
        private set

    val isLoaded: Boolean get() = pools.isNotEmpty()

    /**
     * Decode every stroke's recordings at [targetRate].
     *
     * Returns the strokes that produced at least one usable sample. Safe to call
     * repeatedly; a later call replaces the pool.
     */
    fun load(targetRate: Int, keys: Collection<StrokeKey> = StrokeKey.ALL): Set<StrokeKey> {
        sampleRate = targetRate
        pools.clear()
        val ok = LinkedHashSet<StrokeKey>()

        for (key in keys) {
            val files = STROKE_FILES[key] ?: continue
            val decoded = ArrayList<PcmSample>(files.size)

            for (file in files) {
                val path = "$ASSET_ROOT/${key.lane.wireId}/$file"
                val bytes = try {
                    assets.open(path).use { it.readBytes() }
                } catch (e: Exception) {
                    // Expected for variants that aren't bundled yet.
                    Log.w(TAG, "no sample at \"$path\" (${e.javaClass.simpleName})")
                    continue
                }
                try {
                    val pcm = WavDecoder.decode(bytes, targetRate)
                    if (pcm.frameCount > 0) decoded.add(pcm)
                    else Log.w(TAG, "empty sample at \"$path\"")
                } catch (e: WavDecoder.WavFormatException) {
                    Log.w(TAG, "undecodable sample \"$path\": ${e.message}")
                }
            }

            if (decoded.isNotEmpty()) {
                pools[key] = decoded
                ok.add(key)
            } else {
                Log.w(TAG, "stroke \"${key.wireName}\" has no usable samples; it will be silent")
            }
        }

        Log.i(TAG, "loaded ${ok.size}/${keys.size} strokes at ${targetRate}Hz")
        return ok
    }

    /**
     * Every decoded stroke, as the plain map [SoundPlayer.attach] wants.
     * A snapshot: mutating the bank afterwards does not change what is playing.
     */
    fun decoded(): Map<StrokeKey, List<PcmSample>> = LinkedHashMap(pools)

    /** Total decoded frames held, for a memory sanity check in tests. */
    fun frameCount(): Int = pools.values.sumOf { samples -> samples.sumOf { it.frameCount } }
}

/**
 * A random index that is NOT [lastIndex]. With one sample there is nothing else
 * to pick, so 0 comes back.
 *
 * This is `_pickIndex` from SoundPlayer.js, reproduced literally: draw from
 * `count - 1` possibilities and skip over the excluded index. Drawing from the
 * full range and re-rolling on a collision would bias less but is a behaviour
 * change, and the existing distribution is what the recordings were auditioned
 * against.
 *
 * ROUND-ROBIN IS THE POINT: a real drum never sounds identical twice in a row,
 * so each stroke maps to several recordings and playing one picks a variant that
 * is not the one just played. Single-sample strokes fall out of this naturally —
 * with one recording the pool of "anything but the last" is empty, so it replays,
 * which is why the closed strokes need no special case.
 *
 * A top-level function rather than a method so it can be tested without an
 * [AssetManager] — the selection rule is the interesting part, and it is pure.
 */
internal fun pickIndex(count: Int, lastIndex: Int, random: Random = Random): Int {
    if (count <= 1) return 0
    var index = random.nextInt(count - 1)
    if (index >= lastIndex) index += 1
    return index
}
