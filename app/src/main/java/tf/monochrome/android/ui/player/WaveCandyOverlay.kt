package tf.monochrome.android.ui.player

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import tf.monochrome.android.domain.model.WaveGlow

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
 * Lines are white (or the album accent) set off by a neon glow in their own
 * colour or a soft drop shadow, per [settings], and swell with [kick].
 *
 * While the song is paused ([isPlaying] false) the scope keeps sliding until
 * its window reaches the last sample, a second or so, and then shows the same
 * picture on every frame, which it redrew, glow and all, sixty to a hundred
 * and twenty times a second. So while paused each frame works the picture out
 * first and redraws only when it changed. It is still worked out every frame,
 * not stopped: the native read clock places the window afresh after a gap of
 * half a second (as it does after a seek), so a scope that stopped asking
 * would jump on its next picture, a resize or a settings change while paused
 * included, instead of carrying on from where it stood.
 */
@Composable
fun WaveCandyOverlay(
    settings: WaveCandySettings,
    accent: Color,
    modifier: Modifier = Modifier,
    kick: FloatState? = null,
    isPlaying: Boolean = true,
) {
    val segs = remember { FloatArray((POINTS - 1) * 4 * 2) }
    val tick = remember { mutableIntStateOf(0) }
    val ink = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    val still = remember { StillScope() }
    val playing by rememberUpdatedState(isPlaying)
    val current by rememberUpdatedState(settings)
    val currentKick by rememberUpdatedState(kick)

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            if (playing) {
                still.forget()
                tick.intValue++
            } else if (still.moved(current, currentKick?.floatValue ?: 0f)) {
                tick.intValue++
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val t = tick.intValue
        still.width = size.width
        still.height = size.height
        val k = kick?.floatValue ?: 0f
        val n = WaveScopeNative.nativeRender(
            segs, POINTS, settings.windowMs, settings.stereo,
            size.width, size.height, settings.gain * (1f + 0.5f * k),
        )
        if (n <= 0) return@Canvas
        val thick = settings.thicknessDp.dp.toPx() * (1f + 0.6f * k)
        val line = if (settings.albumColor) accent else Color.White
        ink.color = line.toArgb()
        ink.strokeWidth = thick
        // The glow is the line paint's own shadow layer: one GPU pass, and the
        // segments blur as one stroke, so their joins do not stack into beads
        // the way a separate translucent underlay did. Hardware canvases draw
        // shadow layers for lines from API 28; below that the line is plain.
        when (settings.glow) {
            WaveGlow.NEON -> ink.setShadowLayer(
                (8f + 6f * k).dp.toPx(), 0f, 0f, line.copy(alpha = 0.95f).toArgb(),
            )
            WaveGlow.SHADOW -> ink.setShadowLayer(
                5.dp.toPx(), 0f, 2.dp.toPx(), Color.Black.copy(alpha = 0.6f).toArgb(),
            )
            WaveGlow.NONE -> ink.clearShadowLayer()
        }
        drawIntoCanvas { c -> c.nativeCanvas.drawLines(segs, 0, n, ink) }
    }
}

/**
 * The paused scope's last picture, to tell a frame that would redraw the same
 * lines from one that would not. Main thread only: the frame loop and the
 * draw both run there.
 */
private class StillScope {
    var width = 0f
    var height = 0f
    private val probe = FloatArray((POINTS - 1) * 4 * 2)
    private val last = FloatArray((POINTS - 1) * 4 * 2)
    private var lastCount = -1

    /** Forget the last picture, so the first paused frame always draws. */
    fun forget() {
        lastCount = -1
    }

    /** Works this frame's picture out with the draw's own arguments; true when it differs from the last. */
    fun moved(settings: WaveCandySettings, kick: Float): Boolean {
        if (width <= 0f || height <= 0f) return true
        val n = WaveScopeNative.nativeRender(
            probe, POINTS, settings.windowMs, settings.stereo,
            width, height, settings.gain * (1f + 0.5f * kick),
        )
        if (n == lastCount && sameLines(probe, last, n)) return false
        probe.copyInto(last, endIndex = n.coerceAtLeast(0))
        lastCount = n
        return true
    }
}

private fun sameLines(a: FloatArray, b: FloatArray, n: Int): Boolean {
    for (i in 0 until n) if (a[i] != b[i]) return false
    return true
}

private const val POINTS = 384
