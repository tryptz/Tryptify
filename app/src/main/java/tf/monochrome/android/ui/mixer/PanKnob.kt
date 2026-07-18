package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import tf.monochrome.android.ui.components.adjustableSemantics
import kotlin.math.cos
import kotlin.math.roundToInt
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

    // Gesture coroutine is keyed on Unit — read the live value through an
    // updated-state holder so a drag starts from the actual current pan.
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    Canvas(
        modifier = modifier
            // Keep the 28dp visual but expand the touch target to the 48dp
            // accessibility minimum so it isn't a near-impossible hit.
            .minimumInteractiveComponentSize()
            .size(28.dp)
            .adjustableSemantics(
                label = "Pan",
                value = value,
                range = -1f..1f,
                stateText = { v ->
                    when {
                        v < -0.02f -> "${(-v * 100).roundToInt()}% left"
                        v > 0.02f -> "${(v * 100).roundToInt()}% right"
                        else -> "Center"
                    }
                },
                onValueChange = onValueChange,
            )
            .pointerInput(Unit) {
                // Delta-based vertical drag (up = right, down = left). The knob
                // sits inside the horizontally-scrolling channel row; an
                // omnidirectional detectDragGestures with absolute-angle mapping
                // both stole the row's horizontal scroll and snapped the pan to
                // wherever the finger first landed. Vertical-only relative drag
                // fixes both — sideways swipes scroll the row, and the grab
                // starts from the current value.
                var startVal = 0f
                var accumPx = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        startVal = latestValue
                        accumPx = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        accumPx += dragAmount
                        // ~150px of travel spans the full -1..+1 range.
                        val newVal = (startVal - accumPx / 150f).coerceIn(-1f, 1f)
                        latestOnValueChange(newVal)

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
                )
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
