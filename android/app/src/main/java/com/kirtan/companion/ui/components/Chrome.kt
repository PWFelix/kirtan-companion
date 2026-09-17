package com.kirtan.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color

/**
 * The chrome every screen is built from.
 *
 * Ported from the shared style objects in `src/ui/styles.js` — the one place the
 * web app deliberately allows styles to live outside a screen, for chrome two or
 * more screens use. Same rule here: anything a single screen draws stays in that
 * screen's file.
 *
 * The visual language is flat matte material colour, so almost every component is
 * a shape, a background and a **1px warm hairline border**. There are no drop
 * shadows anywhere in this app: index.css replaces elevation with `--rule`, and
 * carrying that over is what keeps the Android build looking like the same
 * instrument rather than a Material-themed approximation of it.
 */

/**
 * The screen frame.
 *
 * maxWidth 430dp centred, with the padding the web frame uses. One invariant
 * matters more than the numbers: **the bottom nav must sit at exactly the same
 * position on every screen**, or it visibly shifts when the user switches tabs.
 * `styles.js` calls this out explicitly, and it is why every screen here takes its
 * padding from this one function rather than choosing its own.
 */
@Composable
internal fun ScreenFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = KirtanTheme.dimens
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PaletteToken.HEAD.color),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = dimens.screenMaxWidth)
                .fillMaxWidth()
                .padding(
                    start = dimens.screenPaddingSide,
                    end = dimens.screenPaddingSide,
                    top = dimens.screenPaddingTop,
                    bottom = dimens.screenPaddingBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(dimens.screenGap),
            content = content,
        )
    }
}

/** A square icon button with a hairline border — the app's most common control. */
@Composable
internal fun HairlineIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = PaletteToken.SYAHI_SOFT.color,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusIconButton)
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .background(
                if (filled) PaletteToken.CLAY.color else Color.Transparent
            )
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.border(BorderStroke(dimens.hairline, dimens.let { KirtanTheme.colors.rule }), shape)
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (filled) PaletteToken.ON_CLAY.color else tint,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/**
 * The primary action: clay background, warm-white label, uppercase and tracked.
 *
 * Full width at thumb height on the play bar, because the one thing this app must
 * never do is make starting a kirtan hard to find or easy to fumble.
 */
@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    minHeight: Dp = 58.dp,
    radius: Dp = KirtanTheme.dimens.radiusPlayBar,
    weight: FontWeight = FontWeight.Bold,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(radius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(PaletteToken.CLAY.color)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = dimens.space4),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PaletteToken.ON_CLAY.color,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            color = PaletteToken.ON_CLAY.color,
            fontSize = 15.2.sp,
            fontWeight = weight,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A secondary, hairline-bordered action button. */
@Composable
internal fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    minHeight: Dp = 46.dp,
) {
    val dimens = KirtanTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPad)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .border(BorderStroke(dimens.hairline, KirtanTheme.colors.rule), shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = dimens.space4),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PaletteToken.SYAHI.color,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = label,
            color = PaletteToken.SYAHI.color,
            fontSize = 15.2.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A labelled slider in a lane's own colour.
 *
 * The web app's `.kc-range` paints the filled part of the track in the accent and
 * the rest in the cell colour, and callers override the accent per lane so an EQ
 * slider reads as belonging to that instrument. [accent] is that override.
 */
@Composable
internal fun KcSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    accent: Color = PaletteToken.CLAY.color,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val dimens = KirtanTheme.dimens
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        colors = SliderDefaults.colors(
            thumbColor = PaletteToken.HEAD.color,
            activeTrackColor = accent,
            inactiveTrackColor = PaletteToken.HEAD_SUNKEN.color,
            disabledThumbColor = PaletteToken.HEAD.color,
            disabledActiveTrackColor = accent,
            disabledInactiveTrackColor = PaletteToken.HEAD_SUNKEN.color,
        ),
    )
}

/**
 * The bottom-sheet chrome shared by every sheet in the app.
 *
 * Top corners at 20dp, head-coloured surface, a title in the display face and a
 * 44dp close button. Material3's [ModalBottomSheet] supplies the scrim, the drag
 * and the dismissal; what is overridden is the colour and shape, because the
 * default Material sheet would look like a different app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KcSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = dimens.radiusSheet, topEnd = dimens.radiusSheet),
        containerColor = PaletteToken.HEAD.color,
        scrimColor = com.kirtan.companion.ui.theme.SheetScrim,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimens.space5,
                        end = dimens.space3,
                        bottom = dimens.space4,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = PaletteToken.SYAHI.color,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (headerAction != null) {
                    headerAction()
                    Spacer(Modifier.width(dimens.space2))
                }
                HairlineIconButton(
                    icon = KcIcons.Back,
                    contentDescription = "Close",
                    onClick = onDismiss,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = dimens.space5,
                        end = dimens.space5,
                        bottom = dimens.space6,
                    ),
                verticalArrangement = Arrangement.spacedBy(dimens.space4),
                content = content,
            )
        }
    }
}

/** A section label: small, uppercase, semibold, tracked. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color ?: PaletteToken.SYAHI_SOFT.color,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
    )
}

/** A hairline rule — this app's substitute for a shadow. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(KirtanTheme.dimens.hairline)
            .background(KirtanTheme.colors.rule)
    )
}
