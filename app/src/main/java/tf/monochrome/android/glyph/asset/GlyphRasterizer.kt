// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius

/**
 * Turns a parsed [GlyphVector] into an immutable [ImageBitmap] at an exact
 * pixel size.
 *
 * This is the whole reason the mode does not need an SVG decoder at draw time.
 * Every asset is rasterized once, ahead of play, at the size the playfield has
 * already measured, and the gameplay loop then only ever calls `drawImage` with
 * a bitmap it already holds. No file is opened, no XML is parsed and no bitmap
 * is allocated between the first note and the last.
 *
 * Scaling is uniform and taken from the viewBox, so the pack's deliberate
 * effect padding is preserved rather than cropped, and a note drawn at 96 px is
 * a fresh rasterization rather than a stretched 64 px one.
 */
object GlyphRasterizer {

    /**
     * @param tint the colour `currentColor` resolves to. Notes and feedback
     *   carry their own colours and pass null, which makes a stray
     *   `currentColor` in that artwork fail loudly at parse-check time instead
     *   of silently painting black.
     */
    fun rasterize(
        vector: GlyphVector,
        widthPx: Int,
        heightPx: Int,
        tint: Color? = null,
    ): ImageBitmap {
        require(widthPx > 0 && heightPx > 0) { "raster size must be positive" }
        val image = ImageBitmap(widthPx, heightPx)
        val canvas = Canvas(image)

        // Uniform scale, centred: a non-uniform fit would shear the arrows and
        // break the seam between a hold body and its tail.
        val scale = minOf(widthPx / vector.viewportWidth, heightPx / vector.viewportHeight)
        val offsetX = (widthPx - vector.viewportWidth * scale) / 2f
        val offsetY = (heightPx - vector.viewportHeight * scale) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        val paint = Paint()
        for (shape in vector.shapes) {
            drawShape(canvas, paint, shape, tint, scale)
        }

        canvas.restore()
        // Handing gameplay a mutable bitmap invites a draw-time mutation that
        // would tear on another thread; freezing it here makes that impossible.
        image.asAndroidBitmap().setHasAlpha(true)
        return image
    }

    private fun drawShape(
        canvas: Canvas,
        paint: Paint,
        shape: GlyphShape,
        tint: Color?,
        scale: Float,
    ) {
        val transform = shape.transform
        if (transform != null) canvas.save()
        when (transform) {
            is GlyphTransform.Translate -> canvas.translate(transform.x, transform.y)
            is GlyphTransform.Rotate -> {
                canvas.translate(transform.pivotX, transform.pivotY)
                canvas.rotate(transform.degrees)
                canvas.translate(-transform.pivotX, -transform.pivotY)
            }
            null -> Unit
        }

        when (shape) {
            is GlyphShape.Rect -> {
                val rect = Rect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
                shape.fill?.let { fill ->
                    configureFill(paint, fill, shape.opacity, tint)
                    if (shape.cornerRadius > 0f) {
                        canvas.drawRoundRect(
                            rect.left, rect.top, rect.right, rect.bottom,
                            shape.cornerRadius, shape.cornerRadius, paint,
                        )
                    } else {
                        canvas.drawRect(rect, paint)
                    }
                }
                shape.stroke?.let { stroke ->
                    configureStroke(paint, stroke, shape.opacity, tint, scale)
                    if (shape.cornerRadius > 0f) {
                        canvas.drawRoundRect(
                            rect.left, rect.top, rect.right, rect.bottom,
                            shape.cornerRadius, shape.cornerRadius, paint,
                        )
                    } else {
                        canvas.drawRect(rect, paint)
                    }
                }
            }

            is GlyphShape.Circle -> {
                val center = Offset(shape.centerX, shape.centerY)
                shape.fill?.let { fill ->
                    configureFill(paint, fill, shape.opacity, tint)
                    canvas.drawCircle(center, shape.radius, paint)
                }
                shape.stroke?.let { stroke ->
                    configureStroke(paint, stroke, shape.opacity, tint, scale)
                    canvas.drawCircle(center, shape.radius, paint)
                }
            }

            is GlyphShape.PathShape -> {
                val path = toPath(shape.commands)
                shape.fill?.let { fill ->
                    configureFill(paint, fill, shape.opacity, tint)
                    canvas.drawPath(path, paint)
                }
                shape.stroke?.let { stroke ->
                    configureStroke(paint, stroke, shape.opacity, tint, scale)
                    canvas.drawPath(path, paint)
                }
            }
        }

        if (transform != null) canvas.restore()
    }

    private fun configureFill(paint: Paint, fill: GlyphPaint, opacity: Float, tint: Color?) {
        paint.style = PaintingStyle.Fill
        paint.pathEffect = null
        paint.color = resolve(fill, tint)
        paint.alpha = opacity * alphaOf(fill)
    }

    private fun configureStroke(
        paint: Paint,
        stroke: GlyphStroke,
        opacity: Float,
        tint: Color?,
        scale: Float,
    ) {
        paint.style = PaintingStyle.Stroke
        paint.color = resolve(stroke.paint, tint)
        paint.alpha = opacity * alphaOf(stroke.paint)
        paint.strokeWidth = stroke.width
        paint.strokeCap = when (stroke.cap) {
            GlyphStroke.Cap.ROUND -> StrokeCap.Round
            GlyphStroke.Cap.SQUARE -> StrokeCap.Square
            GlyphStroke.Cap.BUTT -> StrokeCap.Butt
        }
        paint.strokeJoin = when (stroke.join) {
            GlyphStroke.Join.ROUND -> StrokeJoin.Round
            GlyphStroke.Join.BEVEL -> StrokeJoin.Bevel
            GlyphStroke.Join.MITER -> StrokeJoin.Miter
        }
        paint.pathEffect = stroke.dashIntervals
            .takeIf { it.size >= 2 }
            ?.let { PathEffect.dashPathEffect(it.toFloatArray()) }
    }

    /**
     * A `currentColor` with no tint supplied is drawn in the pack's paper
     * colour rather than dropped: the mode should look wrong-but-visible if a
     * call site forgets a tint, not lose an icon.
     */
    private fun resolve(paint: GlyphPaint, tint: Color?): Color = when (paint) {
        is GlyphPaint.Solid -> Color(paint.argb)
        is GlyphPaint.CurrentColor -> tint ?: Color(0xFFF8FAFF)
    }

    private fun alphaOf(paint: GlyphPaint): Float = when (paint) {
        is GlyphPaint.Solid -> paint.alpha
        is GlyphPaint.CurrentColor -> paint.alpha
    }

    private fun toPath(commands: List<GlyphPathCommand>): Path {
        val path = Path()
        for (command in commands) {
            when (command) {
                is GlyphPathCommand.MoveTo -> path.moveTo(command.x, command.y)
                is GlyphPathCommand.LineTo -> path.lineTo(command.x, command.y)
                is GlyphPathCommand.QuadTo -> path.quadraticTo(
                    command.controlX, command.controlY, command.x, command.y,
                )
                is GlyphPathCommand.CubicTo -> path.cubicTo(
                    command.control1X, command.control1Y,
                    command.control2X, command.control2Y,
                    command.x, command.y,
                )
                GlyphPathCommand.Close -> path.close()
            }
        }
        return path
    }
}

/** A rounded rect helper kept out of the hot path; used by decor drawing. */
internal fun roundRectOf(rect: Rect, radius: Float): RoundRect =
    RoundRect(rect, CornerRadius(radius, radius))
