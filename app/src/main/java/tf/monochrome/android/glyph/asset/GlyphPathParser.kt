// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SVG path-data to [GlyphPathCommand].
 *
 * Written rather than borrowed because the two obvious sources both cost
 * something: `androidx.core.graphics.PathParser` returns an `android.graphics`
 * object, which puts every path test on an emulator, and Compose's vector
 * parser is tied to its own painter. Producing a plain data model instead keeps
 * the whole pack testable on the JVM and leaves rasterization a separate,
 * replaceable step.
 *
 * The full grammar is implemented — including relative commands, implicit
 * repeats, smooth curves and elliptical arcs — even though the shipped pack
 * only exercises a subset, because a parser that silently mis-draws an unusual
 * command is worse than one that never sees it.
 */
object GlyphPathParser {

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(data: String): List<GlyphPathCommand> {
        val commands = ArrayList<GlyphPathCommand>()
        val scanner = NumberScanner(data)

        var currentX = 0f
        var currentY = 0f
        var startX = 0f
        var startY = 0f
        // Reflection anchors for S and T. They reset to the current point after
        // any command that is not a curve of the matching kind, which is the
        // rule that makes a lone S behave like a plain C.
        var lastCubicControlX = 0f
        var lastCubicControlY = 0f
        var lastQuadControlX = 0f
        var lastQuadControlY = 0f
        var previous = ' '
        var command = ' '

        while (true) {
            scanner.skipSeparators()
            if (scanner.atEnd()) break

            val next = scanner.peek()
            if (next.isLetter()) {
                command = next
                scanner.advance()
            } else if (command == ' ') {
                throw ParseException("path data must start with a command: $data")
            } else if (command == 'M') {
                // An implicit repeat after a moveto is a lineto, per the spec.
                command = 'L'
            } else if (command == 'm') {
                command = 'l'
            }

            val relative = command.isLowerCase()
            val originX = if (relative) currentX else 0f
            val originY = if (relative) currentY else 0f

            when (command.uppercaseChar()) {
                'M' -> {
                    currentX = scanner.number() + originX
                    currentY = scanner.number() + originY
                    startX = currentX
                    startY = currentY
                    commands += GlyphPathCommand.MoveTo(currentX, currentY)
                }
                'L' -> {
                    currentX = scanner.number() + originX
                    currentY = scanner.number() + originY
                    commands += GlyphPathCommand.LineTo(currentX, currentY)
                }
                'H' -> {
                    currentX = scanner.number() + originX
                    commands += GlyphPathCommand.LineTo(currentX, currentY)
                }
                'V' -> {
                    currentY = scanner.number() + originY
                    commands += GlyphPathCommand.LineTo(currentX, currentY)
                }
                'C' -> {
                    val control1X = scanner.number() + originX
                    val control1Y = scanner.number() + originY
                    val control2X = scanner.number() + originX
                    val control2Y = scanner.number() + originY
                    currentX = scanner.number() + originX
                    currentY = scanner.number() + originY
                    commands += GlyphPathCommand.CubicTo(
                        control1X, control1Y, control2X, control2Y, currentX, currentY,
                    )
                    lastCubicControlX = control2X
                    lastCubicControlY = control2Y
                }
                'S' -> {
                    val reflected = previous.uppercaseChar() == 'C' || previous.uppercaseChar() == 'S'
                    val control1X = if (reflected) 2 * currentX - lastCubicControlX else currentX
                    val control1Y = if (reflected) 2 * currentY - lastCubicControlY else currentY
                    val control2X = scanner.number() + originX
                    val control2Y = scanner.number() + originY
                    currentX = scanner.number() + originX
                    currentY = scanner.number() + originY
                    commands += GlyphPathCommand.CubicTo(
                        control1X, control1Y, control2X, control2Y, currentX, currentY,
                    )
                    lastCubicControlX = control2X
                    lastCubicControlY = control2Y
                }
                'Q' -> {
                    val controlX = scanner.number() + originX
                    val controlY = scanner.number() + originY
                    currentX = scanner.number() + originX
                    currentY = scanner.number() + originY
                    commands += GlyphPathCommand.QuadTo(controlX, controlY, currentX, currentY)
                    lastQuadControlX = controlX
                    lastQuadControlY = controlY
                }
                'T' -> {
                    val reflected = previous.uppercaseChar() == 'Q' || previous.uppercaseChar() == 'T'
                    val controlX = if (reflected) 2 * currentX - lastQuadControlX else currentX
                    val controlY = if (reflected) 2 * currentY - lastQuadControlY else currentY
                    currentX = scanner.number() + originX
                    currentY = scanner.number() + originY
                    commands += GlyphPathCommand.QuadTo(controlX, controlY, currentX, currentY)
                    lastQuadControlX = controlX
                    lastQuadControlY = controlY
                }
                'A' -> {
                    val radiusX = scanner.number()
                    val radiusY = scanner.number()
                    val rotation = scanner.number()
                    val largeArc = scanner.flag()
                    val sweep = scanner.flag()
                    val endX = scanner.number() + originX
                    val endY = scanner.number() + originY
                    commands += arcToCubics(
                        currentX, currentY, radiusX, radiusY, rotation, largeArc, sweep, endX, endY,
                    )
                    currentX = endX
                    currentY = endY
                }
                'Z' -> {
                    commands += GlyphPathCommand.Close
                    currentX = startX
                    currentY = startY
                }
                else -> throw ParseException("unsupported path command '$command' in: $data")
            }

            previous = command
        }

        return commands
    }

