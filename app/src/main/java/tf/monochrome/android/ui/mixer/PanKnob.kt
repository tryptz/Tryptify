package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compact arc-style pan knob control (270-degree sweep).
 *
 * Indicator travels from 7-o'clock (-1 = hard left) through
 * 12-o'clock (0 = centre) to 5-o'clock (+1 = hard right).
 *
 * Uses MaterialTheme colours so the knob adapts to the current app
 * theme and blends with liquidGlass surfaces.  28 dp default size
 * fits the narrow FL Studio-layout channel strips.
 */
@Composable
fun PanKnob(
    value: Float,               // -1 ... +1
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    // A translucent-white ring reads as a recessed groove on the dark strip;
    // the theme's surfaceContainer was too low-contrast to see the knob at all.
    val trackColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val activeColor = accentColor
    val dotColor    = if (accentColor.luminance() > 0.55f) Color.Black else Color.White
    val haptic      = LocalHapticFeedback.current

    // Remember whether we already fired a haptic for the centre detent
    val firedDetent = remember { mutableSetOf<Boolean>() }

    Canvas(
        modifier = modifier
            .size(28.dp)
            .pointerInput(Unit) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                detectDragGestures { change, _ ->
                    change.consume()
                    val pos = change.position
                    // atan2 with 0 = up (12 o'clock)
                    val angle = atan2(pos.x - cx, -(pos.y - cy)).toFloat()
                    val maxAngle = (135f * PI / 180f).toFloat()
                    val clamped = angle.coerceIn(-maxAngle, maxAngle)
                    val newVal = (clamped / maxAngle).coerceIn(-1f, 1f)
                    onValueChange(newVal)

                    // Haptic tick at centre detent
                    if (newVal in -0.05f..0.05f) {
                        if (firedDetent.isEmpty()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            firedDetent.add(true)
                        }
                    } else {
                        firedDetent.clear()
                    }
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f - 4.dp.toPx()
        val strokeWidth = 2.5.dp.toPx()

        // Background arc (270°, starting at 7 o'clock)
        drawArc(
            color      = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter  = false,
            style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Active arc from centre (12 o'clock)
        val fraction = (value + 1f) / 2f
        val centreFraction = 0.5f
        if (fraction != centreFraction) {
            val sweepDeg = (fraction - centreFraction) * 270f
            val startDeg = 135f + centreFraction * 270f
            drawArc(
                color      = activeColor,
                startAngle = startDeg,
                sweepAngle = sweepDeg,
                useCenter  = false,
                style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Indicator dot
        val indicatorAngle = Math.toRadians((135.0 + fraction * 270.0)).toFloat()
        val dotRadius = 2.5.dp.toPx()
        val dotX = cx + radius * cos(indicatorAngle)
        val dotY = cy + radius * sin(indicatorAngle)
        drawCircle(color = activeColor, radius = dotRadius + 0.5.dp.toPx(), center = Offset(dotX, dotY))
        drawCircle(color = dotColor, radius = dotRadius, center = Offset(dotX, dotY))

        // Centre tick mark
        val tickAngle = Math.toRadians(270.0).toFloat()
        val tickInner = radius - 2.dp.toPx()
        val tickOuter = radius + 2.dp.toPx()
        drawLine(
            color       = trackColor,
            start       = Offset(cx + tickInner * cos(tickAngle), cy + tickInner * sin(tickAngle)),
            end         = Offset(cx + tickOuter * cos(tickAngle), cy + tickOuter * sin(tickAngle)),
            strokeWidth = 1.dp.toPx(),
            cap         = StrokeCap.Round
        )
    }
}
