package tf.monochrome.android.ui.carmode

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.model.EqBand
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun GraphicEqComponent(
    bands: List<EqBand>,
    onBandChange: (index: Int, value: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Frequency response graph
        FrequencyResponseGraph(
            bands = bands,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Sliders in a scrollable row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            for ((index, band) in bands.withIndex()) {
                EqSlider(
                    band = band,
                    onValueChange = { onBandChange(index, it) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
fun EqSlider(
    band: EqBand,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val minValue = -12f
    val maxValue = 12f

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        // Frequency label
        Text(
            text = formatFrequency(band.freq),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Gain label
        Text(
            text = "${band.gain.toInt()} dB",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Slider track
        Box(
            modifier = Modifier
                .width(32.dp)
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        val newGain = (band.gain - dragAmount / 2).coerceIn(minValue, maxValue)
                        onValueChange(newGain)
                        change.consume()
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // Filled portion
            val fillRatio = (band.gain - minValue) / (maxValue - minValue)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fillRatio)
                    .background(
                        if (band.gain > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
            )

            // Knob at the filled portion
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
    }
}

@Composable
fun FrequencyResponseGraph(
    bands: List<EqBand>,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val curveColor = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.drawBehind {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Draw 0dB line
        drawLine(
            color = gridColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        // Draw frequency grid
        val frequencies = listOf(20, 100, 1000, 10000, 20000)
        for (freq in frequencies) {
            val xPos = (logFrequency(freq.toFloat()) / logFrequency(20000f)) * width
            drawLine(
                color = gridColor.copy(alpha = 0.3f),
                start = Offset(xPos, 0f),
                end = Offset(xPos, height),
                strokeWidth = 0.5f
            )
        }

        // Draw curve
        if (bands.isNotEmpty()) {
            var lastX = 0f
            var lastY = centerY

            for (i in 0..100) {
                val ratio = i / 100f
                val freq = 20f * (20000f / 20f).pow(ratio)
                val gain = interpolateGain(freq, bands)

                val xPos = (logFrequency(freq) / logFrequency(20000f)) * width
                val yPos = centerY - (gain / 12f) * (height / 2)

                if (i > 0) {
                    drawLine(
                        color = curveColor,
                        start = Offset(lastX, lastY),
                        end = Offset(xPos, yPos),
                        strokeWidth = 2f
                    )
                }
                lastX = xPos
                lastY = yPos
            }
        }
    })
}

private fun formatFrequency(freq: Float): String {
    return when {
        freq >= 1000 -> "${(freq / 1000).toInt()}k"
        else -> "${freq.toInt()}"
    }
}

private fun logFrequency(freq: Float): Float {
    return log10(freq)
}

private fun interpolateGain(freq: Float, bands: List<EqBand>): Float {
    if (bands.isEmpty()) return 0f

    // Find the two closest bands
    var left: EqBand? = null
    var right: EqBand? = null

    for (band in bands) {
        when {
            band.freq <= freq -> left = band
            band.freq > freq && right == null -> {
                right = band
                break
            }
        }
    }

    return when {
        left == null && right != null -> right.gain
        left != null && right == null -> left.gain
        left != null && right != null -> {
            val ratio = (logFrequency(freq) - logFrequency(left.freq)) /
                    (logFrequency(right.freq) - logFrequency(left.freq))
            left.gain + (right.gain - left.gain) * ratio
        }
        else -> 0f
    }
}
