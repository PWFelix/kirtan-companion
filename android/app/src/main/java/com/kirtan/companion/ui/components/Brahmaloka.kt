package com.kirtan.companion.ui.components

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.kirtan.companion.R
import com.kirtan.companion.data.BRAHMA_HOLD_MS
import com.kirtan.companion.data.NORMAL_MAX_BPM
import com.kirtan.companion.data.tempoRange
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.theme.color
import com.kirtan.companion.ui.theme.frauncesFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Brahmaloka time — the tempo slider's high range and the weather that opens it.
// The rules are pure and live in data/Meter.kt; this file is only the feel:
// the hold-to-unlock slider, the ✦ badge, the storm overlay and the thunder.
// Same design as the web's hooks/useBrahmaHold.js + ui/BrahmalokaBurst.jsx.
// ─────────────────────────────────────────────────────────────────────────────

private val Gold = Color(0xFFF2C14E)
private val Fire = Color(0xFFD9541E)
private val Ember = Color(0xFFB8471A)

/**
 * The tempo slider: 40–200, or zoomed out to 40–500 in Brahmaloka — same width,
 * the thumb moves. Screens put the ✦ badge (the way back) by the BPM number, never
 * in the slider's row, so the slider never loses width or pushes its row off screen. Holding the thumb at 200
 * for [BRAHMA_HOLD_MS] calls [onUnlock], with a glow swelling round the thumb
 * while you hold so it is clear something is coming.
 *
 * KEYED ON THE MODE so the slider is rebuilt when the range changes. That ends
 * the drag in progress — which is the point: the finger is still pressed at the
 * right-hand end, and a live drag on the new 40–500 range would fling the
 * tempo to ~490 on its next move.
 */
@Composable
internal fun BpmSlider(
    bpm: Int,
    brahmaloka: Boolean,
    onChange: (Int) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier) {
    key(brahmaloka) {
        val range = tempoRange(brahmaloka)
        val source = remember { MutableInteractionSource() }
        val dragged by source.collectIsDraggedAsState()
        val pressed by source.collectIsPressedAsState()
        val charging = enabled && !brahmaloka && bpm >= NORMAL_MAX_BPM && (dragged || pressed)
        val unlock by rememberUpdatedState(onUnlock)
        val glow = remember { Animatable(0f) }
        LaunchedEffect(charging) {
            if (!charging) {
                glow.snapTo(0f)
                return@LaunchedEffect
            }
            launch { glow.animateTo(1f, tween(BRAHMA_HOLD_MS.toInt(), easing = FastOutLinearInEasing)) }
            delay(BRAHMA_HOLD_MS)
            unlock()
        }
        KcSlider(
            value = bpm.coerceIn(range.first, range.last).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            accent = if (brahmaloka) Fire else PaletteToken.CLAY.color,
            interactionSource = source,
            modifier = Modifier.fillMaxWidth().drawBehind {
                val g = glow.value
                if (g <= 0f) return@drawBehind
                // Charging only happens at 200, so the thumb is at the right end.
                val c = Offset(size.width - 10.dp.toPx(), size.height / 2)
                val r = (14 + 26 * g).dp.toPx()
                drawCircle(
                    Brush.radialGradient(listOf(Gold.copy(alpha = 0.75f * g), Fire.copy(alpha = 0.35f * g), Color.Transparent), c, r),
                    radius = r, center = c,
                )
            },
        )
    }
    }
}

