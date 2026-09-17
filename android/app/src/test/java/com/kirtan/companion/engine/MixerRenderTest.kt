package com.kirtan.companion.engine

import com.kirtan.companion.data.BEATS
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The mixer, driven offline and listened to numerically.
 *
 * These tests render real blocks of audio through the actual DSP path — voices,
 * per-end biquad chains, faders, master — with no AudioTrack, no thread and no
 * device. That is only possible because [SoundPlayer.attach] takes a plain map of
 * samples rather than an [SampleBank], and it is the difference between knowing
 * the engine COMPILES and knowing it MAKES SOUND.
 *
 * The signals are synthetic on purpose. A constant-amplitude sample makes a gain
 * assertion exact to floating-point tolerance, which a real drum recording — with
 * its own envelope — could never be.
 */
class MixerRenderTest {

    private val rate = 48_000
    private val block = 256

    /** A flat sample: every frame the same amplitude, so gains are exactly checkable. */
    private fun flat(amplitude: Float, seconds: Double = 4.0) =
        PcmSample(FloatArray((seconds * rate).toInt()) { amplitude }, rate)

    private fun sine(frequency: Double, amplitude: Float = 0.5f, seconds: Double = 4.0) =
        PcmSample(
            FloatArray((seconds * rate).toInt()) {
                (amplitude * sin(2 * Math.PI * frequency * it / rate)).toFloat()
            },
            rate,
        )

    /**
     * A mixer with every stroke mapped to a copy of [sample].
     *
     * A COPY PER KEY, not one shared instance: the mixer attributes a voice to its
     * lane by sample IDENTITY (that is how a playing voice finds its end's EQ chain
     * and fader), so handing every stroke the same object would attribute them all
     * to whichever lane was indexed last. Sharing one instance here made every
     * "per-lane" assertion measure the wrong lane.
     */
    private fun mixerWith(sample: PcmSample): SoundPlayer =
        SoundPlayer(rate, block).also { player ->
            player.attach(
                StrokeKey.ALL.associateWith { key ->
                    listOf(PcmSample(sample.data.copyOf(), sample.sampleRate))
                }
            )
        }

    /** Render [blocks] blocks, concatenating the output. */
    private fun render(player: SoundPlayer, blocks: Int): FloatArray {
        val out = FloatArray(blocks * block)
        val scratch = FloatArray(block)
        for (b in 0 until blocks) {
            player.renderBlock(scratch, block)
            scratch.copyInto(out, b * block)
        }
        return out
    }

    private fun peak(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        var p = 0f
        for (i in from until to.coerceAtMost(samples.size)) p = maxOf(p, abs(samples[i]))
        return p
    }

    // ── Signal presence and placement ──────────────────────────────────────

    @Test
    fun `nothing triggered renders digital silence`() {
        val out = render(mixerWith(flat(0.5f)), 4)
        assertEquals("an idle mixer must be exactly silent", 0f, peak(out), 0f)
    }

    @Test
    fun `a stroke sounds at exactly the offset it was placed at`() {
        // This is the property that makes the timing sample-accurate rather than
        // block-accurate: a trigger at frame 137 must contribute nothing before
        // 137 and something from 137 onward.
        val player = mixerWith(flat(0.5f))
        player.play(StrokeKey(LaneId.DAYAN, true), 137)

        val out = render(player, 1)

        for (i in 0 until 137) assertEquals("frame $i should still be silent", 0f, out[i], 0f)
        assertNotEquals("frame 137 should be sounding", 0f, out[137])
    }

    @Test
    fun `a stroke at offset zero sounds from the first frame`() {
        val player = mixerWith(flat(0.5f))
        player.play(StrokeKey(LaneId.BAYAN, true), 0)
        assertNotEquals(0f, render(player, 1)[0])
    }

