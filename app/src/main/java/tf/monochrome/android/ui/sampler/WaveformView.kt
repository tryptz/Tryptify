// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.patterns.PatternHaptics
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.abs

/**
 * A waveform with draggable trim handles.
 *
 * Drawn from pre-computed min/max peak pairs, never from the samples
 * themselves. A three-second capture is 288,000 floats and the view is a few
 * hundred pixels wide; walking the buffer on every frame would be the single
 * most expensive thing on the screen, for a result identical to walking it
 * once. [SampleEdits.peaks] does that walk, and the same peak file is what the
 * library list draws its thumbnails from.
 *
 * Peaks rather than averages, too: an averaged waveform of a drum hit is a
 * flat smear, and the transient — the one thing the user is looking for when
 * they place a start handle — disappears entirely.
 */
@Composable
fun WaveformView(
    peaks: FloatArray,
    modifier: Modifier = Modifier,
    accent: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    startFraction: Float = 0f,
    endFraction: Float = 1f,
    playheadFraction: Float? = null,
    interactive: Boolean = true,
    onTrimChange: (Float, Float) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surface
    var width by remember { mutableFloatStateOf(1f) }
    // Which handle a drag grabbed. Decided once, at touch-down, from whichever
    // is nearer — re-deciding mid-drag lets the handles swap under the finger
    // when they cross, which feels broken.
    var dragging by remember { mutableStateOf<Handle?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT_DP.dp)
            .clip(MonoDimens.shapeMd)
            .background(surface.copy(alpha = 0.55f))
            .then(
                if (!interactive) {
                    Modifier
                } else {
                    Modifier.pointerInput(peaks) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                dragging = if (
                                    abs(fraction - startFraction) <= abs(fraction - endFraction)
                                ) {
                                    Handle.Start
                                } else {
                                    Handle.End
                                }
                                PatternHaptics.pageChange(haptics)
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ ->
                            change.consume()
                            width = size.width.toFloat().coerceAtLeast(1f)
                            val fraction = (change.position.x / width).coerceIn(0f, 1f)
                            when (dragging) {
                                // A minimum gap keeps the selection from
                                // collapsing to nothing, which would otherwise
                                // be one careless drag away from a sample that
                                // cannot be saved.
                                Handle.Start -> onTrimChange(
                                    fraction.coerceAtMost(endFraction - MIN_SELECTION),
                                    endFraction,
                                )

                                Handle.End -> onTrimChange(
                                    startFraction,
                                    fraction.coerceAtLeast(startFraction + MIN_SELECTION),
                                )

                                null -> Unit
                            }
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val mid = size.height / 2f
            val buckets = peaks.size / 2
            if (buckets <= 0) return@Canvas
            val step = size.width / buckets

            // Dim everything outside the selection rather than hiding it: the
            // user needs to see what they are about to cut off, especially
            // when hunting for the transient at the start.
            for (i in 0 until buckets) {
                val lo = peaks[i * 2]
                val hi = peaks[i * 2 + 1]
                val x = i * step
                val fraction = if (buckets > 1) i.toFloat() / (buckets - 1) else 0f
                val inside = fraction in startFraction..endFraction
                drawLine(
                    color = if (inside) accent else accent.copy(alpha = 0.22f),
                    start = Offset(x, mid - hi * mid * 0.92f),
                    end = Offset(x, mid - lo * mid * 0.92f),
                    strokeWidth = (step * 0.8f).coerceAtLeast(1f),
                )
            }

            // Zero line, so a quiet sample still reads as centred audio
            // rather than as an empty box.
            drawLine(
                color = Color.White.copy(alpha = 0.10f),
                start = Offset(0f, mid),
                end = Offset(size.width, mid),
                strokeWidth = 1f,
            )

            if (interactive) {
                drawHandle(startFraction * size.width, size, accent)
                drawHandle(endFraction * size.width, size, accent)
                drawRect(
                    color = accent.copy(alpha = 0.06f),
                    topLeft = Offset(startFraction * size.width, 0f),
                    size = Size((endFraction - startFraction) * size.width, size.height),
                )
            }

            playheadFraction?.let { position ->
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(position * size.width, 0f),
                    end = Offset(position * size.width, size.height),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
    x: Float,
    size: Size,
    accent: Color,
) {
    drawLine(
        color = Color.White.copy(alpha = 0.92f),
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 2.5f,
    )
    // A grip block at the top so the handle is findable by eye and gives the
    // finger something to aim at that is wider than the line itself.
    drawRoundRect(
        color = accent,
        topLeft = Offset(x - 5f, 0f),
        size = Size(10f, 14f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
    )
}

/** A compact, non-interactive waveform for list rows. */
@Composable
fun WaveformThumbnail(
    peaks: FloatArray?,
    modifier: Modifier = Modifier,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val buckets = (peaks?.size ?: 0) / 2
        if (peaks == null || buckets <= 0) {
            drawLine(
                color = color.copy(alpha = 0.25f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.5f,
            )
            return@Canvas
        }
        val mid = size.height / 2f
        val step = size.width / buckets
        for (i in 0 until buckets) {
            val x = i * step
            drawLine(
                color = color.copy(alpha = 0.75f),
                start = Offset(x, mid - peaks[i * 2 + 1] * mid),
                end = Offset(x, mid - peaks[i * 2] * mid),
                strokeWidth = step.coerceAtLeast(1f),
            )
        }
    }
}

/** Live input meter shown while a capture is running. */
@Composable
fun CaptureMeter(
    peak: Float,
    modifier: Modifier = Modifier,
    accent: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
) {
    val clipping = peak > 0.99f
    Canvas(modifier = modifier.fillMaxWidth().height(6.dp)) {
        drawRect(color = accent.copy(alpha = 0.12f))
        drawRect(
            color = if (clipping) Color.Red else accent,
            size = Size(size.width * peak.coerceIn(0f, 1f), size.height),
        )
        if (clipping) {
            drawRect(color = Color.Red, style = Stroke(width = 2f))
        }
    }
}

private enum class Handle { Start, End }

private const val WAVEFORM_HEIGHT_DP = 128

/** Shortest selection the trim handles allow, as a fraction of the whole. */
private const val MIN_SELECTION = 0.01f
