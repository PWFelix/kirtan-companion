package com.kirtan.companion.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color

/** The five destinations, in the fixed order the web app's tab array uses. */
internal enum class Tab(val icon: ImageVector, val label: String) {
    HOME(KcIcons.Home, "Home"),
    BEATS(KcIcons.Beats, "Beats"),
    EDITOR(KcIcons.Pencil, "Editor"),
    LEARN(KcIcons.Cap, "Learn"),
    SETTINGS(KcIcons.Cog, "Settings"),
}

/** index.css's nav easing: `cubic-bezier(0.22, 1, 0.36, 1)`, decelerating. */
private val NavEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * The bottom navigation, with its sliding clay pill.
 *
 * Ported from `src/ui/BottomNav.jsx`. Two properties of the original are worth
 * carrying over, because they are the difference between a nav bar that feels made
 * and one that feels assembled:
 *
 *  1. THE SAME ELEMENT ON EVERY SCREEN. On the web one `<BottomNav>` instance is
 *     built once per render and handed to each screen, so the bar is literally the
 *     same node across a navigation and never shifts. The equivalent here is that
 *     the bar lives OUTSIDE the screen-switching content in
 *     [com.kirtan.companion.ui.KirtanApp], not inside each screen — which is also
 *     why every screen takes its frame from
 *     [com.kirtan.companion.ui.components.ScreenFrame] rather than choosing its own
 *     padding. index.css calls this out as a hard invariant; the editor included.
 *
 *  2. THE PILL SLIDES, THE INK WAITS FOR IT. index.css delays the active label's
 *     colour change by 130ms so the ink arrives as the pill settles rather than
 *     racing it, and applies NO delay when a cell is losing focus — so the old
 *     label doesn't linger over the moving pill. That asymmetry is reproduced
 *     below with the delay conditioned on `isActive`.
 *
 * Tapping Editor does not switch screens: it opens a blank draft with no back
 * button, which is why [onOpenEditor] is separate from the other four callbacks.
 */
@Composable
internal fun BottomNav(
    active: Tab,
    modifier: Modifier = Modifier,
    onHome: () -> Unit,
    onBeats: () -> Unit,
    onOpenEditor: () -> Unit,
    onLearn: () -> Unit,
    onSettings: () -> Unit,
) {
    val dimens = KirtanTheme.dimens
    val rule = KirtanTheme.colors.rule
    val barShape = RoundedCornerShape(dimens.radiusPill)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = dimens.screenMaxWidth)
            .clip(barShape)
            .background(PaletteToken.HEAD_WORN.color)
            .border(BorderStroke(dimens.hairline, rule), barShape)
            .padding(dimens.navPillInset)
            .height(dimens.navCellHeight),
    ) {
        // The pill is positioned in Dp derived from the measured bar width, so it
        // stays correct if the bar is ever narrower than the screen (it is: the
        // frame caps at 430dp on a tablet).
        val cellWidth: Dp = maxWidth / Tab.entries.size
        val pillOffset by animateDpAsState(
            targetValue = cellWidth * active.ordinal,
            animationSpec = tween(durationMillis = 260, easing = NavEasing),
            label = "navPill",
        )

        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(cellWidth)
                .fillMaxHeight()
                .clip(barShape)
                .background(PaletteToken.CLAY.color)
        )

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Tab.entries.forEach { tab ->
                NavCell(
                    tab = tab,
                    isActive = tab == active,
                    onClick = when (tab) {
                        Tab.HOME -> onHome
                        Tab.BEATS -> onBeats
                        Tab.EDITOR -> onOpenEditor
                        Tab.LEARN -> onLearn
                        Tab.SETTINGS -> onSettings
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** One tab: icon over a 10px tracked label, inked by [isActive]. */
@Composable
private fun NavCell(
    tab: Tab,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KirtanTheme.dimens
    val target = if (isActive) PaletteToken.ON_CLAY.color else PaletteToken.SYAHI_SOFT.color

    // The 130ms delay on the way IN only — see property 2 in the header.
    val ink by animateColorAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = 120,
            delayMillis = if (isActive) 130 else 0,
        ),
        label = "navInk",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(dimens.radiusPill))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(dimens.navLabelGap))
        Text(
            text = tab.label,
            color = ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
