package com.kirtan.companion.data

import com.kirtan.companion.data.model.LaneId
import com.kirtan.companion.data.model.Stroke

/**
 * The single source of truth for how each stroke type LOOKS.
 *
 * Ported from `src/data/strokes.js`. Every visual indicator reads from here, so
 * the whole app agrees on what an "open" or "closed" stroke looks like. To add
 * a new stroke type later (duggi, nak…), add ONE entry here and it appears
 * correctly everywhere — no other file needs changing, provided the shape is
 * already drawable.
 */

/** Shapes the strip and editor can draw. */
enum class StrokeShape { CIRCLE, SQUARE, DIAMOND, TRIANGLE, RING, REST }

data class StrokeVisual(
    val shape: StrokeShape,
    val color: PaletteToken?,
    val label: String,
)

private val STROKES: Map<Stroke, StrokeVisual> = mapOf(
    Stroke.OPEN to StrokeVisual(StrokeShape.CIRCLE, PaletteToken.CLAY, "open"),
    Stroke.CLOSED to StrokeVisual(StrokeShape.SQUARE, PaletteToken.STRAP, "closed"),

    // ── Ready for when these sounds are added ──
    // Stroke.DUGGI to StrokeVisual(StrokeShape.DIAMOND, PaletteToken.DUGGI, "duggi"),
    // Stroke.NAK to StrokeVisual(StrokeShape.TRIANGLE, PaletteToken.NAK, "nak"),
)

/** The look of an empty step (a rest — no stroke). */
val REST_VISUAL = StrokeVisual(StrokeShape.REST, null, "rest")

/**
 * Look up the visual for a stroke value. Unknown and null both fall back to
 * [REST_VISUAL], so a pattern array can be shorter than the loop without
 * throwing.
 */
fun strokeVisual(value: Stroke?): StrokeVisual =
    if (value != null) STROKES[value] ?: REST_VISUAL else REST_VISUAL

/**
 * The colour token for a lane. Lives beside the stroke visuals because both are
 * "how does the strip paint this" facts, and adding an instrument means adding
 * one entry in each.
 */
val LaneId.colorToken: PaletteToken
    get() = when (this) {
        LaneId.DAYAN -> PaletteToken.LANE_DAYAN
        LaneId.BAYAN -> PaletteToken.LANE_BAYAN
        LaneId.KARTAL -> PaletteToken.LANE_KARTAL
    }
