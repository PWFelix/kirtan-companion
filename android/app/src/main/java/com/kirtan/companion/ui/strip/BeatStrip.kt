package com.kirtan.companion.ui.strip

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.Bols
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.data.SUBDIVISION_LABEL
import com.kirtan.companion.data.colorToken
import com.kirtan.companion.data.generateGuidedLabels
import com.kirtan.companion.data.labelsFromGroups
import com.kirtan.companion.data.model.Beat
import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import kotlin.math.roundToInt

/** The gap between cells, and the minimum a cell may be squeezed to. */
private val CELL_GAP = 2.dp
private val CELL_MIN_FULL = 34.dp
private val CELL_MIN_MINI = 9.dp
private val CELL_HEIGHT_FULL = 46.dp
private val CELL_HEIGHT_MINI = 16.dp

/**
 * The straight-line beat visualiser.
 *
 * Ported from `src/BeatStrip.jsx`. One component, three jobs: full-size on Home,
 * miniature beside each row of the library, and (with [onCellTap]) the editor's
 * overview. The web app's circular `BeatIndicator` still exists in its source but
 * nothing imports it — this replaced it, and it is deliberately NOT ported.
 *
 * ── THE MOTION MODEL ──
 * Two things move while playing, and they move for different reasons:
 *
 *  1. THE PLAYHEAD glides continuously. It reads the engine's bar phase once per
 *     frame inside [withFrameNanos] and writes an offset. Phase comes from the
 *     audio clock rather than from an animation, so it stays exactly in step with
 *     the sound and self-corrects — a declarative animation would drift against
 *     the tempo, and a drift you can hear against a drum is unbearable.
 *  2. THE ACTIVE CELL lights up, driven by [step] as an ordinary parameter.
 *
 * On the web these were imperative DOM writes, to avoid rebuilding a memoised
 * tree. Compose does not need that trick — reading state inside a narrow
 * composable already confines the invalidation — but the principle carries over
 * and is the reason phase is NOT hoisted into a ViewModel: at 60 Hz it would
 * invalidate every collector to move one vertical bar.
 *
 * A sounding mark turns SYAHI-BLACK rather than to its lane colour. That
 * inversion is the design's one poetic gesture: on a real drumhead the black
 * circle at the centre is where the sound comes from.
 *
 * NOT PORTED: the loop-position bar under the strip (a 4dp track with a
 * strap-coloured window showing which part of an overflowing beat is on screen).
 * It is a scroll affordance only, and horizontal scrolling here is rare — the
 * cell sizing below guarantees eight cells always fit.
 */
@Composable
internal fun BeatStrip(
    beat: Beat,
    modifier: Modifier = Modifier,
    step: Int = -1,
    playing: Boolean = false,
    getPhase: () -> Double = { 0.0 },
    mutedEnds: Map<LaneId, Boolean> = emptyMap(),
    onToggleMute: ((LaneId) -> Unit)? = null,
    onCellTap: ((LaneId, Int) -> Unit)? = null,
    showBols: Boolean = false,
    mini: Boolean = false,
) {
    // Mini strips show PRIMARY lanes only: a beat's rhythmic identity. At 8dp
    // marks four stacked lanes are noise. A full strip always draws the primaries
    // but draws a non-primary lane only when the beat actually has a HIT in it —
    // which keeps a beat with no cymbals from sprouting an empty brass row. That
    // matters most for imported beats: the share codec hands every lane an
    // all-rest array whether the sender used it or not, so "has data" is not the
    // same test as "has a hit".
    val lanes = remember(beat, mini) {
        beat.activeLanes().filter { lane ->
            if (mini) lane.primary
            else lane.primary || beat.pattern(lane)?.any { it != null } == true
        }
    }

    val cellMin = if (mini) CELL_MIN_MINI else CELL_MIN_FULL
    val cellHeight = if (mini) CELL_HEIGHT_MINI else CELL_HEIGHT_FULL

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = maxWidth

        // Cell width solves the constraint the web CSS expresses as
        // `repeat(steps, minmax(cellmin, 1fr))`: short beats stretch to fill the
        // row, long beats fall back to the minimum and overflow into a scroll.
        // `maxOf` rather than `kotlin.math.max`, which has no Dp overload.
        val gaps = CELL_GAP * (beat.steps - 1).coerceAtLeast(0)
        val flexible = ((available - gaps) / beat.steps).coerceAtLeast(0.dp)
        val cellWidth = maxOf(cellMin, flexible)
        val trackWidth = cellWidth * beat.steps + gaps
        val scrollable = trackWidth > available

        val scrollState = rememberScrollState()
        val trackWidthPx = with(LocalDensity.current) { trackWidth.toPx() }

        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .width(if (scrollable) trackWidth else available)
                    .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
                verticalArrangement = Arrangement.spacedBy(if (mini) 3.dp else 6.dp),
            ) {
                if (!mini) {
                    NumbersRow(beat = beat, cellWidth = cellWidth)
                }
                for (lane in lanes) {
                    LaneBlock(
                        lane = lane,
                        beat = beat,
                        step = step,
                        playing = playing,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        mini = mini,
                        muted = mutedEnds[lane] == true,
                        showBols = showBols,
                        onToggleMute = onToggleMute,
                        onCellTap = onCellTap,
                    )
                }
            }

            if (!mini) {
                // matchParentSize is a BoxScope extension, so it can only be
                // written here, at the call site inside the wrapper Box.
                Playhead(
                    modifier = Modifier.matchParentSize(),
                    playing = playing,
                    getPhase = getPhase,
                    trackWidthPx = trackWidthPx,
                    scrollState = scrollState,
                    steps = beat.steps,
                    scrollable = scrollable,
                )
            }
        }
    }
}

