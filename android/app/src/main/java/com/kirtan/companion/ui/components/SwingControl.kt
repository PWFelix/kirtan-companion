package com.kirtan.companion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.R
import com.kirtan.companion.data.Swing
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.theme.color
import kotlin.math.roundToInt

/**
 * The swing slider, shared by the mixer (a live nudge) and the beat editor
 * (saved with the beat): 50 straight to 67 triplet, whole percent steps.
 */
@Composable
internal fun SwingControl(value: Int, onChange: (Int) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KirtanTheme.dimens.space1)) {
        SectionLabel(stringResource(R.string.swing_title))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KcSlider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = Swing.STRAIGHT.toFloat()..Swing.MAX.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = swingLabel(value),
                color = PaletteToken.SYAHI.color,
                fontSize = 13.4.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(72.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Text(text = hint, color = PaletteToken.SYAHI_SOFT.color, fontSize = 12.sp)
    }
}

@Composable
private fun swingLabel(value: Int): String = when {
    value <= Swing.STRAIGHT -> stringResource(R.string.swing_straight)
    value >= Swing.MAX -> stringResource(R.string.swing_triplet)
    else -> "$value%"
}
