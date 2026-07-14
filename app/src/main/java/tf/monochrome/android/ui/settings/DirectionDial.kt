package tf.monochrome.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A full-360° direction joystick: drag anywhere on the dial to aim it. 0° = up,
 * increasing clockwise — the same convention the god-ray shader uses. Unlike the
 * bounded [tf.monochrome.android.ui.mixer.PanKnob], the touch angle is read
 * absolutely with no clamp so it spins the whole way round.
 */
@Composable
internal fun DirectionDial(
    angleDeg: Float,
    onAngleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val dotColor = if (accentColor.luminance() > 0.55f) Color.Black else Color.White

    Canvas(
        modifier = modifier
            .size(64.dp)
            .pointerInput(Unit) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                fun emit(pos: Offset) {
                    // atan2(x, -y): 0 = up (12 o'clock), increasing clockwise.
                    val deg = (Math.toDegrees(
                        atan2((pos.x - cx).toDouble(), -(pos.y - cy).toDouble())
                    ).toFloat() + 360f) % 360f
                    onAngleChange(deg)
                }
                detectDragGestures(onDragStart = { emit(it) }) { change, _ ->
                    change.consume()
                    emit(change.position)
                }
            },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f - 5.dp.toPx()

        // Full-circle track.
        drawCircle(
            color = trackColor,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5.dp.toPx()),
        )

        // Direction: 0° = up, clockwise → (sin, -cos).
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val dx = sin(rad)
        val dy = -cos(rad)

        // Needle from centre to the rim.
        drawLine(
            color = accentColor,
            start = Offset(cx, cy),
            end = Offset(cx + dx * radius, cy + dy * radius),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Handle dot at the rim.
        val handle = Offset(cx + dx * radius, cy + dy * radius)
        drawCircle(color = accentColor, radius = 7.dp.toPx(), center = handle)
        drawCircle(color = dotColor, radius = 4.dp.toPx(), center = handle)

        // Small hub.
        drawCircle(color = accentColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
    }
}
