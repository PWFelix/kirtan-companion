package com.kirtan.companion.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kirtan.companion.R
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.ui.Wordmark
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.theme.color

/**
 * The entry screen.
 *
 * Ported from `src/Splash.jsx`. On the web this screen exists because of a
 * platform rule: browsers refuse to start audio before a user gesture, so the
 * Begin tap does double duty as the audio unlock, and the async library load
 * hides behind it. `Splash.jsx` says so in its own header — *"Future native
 * builds keep this screen but drop the button — native apps have no audio-unlock
 * requirement."*
 *
 * This port keeps the button anyway, for a different reason: it is still the
 * moment the user has said "I want sound now", which is the right time to bind
 * the media controller and open the output device. Opening an AudioTrack on
 * process start instead would hold the device through the whole time the app sits
 * in the background, and would take audio focus before the user asked for it.
 *
 * The wheel is not an `<img>` on the web either — it is a clay-filled div with
 * the PNG's transparency used as a CSS mask, so it recolours to the exact `--clay`
 * token. A tint [ColorFilter] is the same idea and re-tints with any future theme.
 */
@Composable
internal fun SplashScreen(
    ready: Boolean,
    onBegin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Same edge-to-edge contract as KirtanApp: the window draws behind the
            // system bars, so the splash clears them itself.
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(PaletteToken.HEAD.color),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.dharma_wheel),
            contentDescription = null,
            modifier = Modifier.size(560.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(PaletteToken.CLAY.color.copy(alpha = 0.10f)),
        )

        // The block sits 40dp above true centre, which is what puts the WORDMARK's
        // centre — not the block's — on the wheel's centre, once the button's
        // height and the gap below it are accounted for.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Wordmark(size = 38.dp)
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                // The label reflects loading but never disables: the web app's
                // rule is that the button is always pressable, because the load
                // resolving is not a precondition for entering the app.
                label = if (ready) "Begin" else "Begin · loading sounds…",
                onClick = onBegin,
                modifier = Modifier.widthIn(max = 300.dp),
                minHeight = 56.dp,
                radius = 16.dp,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
