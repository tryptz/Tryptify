package tf.monochrome.android.ui.mixer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Segmented vertical VU meter.
 *
 * A recessed rounded track with thin horizontal segments that light up from
 * the bottom based on the current level. Only active (and the held peak)
 * segments are painted, so the inactive track stays clean instead of showing
 * a column of faint dots. Colours run low → warning → clip near the top.
 *
 * @param levelDb current level in dB (typically -60 … +6).
 * @param muted   when true the bar drops to zero and dims.
 */
@Composable
fun VuMeter(
    levelDb: Float,
    muted: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    // Normalise dB -> 0..1 fraction  (-60 dB -> 0,  0 dB -> 1,  +6 dB -> clamp 1)
    val targetFraction = if (muted) 0f
    else ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    val warningColor = lerp(accentColor, Color(0xFFFFD166), 0.55f)
    val clipColor = lerp(accentColor, MaterialTheme.colorScheme.error, 0.80f)
    val lowColor = accentColor.copy(alpha = 0.80f)
    val trackColor = Color.Black.copy(alpha = 0.38f)

    val animatedLevel by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "vuLevel"
    )

    // ── Peak hold — tracks max and decays slowly ───────────────────────
    var peak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animatedLevel) {
        if (animatedLevel > peak) peak = animatedLevel
    }
    LaunchedEffect(peak) {
        if (peak > 0f) {
            delay(800)
            peak = (peak - 0.04f).coerceAtLeast(0f)
        }
    }

    Canvas(
        modifier = modifier
            .width(7.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(3.5.dp))
    ) {
        val w = size.width
        val h = size.height
        val segH = 3.dp.toPx()
        val gapH = 1.2.dp.toPx()
        val stepH = segH + gapH
        val segCount = (h / stepH).toInt().coerceAtLeast(1)

        // Recessed track
        drawRect(color = trackColor)

        val activeSegs = (segCount * animatedLevel).toInt()
        val peakSeg = (segCount * peak).toInt().coerceIn(0, segCount - 1)

        for (i in 0 until segCount) {
            val isActive = i < activeSegs
            val isPeak = i == peakSeg && peak > 0.01f
            if (!isActive && !isPeak) continue   // keep the inactive track clean

            val segY = h - (i + 1) * stepH
            val fraction = i.toFloat() / segCount
            val color = when {
                fraction > 0.88f -> clipColor
                fraction > 0.72f -> warningColor
                fraction > 0.40f -> accentColor
                else             -> lowColor
            }
            drawRect(
                color   = if (isPeak && !isActive) color.copy(alpha = 0.85f) else color,
                topLeft = Offset(0f, segY),
                size    = Size(w, segH)
            )
        }
    }
}
