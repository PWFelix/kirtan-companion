package com.kirtan.companion.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.R
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.Wordmark
import com.kirtan.companion.ui.components.HairlineIconButton
import com.kirtan.companion.ui.icons.KcIcons
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color

/**
 * About: the name, the splash's mridanga, and the dedication.
 *
 * Opened from Settings and drawn in Settings' place rather than as a sheet — it
 * is a page to rest on, not a control to dismiss. Back (arrow or system gesture)
 * returns to Settings.
 *
 * The drawing sits behind the lockup at the splash's faint clay tint, so this
 * reads as the splash again, quietly, rather than as a new design.
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val dimens = KirtanTheme.dimens
    val context = LocalContext.current
    // From the package rather than BuildConfig, so this needs no build-feature flag.
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.screenPaddingSide)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(dimens.space4))
        HairlineIconButton(icon = KcIcons.Back, contentDescription = "Back", onClick = onBack)

        Box(
            modifier = Modifier.fillMaxWidth().height(380.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.splash_mridanga),
                contentDescription = null,
                modifier = Modifier.size(380.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(PaletteToken.CLAY.color.copy(alpha = 0.22f)),
            )
            Wordmark(size = 34.dp)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.space4),
        ) {
            Text(
                text = "paraṁ vijayate śrī-kṛṣṇa-saṅkīrtanam",
                color = PaletteToken.CLAY.color,
                fontSize = 18.sp,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "a World Sankirtan Party production",
                color = PaletteToken.SYAHI.color,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "All glories to Śrīla Prabhupāda!",
                color = PaletteToken.SYAHI.color,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            version?.let {
                Text(
                    text = "Version $it",
                    color = KirtanTheme.colors.faint,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(dimens.space4))
        }
    }
}
