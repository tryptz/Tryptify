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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.isActive

/**
 * FL Studio's Wave Candy oscilloscope, drawn over the artwork: the waveform,
 * nothing else on top of the cover.
 *
 * [stereo] draws the two channels apart — left across the top, right across the
 * bottom, so a wide mix shows two different lines. Mono draws one line through
 * the middle, the two channels summed, a little taller since it has the room.
 *
 * Wave Candy is black ink on a pale panel; over album art that is unreadable
 * half the time, so lines are white with a dark underlay that holds on light
 * covers too. They swell with [kick].
 *
 * Samples come from [read] (SpectrumAnalyzerTap.copyScope) into buffers
 * allocated once; one int state bumped per frame redraws the canvas without
 * recomposing anything else.
 */
@Composable
fun WaveCandyOverlay(
    read: (FloatArray, FloatArray) -> Int,
    modifier: Modifier = Modifier,
    stereo: Boolean = true,
    /** The kick pulse (rememberKickPulse): the lines swell on it. */
    kick: androidx.compose.runtime.FloatState? = null,
) {
    val l = remember { FloatArray(FRAMES) }
    val r = remember { FloatArray(FRAMES) }
    val count = remember { mutableIntStateOf(0) }
    val tick = remember { mutableIntStateOf(0) }
    val currentRead = rememberUpdatedState(read)

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
        if (n < 2) return@Canvas
        val k = kick?.floatValue ?: 0f
        val w = size.width
        val h = size.height
        val lines = if (stereo) {
            val amp = h * (0.13f + 0.07f * k)
            listOf(
                waveformPath(n, w, h * 0.25f, amp) { l[it] },
                waveformPath(n, w, h * 0.75f, amp) { r[it] },
            )
        } else {
            val amp = h * (0.22f + 0.10f * k)
            listOf(waveformPath(n, w, h * 0.5f, amp) { (l[it] + r[it]) * 0.5f })
        }
        for (p in lines) {
            drawPath(p, SHADE, style = Stroke(width = 6f + 2f * k, cap = StrokeCap.Round))
            drawPath(p, INK, style = Stroke(width = 2.6f + 2f * k, cap = StrokeCap.Round))
        }
    }
}

/** One channel across the width, centred on [baseY], positive up. */
private inline fun waveformPath(n: Int, w: Float, baseY: Float, amp: Float, sample: (Int) -> Float): Path {
    val path = Path()
    val step = maxOf(1, n / WAVE_POINTS)
    var i = 0
    var first = true
    while (i < n) {
        val x = i.toFloat() / (n - 1) * w
        val y = baseY - sample(i).coerceIn(-1f, 1f) * amp
        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        i += step
    }
    return path
}

private val INK = Color.White.copy(alpha = 0.92f)
private val SHADE = Color.Black.copy(alpha = 0.45f)
private const val FRAMES = 1024
private const val WAVE_POINTS = 512
