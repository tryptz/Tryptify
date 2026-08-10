package tf.monochrome.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The resting primary action on Home and at the top of Discover: seed a radio
 * station and let it run.
 *
 * Its glass is hand-rolled rather than the shared `Modifier.liquidGlass` — a
 * shadow, a body gradient, a specular sweep and a gradient rim, tuned to the
 * accent — so it needs its own flat branch when glass is off. Both branches
 * keep the same shape, padding and three states, so the button doesn't move
 * when the setting changes; only its material does.
 */
@Composable
fun PlayRadioButton(
    isActive: Boolean,
    isGenerating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Inset from the screen edge on Home; zero inside the Discover hero card,
    // which is already padded.
    horizontalPadding: Dp = 16.dp,
) {
    val accent = if (isActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    // Specular highlight is white glass on dark themes; a touch of black keeps the
    // "wet glass" read on light ones without washing the accent out.
    val specular = if (isDark) Color.White else Color.White.copy(alpha = 0.6f)
    val glass = LocalPerformanceProfile.current.allowHazeBlur

    val surface = if (glass) {
        Modifier
            // Under shadow: a soft, accent-tinted drop shadow so the pill floats
            // above the list. clip=false lets the shadow spill past the shape.
            .shadow(
                elevation = 12.dp,
                shape = MonoDimens.shapePill,
                clip = false,
                ambientColor = accent,
                spotColor = accent,
            )
            .clip(MonoDimens.shapePill)
            // Liquid glass: translucent accent fill with a top-lit vertical sheen
            // (brighter at the top edge, deeper toward the bottom) — the base
            // "body" of the glass.
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.94f),
                        accent.copy(alpha = 0.70f),
                    )
                )
            )
            // Specular sweep: a bright highlight across the top third that fades
            // out, giving the pill its glossy, refractive wet-glass surface.
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        specular.copy(alpha = 0.30f),
                        Color.Transparent,
                    )
                )
            )
            // Specular rim: a luminous edge, brightest along the top, that reads
            // as light catching the glass border.
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        specular.copy(alpha = 0.55f),
                        specular.copy(alpha = 0.08f),
                    )
                ),
                shape = MonoDimens.shapePill,
            )
    } else {
        // Flat: one opaque accent fill, no shadow, no gradients, no rim.
        Modifier
            .clip(MonoDimens.shapePill)
            .background(accent)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .then(surface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val contentColor =
            if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onPrimary
        if (isGenerating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Icon(
                if (isActive) Icons.Default.GraphicEq else Icons.Default.Podcasts,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = when {
                isGenerating -> "Finding similar tracks…"
                isActive -> "Radio on — tap to stop"
                else -> "Play Radio"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
