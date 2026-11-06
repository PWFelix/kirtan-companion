package com.kirtan.companion.ui.beats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One button revealed behind a swiped row. */
internal data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val content: Color,
    val onClick: () -> Unit,
)

/**
 * A row that slides left to reveal [actions] behind it, the way a mail list does.
 *
 * Hand-rolled on a horizontal drag rather than Material's SwipeToDismiss because
 * that one DISMISSES — a full swipe deletes — and here a swipe only reveals: a
 * beat is someone's work, and deleting it should take a deliberate tap, then a
 * confirmation, not a flick on the way to scrolling.
 *
 * Open state is HOISTED ([open] / [onOpenChange]) so the list can keep a single
 * row open: opening one closes the last, as on iOS. Horizontal drags only, so the
 * LazyColumn's vertical scroll is untouched.
 */
@Composable
internal fun SwipeActionsRow(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val density = LocalDensity.current
    val actionWidth = 76.dp
    val gap = dimens.space1
    val revealPx = with(density) { (actionWidth * actions.size + gap * actions.size).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Follow the hoisted state, so closing from outside (another row opened, an
    // action taken) animates this row shut.
    LaunchedEffect(open, revealPx) {
        offset.animateTo(if (open) -revealPx else 0f, spring(stiffness = 600f))
    }

    Box(modifier = modifier) {
        // The actions sit under the row's right edge, uncovered as it slides.
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (action in actions) {
                Column(
                    modifier = Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(dimens.radiusPad))
                        .background(action.background)
                        .clickable {
                            onOpenChange(false)
                            action.onClick()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = action.content,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = action.label,
                        color = action.content,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(revealPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dx).coerceIn(-revealPx, 0f))
                            }
                        },
                        // Past halfway it opens, otherwise it springs shut.
                        onDragEnd = {
                            val shouldOpen = offset.value < -revealPx / 2
                            onOpenChange(shouldOpen)
                            scope.launch {
                                offset.animateTo(if (shouldOpen) -revealPx else 0f, spring(stiffness = 600f))
                            }
                        },
                        onDragCancel = {
                            scope.launch { offset.animateTo(if (open) -revealPx else 0f) }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

/** The palette's colours for the two library actions, in one place. */
internal object SwipeColors {
    val shareBg: Color @Composable get() = PaletteToken.STRAP.color
    val shareFg: Color @Composable get() = PaletteToken.ON_CLAY.color
    val deleteBg: Color @Composable get() = PaletteToken.DANGER.color
    val deleteFg: Color @Composable get() = PaletteToken.ON_CLAY.color
}
