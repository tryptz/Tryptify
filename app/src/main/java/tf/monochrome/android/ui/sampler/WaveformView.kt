// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The two read-only waveform surfaces.
 *
 * The editable one is [WaveEditor], which owns zoom, selection and the
 * playhead. These two exist because neither of their jobs needs any of that:
 * a list row wants a thumbnail, and a running capture wants a meter.
 *
 * Both draw from pre-computed min/max peak pairs rather than from samples.
 * A three-second capture is 288,000 floats and a row is sixty pixels wide;
 * walking the buffer per frame would be the most expensive thing on the
 * screen for a result identical to walking it once. Peaks rather than
 * averages, too — an averaged waveform of a drum hit is a flat smear, and the
 * transient is the one thing anyone is looking for.
 */

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
            // A flat line rather than an empty box: a sample whose peak cache
            // is missing should still look like a row with audio in it.
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

/** Live level meter shown while a capture is running. */
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
        // Clipping gets its own outline as well as the colour change. On a
        // six-dp bar at the top of a dark screen, colour alone is easy to miss
        // — and a clipped capture is worth re-taking.
        if (clipping) {
            drawRect(color = Color.Red, style = Stroke(width = 2f))
        }
    }
}
