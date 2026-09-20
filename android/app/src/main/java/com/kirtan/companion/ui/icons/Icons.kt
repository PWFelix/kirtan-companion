package com.kirtan.companion.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtan.companion.ui.theme.Clay
import com.kirtan.companion.ui.theme.OnClay
import com.kirtan.companion.ui.theme.Rule

/**
 * Every icon the app draws.
 *
 * Ported one-for-one from `src/ui/icons.jsx`, which keeps them together for the
 * same reason: icons are the one thing that genuinely IS shared chrome — the
 * pencil appears on Home and in the nav, the speaker in the mixer, the info dot
 * in the beat list. Hunting for "where is the lock drawn" should be one file,
 * not four.
 *
 * Each is an [ImageVector] on the SVG's own 24×24 viewBox, with `defaultWidth`
 * and `defaultHeight` set to the pixel size that component used (`width="18"`
 * → 18.dp). So `Icon(KcIcons.LockClosed, …)` with no size modifier lands on the
 * size the web drew it at, and the stroke weight scales with it exactly as it
 * does in the browser: `strokeWidth="1.8"` on a 24 viewBox rendered at 18px is
 * 1.35px, and here it is 1.35dp.
 *
 * They are drawn in black and left that way. Every one of these icons is a
 * stroke on `currentColor` in the JSX, which means the calling button's colour
 * drives it and no icon needs a colour prop — `Icon(…, tint = …)` reproduces
 * that precisely, because a solid tint is applied as a `SrcIn` colour filter
 * over the drawn strokes. Passing a colour into the vector instead would bake
 * it in and break the tinting.
 *
 * Strokes stay strokes. None of these has been traced into a filled outline:
 * `strokeLineWidth`, `StrokeCap.Round` and `StrokeJoin.Round` carry the same
 * numbers the SVG declared, so the icons keep the soft, slightly hand-drawn
 * ends that the flat matte palette depends on. (Every icon in the JSX that has
 * a corner also declares `strokeLinejoin="round"`; the four that do not — beats,
 * mixer, info, search — are made of disjoint single-segment strokes with no
 * corners at all, so the join is immaterial and stated once here rather than
 * per icon.)
 *
 * The three transport glyphs ([Play], [Pause], [Start]) are the exception: the
 * JSX fills them, "solid, not stroked, so they read at a glance on the clay play
 * bar", and they are filled here too.
 */

/** The viewBox every icon in `icons.jsx` uses. */
private const val ViewBox = 24f

/** `strokeWidth="1.8"` on every icon except the check. */
private const val StrokeWidth = 1.8f

/** `strokeWidth="2.4"` — the check is deliberately heavier. */
private const val CheckStrokeWidth = 2.4f

/**
 * The colour the vectors are authored in. It is never seen: `Icon`'s tint
 * replaces it via a `SrcIn` filter, which is the Compose equivalent of the
 * `currentColor` these SVGs stroke with.
 */
private val IconPaint = SolidColor(Color.Black)

/**
 * Build a stroked icon. One `path` holds every subpath of the SVG — the JSX
 * often splits them across elements, but they all carry the same stroke and
 * nothing overlaps at partial alpha, so merging them changes nothing and keeps
 * one vector per icon.
 */
private fun strokedIcon(
    name: String,
    size: Dp,
    strokeWidth: Float = StrokeWidth,
    body: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size,
    defaultHeight = size,
    viewportWidth = ViewBox,
    viewportHeight = ViewBox,
).apply {
    path(
        fill = null,
        stroke = IconPaint,
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = body,
    )
}.build()

/** Build one of the filled transport glyphs. */
private fun filledIcon(
    name: String,
    size: Dp,
    body: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size,
    defaultHeight = size,
    viewportWidth = ViewBox,
    viewportHeight = ViewBox,
).apply {
    path(fill = IconPaint, pathBuilder = body)
}.build()

internal object KcIcons {

