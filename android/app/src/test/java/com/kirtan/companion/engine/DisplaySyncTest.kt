package com.kirtan.companion.engine

import com.kirtan.companion.storage.EqPrefsCodec
import com.kirtan.companion.storage.EqPrefsSnapshot
import com.kirtan.companion.ui.sync.SyncCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Display sync, end to end in its pure parts: where the presented frame is
 * extrapolated from the output timestamp, how taps become a delay, and that the
 * per-device delays survive a save.
 */
class DisplaySyncTest {

    private val rate = 48_000

    // ── OutputClock ────────────────────────────────────────────────────────

    @Test
    fun `the presented frame advances at the sample rate from the stamp`() {
        val stamp = OutputClock.Stamp(frame = 10_000, nanos = 1_000_000_000)
        // 100 ms later: 4800 frames on.
        assertEquals(
            14_800L,
            OutputClock.presentedFrame(stamp, 1_100_000_000, rate, rendered = 50_000),
        )
    }

    @Test
    fun `a missing or stale stamp is not trusted`() {
        assertNull(OutputClock.presentedFrame(null, 0, rate, 50_000))
        val stamp = OutputClock.Stamp(frame = 10_000, nanos = 0)
        assertNull(OutputClock.presentedFrame(stamp, OutputClock.STALE_NANOS + 1, rate, 50_000))
    }

    @Test
    fun `extrapolation never runs past what has been rendered`() {
        val stamp = OutputClock.Stamp(frame = 10_000, nanos = 0)
        assertEquals(12_000L, OutputClock.presentedFrame(stamp, 500_000_000, rate, rendered = 12_000))
    }

    @Test
    fun `milliseconds convert to signed frames`() {
        assertEquals(9_600L, OutputClock.msToFrames(200, rate))
        assertEquals(-2_400L, OutputClock.msToFrames(-50, rate))
    }

    // ── SyncCalibration ────────────────────────────────────────────────────

    private fun taps(vararg ms: Double) = ms.toList()

    @Test
    fun `no estimate until enough taps`() {
        assertNull(SyncCalibration.estimate(List(SyncCalibration.WARM_UP + SyncCalibration.TAPS_NEEDED - 1) { 100.0 }))
    }

    @Test
    fun `the delay is the median of the taps after the warm-up, rounded`() {
        // Warm-up taps are wild and ignored; one slip of 400 does not drag it.
        val offsets = taps(-300.0, 380.0, 148.0, 152.0, 150.0, 146.0, 155.0, 151.0, 149.0, 400.0, 153.0, 147.0)
        assertEquals(SyncCalibration.Result.Delay(150), SyncCalibration.estimate(offsets))
    }

    @Test
    fun `scattered taps are rejected rather than guessed at`() {
        val offsets = taps(0.0, 0.0, -200.0, 150.0, -50.0, 220.0, 10.0, -180.0, 90.0, 300.0, -120.0, 40.0)
        assertEquals(SyncCalibration.Result.TooUneven, SyncCalibration.estimate(offsets))
    }

    @Test
    fun `a phone that over-reports gives a negative delay`() {
        val offsets = List(12) { -42.0 }
        assertEquals(SyncCalibration.Result.Delay(-40), SyncCalibration.estimate(offsets))
    }

    // ── Prefs ──────────────────────────────────────────────────────────────

    @Test
    fun `per-device delays and prompted devices round-trip`() {
        val snapshot = EqPrefsSnapshot.DEFAULT.copy(
            displayDelays = mapOf("bt:JBL Flip 5" to 180, "speaker" to -20),
            syncPrompted = setOf("bt:JBL Flip 5", "bt:Pixel Buds"),
        )
        val back = EqPrefsCodec.decode(EqPrefsCodec.encode(snapshot))
        assertEquals(snapshot.displayDelays, back.displayDelays)
        assertEquals(snapshot.syncPrompted, back.syncPrompted)
        assertEquals(180, back.displayDelay("bt:JBL Flip 5"))
        assertEquals(0, back.displayDelay("bt:never seen"))
    }

    @Test
    fun `out-of-range or junk delays are clamped or dropped`() {
        val back = EqPrefsCodec.decode(
            """{"displayDelays":{"a":5000,"b":-900,"c":"soon","d":42.4},"syncPrompted":["x",3]}""",
        )
        assertEquals(
            mapOf(
                "a" to EqPrefsSnapshot.DISPLAY_DELAY_MAX_MS,
                "b" to EqPrefsSnapshot.DISPLAY_DELAY_MIN_MS,
                "d" to 42,
            ),
            back.displayDelays,
        )
        assertEquals(setOf("x"), back.syncPrompted)
    }

    @Test
    fun `old prefs without the keys decode to no delays`() {
        val back = EqPrefsCodec.decode("""{"feel":"off"}""")
        assertEquals(emptyMap<String, Int>(), back.displayDelays)
        assertEquals(emptySet<String>(), back.syncPrompted)
    }
}
