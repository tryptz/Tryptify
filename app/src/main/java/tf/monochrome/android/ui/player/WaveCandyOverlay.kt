package tf.monochrome.android.ui.player

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import tf.monochrome.android.audio.eq.WaveScopeNative
import tf.monochrome.android.domain.model.WaveCandySettings

/**
 * FL Studio's Wave Candy oscilloscope over the artwork: the waveform and
 * nothing else on top of the cover — stereo (left across the top, right
 * across the bottom) or mono (one summed line), per [settings].
 *
 * The work is native (cpp/dsp/scope/wave_scope.cpp): it slides the window
 * smoothly between audio chunks — the old scope only moved when a chunk
 * landed, which is what read as a low frame rate — reduces it to points
 * keeping the peaks, and hands back line segments in pixels. This side only
 * draws them: one `drawLines` per pass on a reused array and reused Paints,
 * nothing allocated per frame, one int state bumped to redraw.
 *
 * Lines are white (or the album accent) over a dark underlay, so they hold on
 * light covers too, and swell with [kick].
 */
@Composable
fun WaveCandyOverlay(
    settings: WaveCandySettings,
    accent: Color,
    modifier: Modifier = Modifier,
    kick: FloatState? = null,
) {
    val segs = remember { FloatArray((POINTS - 1) * 4 * 2) }
    val tick = remember { mutableIntStateOf(0) }
    val ink = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    val shade = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            tick.intValue++
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val t = tick.intValue
        val k = kick?.floatValue ?: 0f
        val n = WaveScopeNative.nativeRender(
            segs, POINTS, settings.windowMs, settings.stereo,
            size.width, size.height, settings.gain * (1f + 0.5f * k),
        )
        if (n <= 0) return@Canvas
        val thick = settings.thicknessDp.dp.toPx() * (1f + 0.6f * k)
        ink.color = (if (settings.albumColor) accent else Color.White).copy(alpha = 0.92f).toArgb()
        ink.strokeWidth = thick
        shade.color = Color.Black.copy(alpha = 0.45f).toArgb()
        shade.strokeWidth = thick + 3.dp.toPx()
        drawIntoCanvas { c ->
            c.nativeCanvas.drawLines(segs, 0, n, shade)
            c.nativeCanvas.drawLines(segs, 0, n, ink)
        }
    }
}

private const val POINTS = 384
