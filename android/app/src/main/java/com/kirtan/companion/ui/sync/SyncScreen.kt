package com.kirtan.companion.ui.sync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.storage.EqPrefsSnapshot
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.components.KcSlider
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.transport.TransportViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sync sound and display: line the playhead up with what is actually heard on
 * the current output, typically a Bluetooth speaker that lags the screen.
 *
 * Two ways in, because they suit different people:
 *
 *  1. TAP ALONG. A plain click plays; the user taps on each one, eyes off the
 *     screen. Where the taps land relative to the clicks the phone thinks are
 *     sounding is the delay the phone did not report ([SyncCalibration]). Ten
 *     seconds, and accurate, because people anticipate a steady beat rather than
 *     react to it.
 *  2. LINE UP. A light flashes on each click, drawn from the same playhead as
 *     the strip, and a slider moves it until flash and click coincide. For
 *     fine-tuning, or for anyone who finds tapping awkward.
 *
 * The click only runs while this screen is open; leaving stops it and gives the
 * drum its feel back.
 */
@Composable
internal fun SyncScreen(transport: TransportViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by transport.state.collectAsState()
    val dimens = KirtanTheme.dimens
    val scope = rememberCoroutineScope()

    var clicking by remember { mutableStateOf(false) }
    var tapping by remember { mutableStateOf(false) }
    val taps = remember { mutableStateListOf<Double>() }
    var result by remember { mutableStateOf<SyncCalibration.Result?>(null) }
    var reportedMs by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        onDispose { transport.stopSyncTest() }
    }

    fun startClick() {
        if (clicking) return
        scope.launch { transport.startSyncTest() }
        clicking = true
    }

    fun stopClick() {
        transport.stopSyncTest()
        clicking = false
        tapping = false
    }

    // What the phone itself reports, refreshed while the click runs. Only
    // meaningful while sound is flowing, so hidden otherwise.
    LaunchedEffect(clicking) {
        if (!clicking) { reportedMs = null; return@LaunchedEffect }
        while (true) {
            delay(500)
            reportedMs = transport.engine.reportedOutputLatencyMs()
        }
    }

    // The flash: lit for the first fifth of each beat, by the playhead WITH the
    // delay applied, so it shows exactly what the strip will.
    var flash by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(clicking) {
        if (!clicking) { flash = 0f; return@LaunchedEffect }
        while (true) {
            withFrameNanos {
                val position = transport.getPhase() * 4
                val into = position - kotlin.math.floor(position)
                flash = if (into < 0.2) (1 - into / 0.2).toFloat() else 0f
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = dimens.screenMaxWidth)
            .padding(dimens.screenPaddingSide)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(dimens.space4),
    ) {
        Spacer(Modifier.height(dimens.space4))
        HairlineIconButton(icon = KcIcons.Back, contentDescription = "Back", onClick = onBack)

        Text(
            text = "Sync sound and display",
            color = PaletteToken.SYAHI.color,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Bluetooth speakers play a little after the screen. If the strip " +
                "or the highway runs ahead of the drum, set the delay here. Each " +
                "speaker remembers its own.",
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

        RouteCard(
            label = state.route.label,
            bluetooth = state.route.bluetooth,
            delayMs = state.displayDelayMs,
            reportedMs = reportedMs,
        )

        // ── 1. Tap along ───────────────────────────────────────────────────
        SectionLabel("1 · Tap along")
        Text(
            text = when {
                !tapping -> "Start the click, then look away and tap the circle " +
                    "on every click you hear."
                else -> "Tap on every click. Listen, don't watch."
            },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

        val counted = (taps.size - SyncCalibration.WARM_UP).coerceAtLeast(0)
        TapPad(
            enabled = tapping,
            label = when {
                tapping -> "${counted.coerceAtMost(SyncCalibration.TAPS_NEEDED)} / ${SyncCalibration.TAPS_NEEDED}"
                else -> "Tap"
            },
            onTap = { uptimeMillis ->
                if (!tapping) return@TapPad
                taps.add(transport.engine.offsetFromNearestStepMs(uptimeMillis * 1_000_000L))
                if (SyncCalibration.complete(taps)) {
                    val r = SyncCalibration.estimate(taps)
                    result = r
                    tapping = false
                    if (r is SyncCalibration.Result.Delay) transport.setDisplayDelay(r.ms)
                }
            },
        )

        when (val r = result) {
            is SyncCalibration.Result.Delay -> Text(
                text = "Done. The display now runs ${describe(r.ms)}. " +
                    "Check it below and fine-tune if you need to.",
                color = PaletteToken.SYAHI.color,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            SyncCalibration.Result.TooUneven -> Text(
                text = "Those taps were too uneven to measure. Try again, " +
                    "keeping a steady tap with the click.",
                color = PaletteToken.CLAY.color,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            null -> Unit
        }

        PrimaryButton(
            label = when {
                tapping -> "Cancel"
                result != null -> "Tap again"
                else -> "Start tapping"
            },
            onClick = {
                if (tapping) {
                    tapping = false
                } else {
                    taps.clear()
                    result = null
                    startClick()
                    tapping = true
                }
            },
        )

        // ── 2. Line up ─────────────────────────────────────────────────────
        SectionLabel("2 · Line up")
        Text(
            text = "The light flashes where the display thinks each click is. " +
                "Slide until flash and click happen together.",
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space4),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(PaletteToken.CLAY.color.copy(alpha = 0.12f + 0.88f * flash))
                    .semantics { contentDescription = "Flash on each click" },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = describe(state.displayDelayMs).replaceFirstChar { it.uppercase() },
                    color = PaletteToken.SYAHI.color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                KcSlider(
                    value = state.displayDelayMs.toFloat(),
                    onValueChange = { transport.setDisplayDelay(((it / 5f).roundToInt()) * 5) },
                    valueRange = EqPrefsSnapshot.DISPLAY_DELAY_MIN_MS.toFloat()..
                        EqPrefsSnapshot.DISPLAY_DELAY_MAX_MS.toFloat(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            SecondaryButton(
                label = if (clicking) "Stop click" else "Play click",
                onClick = { if (clicking) stopClick() else startClick() },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = "Reset to 0",
                onClick = { transport.setDisplayDelay(0) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(dimens.space6))
    }
}

/** "on time", "120 ms later", "30 ms earlier". */
private fun describe(ms: Int): String = when {
    ms == 0 -> "on time, with no extra delay"
    ms > 0 -> "$ms ms later"
    else -> "${-ms} ms earlier"
}

@Composable
private fun RouteCard(label: String, bluetooth: Boolean, delayMs: Int, reportedMs: Int?) {
    val dimens = KirtanTheme.dimens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusPad))
            .background(KirtanTheme.colors.rule.copy(alpha = 0.35f))
            .padding(dimens.space4),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "PLAYING THROUGH",
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            text = if (bluetooth) "$label · Bluetooth" else label,
            color = PaletteToken.SYAHI.color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = buildString {
                append("Display delay: ")
                append(if (delayMs == 0) "none" else "${if (delayMs > 0) "+" else ""}$delayMs ms")
                if (reportedMs != null) append(" · phone reports $reportedMs ms, already allowed for")
            },
            color = PaletteToken.SYAHI_SOFT.color,
            fontSize = 13.sp,
        )
    }
}

/**
 * The big round tap target. Reports the touch's own event time, not the time
 * the handler ran, so a busy UI thread cannot skew the measurement.
 */
@Composable
private fun TapPad(enabled: Boolean, label: String, onTap: (uptimeMillis: Long) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(if (pressed) 0.95f else 1f)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(CircleShape)
                .background(PaletteToken.CLAY.color.copy(alpha = if (pressed) 0.30f else 0.14f))
                .border(3.dp, PaletteToken.CLAY.color, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onTap(down.uptimeMillis)
                        pressed = true
                        waitForUpOrCancellation()
                        pressed = false
                    }
                }
                .semantics { contentDescription = "Tap pad: tap on every click" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = PaletteToken.CLAY_DEEP.color,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
