package com.kirtan.companion.data

import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.engine.MusicalClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * The pure data layer's invariants.
 *
 * These look like they restate the declarations, and mostly they do — that is
 * the point. Beat data is transcribed BY HAND from a book, and the failure mode
 * is silent: a mistyped cell plays a wrong stroke that nobody notices until a
 * musician does, and a wrong `steps` value makes the sequencer play half the
 * pattern. The web project learned this the hard way; `beats.js` carries a
 * comment about a stale `steps: 6` that "made the strip wrap into extra rows and
 * the sequencer play only half the pattern". Checking it here means the same
 * mistake fails a build instead of a kirtan.
 */
class DataTest {

    // ── Built-in beats ─────────────────────────────────────────────────────

    @Test
    fun `every built-in beat is internally consistent`() {
        for (beat in BEATS) {
            val id = beat.id ?: error("a built-in beat must have an id")

            // steps == sum(groups) is the real invariant. The older
            // `beatsPerBar × cellsPerGroup == steps` is NOT generally true: it
            // holds only when cells-per-group equals cells-per-quarter, and a
            // compound meter breaks it (matan is 48 cells over 24 quarters).
            assertEquals("$id: groups must sum to steps",
                beat.steps, sumGroups(beat.groups ?: error("$id has no groups")))

            assertTrue("$id: steps out of the shareable range", beat.steps in 1..ShareCodec.MAX_STEPS)
            assertTrue("$id: bpm out of range", beat.bpm in MIN_BPM..MAX_BPM)

            // Every declared lane must have exactly `steps` cells. A short array
            // reads as trailing rests and silently truncates the pattern.
            for (lane in beat.activeLanes()) {
                assertEquals("$id: lane ${lane.wireId} length", beat.steps, beat.pattern(lane)?.size)
            }

            // Both mridanga ends are the beat's identity; a beat without them is
            // not a mridanga beat. The karatalas are optional by design.
            assertNotNull("$id: no dayan row", beat.pattern(LaneId.DAYAN))
            assertNotNull("$id: no bayan row", beat.pattern(LaneId.BAYAN))
        }
    }

    @Test
    fun `built-in ids are unique and stable`() {
        // Stable, not just unique: a shared playlist stores built-in ids, and a
        // cloud `beat_ids` array can mix a built-in id with a custom uuid.
        // Renaming an id silently orphans every playlist that referenced it.
        // These are slug-derived from the names by scripts/generateBuiltinBeats.mjs,
        // so a renamed beat gets a new id — which is why the set is pinned here.
        val ids = BEATS.mapNotNull { it.id }
        assertEquals("duplicate built-in ids", ids.size, ids.distinct().size)
        assertEquals(
            setOf(
                "double_time_2", "daspahir_taal", "tehai", "pick_up", "bhajani_taal",
                "matan", "lofa_taal_two_beat_damodarastakam", "iskcon_smasher",
                "keherva_medium_speed",
            ),
            ids.toSet(),
        )
    }

    @Test
    fun `built-in beats are read-only and marked as built in`() {
        for (beat in BEATS) {
            assertTrue("${beat.id} should be built in", beat.isBuiltIn)
            assertTrue("${beat.id} should be read-only", beat.readOnly)
        }
    }

    @Test
    fun `every shipped grid divides the bar evenly`() {
        // The step counts in the set. What actually matters is not the list but
        // the property below it: for each, PPQ × beatsPerBar must divide evenly by
        // steps, or the loop drifts. Compound meters make this worth checking —
        // `matan` is 48 cells over 24 quarters, so cells-per-group (3) is not
        // cells-per-quarter (2).
        assertEquals(setOf(8, 16, 24, 32, 48), BEATS.map { it.steps }.toSet())

        for (beat in BEATS) {
            val ticksPerBar = MusicalClock.PPQ * beat.beatsPerBar
            assertEquals(
                "${beat.id}: ${ticksPerBar} ticks per bar does not divide into ${beat.steps} steps",
                0.0,
                ticksPerBar % beat.steps,
                1e-9,
            )
        }
    }