    @Test
    fun `a voice keeps sounding across block boundaries until its sample ends`() {
        // A 4-second dayan ring at 140 BPM overlaps many blocks; if a voice were
        // dropped at a boundary the drum would stutter every 5 ms.
        val player = mixerWith(flat(0.4f, seconds = 0.5))
        player.play(StrokeKey(LaneId.DAYAN, true), 0)

        val out = render(player, 40) // 10240 frames ≈ 0.21 s, well inside 0.5 s
        for (b in 0 until 40) {
            val p = peak(out, b * block, (b + 1) * block)
            assertNotEquals("block $b went silent mid-decay", 0f, p)
        }
    }

    @Test
    fun `a voice stops when its sample runs out`() {
        val player = mixerWith(flat(0.4f, seconds = 0.01)) // 480 frames
        player.play(StrokeKey(LaneId.DAYAN, true), 0)

        val out = render(player, 8) // 2048 frames, well past the sample end
        // Not exactly zero: the end's EQ chain is IIR, so its delay registers keep
        // a decaying tail for a while after the input stops. What matters is that
        // the voice itself is gone, i.e. the level is down in the noise floor.
        assertTrue(
            "audio continued past the end of the sample (${peak(out, from = 1000)})",
            peak(out, from = 1000) < 1e-3f,
        )
        assertEquals(0, player.activeVoiceCount())
    }

    // ── Gains ──────────────────────────────────────────────────────────────

    @Test
    fun `the master fader scales output linearly`() {
        // Measure the SETTLED level, not the first frame: a fader move ramps over
        // 50 ms by design, so the peak of a one-block render sits at the ramp's
        // start and says nothing about where it lands.
        val full = mixerWith(flat(0.4f)).also { it.play(StrokeKey(LaneId.DAYAN, true), 0) }
        val fullOut = render(full, 20)

        val half = mixerWith(flat(0.4f)).also {
            it.setVolume(0.5f)
            it.play(StrokeKey(LaneId.DAYAN, true), 0)
        }
        val halfOut = render(half, 20)

        assertEquals(
            peak(fullOut, from = 10 * block),
            2f * peak(halfOut, from = 10 * block),
            1e-4f,
        )
    }

    @Test
    fun `ducking multiplies the master fader rather than replacing it`() {
        // The point of keeping duck separate from user volume: a navigation
        // prompt must not clobber the level the user chose.
        val player = mixerWith(flat(0.4f)).also {
            it.setVolume(0.5f)
            it.setMasterDuck(0.25f)
            it.play(StrokeKey(LaneId.DAYAN, true), 0)
        }
        val ducked = peak(render(player, 20), from = 10 * block)

        val reference = mixerWith(flat(0.4f)).also { it.play(StrokeKey(LaneId.DAYAN, true), 0) }
        val unity = peak(render(reference, 20), from = 10 * block)

        assertEquals(unity * 0.5f * 0.25f, ducked, 1e-4f)
        // And the user's own setting survived the duck.
        assertEquals(0.5f, player.volume(), 0f)
    }

    @Test
    fun `muting an end silences only that end`() {
        val player = mixerWith(flat(0.4f))
        player.setEndMuted(LaneId.DAYAN, true)
        player.play(StrokeKey(LaneId.DAYAN, true), 0)
        player.play(StrokeKey(LaneId.BAYAN, true), 0)

        // Render past the 50 ms mute ramp so the assertion measures the settled
        // state rather than the tail of the fade.
        val out = render(player, 60)
        val tail = peak(out, from = 40 * block)

        // The bayan is still audible; the dayan is gone. Compare against a
        // reference where only the bayan plays.
        val bayanOnly = mixerWith(flat(0.4f)).also { it.play(StrokeKey(LaneId.BAYAN, true), 0) }
        val expected = peak(render(bayanOnly, 60), from = 40 * block)

        assertEquals("the unmuted end should be untouched", expected, tail, 1e-4f)
    }

    @Test
    fun `unmuting restores the user's own volume, not unity`() {
        // Volume and mute are separate facts for exactly this reason: collapsing
        // them into one number is what makes a muted-then-unmuted lane come back
        // at the wrong level.
        val player = mixerWith(flat(0.4f))
        player.setEndVolume(LaneId.DAYAN, 0.4f)
        player.setEndMuted(LaneId.DAYAN, true)
        player.setEndMuted(LaneId.DAYAN, false)
        assertEquals(0.4f, player.endVolume(LaneId.DAYAN), 0f)
        assertTrue(!player.isEndMuted(LaneId.DAYAN))
    }