/** Shown in Brahmaloka: says so, and is the way back to 40–200. */
@Composable
internal fun BrahmalokaBadge(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.brahma_leave)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFFF7E3B5))
            .border(BorderStroke(1.dp, Color(0xFFD49A2A)), CircleShape)
            .clickable(role = Role.Button, onClick = onLeave)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("✦", color = Ember, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The thunder: brown noise through a lowpass sinking 900→120 Hz, over a sub-bass
 * sine falling 48→30 Hz. Synthesised into a one-shot AudioTrack on its own
 * thread — a UI sound, deliberately outside the engine's mix and clock, so it
 * can never disturb the beat that may be playing.
 */
internal object Rumble {
    private const val RATE = 48_000
    private const val SECONDS = 2.4

    fun play() {
        thread(name = "brahmaloka-rumble", isDaemon = true) {
            runCatching {
                val n = (RATE * SECONDS).toInt()
                val pcm = ShortArray(n)
                var brown = 0.0
                var lp = 0.0
                var phase = 0.0
                for (i in 0 until n) {
                    val t = i / RATE.toDouble()
                    brown = (brown + 0.02 * (Random.nextDouble() * 2 - 1)) / 1.02
                    val cutoff = 900 * (120.0 / 900).pow(t / SECONDS)
                    lp += (1 - exp(-2 * PI * cutoff / RATE)) * (brown * 3.5 - lp)
                    val noiseEnv = min(1.0, t / 0.03) * 0.9 * (1 - t / SECONDS).pow(2)
                    val f = 48 * (30.0 / 48).pow(min(t, 1.8) / 1.8)
                    phase += 2 * PI * f / RATE
                    val subEnv = min(1.0, t / 0.08) * 0.5 * max(0.0, 1 - t / 1.8).pow(2)
                    val s = (lp * 4 * noiseEnv + sin(phase) * subEnv).coerceIn(-1.0, 1.0)
                    pcm[i] = (s * 0.8 * Short.MAX_VALUE).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build()
                track.write(pcm, 0, n)
                track.play()
                Thread.sleep((SECONDS * 1000).toLong() + 200)
                track.release()
            }
        }
    }
}

private const val BURST_MS = 2200f

private class Bolt(val at: Float, val pts: List<Offset>, val thin: Boolean)
private class EmberDrop(val x: Float, val born: Float, val vy: Float, val drift: Float, val size: Float, val hue: Float, val phase: Float)
private class Storm(val bolts: List<Bolt>, val embers: List<EmberDrop>)

/** A jagged line by midpoint displacement. */
private fun boltPath(x0: Float, y0: Float, x1: Float, y1: Float, spread0: Float): List<Offset> {
    var pts = listOf(Offset(x0, y0), Offset(x1, y1))
    var spread = spread0
    repeat(6) {
        val next = ArrayList<Offset>(pts.size * 2)
        next += pts[0]
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            next += Offset((a.x + b.x) / 2 + (Random.nextFloat() - 0.5f) * spread, (a.y + b.y) / 2)
            next += b
        }
        pts = next
        spread *= 0.55f
    }
    return pts
}

private fun makeStorm(w: Float, h: Float, density: Float): Storm {
    val bolts = ArrayList<Bolt>()
    fun strike(at: Float) {
        val x = w * (0.15f + Random.nextFloat() * 0.7f)
        val main = boltPath(x, -10f, x + (Random.nextFloat() - 0.5f) * w * 0.4f, h * (0.55f + Random.nextFloat() * 0.3f), w * 0.25f)
        bolts += Bolt(at, main, false)
        val fork = main[(main.size * (0.3f + Random.nextFloat() * 0.3f)).toInt()]
        bolts += Bolt(at, boltPath(fork.x, fork.y, fork.x + (Random.nextFloat() - 0.5f) * w * 0.5f, fork.y + h * 0.25f, w * 0.12f), true)
    }
    strike(0f); strike(0.05f); strike(0.42f)
    val embers = List(160) {
        EmberDrop(
            x = Random.nextFloat() * w,
            born = Random.nextFloat() * 0.75f,
            vy = (320 + Random.nextFloat() * 520) * density / 2.6f,
            drift = (Random.nextFloat() - 0.5f) * 60 * density / 2.6f,
            size = (1.5f + Random.nextFloat() * 3.5f) * density / 1.6f,
            hue = 18 + Random.nextFloat() * 30,
            phase = Random.nextFloat() * 2 * PI.toFloat(),
        )
    }
    return Storm(bolts, embers)
}

private fun DrawScope.drawStorm(storm: Storm, f: Float) {
    // Storm-dark wash so the light has something to burn against, under a saffron glow.
    drawRect(Color(0xFF260E08).copy(alpha = 0.62f * (1 - f).pow(0.7f)))
    drawRect(Color(0xFFFF781E).copy(alpha = 0.22f * (1 - f)))
    val flash = max(0f, 1 - f / 0.08f) * 0.85f + max(0f, 1 - abs(f - 0.43f) / 0.05f) * 0.5f
    if (flash > 0) drawRect(Color(0xFFFFF8E1).copy(alpha = min(1f, flash)))

    for (b in storm.bolts) {
        val age = f - b.at
        if (age < 0 || age > 0.35f) continue
        val a = (1 - age / 0.35f) * (0.6f + 0.4f * Random.nextFloat())
        val path = Path().apply {
            moveTo(b.pts[0].x, b.pts[0].y)
            for (i in 1 until b.pts.size) lineTo(b.pts[i].x, b.pts[i].y)
        }
        // No shadowBlur in Compose: stack wide, faint strokes for the glow.
        val base = if (b.thin) 1.5f else 3.5f
        drawPath(path, Color(0xFFFFBE50).copy(alpha = 0.18f * a), style = Stroke(base * 9, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color(0xFFFFD27A).copy(alpha = 0.4f * a), style = Stroke(base * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color(0xFFFFECAA).copy(alpha = a), style = Stroke(base * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = a), style = Stroke(base * 0.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    val secs = f * BURST_MS / 1000f
    val fade = min(1f, (1 - f) * 3)
    for (e in storm.embers) {
        if (f < e.born) continue
        val t = secs - e.born * BURST_MS / 1000f
        val y = -20f + e.vy * t
        if (y > size.height + 20) continue
        val x = e.x + e.drift * t + sin(e.phase + t * 6) * 6
        val head = Color.hsv(min(360f, e.hue + 15), 0.85f, 1f).copy(alpha = fade)
        drawLine(
            Brush.verticalGradient(listOf(head.copy(alpha = 0f), head), startY = y - e.size * 7, endY = y),
            Offset(x, y - e.size * 7), Offset(x, y), strokeWidth = e.size, cap = StrokeCap.Round,
        )
    }
}

/**
 * Wraps the app. Each new [fireKey] (above zero) plays ~2 s of weather over
 * everything — flash, forked lightning, fire rain, the content shuddering, a
 * haptic thump and the title — then leaves nothing behind. The overlay takes no
 * input, so it can never steal a tap.
 *
 * With animations switched off in system settings (the Android reduced-motion
 * switch) there is no shake, flash or particles — only the title fading.
 */
@Composable
internal fun BrahmalokaStage(fireKey: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val calm = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // 1 = idle. Driven by frame time rather than an Animatable so a zero
    // animator scale can't skip it (that would also skip the calm caption).
    var f by remember { mutableFloatStateOf(1f) }
    val storm = remember(fireKey) { arrayOfNulls<Storm>(1) }

    LaunchedEffect(fireKey) {
        if (fireKey <= 0) return@LaunchedEffect
        launch {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            delay(160)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            delay(260)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        val t0 = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            f = min(1f, (now - t0) / 1e6f / BURST_MS)
            if (f >= 1f) break
        }
    }

    val active = f < 1f
    Box(modifier) {
        Box(
            Modifier.graphicsLayer {
                if (active && !calm) {
                    val ms = f * BURST_MS
                    if (ms < 650) {
                        val amp = 7.dp.toPx() * (1 - ms / 650)
                        translationX = amp * sin(ms / 1000 * 2 * PI.toFloat() * 12)
                        translationY = amp * 0.45f * sin(ms / 1000 * 2 * PI.toFloat() * 9)
                        rotationZ = 0.4f * (1 - ms / 650) * sin(ms / 1000 * 2 * PI.toFloat() * 7)
                    }
                }
            },
        ) { content() }

        if (active) {
            if (!calm) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = storm[0] ?: makeStorm(size.width, size.height, density).also { storm[0] = it }
                    drawStorm(s, f)
                }
            }
            // Caption: in by 18%, held, out over the last quarter.
            val ms = f * BURST_MS
            val alpha = when {
                ms < 0.18f * BURST_MS -> ms / (0.18f * BURST_MS)
                ms > 0.75f * BURST_MS -> (BURST_MS - ms) / (0.25f * BURST_MS)
                else -> 1f
            }.coerceIn(0f, 1f)
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 24.dp).graphicsLayer { this.alpha = alpha },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.brahma_title),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = frauncesFamily(FontWeight.Bold, 40.sp),
                        fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFF4DC),
                        shadow = Shadow(Color(0xFFE8762A), Offset.Zero, 28f),
                    ),
                )
                Text(
                    stringResource(R.string.brahma_caption),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                    style = TextStyle(
                        fontSize = 15.sp, fontStyle = FontStyle.Italic, color = Color(0xFFFFF4DC),
                        shadow = Shadow(Color(0xFF9A3412), Offset.Zero, 14f),
                    ),
                )
            }
        }
    }
}
