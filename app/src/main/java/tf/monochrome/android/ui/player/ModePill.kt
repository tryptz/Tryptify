package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
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
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.ui.components.liquidGlass
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

@Composable
internal fun ModeSelector(
    selectedMode: NowPlayingViewMode,
    onModeSelected: (NowPlayingViewMode) -> Unit,
    onDspMixClick: () -> Unit
) {
    Row(
        // 5 pills × weight(1) + 4 × 6dp gap × labelMedium text — tightened
        // from 10dp gap so "DSP Mix" fits on one line on a 360-dp-wide phone.
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Album,
            label = stringResource(R.string.mode_art),
            selected = selectedMode == NowPlayingViewMode.COVER_ART,
            onClick = { onModeSelected(NowPlayingViewMode.COVER_ART) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Equalizer,
            label = stringResource(R.string.mode_visual),
            selected = selectedMode == NowPlayingViewMode.VISUALIZER,
            onClick = { onModeSelected(NowPlayingViewMode.VISUALIZER) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
            label = stringResource(R.string.mode_lyrics),
            selected = selectedMode == NowPlayingViewMode.LYRICS,
            onClick = { onModeSelected(NowPlayingViewMode.LYRICS) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = stringResource(R.string.queue),
            selected = selectedMode == NowPlayingViewMode.QUEUE,
            onClick = { onModeSelected(NowPlayingViewMode.QUEUE) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Tune,
            label = stringResource(R.string.mode_dsp_mix),
            selected = false,
            onClick = onDspMixClick
        )
    }
}

@Composable
internal fun ModePill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "modePillScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .liquidGlass(
                shape = RoundedCornerShape(20.dp),
                tintAlpha = if (selected) 0.22f else 0.06f,
                borderAlpha = if (selected) 0.18f else 0.0f
            )
            // Horizontal padding reduced from 8 → 4 dp so a 5-pill row on a
            // 360-dp-wide phone has enough internal width for the widest
            // label ("DSP Mix") to render on one line.
            .padding(vertical = 12.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