    @Test
    fun `the bayan carries its fixed makeup gain`() {
        // Phone speakers reproduce the bass head far more weakly than the dayan's
        // ring, so the mixer's "100%" means "balanced on a phone".
        val dayan = mixerWith(flat(0.4f)).also { it.play(StrokeKey(LaneId.DAYAN, true), 0) }
        val bayan = mixerWith(flat(0.4f)).also { it.play(StrokeKey(LaneId.BAYAN, true), 0) }

        val ratio = peak(render(bayan, 20), from = 10 * block) /
            peak(render(dayan, 20), from = 10 * block)
        assertEquals("bayan makeup should be 1.25", 1.25f, ratio, 1e-4f)
    }

    // ── The EQ chains ──────────────────────────────────────────────────────

    @Test
    fun `a flat EQ chain is transparent end to end`() {
        // Not just per-filter unity (EngineTest covers that) but the whole chain
        // inside the mixer, where a mis-wired cascade or a double-applied gain
        // would show up.
        val player = mixerWith(flat(0.4f))
        player.play(StrokeKey(LaneId.DAYAN, true), 0)
        val out = render(player, 1)

        // Expected: sample × makeup(1.0) × endVolume(1.0) × master(1.0).
        assertEquals(0.4f, peak(out), 1e-5f)
    }

    @Test
    fun `boosting a band changes the output, and only for that end`() {
        val signal = sine(1_000.0)

        val boosted = mixerWith(signal).also {
            it.setEqBand(LaneId.DAYAN, 2, 12.0)   // the 1 kHz peaking band
            it.play(StrokeKey(LaneId.DAYAN, true), 0)
        }
        val boostedPeak = peak(render(boosted, 4), from = 2 * block)

        val flat = mixerWith(signal).also { it.play(StrokeKey(LaneId.DAYAN, true), 0) }
        val flatPeak = peak(render(flat, 4), from = 2 * block)

        assertTrue(
            "+12 dB at 1 kHz should raise a 1 kHz tone (was $flatPeak, got $boostedPeak)",
            boostedPeak > flatPeak * 2f,
        )

        // The bayan's chain is a separate cascade; boosting the dayan must not
        // touch it.
        val other = mixerWith(signal).also {
            it.setEqBand(LaneId.DAYAN, 2, 12.0)
            it.play(StrokeKey(LaneId.BAYAN, true), 0)
        }
        // The bayan also carries makeup, so compare against its own flat run.
        val otherFlat = mixerWith(signal).also { it.play(StrokeKey(LaneId.BAYAN, true), 0) }
        assertEquals(
            peak(render(otherFlat, 4), from = 2 * block),
            peak(render(other, 4), from = 2 * block),
            1e-5f,
        )
    }

    @Test
    fun `EQ band gains are clamped to the shared range`() {
        val player = mixerWith(flat(0.4f))
        player.setEqBand(LaneId.DAYAN, 0, 999.0)
        assertEquals(com.kirtan.companion.data.EQ_MAX_DB, player.eqBandDb(LaneId.DAYAN, 0)!!, 0.0)
        player.setEqBand(LaneId.DAYAN, 0, -999.0)
        assertEquals(com.kirtan.companion.data.EQ_MIN_DB, player.eqBandDb(LaneId.DAYAN, 0)!!, 0.0)
    }

    @Test
    fun `only the mridanga ends have an EQ chain`() {
        val player = mixerWith(flat(0.4f))
        assertTrue(player.hasEq(LaneId.DAYAN))
        assertTrue(player.hasEq(LaneId.BAYAN))
        // The karatalas are a separate instrument, not a head of the mridanga, so
        // their path stays untouched by design.
        assertTrue(!player.hasEq(LaneId.KARTAL))

        // Setting EQ on them is a safe no-op rather than a throw.
        player.setEqBand(LaneId.KARTAL, 0, 12.0)
        assertEquals(null, player.eqBandDb(LaneId.KARTAL, 0))
    }