    /**
     * Endpoint-parameterized arc to a run of cubics.
     *
     * The conversion is the one from the SVG implementation notes: recover the
     * centre, then emit a cubic per quarter-turn or less, because a single
     * cubic cannot hold more than about 90° of an ellipse without visible
     * error.
     */
    private fun arcToCubics(
        startX: Float,
        startY: Float,
        radiusXInput: Float,
        radiusYInput: Float,
        rotationDegrees: Float,
        largeArc: Boolean,
        sweep: Boolean,
        endX: Float,
        endY: Float,
    ): List<GlyphPathCommand> {
        // A zero radius or a zero-length arc degenerates to a straight line;
        // the spec says to draw it rather than to fail.
        if (startX == endX && startY == endY) return emptyList()
        var radiusX = abs(radiusXInput)
        var radiusY = abs(radiusYInput)
        if (radiusX < 1e-6f || radiusY < 1e-6f) {
            return listOf(GlyphPathCommand.LineTo(endX, endY))
        }

        val phi = Math.toRadians(rotationDegrees.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val halfDx = (startX - endX) / 2.0
        val halfDy = (startY - endY) / 2.0
        val x1 = cosPhi * halfDx + sinPhi * halfDy
        val y1 = -sinPhi * halfDx + cosPhi * halfDy

        // Radii too small to span the chord are scaled up together, which is
        // what the spec prescribes instead of treating the path as invalid.
        val lambda = (x1 * x1) / (radiusX * radiusX) + (y1 * y1) / (radiusY * radiusY)
        if (lambda > 1.0) {
            val scale = sqrt(lambda)
            radiusX = (radiusX * scale).toFloat()
            radiusY = (radiusY * scale).toFloat()
        }

        val rxSquared = radiusX.toDouble() * radiusX
        val rySquared = radiusY.toDouble() * radiusY
        val numerator = rxSquared * rySquared - rxSquared * y1 * y1 - rySquared * x1 * x1
        val denominator = rxSquared * y1 * y1 + rySquared * x1 * x1
        val factor = if (denominator <= 0.0) 0.0 else {
            val raw = sqrt((numerator / denominator).coerceAtLeast(0.0))
            if (largeArc == sweep) -raw else raw
        }

        val cx1 = factor * radiusX * y1 / radiusY
        val cy1 = -factor * radiusY * x1 / radiusX
        val centerX = cosPhi * cx1 - sinPhi * cy1 + (startX + endX) / 2.0
        val centerY = sinPhi * cx1 + cosPhi * cy1 + (startY + endY) / 2.0

        val startAngle = angleBetween(1.0, 0.0, (x1 - cx1) / radiusX, (y1 - cy1) / radiusY)
        var sweepAngle = angleBetween(
            (x1 - cx1) / radiusX,
            (y1 - cy1) / radiusY,
            (-x1 - cx1) / radiusX,
            (-y1 - cy1) / radiusY,
        )
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI
        if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI

        val segments = ceil(abs(sweepAngle) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val delta = sweepAngle / segments
        // The magic constant that makes a cubic match a circular arc of `delta`
        // radians at its endpoints and midpoint.
        val alpha = 4.0 / 3.0 * kotlin.math.tan(delta / 4.0)

        val out = ArrayList<GlyphPathCommand>(segments)
        var angle = startAngle
        for (index in 0 until segments) {
            val nextAngle = angle + delta
            val cosStart = cos(angle)
            val sinStart = sin(angle)
            val cosEnd = cos(nextAngle)
            val sinEnd = sin(nextAngle)

            val pointStartX = centerX + radiusX * cosPhi * cosStart - radiusY * sinPhi * sinStart
            val pointStartY = centerY + radiusX * sinPhi * cosStart + radiusY * cosPhi * sinStart
            val pointEndX = centerX + radiusX * cosPhi * cosEnd - radiusY * sinPhi * sinEnd
            val pointEndY = centerY + radiusX * sinPhi * cosEnd + radiusY * cosPhi * sinEnd

            val tangentStartX = -radiusX * cosPhi * sinStart - radiusY * sinPhi * cosStart
            val tangentStartY = -radiusX * sinPhi * sinStart + radiusY * cosPhi * cosStart
            val tangentEndX = -radiusX * cosPhi * sinEnd - radiusY * sinPhi * cosEnd
            val tangentEndY = -radiusX * sinPhi * sinEnd + radiusY * cosPhi * cosEnd

            out += GlyphPathCommand.CubicTo(
                (pointStartX + alpha * tangentStartX).toFloat(),
                (pointStartY + alpha * tangentStartY).toFloat(),
                (pointEndX - alpha * tangentEndX).toFloat(),
                (pointEndY - alpha * tangentEndY).toFloat(),
                pointEndX.toFloat(),
                pointEndY.toFloat(),
            )
            angle = nextAngle
        }
        return out
    }

    private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val length = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        if (length <= 0.0) return 0.0
        val sign = if (ux * vy - uy * vx < 0) -1.0 else 1.0
        return sign * kotlin.math.acos((dot / length).coerceIn(-1.0, 1.0))
    }

    /**
     * Tokenizer for SVG's number syntax, which is not Kotlin's: separators are
     * optional, a minus sign starts a new number without one, and `1.5.5` is
     * two numbers.
     */
    private class NumberScanner(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun peek(): Char = text[index]

        fun advance() { index += 1 }

        fun skipSeparators() {
            while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) {
                index += 1
            }
        }

        /** Arc flags are a single character; `10` is two flags, not ten. */
        fun flag(): Boolean {
            skipSeparators()
            if (atEnd()) throw ParseException("expected an arc flag in: $text")
            return when (val character = text[index++]) {
                '0' -> false
                '1' -> true
                else -> throw ParseException("expected an arc flag, found '$character' in: $text")
            }
        }

        fun number(): Float {
            skipSeparators()
            val start = index
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
            var seenDot = false
            while (index < text.length) {
                val character = text[index]
                when {
                    character.isDigit() -> index += 1
                    character == '.' && !seenDot -> { seenDot = true; index += 1 }
                    (character == 'e' || character == 'E') &&
                        index + 1 < text.length &&
                        (text[index + 1].isDigit() || text[index + 1] == '-' || text[index + 1] == '+') -> {
                        index += 2
                        while (index < text.length && text[index].isDigit()) index += 1
                    }
                    else -> break
                }
            }
            if (index == start) throw ParseException("expected a number at $start in: $text")
            return text.substring(start, index).toFloatOrNull()
                ?: throw ParseException("malformed number '${text.substring(start, index)}'")
        }
    }
}