    @Test
    fun `a beat either has cymbals that ring on its pulses, or has no cymbal row`() {
        // The convention: "no cymbals" means the kartal key is ABSENT, not present
        // and all rests. The strip's lane filter and the editor's save both rely
        // on that, so an all-rest brass row would render as an empty lane.
        var withCymbals = 0
        for (beat in BEATS) {
            val kartal = beat.pattern(LaneId.KARTAL)
            if (kartal == null) continue
            withCymbals++
            assertTrue(
                "${beat.id} has a kartal row with no hits in it",
                kartal.any { it != null },
            )
            // The cymbals are the congregation's timekeeper: they ring on the
            // numbered pulses. Checking this catches a pattern shifted by a cell
            // during authoring, which is easy to do and hard to hear.
            assertEquals(
                "${beat.id}: the first pulse must ring",
                Stroke.OPEN,
                kartal[0],
            )
        }
        assertTrue("expected at least one beat with a cymbal row", withCymbals >= 1)
    }

    @Test
    fun `group headings are distinct in first-appearance order and need not be contiguous`() {
        for (beat in BEATS) assertNotNull("${beat.id} has no group heading", beat.group)

        // NOT contiguous, and that is fine: Iskcon smasher is a Sixteenths beat
        // that sits AFTER the two Straight ones in BEATS. The Beats screen takes
        // the DISTINCT headings in first-appearance order and then FILTERS the
        // full list by heading, so a non-adjacent beat still lands in the right
        // section. Asserting contiguity would be asserting a constraint the app
        // has never had.
        assertEquals(listOf("Sixteenths", "Straight"), BUILT_IN_GROUPS)

        // Every beat appears in exactly one section, and sections reproduce the
        // beats in BEATS order — that is what the screen renders.
        val rendered = BUILT_IN_GROUPS.flatMap { g -> BEATS.filter { it.group == g } }
        assertEquals("a beat was rendered twice or not at all", BEATS.size, rendered.size)
        assertEquals(BEATS.map { it.id }.toSet(), rendered.map { it.id }.toSet())

        // The section that actually exercises the non-contiguity: keherva is a
        // Straight beat that comes after two Sixteenths ones in BEATS.
        assertEquals(
            listOf("matan", "lofa_taal_two_beat_damodarastakam", "keherva_medium_speed"),
            BEATS.filter { it.group == "Straight" }.map { it.id },
        )
        assertEquals(
            "the Sixteenths section should hold everything else",
            BEATS.size - 3,
            BEATS.filter { it.group == "Sixteenths" }.size,
        )
    }

    // ── Meter derivation ───────────────────────────────────────────────────

    @Test
    fun `cpq is derived from timing fields, never from cellsPerGroup`() {
        // The subtle one, and the reason cpqFor exists separately: for 6/8 the
        // editor saves cellsPerGroup 3 while cpq is 2, so reading cellsPerGroup
        // would make the beat play half again too fast.
        val sixEight = BEATS.first().copy(
            id = null, steps = 12, beatsPerBar = 6.0, cellsPerGroup = 3, groups = listOf(3, 3),
        )
        assertEquals(2, cpqFor(sixEight))
        assertEquals(3, sixEight.cellsPerGroup)
        assertEquals(listOf(3, 3), groupsFor(sixEight))
    }

    @Test
    fun `every built-in stores its groups, and they sum to its steps`() {
        // The current set is generated from a share payload, which always carries
        // groups, so reconstruction is not exercised by the shipped data. It is
        // still supported — see the next test — for beats that predate the model.
        for (beat in BEATS) {
            val groups = beat.groups
            assertNotNull("${beat.id} should carry its groups", groups!!)
            assertEquals("${beat.id}: groups must sum to steps", beat.steps, sumGroups(groups))

            // The same rule ShareCodec.decodeBeat applies: uniform groups keep
            // their size as cellsPerGroup, uneven ones fall back to cells-per-
            // quarter. If the generator and the decoder ever disagree, a beat
            // round-trips to a different meter than it shipped with.
            val uniform = groups.all { it == groups[0] }
            assertEquals(
                "${beat.id}: cellsPerGroup disagrees with the codec's rule",
                if (uniform) groups[0] else cpqFor(beat),
                beat.cellsPerGroup,
            )
        }
    }

    @Test
    fun `groups are reconstructed for a beat that predates the groups model`() {
        // Older beats — and anything hand-written before groups existed — carry
        // only cellsPerGroup. groupsFor has to rebuild the bar from that,
        // uniformly, and land on the same step count.
        val legacy = BEATS.first().copy(id = null, groups = null)
        val groups = groupsFor(legacy)
        assertEquals(legacy.steps, sumGroups(groups))
        assertTrue("reconstruction should be uniform", groups.all { it == groups[0] })
        assertEquals(legacy.cellsPerGroup, groups[0])
    }

