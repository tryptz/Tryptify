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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.components.liquidGlass

/**
 * Compact tool row beneath the transport controls: Lyrics · Timer · Mixer/FX · Playlist.
 * (Monitoring + effect controls live in the pull-up "Audio tools" panel.)
 */
@Composable
fun PlayerActionDock(
    accent: Color,
    lyricsActive: Boolean,
    onLyrics: () -> Unit,
    onTimer: () -> Unit,
    onMixer: () -> Unit,
    onPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(PlayerDesignTokens.GlassCornerLarge),
                tintAlpha = PlayerDesignTokens.GlassTintMedium,
                borderAlpha = PlayerDesignTokens.GlassTintSoft,
            )
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        DockAction(Icons.Default.Lyrics, "Lyrics", accent, lyricsActive, onLyrics)
        DockAction(Icons.Default.Timer, "Timer", accent, false, onTimer)
        DockAction(Icons.Default.Tune, "Mixer/FX", accent, false, onMixer)
        DockAction(Icons.AutoMirrored.Filled.QueueMusic, "Playlist", accent, false, onPlaylist)
    }
}

@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dockActionScale",
    )
    val iconTint = accent
    val labelTint = if (selected) accent else Color.White.copy(alpha = 0.7f)

    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(PlayerDesignTokens.ActionIconSize),
            tint = iconTint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
