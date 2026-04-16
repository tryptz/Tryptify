package tf.monochrome.android.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import kotlin.math.exp
import kotlin.math.max

/**
 * FabFilter-style smoothed spectrum waveform overlay for the Now Playing
 * screen. Renders the live FFT bins from [SpectrumAnalyzerTap] as a flowing
 * envelope filled to the baseline.
 *
 * Implementation notes:
 *  - The smoothing buffer is allocated once and mutated in place inside the
 *    per-frame coroutine, so we don't churn the GC at 60 fps. A monotonic
 *    counter inside a `mutableIntStateOf` is the only Compose state we
 *    write to — that's enough to invalidate the Canvas without recomposing
 *    anything else on the screen.
 *  - Separate attack/release time constants give the envelope a snappy
 *    response on transients and a longer tail on decays, matching the
 *    "Pro-Q" look the reference EQ editor uses.
 *  - The overlay always paints; if the audio falls silent the bins decay
 *    naturally toward the floor and the envelope flattens.
 */
@Composable
fun SpectrumOverlay(
    bins: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    /** dB above 0 (pink-noise reference) that maps to the top of the canvas. */
    headroomDb: Float = 36f,
    /** dB below 0 that maps to the baseline. */
    floorDb: Float = -24f,
    /** Approach factor per 60 fps frame for rising bins (0..1, higher = snappier). */
    attack: Float = 0.55f,
    /** Approach factor per 60 fps frame for falling bins. */
    release: Float = 0.12f,
) {
    // Persistent in-place smoothing buffer — never replaced.
    val smoothed = remember {
        FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { floorDb }
    }
    // Bumped once per frame to invalidate the Canvas without allocating.
    val tick = remember { mutableIntStateOf(0) }
    // The parent re-emits a fresh FloatArray on every FFT frame; without
    // rememberUpdatedState the LaunchedEffect would forever read the array
    // captured at first composition and the envelope would never animate.
    // rememberUpdatedState so the long-lived LaunchedEffect(Unit) coroutine
    // always reads the latest values even though it never restarts.
    val currentBins by rememberUpdatedState(bins)
    val currentAttack by rememberUpdatedState(attack)
    val currentRelease by rememberUpdatedState(release)
    val currentColor by rememberUpdatedState(color)

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (lastFrameNanos == 0L) (1f / 60f)
                else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            lastFrameNanos = now

            val src = currentBins
            val n = minOf(src.size, smoothed.size)
            if (n == 0) {
                tick.intValue++
                continue
            }
            // Frame-rate–independent exponential smoothing with split
            // attack/release for the snappy-on-rise, gentle-on-fall feel.
            val attackAlpha = (1f - exp(-currentAttack * 60f * dt)).coerceIn(0f, 1f)
            val releaseAlpha = (1f - exp(-currentRelease * 60f * dt)).coerceIn(0f, 1f)
            for (i in 0 until n) {
                val target = src[i]
                val cur = smoothed[i]
                val a = if (target > cur) attackAlpha else releaseAlpha
                smoothed[i] = cur + (target - cur) * a
            }
            tick.intValue++
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // Subscribe to the per-frame tick so the Canvas redraws.
        @Suppress("UNUSED_VARIABLE")
        val t = tick.intValue

        val n = smoothed.size
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        val span = max(0.001f, headroomDb - floorDb)

        // Map bin index -> x (linear; bins are already log-spaced in
        // frequency by SpectrumAnalyzerTap), magnitude -> y (inverted).
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            xs[i] = i.toFloat() / (n - 1).toFloat() * w
            val db = smoothed[i].coerceIn(floorDb, headroomDb)
            val tNorm = (db - floorDb) / span
            ys[i] = h - tNorm * h
        }

        // Catmull-Rom -> cubic Bézier for the flowing FabFilter envelope.
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

        // Filled body: bright at the envelope peak, fading to transparent
        // at the baseline so the album art still shows through.
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
                    currentColor.copy(alpha = 0.75f),
                    currentColor.copy(alpha = 0.35f),
                    currentColor.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h
            )
        )

        // Crisp envelope outline for definition over busy artwork.
        drawPath(
            path = envelope,
            color = Color.White.copy(alpha = 0.92f),
            style = Stroke(width = 2.5f)
        )
        drawPath(
            path = envelope,
            color = currentColor.copy(alpha = 0.55f),
            style = Stroke(width = 5f)
        )

        // Soft glow dot at the loudest bin — moves with the music.
        var peakIdx = 0
        var peakY = h
        for (i in 0 until n) if (ys[i] < peakY) { peakY = ys[i]; peakIdx = i }
        if (peakY < h - 4f) {
            drawCircle(
                color = currentColor.copy(alpha = 0.55f),
                radius = 9f,
                center = Offset(xs[peakIdx], peakY)
            )
            drawCircle(
                color = Color.White,
                radius = 3f,
                center = Offset(xs[peakIdx], peakY)
            )
        }
    }
}
