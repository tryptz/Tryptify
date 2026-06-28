package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * Vertical gain fader with a logarithmic-feel scale.
 *
 * Bottom 60 % of travel covers  -60 … 0 dB (unity).
 * Top    40 % of travel covers    0 … +24 dB.
 *
 * Rendered as a recessed groove with a coloured value-fill rising up to a
 * weighted fader cap, so the channel reads as a real console fader rather
 * than a hairline. The fader fills the WIDTH it is given by its parent — keep
 * it in a width-bounded slot (weight / fillMaxWidth / a fixed width).
 *
 * @param gainDb       current gain in dB (-60 … +24).
 * @param onGainChange callback when the user moves the fader.
 * @param accentColor  colour for the value-fill below the cap and the cap's indicator.
 */
@Composable
fun VerticalFader(
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val colors      = MaterialTheme.colorScheme
    val grooveTop   = Color.Black.copy(alpha = 0.30f)
    val grooveBot   = Color.Black.copy(alpha = 0.52f)
    val capTop      = lerp(colors.surfaceContainerHighest, Color.White, 0.12f)
    val capBottom   = lerp(colors.surfaceContainerHigh, Color.Black, 0.35f)
    val capBorder   = Color.White.copy(alpha = 0.22f)
    val tickColor   = colors.onSurfaceVariant.copy(alpha = 0.26f)
    val unityColor  = accentColor.copy(alpha = 0.70f)
    val haptic      = LocalHapticFeedback.current
    val prevInDetent = remember { mutableSetOf<Boolean>() }

    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val db = yToDb(change.position.y, size.height.toFloat())
                    onGainChange(db)
                    hapticAtUnity(db, prevInDetent, haptic)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onGainChange(yToDb(offset.y, size.height.toFloat()))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val grooveW = 8.dp.toPx()
        val grooveR = grooveW / 2f
        val capW = (w - 6.dp.toPx()).coerceIn(20.dp.toPx(), 34.dp.toPx())
        val capH = 16.dp.toPx()
        val capR = 5.dp.toPx()
        val thumbY = dbToY(gainDb, h)

        // ── Recessed groove ───────────────────────────────────────────────
        drawRoundRect(
            brush      = Brush.verticalGradient(listOf(grooveTop, grooveBot)),
            topLeft    = Offset(cx - grooveR, 0f),
            size       = Size(grooveW, h),
            cornerRadius = CornerRadius(grooveR)
        )

        // ── dB tick marks (both sides of the groove) ──────────────────────
        val ticks = listOf(24f, 18f, 12f, 6f, 0f, -6f, -12f, -18f, -24f, -36f, -48f, -60f)
        val tickGap = grooveR + 3.dp.toPx()
        for (db in ticks) {
            val y = dbToY(db, h)
            val len = if (db == 0f) 5.dp.toPx() else 3.dp.toPx()
            drawLine(tickColor, Offset(cx - tickGap - len, y), Offset(cx - tickGap, y), strokeWidth = 1.dp.toPx())
            drawLine(tickColor, Offset(cx + tickGap, y), Offset(cx + tickGap + len, y), strokeWidth = 1.dp.toPx())
        }

        // ── Value fill below the cap ──────────────────────────────────────
        if (thumbY < h) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.95f), accentColor.copy(alpha = 0.42f)),
                    startY = thumbY,
                    endY   = h
                ),
                topLeft    = Offset(cx - grooveR, thumbY),
                size       = Size(grooveW, h - thumbY),
                cornerRadius = CornerRadius(grooveR)
            )
        }

        // ── Unity (0 dB) reference line ───────────────────────────────────
        val unityY = dbToY(0f, h)
        drawLine(
            color       = unityColor,
            start       = Offset(cx - capW / 2f, unityY),
            end         = Offset(cx + capW / 2f, unityY),
            strokeWidth = 1.dp.toPx()
        )

        // ── Fader cap ─────────────────────────────────────────────────────
        val capLeft = cx - capW / 2f
        val capTopY = thumbY - capH / 2f
        // Drop shadow
        drawRoundRect(
            color      = Color.Black.copy(alpha = 0.40f),
            topLeft    = Offset(capLeft, capTopY + 3.dp.toPx()),
            size       = Size(capW, capH),
            cornerRadius = CornerRadius(capR)
        )
        // Body
        drawRoundRect(
            brush      = Brush.verticalGradient(listOf(capTop, capBottom)),
            topLeft    = Offset(capLeft, capTopY),
            size       = Size(capW, capH),
            cornerRadius = CornerRadius(capR)
        )
        // Border
        drawRoundRect(
            color      = capBorder,
            topLeft    = Offset(capLeft, capTopY),
            size       = Size(capW, capH),
            cornerRadius = CornerRadius(capR),
            style      = Stroke(width = 1.dp.toPx())
        )
        // Centre indicator line in accent
        drawLine(
            color       = accentColor,
            start       = Offset(capLeft + 4.dp.toPx(), thumbY),
            end         = Offset(capLeft + capW - 4.dp.toPx(), thumbY),
            strokeWidth = 2.dp.toPx(),
            cap         = StrokeCap.Round
        )
    }
}

// ── Mapping helpers ──────────────────────────────────────────────────────

/** Y pixel → dB.  Top = +24 dB, 40 % = 0 dB, bottom = -60 dB. */
private fun yToDb(y: Float, height: Float): Float {
    val fraction = (y / height).coerceIn(0f, 1f)
    return if (fraction <= 0.4f) {
        24f * (1f - fraction / 0.4f)
    } else {
        -60f * ((fraction - 0.4f) / 0.6f)
    }
}

/** dB → Y pixel. */
private fun dbToY(db: Float, height: Float): Float {
    val clamped = db.coerceIn(-60f, 24f)
    return if (clamped >= 0f) {
        height * 0.4f * (1f - clamped / 24f)
    } else {
        height * (0.4f + 0.6f * (-clamped / 60f))
    }
}

private fun hapticAtUnity(
    db: Float,
    prev: MutableSet<Boolean>,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    if (db.absoluteValue < 1f) {
        if (prev.isEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            prev.add(true)
        }
    } else {
        prev.clear()
    }
}
