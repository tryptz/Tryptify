package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R

/**
 * Primary transport row: previous · rewind 10s · play/pause · forward 10s ·
 * next. The icons are solid glyph shapes and carry the refractive [playerGlass]
 * treatment (tunable in the Studio's Player Glass tab), so the buttons read as
 * 3D liquid glass like the lyrics.
 */
@Composable
fun PlayerTransportControls(
    isPlaying: Boolean,
    accent: Color,
    onPrevious: () -> Unit,
    onRewind10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(painterResource(R.drawable.ic_glass_skip_previous), "Previous", accent, onPrevious)
        TransportIcon(rememberVectorPainter(Icons.Default.Replay10), "Rewind 10 seconds", accent, onRewind10)

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.92f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "playScale",
        )
        // Play/pause is a HOLLOW glass ring: the circular band carries the glass
        // treatment and the interior is empty (the reverse of a solid disc), with
        // the play/pause glyph floating glass in the centre.
        Box(
            modifier = Modifier
                .size(PlayerDesignTokens.PlayButtonSize)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGlass(tint = accent),
            ) {
                val stroke = size.minDimension * 0.12f
                drawCircle(
                    color = accent,
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
            }
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_glass_pause else R.drawable.ic_glass_play
                ),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier
                    .size(30.dp)
                    .playerGlass(tint = accent),
                tint = accent,
            )
        }

        TransportIcon(rememberVectorPainter(Icons.Default.Forward10), "Forward 10 seconds", accent, onForward10)
        TransportIcon(painterResource(R.drawable.ic_glass_skip_next), "Next", accent, onNext)
    }
}

@Composable
private fun TransportIcon(painter: Painter, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painter,
            contentDescription = description,
            modifier = Modifier
                .size(PlayerDesignTokens.TransportIconSize)
                .playerGlass(tint = tint),
            tint = tint,
        )
    }
}
