// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.player

import android.os.Build
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.filter
import tf.monochrome.android.motion.MotionBlurQuality
import tf.monochrome.android.motion.rememberMotionBlur
import tf.monochrome.android.performance.LocalLowPerformance

/**
 * The lyric glide's motion blur: how far to blur the lyrics along their motion
 * right now, as a Gaussian sigma in px, 0 while they are still.
 *
 * Frames run only while the list scrolls (the glide to the next line, or a
 * drag) and until the blur has faded after it; a still list costs nothing.
 * The speed comes from the native tracker (`cpp/motion`), fed how far the
 * lines have travelled. Android 12 and later, where a layer can be blurred;
 * off with the app's animations switched off.
 */
@Composable
internal fun rememberLyricMotionBlur(listState: LazyListState, level: Int): State<Float> {
    val sigma = remember { mutableFloatStateOf(0f) }
    val effective = if (!lyricMotionBlurSupported() || LocalLowPerformance.current.disableAnimations) {
        MotionBlurQuality.OFF
    } else {
        MotionBlurQuality.coerce(level)
    }
    val blur = rememberMotionBlur(effective > MotionBlurQuality.OFF)
    LaunchedEffect(blur, effective, listState) {
        sigma.floatValue = 0f
        if (blur == null) return@LaunchedEffect
        val travel = ListTravel()
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect {
            blur.reset()
            travel.reset()
            do {
                val moving = listState.isScrollInProgress
                withFrameNanos { nanos ->
                    val info = listState.layoutInfo
                    // A line that jumped out of view (a seek far away) is a
                    // jump, not a speed: start again from rest.
                    if (!travel.update(info)) blur.reset()
                    blur.step(
                        frameNanos = nanos,
                        positionPx = travel.positionPx,
                        jumpPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
                        quality = effective,
                        filmExposure = true,
                    )
                    sigma.floatValue = blur.sigmaPx
                }
            } while (moving || sigma.floatValue > 0f)
        }
    }
    return sigma
}

internal fun lyricMotionBlurSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Blurs what is drawn inside it along y by [sigma] px. A blur of 0 sets no
 * effect at all, so the layer is not taken offscreen while the lyrics rest.
 * Effects are kept per quarter-pixel step, the step the tracker rounds to, so
 * a glide builds each one once instead of one a frame.
 */
internal fun Modifier.lyricMotionBlur(sigma: State<Float>): Modifier = graphicsLayer {
    renderEffect = LyricBlurEffects.forSigma(sigma.value)
}

private object LyricBlurEffects {
    private const val STEPS_PER_PX = 4
    private const val MAX_STEPS = 64
    private val cache = arrayOfNulls<BlurEffect>(MAX_STEPS + 1)

    fun forSigma(sigma: Float): BlurEffect? {
        val step = (sigma * STEPS_PER_PX).toInt().coerceIn(0, MAX_STEPS)
        // Android's blur takes a radius and makes it sigma = 0.57735·r + 0.5,
        // so a sigma under half a pixel has no radius at all.
        val radius = (step.toFloat() / STEPS_PER_PX - 0.5f) / 0.57735f
        if (radius <= 0f) return null
        return cache[step] ?: BlurEffect(0f, radius, TileMode.Decal).also { cache[step] = it }
    }
}

/**
 * How far the lyric lines have travelled, px, from where the tracker started:
 * an endless coordinate made out of a lazy list, whose items come and go. Each
 * frame finds the line it measured from last frame, takes its move, and then
 * measures from the line in the middle, which is the likeliest to still be
 * there next frame. Main thread only.
 */
private class ListTravel {
    private var index = -1
    private var offset = 0
    var positionPx = 0f
        private set

    fun reset() {
        index = -1
        positionPx = 0f
    }

    /** False when last frame's line is gone and the move cannot be told. */
    fun update(info: LazyListLayoutInfo): Boolean {
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return false
        var found = index < 0
        if (index >= 0) {
            for (item in items) {
                if (item.index == index) {
                    // Scrolling on, the lines move up and their offsets shrink.
                    positionPx += (offset - item.offset).toFloat()
                    found = true
                    break
                }
            }
        }
        val middle = items[items.size / 2]
        index = middle.index
        offset = middle.offset
        return found
    }
}
