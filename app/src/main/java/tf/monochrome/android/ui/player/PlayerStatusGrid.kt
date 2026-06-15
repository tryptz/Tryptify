package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.components.liquidGlass

/**
 * Bottom 2×2 grid surfacing the player's premium audio tools: output device,
 * sound/AutoEQ, playback speed and sleep timer.
 */
@Composable
fun PlayerStatusGrid(
    accent: Color,
    outputLabel: String,
    soundLabel: String,
    speedLabel: String,
    sleepLabel: String,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Four related hues derived from the album accent so the cards stay varied
    // but track the current artwork.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Headphones,
                title = "Output",
                value = outputLabel,
                accent = accent,
                onClick = onOutput,
            )
            StatusCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Tune,
                title = "Sound",
                value = soundLabel,
                accent = accent.shiftHue(36f),
                onClick = onSound,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Speed,
                title = "Speed",
                value = speedLabel,
                accent = accent.shiftHue(72f),
                onClick = onSpeed,
            )
            StatusCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Bedtime,
                title = "Sleep timer",
                value = sleepLabel,
                accent = accent.shiftHue(-36f),
                onClick = onSleep,
            )
        }
    }
}

/** Rotate a color's hue by [degrees], preserving saturation/value. */
private fun Color.shiftHue(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "statusCardScale",
    )
    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .liquidGlass(
                shape = RoundedCornerShape(PlayerDesignTokens.GlassCornerMedium),
                tintAlpha = PlayerDesignTokens.GlassTintStrong,
                borderAlpha = PlayerDesignTokens.GlassTintSoft,
            ),
        shape = RoundedCornerShape(PlayerDesignTokens.GlassCornerMedium),
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
