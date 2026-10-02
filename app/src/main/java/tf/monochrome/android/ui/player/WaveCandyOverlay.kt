package tf.monochrome.android.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FL Studio's Wave Candy, oscilloscope + goniometer, drawn over the artwork.
 *
 * Top: the live waveform (mid, (L+R)/2) as a line across the cover. Bottom:
 * the same line mirrored, as Wave Candy draws it. Centre: the stereo dot
 * cloud — each frame plotted at side (L−R) across, mid (L+R) up — over dashed
 * guides at the polar angles, labelled +L/+R/−R/−L in the corners. A mono
 * track collapses to a vertical stroke; wide stereo spreads sideways.
 *
 * Wave Candy is black ink on a pale panel; over album art that is unreadable
 * half the time, so lines are white with a dark underlay that holds on light
 * covers too.
 *
 * Samples come from [read] (SpectrumAnalyzerTap.copyScope) into buffers
 * allocated once; one int state bumped per frame redraws the canvas without
 * recomposing anything else.
 */
@Composable
fun WaveCandyOverlay(
    read: (FloatArray, FloatArray) -> Int,
    modifier: Modifier = Modifier,
    /** The kick pulse (rememberKickPulse): the lines swell and brighten on it. */
    kick: androidx.compose.runtime.FloatState? = null,
) {
    val l = remember { FloatArray(FRAMES) }
    val r = remember { FloatArray(FRAMES) }
    val count = remember { mutableIntStateOf(0) }
    val tick = remember { mutableIntStateOf(0) }
    val currentRead = rememberUpdatedState(read)
    val measurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            count.intValue = currentRead.value(l, r)
            tick.intValue++
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val t = tick.intValue
        val n = count.intValue
        val k = kick?.floatValue ?: 0f
        val w = size.width
        val h = size.height
        val ink = Color.White.copy(alpha = 0.92f)
        val shade = Color.Black.copy(alpha = 0.45f)
        val guide = Color.White.copy(alpha = 0.32f)

        // ── Guides: dashed rays from the centre at the goniometer's angles.
        val c = Offset(w / 2f, h / 2f)
        val reach = minOf(w, h) * 0.46f
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        for (deg in GUIDE_ANGLES) {
            val a = deg * PI.toFloat() / 180f
            val dx = cos(a) * reach
            val dy = sin(a) * reach
            drawLine(guide, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 2.5f, pathEffect = dash)
        }

        val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        fun label(text: String, x: Float, y: Float) {
            val m = measurer.measure(text, labelStyle)
            drawText(m, topLeft = Offset(x - m.size.width / 2f, y - m.size.height / 2f))
        }
        label("+ L", w * 0.11f, h * 0.10f)
        label("+ R", w * 0.89f, h * 0.10f)
        label("- R", w * 0.11f, h * 0.90f)
        label("- L", w * 0.89f, h * 0.90f)
        if (n < 2) return@Canvas

        // ── Waveform, top and mirrored bottom.
        val amp = h * (0.13f + 0.07f * k)
        val top = waveformPath(l, r, n, w, h * 0.20f, -amp)
        val bottom = waveformPath(l, r, n, w, h * 0.80f, amp)
        for (p in listOf(top, bottom)) {
            drawPath(p, shade, style = Stroke(width = 6f, cap = StrokeCap.Round))
            drawPath(p, ink, style = Stroke(width = 2.6f + 2f * k, cap = StrokeCap.Round))
        }

        // ── Goniometer cloud.
        drawCloud(l, r, n, c, minOf(w, h) * (0.16f + 0.06f * k), ink)
    }
}

/** Mid signal across the width, centred on [baseY]; [amp] signs the direction. */
private fun waveformPath(l: FloatArray, r: FloatArray, n: Int, w: Float, baseY: Float, amp: Float): Path {
    val path = Path()
    val step = maxOf(1, n / WAVE_POINTS)
    var i = 0
    var first = true
    while (i < n) {
        val x = i.toFloat() / (n - 1) * w
        val y = baseY + ((l[i] + r[i]) * 0.5f).coerceIn(-1f, 1f) * amp
        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        i += step
    }
    return path
}

/** One dot per frame: side across, mid up — the goniometer's rotated L/R plane. */
private fun DrawScope.drawCloud(l: FloatArray, r: FloatArray, n: Int, c: Offset, scale: Float, ink: Color) {
    val dot = ink.copy(alpha = 0.55f)
    val step = maxOf(1, n / CLOUD_POINTS)
    var i = 0
    while (i < n) {
        val side = (l[i] - r[i]) * 0.7071f
        val mid = (l[i] + r[i]) * 0.7071f
        drawCircle(dot, radius = 1.6f, center = Offset(c.x + side * scale, c.y - mid * scale))
        i += step
    }
}

private const val FRAMES = 1024
private const val WAVE_POINTS = 512
private const val CLOUD_POINTS = 600
// Vertical, the two diagonals (pure L, pure R), and a shallower pair either side.
private val GUIDE_ANGLES = floatArrayOf(90f, 45f, -45f, 22f, -22f)