    @Test
    fun `stored groups win over reconstruction`() {
        val uneven = BEATS.first().copy(
            id = null, steps = 7, beatsPerBar = 3.5, cellsPerGroup = 2, groups = listOf(2, 2, 3),
        )
        assertEquals(listOf(2, 2, 3), groupsFor(uneven))
        assertEquals(7, sumGroups(groupsFor(uneven)))
    }

    @Test
    fun `bpm is clamped to the shared bounds`() {
        assertEquals(MIN_BPM, clampBpm(0))
        assertEquals(MAX_BPM, clampBpm(9999))
        assertEquals(120, clampBpm(120))
    }

    // ── Step labels ────────────────────────────────────────────────────────

    @Test
    fun `guided labels number the downbeats and dot the subdivisions`() {
        assertEquals(listOf("1", "·", "2", "·", "3", "·", "4", "·"), generateGuidedLabels(8, 2))
        assertEquals(listOf("1", "·", "·", "2", "·", "·"), generateGuidedLabels(6, 3))
    }

    @Test
    fun `labels from groups support uneven meters`() {
        // 7/8 as [2,2,3] — the case a single cellsPerGroup cannot express, and
        // the reason labelsFromGroups exists alongside generateGuidedLabels.
        assertEquals(listOf("1", "·", "2", "·", "3", "·", "·"), labelsFromGroups(listOf(2, 2, 3)))
    }

    @Test
    fun `labels agree with the pattern length they will sit above`() {
        for (beat in BEATS) {
            val labels = labelsFromGroups(groupsFor(beat))
            assertEquals("${beat.id}: one label per cell", beat.steps, labels.size)
        }
    }

    @Test
    fun `a degenerate group size cannot divide by zero`() {
        // cellsPerGroup is always >= 1 in real data, so this input cannot occur.
        // It is checked because the two platforms differ and the difference is
        // deliberate: the JS version computes `i % 0`, which is NaN, and every
        // label silently becomes a dot. Treating 0 as 1 keeps every cell a
        // downbeat instead — still wrong for a nonsense input, but it does not
        // produce an unlabelled grid, and it cannot throw.
        assertEquals(listOf("1", "2", "3"), generateGuidedLabels(3, 0))
    }

    // ── Tuning ─────────────────────────────────────────────────────────────

    @Test
    fun `cents to rate matches the equal-temperament definition`() {
        assertEquals(1.0, centsToRate(0), 1e-12)
        assertEquals(2.0, centsToRate(1200), 1e-12)       // one octave up
        assertEquals(0.5, centsToRate(-1200), 1e-12)      // one octave down
        assertEquals(2.0.pow(1 / 12.0), centsToRate(100), 1e-12) // one semitone
        // ±600 is a tritone either way, and the two must be exact reciprocals:
        // tuning up then down by the same amount has to land back on the pitch.
        assertEquals(1.0, centsToRate(600) * centsToRate(-600), 1e-12)
    }

    @Test
    fun `cents are clamped to the shared bounds`() {
        assertEquals(TUNE_MIN_CENTS, clampCents(-99999))
        assertEquals(TUNE_MAX_CENTS, clampCents(99999))
        assertEquals(0, clampCents(0))
    }

    @Test
    fun `note naming puts A440 at A4 with no residual`() {
        assertEquals("A4", freqToNoteName(440.0))
        assertEquals("A5", freqToNoteName(880.0))
        assertEquals("C4", freqToNoteName(261.6256))
    }

    @Test
    fun `the measured end pitches name the notes the web app names`() {
        // These exact strings come from running src/data/tuning.js's own
        // freqToNoteName under Node against END_BASE_FREQ, so this asserts
        // cross-platform parity rather than my arithmetic. The residual is what
        // keeps the readout honest: a hand-tuned drum sits between notes, and
        // showing "E5" for something 46 cents flat would be a lie the user could
        // hear. Note the U+2212 minus sign, which the web readout uses.
        assertEquals("E5 −46¢", freqToNoteName(END_BASE_FREQ["dayan"]!!))
        assertEquals("D♯2 +47¢", freqToNoteName(END_BASE_FREQ["bayan"]!!))
    }

    @Test
    fun `both mridanga ends have a measured base frequency`() {
        // The mixer's tune readout divides by these; a missing entry would show
        // nothing rather than fail loudly.
        for (lane in listOf(LaneId.DAYAN, LaneId.BAYAN)) {
            val freq = END_BASE_FREQ[lane.wireId]
            assertNotNull("no base frequency for ${lane.wireId}", freq)
            assertTrue("${lane.wireId} base frequency is not positive", freq!! > 0.0)
        }
        // The karatalas are never tuned, so they must NOT have an entry — an
        // absent key is how the mixer knows not to offer a tune slider.
        assertNull(END_BASE_FREQ[LaneId.KARTAL.wireId])
    }