    @Test
    fun `the karatalas bypass the EQ entirely`() {
        val signal = sine(1_000.0)
        // A wild boost on the dayan chain must not affect a kartal voice, whose
        // path goes straight to its own fader.
        val player = mixerWith(signal).also {
            it.setEqBand(LaneId.DAYAN, 2, 12.0)
            it.play(StrokeKey(LaneId.KARTAL, true), 0)
        }
        val withEq = peak(render(player, 4), from = 2 * block)

        val reference = mixerWith(signal).also { it.play(StrokeKey(LaneId.KARTAL, true), 0) }
        assertEquals(peak(render(reference, 4), from = 2 * block), withEq, 1e-6f)
    }

    // ── Pitch ──────────────────────────────────────────────────────────────

    @Test
    fun `pitching an end up shortens how long its sample lasts`() {
        // Retuning by playback rate raises the pitch AND shortens the decay, which
        // is exactly what tightening a real head does — the reason the web engine
        // tunes by rate rather than by a pitch-shift node.
        val seconds = 1.0
        val sample = flat(0.4f, seconds)

        val normal = mixerWith(sample).also { it.play(StrokeKey(LaneId.DAYAN, true), 0) }
        val up = mixerWith(sample).also {
            it.setEndPitch(LaneId.DAYAN, 600)   // a tritone up → ×1.414
            it.play(StrokeKey(LaneId.DAYAN, true), 0)
        }

        // At +600 cents the sample is consumed ~1.414× faster, so it must run out
        // sooner. Assert on the VOICE LIFECYCLE rather than on the rendered level:
        // the end's EQ chain is IIR and rings for a while after its input stops,
        // so "silence" in the audio is a matter of how long you wait, whereas the
        // voice list is exact.
        val frames = (seconds * rate * 0.85).toInt()
        val blocks = frames / block + 1
        render(normal, blocks)
        render(up, blocks)

        assertTrue(
            "the untuned voice should still be sounding at 85% of its length",
            normal.activeVoiceCount() > 0,
        )
        assertEquals(
            "the pitched-up voice should have finished by 85% of its length",
            0,
            up.activeVoiceCount(),
        )
    }

    @Test
    fun `pitch is clamped to the shared cents range`() {
        val player = mixerWith(flat(0.4f))
        player.setEndPitch(LaneId.DAYAN, 99_999)
        assertEquals(
            com.kirtan.companion.data.centsToRate(com.kirtan.companion.data.TUNE_MAX_CENTS),
            player.endPitchRate(LaneId.DAYAN),
            1e-12,
        )
        player.setEndPitch(LaneId.DAYAN, 0)
        assertEquals(1.0, player.endPitchRate(LaneId.DAYAN), 1e-12)
    }

    // ── Polyphony ──────────────────────────────────────────────────────────

    @Test
    fun `voices are capped per lane so a long ring cannot grow the list forever`() {
        val player = mixerWith(flat(0.05f, seconds = 10.0))
        val key = StrokeKey(LaneId.DAYAN, true)
        // A 10 s sample triggered 100 times: without stealing, that is 100 voices
        // and an audio thread that cannot keep up with real time.
        repeat(100) { player.play(key, 0) }
        render(player, 1)

        assertTrue(
            "voice count was ${player.activeVoiceCount()}",
            player.activeVoiceCount() <= SoundPlayer.MAX_VOICES_PER_LANE,
        )
    }

