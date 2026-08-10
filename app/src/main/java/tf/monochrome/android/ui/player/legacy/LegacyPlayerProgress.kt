package tf.monochrome.android.ui.player.legacy

import tf.monochrome.android.ui.player.PlayerDesignTokens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Scrubber plus elapsed / center-label / total time. The center label carries
 * the quality or queue-position context from the generated design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyPlayerProgress(
    fraction: Float,
    elapsedLabel: String,
    totalLabel: String,
    centerLabel: String,
    accent: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    // Hold the live drag value: onValueChangeFinished(fraction) commits the
    // composition-captured (stale) fraction, so a tap-to-seek snaps back.
    // Material3 runs onValueChange and onValueChangeFinished inside the same
    // pointer dispatch, with no recomposition between them, so the closure
    // still holds the pre-tap value. The current player carries the same fix.
    var latestSeek by remember { mutableFloatStateOf(fraction) }
    val thumbSize by animateDpAsState(
        targetValue = if (dragged) 18.dp else PlayerDesignTokens.ProgressThumbSize,
        label = "progressThumb",
    )
    val colors = SliderDefaults.colors(
        thumbColor = accent,
        activeTrackColor = accent,
        inactiveTrackColor = Color.White.copy(alpha = 0.20f),
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { latestSeek = it; onSeek(it) },
            onValueChangeFinished = { onSeekFinished(latestSeek) },
            modifier = Modifier.fillMaxWidth(),
            interactionSource = interaction,
            colors = colors,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = colors,
                    thumbSize = DpSize(thumbSize, thumbSize),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(PlayerDesignTokens.ProgressHeight),
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = elapsedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            if (centerLabel.isNotBlank()) {
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
