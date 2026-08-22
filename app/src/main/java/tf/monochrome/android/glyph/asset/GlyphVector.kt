// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

/**
 * A parsed glyph-pack drawing, independent of any renderer.
 *
 * The pack ships as SVG because that is what the generator writes and what a
 * designer can open, but Android has no SVG decoder and this project has not
 * taken one on. Rather than rasterize the masters at build time — which throws
 * away the resolution the pack exists to provide — each file is parsed once
 * into this model and rasterized on demand at the exact pixel size the
 * playfield needs.
 *
 * Everything here is immutable and free of Android types so the parser can be
 * exercised as an ordinary unit test, which is what
 * `GlyphAssetCatalogTest` uses to prove that every asset the manifest names
 * actually parses.
 */
data class GlyphVector(
    /** The SVG viewBox, kept verbatim: the pack's padding is intentional. */
    val viewportWidth: Float,
    val viewportHeight: Float,
    val shapes: List<GlyphShape>,
) {
    init {
        require(viewportWidth > 0f && viewportHeight > 0f) { "viewBox must be positive" }
    }

    val aspectRatio: Float get() = viewportWidth / viewportHeight
}

/**
 * One drawing operation.
 *
 * Rects and circles stay their own shapes rather than being flattened to paths:
 * a rounded rect is the pack's most common primitive by an order of magnitude
 * and drawing it directly is both faster and free of the corner artefacts a
 * bezier approximation introduces at small sizes.
 */
sealed interface GlyphShape {
    val fill: GlyphPaint?
    val stroke: GlyphStroke?
    val opacity: Float
    val transform: GlyphTransform?

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float,
        override val fill: GlyphPaint?,
        override val stroke: GlyphStroke?,
        override val opacity: Float,
        override val transform: GlyphTransform?,
    ) : GlyphShape

    data class Circle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        override val fill: GlyphPaint?,
        override val stroke: GlyphStroke?,
        override val opacity: Float,
        override val transform: GlyphTransform?,
    ) : GlyphShape

    data class PathShape(
        val commands: List<GlyphPathCommand>,
        override val fill: GlyphPaint?,
        override val stroke: GlyphStroke?,
        override val opacity: Float,
        override val transform: GlyphTransform?,
    ) : GlyphShape
}

/**
 * A colour, or the instruction to use the caller's colour.
 *
 * `currentColor` is how the pack marks its tintable UI artwork. Notes and
 * judgement wordmarks carry their own semantic colours and never resolve to
 * [CurrentColor], which is what stops a global tint from flattening the beat
 * palette into one hue.
 */
sealed interface GlyphPaint {
    /** Straight ARGB. */
    data class Solid(val argb: Int, val alpha: Float = 1f) : GlyphPaint

    /** Resolve against whatever colour the call site is drawing in. */
    data class CurrentColor(val alpha: Float = 1f) : GlyphPaint
}

data class GlyphStroke(
    val paint: GlyphPaint,
    val width: Float,
    val cap: Cap,
    val join: Join,
    /** Non-empty for a dashed stroke; the pack uses this only on decor. */
    val dashIntervals: List<Float> = emptyList(),
) {
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
}

/** The two transforms the pack uses. Anything else is rejected at parse time. */
sealed interface GlyphTransform {
    data class Translate(val x: Float, val y: Float) : GlyphTransform
    data class Rotate(val degrees: Float, val pivotX: Float, val pivotY: Float) : GlyphTransform
}

/**
 * A path reduced to five primitives.
 *
 * The parser normalizes relative commands to absolute, expands H/V/S/T into
 * their general forms, and converts elliptical arcs to cubics, so a renderer
 * only ever has to implement move/line/quad/cubic/close. That keeps the
 * Android-facing code trivial and puts every fiddly case under unit test.
 */
sealed interface GlyphPathCommand {
    data class MoveTo(val x: Float, val y: Float) : GlyphPathCommand
    data class LineTo(val x: Float, val y: Float) : GlyphPathCommand
    data class QuadTo(
        val controlX: Float,
        val controlY: Float,
        val x: Float,
        val y: Float,
    ) : GlyphPathCommand

    data class CubicTo(
        val control1X: Float,
        val control1Y: Float,
        val control2X: Float,
        val control2Y: Float,
        val x: Float,
        val y: Float,
    ) : GlyphPathCommand

    data object Close : GlyphPathCommand
}