    @Test
    fun `round-robin cycles through the variants it was given`() {
        // Three distinguishable recordings for one stroke; over enough triggers
        // all three must be heard, and never the same one twice running.
        val a = flat(0.10f); val b = flat(0.20f); val c = flat(0.30f)
        val player = SoundPlayer(rate, block).also {
            it.attach(mapOf(StrokeKey(LaneId.DAYAN, true) to listOf(a, b, c)))
        }

        val key = StrokeKey(LaneId.DAYAN, true)
        val heard = LinkedHashSet<Float>()
        var lastPeak = -1f
        repeat(12) {
            player.play(key, 0)
            val p = peak(render(player, 1))
            // Each trigger is one voice at a known amplitude, so the peak names
            // the variant that was chosen.
            heard.add(Math.round(p * 100f) / 100f)
            assertNotEquals("repeated the previous variant", lastPeak, p)
            lastPeak = p
            player.stopAllVoices()
        }
        assertEquals(setOf(0.1f, 0.2f, 0.3f), heard)
    }

    @Test
    fun `an unknown stroke is a silent no-op`() {
        val player = SoundPlayer(rate, block).also {
            it.attach(mapOf(StrokeKey(LaneId.DAYAN, true) to listOf(flat(0.5f))))
        }
        player.play(StrokeKey(LaneId.KARTAL, false), 0) // never attached
        assertEquals(0f, peak(render(player, 1)), 0f)
    }

    // ── The whole musical path ─────────────────────────────────────────────

    @Test
    fun `a full bar of a real beat triggers every stroke of every lane exactly once`() {
        // The integration check: clock → sequencer → mixer, over one bar of an
        // actual shipped beat, driven block by block the way the audio thread
        // drives it. If the tick arithmetic, the phase derivation or the trigger
        // placement were wrong, a stroke would be missing or doubled here.
        for (beat in BEATS) {
            val clock = MusicalClock(rate).apply {
                setBpm(beat.bpm.toDouble(), 0)
                setMeter(beat.steps, beat.beatsPerBar)
                start(0)
            }
            val sequencer = Sequencer(clock).apply { this.beat = beat }
            val player = mixerWith(flat(0.2f))

            val barFrames = clock.frameAt(clock.ticksPerBar.toDouble())
            val events = ArrayList<StepEvent>()
            val scratch = FloatArray(block)
            val fired = ArrayList<Pair<Int, LaneId>>()
            // Accumulated across blocks: `events` is cleared each block, so
            // reading it after the loop would only see the last block's steps.
            val stepsInOrder = ArrayList<Int>()

            var from = 0L
            while (from < barFrames) {
                val count = minOf(block.toLong(), barFrames - from).toInt()
                events.clear()
                sequencer.collectStepEvents(from, from + count, events)
                for (event in events) {
                    stepsInOrder.add(event.step)
                    val offset = (event.frame - from).toInt()
                    for (lane in LaneId.ORDERED) {
                        val stroke = beat.strokeAt(lane, event.step) ?: continue
                        player.play(StrokeKey(lane, stroke == Stroke.OPEN), offset)
                        fired.add(event.step to lane)
                    }
                }
                player.renderBlock(scratch, count)
                from += count
            }

            // Every non-rest cell of every lane must have fired exactly once.
            // An explicit comparator, because Kotlin's Pair is not Comparable.
            val byCell = compareBy<Pair<Int, LaneId>>({ it.first }, { it.second.name })
            val expected = beat.activeLanes().flatMap { lane ->
                (0 until beat.steps).mapNotNull { step ->
                    beat.strokeAt(lane, step)?.let { step to lane }
                }
            }.sortedWith(byCell)
            assertEquals(
                "${beat.id}: strokes fired did not match the pattern",
                expected,
                fired.sortedWith(byCell),
            )

            // Every step index appeared exactly once, in ascending order. This is
            // the property the phase-derived model exists to guarantee, checked
            // against a real shipped pattern rather than a synthetic grid.
            assertEquals("${beat.id}: step count", beat.steps, stepsInOrder.size)
            assertEquals("${beat.id}: steps out of order or repeated", (0 until beat.steps).toList(), stepsInOrder)
        }
    }

