package tf.monochrome.android.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * A FabFilter-style smoothed spectrum waveform overlay for the Now Playing screen.
 *
 * Renders the live FFT bins from [SpectrumAnalyzerTap] (256 log-spaced bins, dB
 * relative to a 0 dB pink-noise reference) as a flowing envelope at the bottom
 * of its parent. Designed to overlay album art without obscuring it — the fill
 * fades from accent-tint at the peak to fully transparent at the bottom edge.
 *
 * The composable smoothly interpolates each bin per-frame (~60 fps) so the
 * envelope glides between FFT updates instead of jittering. Empty / silent
 * input collapses to the baseline.
 */
@Composable
fun SpectrumOverlay(
    bins: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    /** Display range in dB above the 0-line that maps to full height. */
    headroomDb: Float = 24f,
    /** Display range in dB below the 0-line that maps to the baseline. */
    floorDb: Float = -36f,
    /** Per-frame approach factor for the smoothed envelope (0..1). */
    smoothing: Float = 0.18f,
) {
    // Locally smoothed bins for buttery motion between FFT frames.
    var smoothed by remember { mutableStateOf(FloatArray(0)) }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastFrameNanos == 0L) (1f / 60f)
                else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            lastFrameNanos = now

            val src = bins
            if (src.isEmpty()) {
                if (smoothed.isNotEmpty()) smoothed = FloatArray(0)
                continue
            }
            val dst = if (smoothed.size == src.size) smoothed else FloatArray(src.size) { floorDb }
            // Frame-rate–independent exponential smoothing.
            val alpha = (1f - kotlin.math.exp(-smoothing * 60f * dt)).coerceIn(0f, 1f)
            for (i in dst.indices) {
                dst[i] = dst[i] + (src[i] - dst[i]) * alpha
            }
            smoothed = dst.copyOf()
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val data = smoothed
        if (data.isEmpty()) return@Canvas
        val n = data.size
        val w = size.width
        val h = size.height
        val span = max(0.001f, headroomDb - floorDb)

        // Build envelope path: x is linear across the canvas (bins are
        // already log-spaced in frequency); y is the bin magnitude clamped
        // to the display range and inverted so high dB sits near the top.
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            xs[i] = i.toFloat() / (n - 1).toFloat() * w
            val db = data[i].coerceIn(floorDb, headroomDb)
            val t = (db - floorDb) / span // 0 at floor, 1 at headroom
            ys[i] = h - t * h
        }

        // Catmull-Rom -> cubic Bézier for a flowing FabFilter look.
        val envelope = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val p0x = xs[(i - 1).coerceAtLeast(0)]; val p0y = ys[(i - 1).coerceAtLeast(0)]
                val p1x = xs[i]; val p1y = ys[i]
                val p2x = xs[i + 1]; val p2y = ys[i + 1]
                val p3x = xs[(i + 2).coerceAtMost(n - 1)]; val p3y = ys[(i + 2).coerceAtMost(n - 1)]
                val c1x = p1x + (p2x - p0x) / 6f
                val c1y = p1y + (p2y - p0y) / 6f
                val c2x = p2x - (p3x - p1x) / 6f
                val c2y = p2y - (p3y - p1y) / 6f
                cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
            }
        }

        // Fill body: vertical gradient from accent at the envelope peak to
        // full transparency at the baseline so the album art shows through.
        val fill = Path().apply {
            addPath(envelope)
            lineTo(xs[n - 1], h)
            lineTo(xs[0], h)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    color.copy(alpha = 0.55f),
                    color.copy(alpha = 0.18f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h
            )
        )

        // Crisp envelope outline for definition over busy artwork.
        drawPath(
            path = envelope,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    color.copy(alpha = 0.85f),
                    color.copy(alpha = 0.95f),
                    color.copy(alpha = 0.85f)
                )
            ),
            style = Stroke(width = 2f)
        )

        // Soft glow dot at the loudest bin — moves with the music.
        var peakIdx = 0
        var peakY = h
        for (i in 0 until n) if (ys[i] < peakY) { peakY = ys[i]; peakIdx = i }
        if (peakY < h - 4f) {
            drawCircle(
                color = color.copy(alpha = 0.55f),
                radius = 6f,
                center = Offset(xs[peakIdx], peakY)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 2.2f,
                center = Offset(xs[peakIdx], peakY)
            )
        }
    }
}
