package com.kirtan.companion.engine

import com.kirtan.companion.data.TUNE_MAX_CENTS
import com.kirtan.companion.data.TUNE_MIN_CENTS
import com.kirtan.companion.data.centsToRate
import com.kirtan.companion.data.model.LaneId

/**
 * The mixer: every voice, every end channel, and the master fader.
 *
 * This is the counterpart of `src/engine/SoundPlayer.js` — the same routing, the
 * same public vocabulary, the same per-end gains — with one addition the web
 * version does not need: [renderBlock]. On the browser, Web Audio owns the graph
 * and the sound card; here WE are the graph, so this class also performs the
 * summing that `AudioNode.connect` did for free.
 *
 * ROUTING (unchanged from SoundPlayer.js):
 *
 *   dayan/bayan:  voices → that end's EQ chain → end gain ─┐
 *   kartal:       voices ─────────────────────→ end gain ─┼→ master gain → out
 *
 * Summing is per END rather than per voice: all of an end's voices are added
 * into one buffer first, and only then does that buffer pass through the end's
 * five biquads. That is what the web graph does — every player connects to the
 * same chain head, so the filters see the sum and share one set of delay
 * registers — and it is roughly five times cheaper than filtering each voice.
 *
 * POLYPHONY IS BOUNDED. A 1.3 s dayan ring at 140 BPM in sixteenths overlaps
 * more than a dozen of itself, and an unbounded voice list would grow until it
 * starved the audio thread. [MAX_VOICES_PER_LANE] caps each end and steals the
 * OLDEST voice on overflow, which is the inaudible choice: the voice nearest the
 * end of its decay is the quietest thing in the mix.
 *
 * The setters may be called from any thread — they only publish a target that
 * the audio thread ramps toward, so a slider can never tear a value mid-block.
 * [play] and [renderBlock] are audio-thread only.
 */