    @Test
    fun `a rendered bar of a real beat is audible and bounded after limiting`() {
        // A DECAYING sample, not a flat one. A constant-amplitude recording would
        // still be at full level seconds later, so every stroke in the bar would
        // pile on top of the last and the mix would grow without bound — an
        // artefact of the test signal rather than anything the engine does. Real
        // drum samples decay, and the voice cap only matters because they don't
        // decay instantly.
        val decay = PcmSample(
            FloatArray((6.0 * rate).toInt()) {
                (0.6f * kotlin.math.exp(-it.toDouble() / (rate * 0.12))).toFloat()
            },
            rate,
        )

        for (beat in BEATS) {
            val clock = MusicalClock(rate).apply {
                setBpm(beat.bpm.toDouble(), 0); setMeter(beat.steps, beat.beatsPerBar); start(0)
            }
            val sequencer = Sequencer(clock).apply { this.beat = beat }
            val player = mixerWith(decay)

            val barFrames = clock.frameAt(clock.ticksPerBar.toDouble())
            val raw = FloatArray(barFrames.toInt() + block)
            val scratch = FloatArray(block)
            val events = ArrayList<StepEvent>()

            var from = 0L
            var written = 0
            while (from < barFrames) {
                val count = minOf(block.toLong(), barFrames - from).toInt()
                events.clear()
                sequencer.collectStepEvents(from, from + count, events)
                for (event in events) {
                    val offset = (event.frame - from).toInt()
                    for (lane in LaneId.ORDERED) {
                        val stroke = beat.strokeAt(lane, event.step) ?: continue
                        player.play(StrokeKey(lane, stroke == Stroke.OPEN), offset)
                    }
                }
                player.renderBlock(scratch, count)
                scratch.copyInto(raw, written, 0, count)
                written += count
                from += count
            }

            val mixPeak = peak(raw, 0, written)
            assertTrue("${beat.id}: a rendered bar was silent", mixPeak > 0f)
            assertTrue("${beat.id}: mix contained NaN or Infinity", mixPeak.isFinite())

            // The limiter is the last stage before the DAC, so this is what
            // actually reaches the speaker: it must never exceed full scale,
            // whatever the mix summed to.
            var outPeak = 0f
            for (i in 0 until written) outPeak = maxOf(outPeak, abs(softClip(raw[i])))
            assertTrue(
                "${beat.id}: limited output exceeded full scale ($outPeak from mix peak $mixPeak)",
                outPeak <= 1.0f,
            )
        }
    }

    // ── The limiter ────────────────────────────────────────────────────────

    @Test
    fun `the limiter is exactly unity below the knee`() {
        // Ordinary playing must be untouched, or a mixer setting would stop
        // meaning what it means on the web.
        for (x in listOf(0f, 0.1f, -0.1f, 0.5f, -0.79f, SOFT_CLIP_KNEE)) {
            assertEquals(x, softClip(x), 0f)
        }
    }

    @Test
    fun `the limiter never exceeds full scale however loud the input`() {
        for (x in listOf(0.81f, 1.0f, 1.5f, 3.0f, 100f, 1e6f, -1e6f)) {
            val y = softClip(x)
            assertTrue("softClip($x) = $y exceeded 1.0", abs(y) <= 1.0f)
            assertTrue("softClip($x) = $y was not finite", y.isFinite())
            // Sign must be preserved: inverting a peak is far worse than clipping it.
            assertEquals(x > 0f, y > 0f)
        }
    }

    @Test
    fun `the limiter is monotonic`() {
        // A non-monotonic limiter folds loud passages back down, which sounds
        // like the drum is being played quieter exactly when it is hit hardest.
        var previous = softClip(0f)
        var x = 0f
        while (x < 4f) {
            x += 0.001f
            val y = softClip(x)
            assertTrue("softClip fell at x=$x ($previous -> $y)", y >= previous - 1e-6f)
            previous = y
        }
    }

    @Test
    fun `the limiter is continuous through the knee`() {
        // A value discontinuity at the knee is a click on every peak that crosses
        // it, which is the artefact the tanh shape exists to avoid.
        val below = softClip(SOFT_CLIP_KNEE - 1e-4f)
        val above = softClip(SOFT_CLIP_KNEE + 1e-4f)
        assertTrue(
            "a jump of ${abs(above - below)} across the knee",
            abs(above - below) < 1e-3f,
        )
    }
}
