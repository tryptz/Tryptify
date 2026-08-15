// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.patterns.PatternHaptics
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the editor is looking at, and what is selected inside it.
 *
 * Both are frame indices rather than fractions. Fractions look tidier until
 * the buffer is cropped underneath them, at which point every stored fraction
 * silently means a different sample than it did a moment ago — which is how a
 * selection ends up drifting after each edit.
 */
data class WaveView(
    val viewStart: Int = 0,
    val viewEnd: Int = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
) {
    val viewSpan: Int get() = (viewEnd - viewStart).coerceAtLeast(1)
    val hasSelection: Boolean get() = selectionEnd > selectionStart

    /** Clamps the window and the selection into a buffer of [frames]. */
    fun coerceInto(frames: Int): WaveView {
        if (frames <= 0) return WaveView()
        val end = viewEnd.coerceIn(1, frames)
        val start = viewStart.coerceIn(0, end - 1)
        return copy(
            viewStart = start,
            viewEnd = end,
            selectionStart = selectionStart.coerceIn(0, frames),
            selectionEnd = selectionEnd.coerceIn(0, frames),
        )
    }

    companion object {
        fun whole(frames: Int) = WaveView(0, frames.coerceAtLeast(1), 0, 0)
    }
}

/**
 * The wave editor surface.
 *
 * Three gestures, and they are separated by *where* they start rather than by
 * a mode switch, because a mode switch on a one-handed screen is a tax paid on
 * every edit:
 *
 * * **Near a selection handle** — drags that handle. The grab radius is in dp,
 *   not frames, so it stays reachable at every zoom level.
 * * **Anywhere else, one finger** — draws a new selection from scratch.
 * * **Two fingers** — pinch to zoom, drag to scroll. Zoom is anchored on the
 *   midpoint between the fingers, so the audio under them stays put; anchoring
 *   on the view centre instead is the thing that makes zooming feel like the
 *   waveform is running away.
 *
 * Rendering asks [tf.monochrome.android.audio.sampler.SampleEdits.peaksOfRange]
 * for exactly the window on screen, so drawing costs the same whether the user
 * is looking at ten seconds or ten milliseconds.
 */