    /**
     * The tempo lock, LOCKED — the shackle closes over the body.
     *
     * An icon and not the words "Lock"/"Locked" for the reason the JSX gives:
     * the button's size must not change with state, or it resizes the flexible
     * slider sitting beside it.
     */
    val LockClosed: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("LockClosed", 18.dp) {
            // <rect x="4" y="11" width="16" height="10" rx="2" />
            moveTo(6f, 11f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(6f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineToRelative(-12f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-6f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
            // M8 11V7a4 4 0 0 1 8 0v4
            moveTo(8f, 11f)
            verticalLineTo(7f)
            arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
            verticalLineToRelative(4f)
        }
    }

    /** The tempo lock, FREE — the shackle springs open to the left. */
    val LockOpen: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("LockOpen", 18.dp) {
            moveTo(6f, 11f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(6f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineToRelative(-12f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-6f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
            // M8 11V7a4 4 0 0 1 7.7-1.5
            moveTo(8f, 11f)
            verticalLineTo(7f)
            arcToRelative(4f, 4f, 0f, false, true, 7.7f, -1.5f)
        }
    }

    /** The Home tab. */
    val Home: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Home", 22.dp) {
            moveTo(3f, 10.2f)
            lineTo(12f, 3f)
            lineToRelative(9f, 7.2f)
            verticalLineTo(20f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            horizontalLineToRelative(-5f)
            verticalLineToRelative(-6f)
            horizontalLineTo(9f)
            verticalLineToRelative(6f)
            horizontalLineTo(4f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            close()
        }
    }

    /** The Settings tab. */
    val Cog: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Cog", 22.dp) {
            moveTo(12.22f, 2f)
            horizontalLineToRelative(-0.44f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineToRelative(0.18f)
            arcToRelative(2f, 2f, 0f, false, true, -1f, 1.73f)
            lineToRelative(-0.43f, 0.25f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 0f)
            lineToRelative(-0.15f, -0.08f)
            arcToRelative(2f, 2f, 0f, false, false, -2.73f, 0.73f)
            lineToRelative(-0.22f, 0.38f)
            arcToRelative(2f, 2f, 0f, false, false, 0.73f, 2.73f)
            lineToRelative(0.15f, 0.1f)
            arcToRelative(2f, 2f, 0f, false, true, 1f, 1.72f)
            verticalLineToRelative(0.51f)
            arcToRelative(2f, 2f, 0f, false, true, -1f, 1.74f)
            lineToRelative(-0.15f, 0.09f)
            arcToRelative(2f, 2f, 0f, false, false, -0.73f, 2.73f)
            lineToRelative(0.22f, 0.38f)
            arcToRelative(2f, 2f, 0f, false, false, 2.73f, 0.73f)
            lineToRelative(0.15f, -0.08f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 0f)
            lineToRelative(0.43f, 0.25f)
            arcToRelative(2f, 2f, 0f, false, true, 1f, 1.73f)
            verticalLineTo(20f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(0.44f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineToRelative(-0.18f)
            arcToRelative(2f, 2f, 0f, false, true, 1f, -1.73f)
            lineToRelative(0.43f, -0.25f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 0f)
            lineToRelative(0.15f, 0.08f)
            arcToRelative(2f, 2f, 0f, false, false, 2.73f, -0.73f)
            lineToRelative(0.22f, -0.39f)
            arcToRelative(2f, 2f, 0f, false, false, -0.73f, -2.73f)
            lineToRelative(-0.15f, -0.08f)
            arcToRelative(2f, 2f, 0f, false, true, -1f, -1.74f)
            verticalLineToRelative(-0.5f)
            arcToRelative(2f, 2f, 0f, false, true, 1f, -1.74f)
            lineToRelative(0.15f, -0.09f)
            arcToRelative(2f, 2f, 0f, false, false, 0.73f, -2.73f)
            lineToRelative(-0.22f, -0.38f)
            arcToRelative(2f, 2f, 0f, false, false, -2.73f, -0.73f)
            lineToRelative(-0.15f, 0.08f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 0f)
            lineToRelative(-0.43f, -0.25f)
            arcToRelative(2f, 2f, 0f, false, true, -1f, -1.73f)
            verticalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
            close()
            // <circle cx="12" cy="12" r="3" />
            moveTo(9f, 12f)
            arcToRelative(3f, 3f, 0f, false, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, false, true, -6f, 0f)
            close()
        }
    }

    /** The Beats tab — four bars of uneven height, the strip in miniature. */
    val Beats: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Beats", 20.dp) {
            moveTo(4f, 6f)
            verticalLineToRelative(12f)
            moveTo(9f, 9f)
            verticalLineToRelative(6f)
            moveTo(14f, 4f)
            verticalLineToRelative(16f)
            moveTo(19f, 8f)
            verticalLineToRelative(8f)
        }
    }

    /**
     * The Learn tab — a scholar's cap.
     *
     * Drawn narrower than the 24 box allows (3 → 21 rather than edge to edge)
     * so its wide mortarboard does not read as heavier than the home and cog
     * icons sitting beside it in the nav.
     */
    val Cap: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Cap", 22.dp) {
            moveTo(12f, 4f)
            lineTo(3f, 8.5f)
            lineToRelative(9f, 4.5f)
            lineToRelative(9f, -4.5f)
            close()
            moveTo(6.5f, 10.8f)
            verticalLineTo(15f)
            curveToRelative(0f, 1.4f, 2.5f, 2.6f, 5.5f, 2.6f)
            reflectiveCurveToRelative(5.5f, -1.2f, 5.5f, -2.6f)
            verticalLineToRelative(-4.2f)
            moveTo(21f, 8.5f)
            verticalLineTo(14f)
        }
    }

    /** The Editor tab, and the pencil beside a beat's name. */
    val Pencil: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Pencil", 20.dp) {
            moveTo(17f, 3f)
            arcToRelative(2.85f, 2.85f, 0f, true, true, 4f, 4f)
            lineTo(7.5f, 20.5f)
            lineTo(2f, 22f)
            lineToRelative(1.5f, -5.5f)
            close()
        }
    }

    /** The info dot on a beat row — opens that beat's detail sheet. */
    val Info: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Info", 18.dp) {
            // <circle cx="12" cy="12" r="9" />
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, false, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, false, true, -18f, 0f)
            close()
            moveTo(12f, 11f)
            verticalLineToRelative(5f)
            // The near-zero segment plus a round cap IS the dot above the "i".
            moveTo(12f, 7.5f)
            horizontalLineToRelative(0.01f)
        }
    }

    /** The mixer sheet — three faders. Loudness, as opposed to [Eq]'s tone. */
    val Mixer: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Mixer", 19.dp) {
            moveTo(5f, 21f)
            verticalLineToRelative(-6f)
            moveTo(5f, 11f)
            verticalLineTo(3f)
            moveTo(12f, 21f)
            verticalLineToRelative(-10f)
            moveTo(12f, 7f)
            verticalLineTo(3f)
            moveTo(19f, 21f)
            verticalLineToRelative(-4f)
            moveTo(19f, 13f)
            verticalLineTo(3f)
            moveTo(2f, 14f)
            horizontalLineToRelative(6f)
            moveTo(9f, 10f)
            horizontalLineToRelative(6f)
            moveTo(16f, 16f)
            horizontalLineToRelative(6f)
        }
    }

    /**
     * Per-end EQ — a frequency-response curve rather than faders, so the two
     * buttons beside a lane read as distinct ideas: loudness ([Mixer]) vs tone.
     * The wiggle nods at the five bands it opens.
     */
    val Eq: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Eq", 19.dp) {
            moveTo(2f, 15f)
            curveToRelative(3f, 0f, 3.5f, -8f, 6.5f, -8f)
            curveToRelative(2.8f, 0f, 3.2f, 9f, 6f, 9f)
            curveToRelative(2.3f, 0f, 3.4f, -3.6f, 7.5f, -4.5f)
        }
    }

    /**
     * Per-end tuning — a tuning fork, so the three lane buttons read as three
     * distinct ideas: loudness ([Mixer]), tone ([Eq]), pitch.
     */
    val Tune: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Tune", 19.dp) {
            moveTo(8f, 3f)
            verticalLineToRelative(6f)
            arcToRelative(4f, 4f, 0f, false, false, 8f, 0f)
            verticalLineTo(3f)
            moveTo(12f, 13f)
            verticalLineToRelative(8f)
        }
    }

    /** A lane is sounding — the mute button on a mixer row, unmuted. */
    val Speaker: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Speaker", 19.dp) {
            moveTo(11f, 5f)
            lineTo(6f, 9f)
            horizontalLineTo(3f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(3f)
            lineToRelative(5f, 4f)
            close()
            moveTo(15.5f, 8.5f)
            arcToRelative(5f, 5f, 0f, false, true, 0f, 7f)
        }
    }

    /** A lane is silenced — the same cone with the wave replaced by a cross. */
    val SpeakerMuted: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("SpeakerMuted", 19.dp) {
            moveTo(11f, 5f)
            lineTo(6f, 9f)
            horizontalLineTo(3f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(3f)
            lineToRelative(5f, 4f)
            close()
            moveTo(16f, 9f)
            lineTo(22f, 15f)
            moveTo(22f, 9f)
            lineTo(16f, 15f)
        }
    }

    /** Steps back out of a sub-page. */
    val Back: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Back", 22.dp) {
            moveTo(15f, 5f)
            lineTo(8f, 12f)
            lineToRelative(7f, 7f)
        }
    }

    /**
     * The "opens a page" affordance on the Beats section cards. The mirror of
     * [Back] so the two read as a matched pair — drill in, step back.
     */
    val ChevronRight: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("ChevronRight", 20.dp) {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineToRelative(-7f, 7f)
        }
    }

    /** The library search field. */
    val Search: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Search", 18.dp) {
            // <circle cx="11" cy="11" r="7" />
            moveTo(4f, 11f)
            arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
            arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
            close()
            moveTo(20f, 20f)
            lineTo(16.5f, 16.5f)
        }
    }

    /** Confirm. Heavier than every other stroke in the set (2.4, not 1.8). */
    val Check: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Check", 22.dp, strokeWidth = CheckStrokeWidth) {
            moveTo(4f, 12.5f)
            lineTo(9.5f, 18f)
            lineTo(20f, 6.5f)
        }
    }

    /**
     * Share — the three-node "send this onward" glyph, not an upload arrow, so
     * it reads the same on iOS and Android where the platform share sheets
     * differ.
     */
    val Share: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Share", 18.dp) {
            // <circle cx="18" cy="5.5" r="2.5" />
            moveTo(15.5f, 5.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 5f, 0f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -5f, 0f)
            close()
            // <circle cx="6" cy="12" r="2.5" />
            moveTo(3.5f, 12f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 5f, 0f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -5f, 0f)
            close()
            // <circle cx="18" cy="18.5" r="2.5" />
            moveTo(15.5f, 18.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 5f, 0f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -5f, 0f)
            close()
            moveTo(8.2f, 10.8f)
            lineTo(15.8f, 6.7f)
            moveTo(8.2f, 13.2f)
            lineTo(15.8f, 17.3f)
        }
    }

    /**
     * The Browse tab's globe. It marks the one tab whose beats come from
     * OUTSIDE this device — today by pasted code, later from the community
     * library (PROJECT_PLAN §7) — which is why it is not a paste/clipboard
     * glyph.
     */
    val Globe: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon("Globe", 16.dp) {
            // <circle cx="12" cy="12" r="9" />
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, false, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, false, true, -18f, 0f)
            close()
            moveTo(3f, 12f)
            horizontalLineToRelative(18f)
            moveTo(12f, 3f)
            curveToRelative(2.5f, 2.6f, 3.8f, 5.6f, 3.8f, 9f)
            reflectiveCurveTo(14.5f, 18.4f, 12f, 21f)
            curveToRelative(-2.5f, -2.6f, -3.8f, -5.6f, -3.8f, -9f)
            reflectiveCurveTo(9.5f, 5.6f, 12f, 3f)
            close()
        }
    }

    /**
     * Play, on the clay play bar. Filled, not stroked, so it reads at a glance;
     * the landscape rail runs it smaller, hence the size prop in the JSX.
     */
    val Play: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        filledIcon("Play", 22.dp) {
            moveTo(8f, 4.5f)
            lineTo(20f, 12f)
            lineTo(8f, 19.5f)
            close()
        }
    }

    /** Pause. Two filled rounded bars — the same 1.5 radius the SVG declares. */
    val Pause: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        filledIcon("Pause", 22.dp) {
            // <rect x="5" y="4" width="5" height="16" rx="1.5" />
            moveTo(6.5f, 4f)
            horizontalLineToRelative(2f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
            verticalLineToRelative(13f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
            horizontalLineToRelative(-2f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
            verticalLineToRelative(-13f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
            close()
            // <rect x="14" y="4" width="5" height="16" rx="1.5" />
            moveTo(15.5f, 4f)
            horizontalLineToRelative(2f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
            verticalLineToRelative(13f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
            horizontalLineToRelative(-2f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
            verticalLineToRelative(-13f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
            close()
        }
    }

    /** The Beats page's Start button — a slightly tighter triangle than [Play]. */
    val Start: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        filledIcon("Start", 18.dp) {
            moveTo(7f, 5f)
            lineTo(19f, 12f)
            lineTo(7f, 19f)
            close()
        }
    }
}

/**
 * The two selection marks. These are the only members of `icons.jsx` that are
 * not SVGs — the JSX builds each out of a styled `<span>` — so they are
 * composables rather than vectors: two colours and a border that change with
 * state, which a single tintable [ImageVector] cannot express.
 *
 * Both are drawn in the JSX's own 24-unit box and scaled to whatever they are
 * laid out at, so a caller can resize one with `Modifier.size(…)` without the
 * border getting relatively thicker.
 */

/** The JSX builds both marks at 24×24. */
private val DotBox = 24.dp

/** `border: 2px solid …` on both marks. */
private val DotBorder = 2.dp

/**
 * Membership check for the add-beats sheet — a clay tick in a rounded square
 * when in, a bare hairline square when not.
 *
 * The tick is [KcIcons.Check]'s own path scaled to 80% of the box rather than
 * the `✓` character the JSX sets in text: drawing the shape keeps the mark
 * looking the same whichever font resolves, and keeps it consistent with every
 * other check in the app.
 */
@Composable
internal fun CheckDot(checked: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(DotBox)) {
        val scale = size.minDimension / DotBox.toPx()
        val border = DotBorder.toPx() * scale
        val corner = CornerRadius(8f * scale)

        if (checked) {
            drawRoundRect(color = Clay, cornerRadius = corner)
            drawCheck(scale, OnClay)
        } else {
            drawRoundRect(
                color = Rule,
                topLeft = Offset(border / 2f, border / 2f),
                size = Size(size.width - border, size.height - border),
                cornerRadius = corner,
                style = Stroke(width = border),
            )
        }
    }
}

/**
 * Selection radio for the beat list — a hollow ring, with a clay dot filling it
 * when the beat is the one loaded.
 */
@Composable
internal fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(DotBox)) {
        val scale = size.minDimension / DotBox.toPx()
        val border = DotBorder.toPx() * scale
        val colour = if (selected) Clay else Rule

        drawCircle(
            color = colour,
            radius = (size.minDimension - border) / 2f,
            style = Stroke(width = border),
        )
        // <span style={{ width: 12, height: 12, borderRadius: "50%" }} />
        if (selected) drawCircle(color = colour, radius = 6f * scale)
    }
}

/**
 * [KcIcons.Check]'s path (`M4 12.5 9.5 18 20 6.5` at 2.4) scaled 80% about the
 * centre of the 24-unit box, which puts it at (5.6 12.4) → (10 16.8) →
 * (18.4 7.6). Drawn as two round-capped lines rather than a path: at a vertex
 * that is indistinguishable from a round join, and it allocates nothing per
 * frame.
 */
private fun DrawScope.drawCheck(scale: Float, colour: Color) {
    val width = 2.2f * scale
    val elbow = Offset(10f * scale, 16.8f * scale)
    drawLine(colour, Offset(5.6f * scale, 12.4f * scale), elbow, width, cap = StrokeCap.Round)
    drawLine(colour, elbow, Offset(18.4f * scale, 7.6f * scale), width, cap = StrokeCap.Round)
}