/**
 * The numbered downbeats and subdivision dots above the lanes.
 *
 * The first cell of each group carries its beat number; subdivisions carry a
 * middle dot (U+00B7) rather than a "+", because it is visually lighter and lets
 * the numbered pulses read clearly for a learner — Group B is who this row is
 * for. Index 0 is the SAM, the first beat of the cycle, and is coloured clay.
 */
@Composable
private fun NumbersRow(beat: Beat, cellWidth: androidx.compose.ui.unit.Dp) {
    val colors = KirtanTheme.colors
    val labels = remember(beat) {
        // An explicit groups array means an uneven meter (7/8 as [2,2,3]); without
        // one, labels are derived from the uniform cellsPerGroup.
        val groups = beat.groups
        if (groups != null) labelsFromGroups(groups)
        else generateGuidedLabels(beat.steps, beat.cellsPerGroup)
    }

    Row(
        modifier = Modifier
            .height(18.dp)
            // Decorative: the cells below already carry the semantics, and a
            // screen reader reciting "1 dot dot 2 dot dot" is noise.
            .semantics { contentDescription = "" },
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
    ) {
        repeat(beat.steps) { index ->
            val label = labels.getOrElse(index) { SUBDIVISION_LABEL }
            val isDot = label == SUBDIVISION_LABEL
            Text(
                text = label,
                modifier = Modifier
                    .width(cellWidth)
                    .alpha(if (isDot) 0.4f else 0.9f),
                fontSize = 11.sp,
                fontWeight = if (isDot) FontWeight.Normal else FontWeight.Bold,
                color = if (index == 0) PaletteToken.CLAY.color else colors.faint,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * A lane label above its row, doubling as a mute toggle.
 *
 * ABOVE rather than in a side column, because that width is needed to fit eight
 * cells on a small phone — the hard constraint the whole strip is designed around:
 * 8 × 34dp + 7 × 2dp = 286dp, which fits inside even a 320dp-wide screen after
 * padding. Losing it would mean the most common beat always scrolls.
 */
@Composable
private fun LaneBlock(
    lane: LaneId,
    beat: Beat,
    step: Int,
    playing: Boolean,
    cellWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    mini: Boolean,
    muted: Boolean,
    showBols: Boolean,
    onToggleMute: ((LaneId) -> Unit)?,
    onCellTap: ((LaneId, Int) -> Unit)?,
) {
    val laneColor = lane.colorToken.color

    Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        if (!mini) {
            val description = if (muted) {
                "${lane.label} drum, muted, tap to unmute"
            } else {
                "${lane.label} drum, playing, tap to mute"
            }
            Text(
                text = lane.label.uppercase(),
                modifier = Modifier
                    .alpha(if (muted) 0.35f else 1f)
                    .then(
                        if (onToggleMute != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onToggleMute(lane) }
                        } else {
                            Modifier
                        }
                    )
                    .semantics { contentDescription = description },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = laneColor,
            )
        }

        // Muted lanes dim rather than disappear: a player practising one hand
        // still needs to see where the other's pattern sits in the bar.
        Row(
            modifier = Modifier
                .alpha(if (muted) 0.3f else 1f)
                .semantics { contentDescription = "${lane.label} pattern" },
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            // Exactly `beat.steps` cells whatever the stored pattern's length:
            // extra data is ignored, missing data renders as a rest. That is the
            // honest rendering of mismatched data, and it matches what the
            // sequencer actually plays.
            repeat(beat.steps) { index ->
                val stroke = beat.strokeAt(lane, index)
                Cell(
                    stroke = stroke,
                    laneColor = laneColor,
                    isActive = playing && index == step && !muted,
                    isSam = index == 0,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    mini = mini,
                    bol = if (showBols && !mini) Bols.forStroke(lane.wireId, stroke?.code) else null,
                    onClick = onCellTap?.let { callback -> { callback(lane, index) } },
                )
            }
        }
    }
}

/**
 * One cell: its background, the sam marker, and the stroke's shape.
 *
 * Filled circle = open, ring = closed, small dot = rest, each in the lane's
 * colour; the SOUNDING one turns syahi and scales up 1.3×. The scale is on the
 * mark rather than the cell so a sounding stroke grows inside its own slot
 * without nudging its neighbours.
 */
@Composable
private fun Cell(
    stroke: Stroke?,
    laneColor: androidx.compose.ui.graphics.Color,
    isActive: Boolean,
    isSam: Boolean,
    cellWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    mini: Boolean,
    bol: String?,
    onClick: (() -> Unit)?,
) {
    val dimens = KirtanTheme.dimens
    val markColor = if (isActive) PaletteToken.SYAHI.color else laneColor
    // The mark shrinks when a bol is printed so BOTH fit inside the cell's fixed
    // height. Reserving extra height per cell that HAS a bol — the obvious
    // implementation — makes a row ragged: rest cells end up shorter than struck
    // ones, leaving whitespace under them and costing three rows' worth of
    // vertical space on a phone, which is exactly the scarcest resource here.
    val markSize = when {
        mini -> 8.dp
        bol != null -> 15.dp
        else -> 18.dp
    }

    Column(
        modifier = Modifier
            .width(cellWidth)
            .height(cellHeight)
            .then(
                if (mini) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(dimens.radiusStripCell))
                        .background(PaletteToken.HEAD_WORN.color)
                }
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(markSize)
                .scale(if (isActive) 1.3f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            when (stroke) {
                Stroke.OPEN -> Box(
                    Modifier
                        .size(markSize)
                        .background(markColor, CircleShape)
                )

                Stroke.CLOSED -> Box(
                    Modifier
                        .size(markSize)
                        .border(if (mini) 2.dp else 3.5.dp, markColor, CircleShape)
                )

                // A rest is a small dot in the rule colour: present, so the grid
                // still reads as a rhythm, but visually recessive.
                null -> Box(
                    Modifier
                        .size(if (mini) 3.dp else 6.dp)
                        .background(
                            if (isActive) PaletteToken.SYAHI.color else PaletteToken.RULE.color,
                            CircleShape,
                        )
                )
            }
        }

        if (bol != null) {
            Text(
                text = bol,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PaletteToken.SYAHI_SOFT.color,
                maxLines = 1,
            )
        }
    }
}

/**
 * The playhead, plus the page-follow behaviour for beats too long to fit.
 *
 * PAGE-TURN, NOT CONTINUOUS SCROLL. When the strip overflows, the viewport holds
 * still while the line sweeps across the visible cells, then turns to the next
 * whole-cell "page" in one motion. Scrolling continuously would have the strip
 * moving, the line moving and the cells lighting all at once, which is unreadable
 * at tempo; a page of written music behaves exactly the way this does.
 *
 * On stop the offset AND the scroll position both reset — otherwise a long beat
 * paused mid-page stays parked on that page, and the next Start plays its first
 * bar with the playhead scrolled out of view.
 */
@Composable
private fun Playhead(
    modifier: Modifier = Modifier,
    playing: Boolean,
    getPhase: () -> Double,
    trackWidthPx: Float,
    scrollState: ScrollState,
    steps: Int,
    scrollable: Boolean,
) {
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing, steps) {
        if (!playing) {
            phase = 0f
            scrollState.scrollTo(0)
            return@LaunchedEffect
        }
        var lastPage = -1
        while (true) {
            withFrameNanos { phase = getPhase().toFloat() }

            if (scrollable) {
                val viewport = scrollState.viewportSize
                val total = scrollState.maxValue + viewport
                if (viewport > 0 && total > viewport + 1) {
                    val cellWidthPx = total.toFloat() / steps
                    val perPage = maxOf(1, (viewport / cellWidthPx).roundToInt())
                    val page = ((phase * steps) / perPage).toInt()
                    if (page != lastPage) {
                        lastPage = page
                        val target = (page * perPage * cellWidthPx).roundToInt()
                            .coerceIn(0, scrollState.maxValue)
                        scrollState.scrollTo(target)
                    }
                }
            }
        }
    }

    if (!playing) return

    // The line is placed as a fraction of the TRACK width, not the viewport, so
    // no pixel measurement of individual cells is needed — the same reason the
    // web version positions it with `left: phase * 100%`.
    //
    // matchParentSize, NOT fillMaxSize: the strip lives inside a scroll region
    // whose height constraint is unbounded, and fillMaxSize against an unbounded
    // max inflates this Box past the lanes it is meant to overlay, adding dead
    // space under the last row. Matching the parent sizes it to exactly the
    // track's own height.
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (phase * trackWidthPx).roundToInt() - scrollState.value,
                        y = 0,
                    )
                }
                .width(2.dp)
                .fillMaxHeight()
                .background(PaletteToken.SYAHI.color)
        )
    }
}