@Composable
fun WaveEditor(
    peaks: FloatArray,
    frames: Int,
    view: WaveView,
    modifier: Modifier = Modifier,
    accent: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    playheadFrame: Int? = null,
    onViewChange: (WaveView) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val onSurface = androidx.compose.material3.MaterialTheme.colorScheme.onSurface

    var size by remember { mutableStateOf(IntSize.Zero) }
    val grabRadiusPx = with(density) { HANDLE_GRAB_DP.dp.toPx() }

    fun frameAt(x: Float): Int {
        val width = size.width.toFloat().coerceAtLeast(1f)
        val fraction = (x / width).coerceIn(0f, 1f)
        return (view.viewStart + fraction * view.viewSpan).roundToInt().coerceIn(0, frames)
    }

    fun xOf(frame: Int): Float {
        val width = size.width.toFloat().coerceAtLeast(1f)
        return (frame - view.viewStart).toFloat() / view.viewSpan * width
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(EDITOR_HEIGHT_DP.dp)
            .clip(MonoDimens.shapeMd)
            .background(surface.copy(alpha = 0.55f))
            .onSizeChanged { size = it }
            .pointerInput(frames) {
                // Two-finger transform first. It runs in its own detector so a
                // pinch is never mistaken for a selection drag; the selection
                // detector below ignores gestures once a second pointer lands.
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (frames <= 0 || size.width <= 0) return@detectTransformGestures
                    val width = size.width.toFloat()
                    val anchorFraction = (centroid.x / width).coerceIn(0f, 1f)
                    val anchorFrame = view.viewStart + anchorFraction * view.viewSpan

                    val zoomed = (view.viewSpan / zoom).coerceIn(
                        MIN_VISIBLE_FRAMES.toFloat(),
                        frames.toFloat(),
                    )
                    // Keep the audio under the fingers where it is, then apply
                    // the pan as a shift in frames.
                    val panFrames = -pan.x / width * zoomed
                    var start = (anchorFrame - anchorFraction * zoomed + panFrames)
                    start = start.coerceIn(0f, (frames - zoomed).coerceAtLeast(0f))
                    onViewChange(
                        view.copy(
                            viewStart = start.roundToInt(),
                            viewEnd = (start + zoomed).roundToInt().coerceAtMost(frames),
                        ).coerceInto(frames),
                    )
                }
            }
            .pointerInput(frames, view.viewStart, view.viewEnd) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (frames <= 0) return@awaitEachGesture

                    val startX = xOf(view.selectionStart)
                    val endX = xOf(view.selectionEnd)
                    // Which handle, if either, the finger came down on.
                    val onStart = view.hasSelection && abs(down.position.x - startX) <= grabRadiusPx
                    val onEnd = view.hasSelection && abs(down.position.x - endX) <= grabRadiusPx
                    // When both are within reach — a very short selection, or a
                    // very zoomed-out view — the nearer one wins, decided once
                    // so the handles cannot swap under the finger mid-drag.
                    val draggingStart = onStart &&
                        (!onEnd || abs(down.position.x - startX) <= abs(down.position.x - endX))
                    val draggingEnd = onEnd && !draggingStart

                    val anchor = when {
                        draggingStart -> view.selectionEnd
                        draggingEnd -> view.selectionStart
                        else -> frameAt(down.position.x)
                    }
                    PatternHaptics.pageChange(haptics)

                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // A second finger means the user is reaching for the
                        // pinch; hand the gesture over rather than dragging a
                        // selection out from under them.
                        if (event.changes.size > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        change.consume()
                        moved = true
                        val here = frameAt(change.position.x)
                        onViewChange(
                            view.copy(
                                selectionStart = minOf(anchor, here),
                                selectionEnd = maxOf(anchor, here),
                            ),
                        )
                    }

                    // A tap with no movement clears the selection rather than
                    // leaving a zero-width one behind — "select nothing" has to
                    // be reachable, and tapping the waveform is where everyone
                    // looks for it.
                    if (!moved && !draggingStart && !draggingEnd) {
                        onViewChange(view.copy(selectionStart = 0, selectionEnd = 0))
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Measured by onSizeChanged above, never assigned here: writing
            // state during the draw phase re-invalidates the very frame that
            // is drawing it.
            val mid = this.size.height / 2f
            val buckets = peaks.size / 2
            if (buckets <= 0 || frames <= 0) return@Canvas
            val step = this.size.width / buckets

            val selStartX = xOf(view.selectionStart)
            val selEndX = xOf(view.selectionEnd)

            // Selection band behind the wave, so the wave stays the brightest
            // thing on the surface.
            if (view.hasSelection) {
                drawRect(
                    color = accent.copy(alpha = 0.16f),
                    topLeft = Offset(selStartX, 0f),
                    size = Size(selEndX - selStartX, this.size.height),
                )
            }

            for (i in 0 until buckets) {
                val lo = peaks[i * 2]
                val hi = peaks[i * 2 + 1]
                val x = i * step
                val inside = view.hasSelection && x in selStartX..selEndX
                drawLine(
                    color = if (!view.hasSelection || inside) accent else accent.copy(alpha = 0.34f),
                    start = Offset(x, mid - hi * mid * 0.92f),
                    end = Offset(x, mid - lo * mid * 0.92f),
                    strokeWidth = (step * 0.9f).coerceAtLeast(1f),
                )
            }

            drawLine(
                color = Color.White.copy(alpha = 0.10f),
                start = Offset(0f, mid),
                end = Offset(this.size.width, mid),
                strokeWidth = 1f,
            )

            if (view.hasSelection) {
                drawEditorHandle(selStartX, this.size, accent)
                drawEditorHandle(selEndX, this.size, accent)
            }

            playheadFrame?.let { frame ->
                if (frame in view.viewStart..view.viewEnd) {
                    val x = xOf(frame)
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f),
                        start = Offset(x, 0f),
                        end = Offset(x, this.size.height),
                        strokeWidth = 2f,
                    )
                }
            }

            // A hairline map of the whole buffer along the bottom, with the
            // visible window marked. Without it, zoomed in past a few percent
            // there is nothing on screen saying where in the sample you are.
            if (view.viewSpan < frames) {
                val trackY = this.size.height - 3f
                drawLine(
                    color = onSurface.copy(alpha = 0.18f),
                    start = Offset(0f, trackY),
                    end = Offset(this.size.width, trackY),
                    strokeWidth = 3f,
                )
                val from = view.viewStart.toFloat() / frames * this.size.width
                val to = view.viewEnd.toFloat() / frames * this.size.width
                drawLine(
                    color = accent,
                    start = Offset(from, trackY),
                    end = Offset(to, trackY),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

private fun DrawScope.drawEditorHandle(x: Float, canvas: Size, accent: Color) {
    drawLine(
        color = Color.White.copy(alpha = 0.92f),
        start = Offset(x, 0f),
        end = Offset(x, canvas.height),
        strokeWidth = 2.5f,
    )
    // Grips at both ends so the handle is findable whichever way the finger
    // comes in, and wide enough to aim at without covering the wave.
    drawRoundRect(
        color = accent,
        topLeft = Offset(x - 6f, 0f),
        size = Size(12f, 16f),
        cornerRadius = CornerRadius(3f),
    )
    drawRoundRect(
        color = accent,
        topLeft = Offset(x - 6f, canvas.height - 16f),
        size = Size(12f, 16f),
        cornerRadius = CornerRadius(3f),
    )
}

/** Reach of a selection handle, in dp so it is constant at any zoom. */
private const val HANDLE_GRAB_DP = 24

private const val EDITOR_HEIGHT_DP = 180

/** Closest zoom: below this the wave is individual samples and unreadable. */
private const val MIN_VISIBLE_FRAMES = 64