class SoundPlayer(
    val sampleRate: Int,
    val blockSize: Int,
) {

    companion object {
        /** Overlap headroom for a long ring at the top of the tempo range. */
        const val MAX_VOICES_PER_LANE = 12
    }

    private val ends: Map<LaneId, EndChannel> = LaneId.ORDERED.associateWith {
        EndChannel(it, sampleRate.toDouble())
    }

    private val master = GainRamp(sampleRate.toDouble(), initial = 1f)

    /** Per-end playback rate, from the tune slider. 1.0 = the recorded pitch. */
    private val pitchRate = HashMap<LaneId, Double>()

    private val voices = ArrayList<Voice>(32)
    private val voicesPerLane = HashMap<LaneId, Int>()

    /** Per-stroke round-robin cursor: the index last played, or -1 for none. */
    private val lastIndex = HashMap<StrokeKey, Int>()

    /**
     * Sample → lane, so voice stealing can find its own lane's oldest voice.
     * Built once by [attach]; identity-keyed, because the same [PcmSample]
     * instance is reused for every trigger of a given recording.
     */
    private val laneOfSample = HashMap<PcmSample, LaneId>()

    // Scratch buffers, one per lane plus the master sum. Allocated once: the
    // audio thread must not allocate, or the GC will stutter the output.
    private val laneBuffers: Map<LaneId, FloatArray> =
        LaneId.ORDERED.associateWith { FloatArray(blockSize) }
    private val masterBuffer = FloatArray(blockSize)

    private var bank: Map<StrokeKey, List<PcmSample>> = emptyMap()

    /**
     * Take the decoded samples and index them by lane.
     *
     * Takes a plain map rather than a [SampleBank] on purpose: the mixer needs
     * the samples, not the asset-loading machinery that produced them. Keeping
     * that dependency out is what makes the whole DSP path testable offline on a
     * JVM with synthesised samples — no AssetManager, no device, no audio
     * hardware. [SampleBank.decoded] is the production source.
     *
     * Until this is called every [play] is a silent no-op, which is how the
     * engine stays safe during the (async) load.
     */
    fun attach(samples: Map<StrokeKey, List<PcmSample>>) {
        bank = samples
        laneOfSample.clear()
        for ((key, list) in samples) {
            for (sample in list) laneOfSample[sample] = key.lane
        }
    }

    // ── Commands ───────────────────────────────────────────────────────────

    /** The user's own master setting, kept separate from [duckFactor]. */
    private var userVolume: Float = 1f

    /**
     * A temporary attenuation applied by the platform's audio-focus rules, NOT a
     * user setting.
     *
     * Ducking has to be a separate factor rather than a call to [setVolume],
     * because otherwise losing focus transiently would overwrite the user's
     * chosen level and regaining it would have to guess what that level was. A
     * real mixer keeps the two apart for the same reason.
     */
    private var duckFactor: Float = 1f

    private fun publishMaster() {
        master.target = (userVolume * duckFactor).coerceIn(0f, 1f)
        master.retarget()
    }

    /** Master volume, 0..1. */
    fun setVolume(value: Float) {
        userVolume = value.coerceIn(0f, 1f)
        publishMaster()
    }

    /** The user's master setting, unaffected by ducking. */
    fun volume(): Float = userVolume

    /**
     * Attenuate for a transient focus loss (1.0 = full, 0.25 = a quarter).
     * Ramps over the same 50 ms as every other gain change, so a navigation
     * prompt dips the drum instead of chopping it.
     */
    fun setMasterDuck(factor: Float) {
        duckFactor = factor.coerceIn(0f, 1f)
        publishMaster()
    }

    /** Per-end volume — the mixer's track faders. */
    fun setEndVolume(lane: LaneId, value: Float) {
        ends[lane]?.setVolume(value)
    }

    /** Mute or unmute one instrument channel, for practice isolation. */
    fun setEndMuted(lane: LaneId, muted: Boolean) {
        ends[lane]?.setMuted(muted)
    }

    fun isEndMuted(lane: LaneId): Boolean = ends[lane]?.isMuted() ?: false

    fun endVolume(lane: LaneId): Float = ends[lane]?.volume() ?: 1f

    /**
     * One band of an end's EQ. Unknown ends — including the karatalas, which
     * carry no chain — are ignored safely rather than throwing.
     */
    fun setEqBand(lane: LaneId, bandIndex: Int, db: Double) {
        ends[lane]?.setEqBand(bandIndex, db)
    }

    fun eqBandDb(lane: LaneId, bandIndex: Int): Double? = ends[lane]?.eqBandDb(bandIndex)

    /** Does this end have a tone chain the mixer should offer controls for? */
    fun hasEq(lane: LaneId): Boolean = ends[lane]?.hasEq ?: false

    /**
     * Per-end tuning in cents, clamped to the shared ±600 range.
     *
     * The rate is read by [Voice.render] per block rather than latched at
     * trigger time, so moving the slider audibly bends a drum that is still
     * ringing. `Tone.Player.playbackRate` behaves the same way; the web code
     * comment says "next trigger" because that is where the change is easiest
     * to hear, not because live voices are exempt.
     */
    fun setEndPitch(lane: LaneId, cents: Int) {
        pitchRate[lane] = centsToRate(cents.coerceIn(TUNE_MIN_CENTS, TUNE_MAX_CENTS))
    }

    fun endPitchRate(lane: LaneId): Double = pitchRate[lane] ?: 1.0

    // ── Audio thread ───────────────────────────────────────────────────────

    /**
     * Trigger a stroke at [offsetInBlock] frames into the block about to be
     * rendered. A stroke whose samples never loaded is a silent no-op, matching
     * the web player's "no sound named ..." path.
     */
    fun play(key: StrokeKey, offsetInBlock: Int) {
        // Round-robin lives with the samples that own it, so the mixer keeps its
        // own cursor per stroke rather than asking the bank.
        val pool = bank[key] ?: return
        val cursor = lastIndex[key] ?: -1
        val index = pickIndex(pool.size, cursor)
        lastIndex[key] = index
        val sample = pool[index]
        val lane = key.lane

        if ((voicesPerLane[lane] ?: 0) >= MAX_VOICES_PER_LANE) stealOldest(lane)

        voices.add(Voice(sample, offsetInBlock))
        voicesPerLane[lane] = (voicesPerLane[lane] ?: 0) + 1
    }

    private fun stealOldest(lane: LaneId) {
        val victim = voices.firstOrNull { laneOfSample[it.sample] == lane } ?: return
        voices.remove(victim)
        voicesPerLane[lane] = (voicesPerLane[lane] ?: 1) - 1
    }

    /** Silence everything already sounding — used on stop. */
    fun stopAllVoices() {
        voices.clear()
        voicesPerLane.clear()
    }

    /**
     * Render [count] frames of the whole mix into [out].
     *
     * Called once per block by the audio thread, AFTER every trigger for this
     * block has been handed to [play].
     */
    fun renderBlock(out: FloatArray, count: Int) {
        // 1. Clear the scratch. fill(from, to) not fill(), because count is
        //    normally smaller than the buffer's capacity.
        for (buffer in laneBuffers.values) buffer.fill(0f, 0, count)
        masterBuffer.fill(0f, 0, count)

        // 2. Render each voice into its own lane's buffer at that lane's rate.
        //    Removal happens in the same pass so a finished voice costs no
        //    second traversal.
        var i = 0
        while (i < voices.size) {
            val voice = voices[i]
            val lane = laneOfSample[voice.sample]
            if (lane == null) {
                // An unindexed sample — cannot happen once attach() has run, but
                // sounding it beats dropping it silently.
                voice.render(masterBuffer, count, 1.0)
                if (voice.finished) voices.removeAt(i) else i++
                continue
            }
            val buffer = laneBuffers[lane]!!
            voice.render(buffer, count, pitchRate[lane] ?: 1.0)
            if (voice.finished) {
                voices.removeAt(i)
                voicesPerLane[lane] = (voicesPerLane[lane] ?: 1) - 1
            } else {
                i++
            }
        }

        // 3. Per end: EQ then fader, summed into master.
        for (lane in LaneId.ORDERED) {
            val buffer = laneBuffers[lane] ?: continue
            ends[lane]?.process(buffer, count)
            for (n in 0 until count) masterBuffer[n] += buffer[n]
        }

        // 4. Master fader, then out.
        master.applyTo(masterBuffer, count)
        System.arraycopy(masterBuffer, 0, out, 0, count)
    }

    /** Voices currently sounding — for a debug readout and tests. */
    fun activeVoiceCount(): Int = voices.size
}