    // ── Stroke visuals and bols ────────────────────────────────────────────

    @Test
    fun `every stroke has a visual and unknown values fall back to a rest`() {
        for (stroke in Stroke.entries) {
            val visual = strokeVisual(stroke)
            assertNotEquals("a real stroke must not render as a rest", "rest", visual.label)
            assertNotNull("a real stroke must have a colour", visual.color)
        }
        assertEquals(REST_VISUAL, strokeVisual(null))
    }

    @Test
    fun `only the mridanga ends carry an EQ chain`() {
        // Frozen by design: the karatalas are a separate instrument, not a head
        // of the mridanga, so their signal path stays untouched.
        assertEquals(5, EQ_BAND_COUNT)
        assertEquals(5, EQ_BANDS.size)
        assertEquals(listOf(-12.0, 12.0), listOf(EQ_MIN_DB, EQ_MAX_DB))
        assertEquals(FilterType.LOW_SHELF, EQ_BANDS.first().type)
        assertEquals(FilterType.HIGH_SHELF, EQ_BANDS.last().type)
        // The shelves take no Q; the three peaking bands all do.
        assertNull(EQ_BANDS[0].q)
        assertNull(EQ_BANDS[4].q)
        for (i in 1..3) assertNotNull("band $i is peaking and needs a Q", EQ_BANDS[i].q)
        assertEquals(EQ_MIN_DB, clampEqDb(-99.0), 0.0)
        assertEquals(EQ_MAX_DB, clampEqDb(99.0), 0.0)
        assertEquals(-3.0, clampEqDb(-3.0), 0.0)
    }

    @Test
    fun `bols name every stroke and the two-hand combinations`() {
        assertEquals("Ta", Bols.forStroke("dayan", "O"))
        assertEquals("Te", Bols.forStroke("dayan", "X"))
        assertEquals("Ge", Bols.forStroke("bayan", "O"))
        assertEquals("Khe", Bols.forStroke("bayan", "X"))
        assertEquals("Ching", Bols.forStroke("kartal", "O"))
        assertEquals("Chip", Bols.forStroke("kartal", "X"))

        assertEquals("Da", Bols.combo("O", "O"))
        assertEquals("Gi", Bols.combo("X", "O"))
        assertEquals("Ta·Khe", Bols.combo("O", "X"))
        assertEquals("Te·Khe", Bols.combo("X", "X"))

        // A rest has no spoken name, and neither does an unknown lane.
        assertNull(Bols.forStroke("dayan", null))
        assertNull(Bols.forStroke("melody", "O"))
        assertNull(Bols.combo(null, "O"))
    }

    // ── Stroke codes ───────────────────────────────────────────────────────

    @Test
    fun `stroke codes round-trip and unknown characters read as rests`() {
        for (stroke in Stroke.entries) assertEquals(stroke, Stroke.fromCode(stroke.code))
        assertNull(Stroke.fromCode(null))
        assertNull(Stroke.fromCode("-"))
        assertNull(Stroke.fromCode("Z"))
    }

    @Test
    fun `lane wire ids are what the share format keys patterns by`() {
        // These strings are part of the wire format, not an implementation
        // detail: a shared link's `p` object is keyed by them, so changing one
        // would break every link already in circulation.
        assertEquals("dayan", LaneId.DAYAN.wireId)
        assertEquals("bayan", LaneId.BAYAN.wireId)
        assertEquals("kartal", LaneId.KARTAL.wireId)
        assertEquals(listOf(LaneId.DAYAN, LaneId.BAYAN, LaneId.KARTAL), LaneId.ORDERED)
        for (lane in LaneId.ORDERED) assertEquals(lane, LaneId.fromWire(lane.wireId))
        assertNull(LaneId.fromWire("melody"))
    }

    @Test
    fun `only the mridanga ends are primary lanes`() {
        assertTrue(LaneId.DAYAN.primary)
        assertTrue(LaneId.BAYAN.primary)
        // The karatalas colour a beat, they don't define its rhythmic identity,
        // so mini strips leave them out.
        assertTrue(!LaneId.KARTAL.primary)
        assertEquals(2, LaneId.ORDERED.count { it.primary })
    }

    @Test
    fun `every lane has a palette token`() {
        for (lane in LaneId.ORDERED) assertNotNull(lane.colorToken)
    }
}
