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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
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
    val glass = LocalPlayerGlass.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(painterResource(R.drawable.ic_glass_skip_previous), "Previous", accent, onPrevious)
        TransportIcon(painterResource(R.drawable.ic_glass_replay_10), "Rewind 10 seconds", accent, onRewind10)

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.92f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "playScale",
        )
        // Play/pause is a SOLID round glass disc with the play/pause symbol
        // punched out (hollow), so the backdrop shows through the glyph and the
        // shader bevels both the disc rim and the cut-out edges. A soft drop
        // shadow (tunable "shadow depth") lifts it off the surface for 3D depth.
        Box(
            modifier = Modifier
                .size(PlayerDesignTokens.PlayButtonSize)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(
                    elevation = (glass.shadowDepth * 22f).dp,
                    shape = CircleShape,
                    clip = false,
                )
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
                    .playerGlass(tint = accent)
                    // Own offscreen layer so the punch-out (BlendMode.Clear) is
                    // contained here and can't clear the player behind it — needed
                    // when the glass effect is off or below API 33.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                drawGlassPlayPauseDisc(isPlaying = isPlaying, fill = accent)
            }
        }

        TransportIcon(painterResource(R.drawable.ic_glass_forward_10), "Forward 10 seconds", accent, onForward10)
        TransportIcon(painterResource(R.drawable.ic_glass_skip_next), "Next", accent, onNext)
    }
}

/**
 * Draws the play/pause round button: a solid [fill] disc with the play triangle
 * or pause bars *punched out* (cleared to transparent) so the glyph reads as a
 * hollow cut-out of the glass. Meant to be drawn inside a layer carrying the
 * [playerGlass] render effect, which bevels the disc edge and the cut-out edges
 * into refractive 3D glass.
 */
internal fun DrawScope.drawGlassPlayPauseDisc(isPlaying: Boolean, fill: Color) {
    val d = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawCircle(color = fill, radius = d / 2f)
    if (isPlaying) {
        val barW = d * 0.11f
        val barH = d * 0.34f
        val gap = d * 0.10f
        val corner = CornerRadius(barW * 0.42f, barW * 0.42f)
        drawRoundRect(
            color = fill,
            topLeft = Offset(cx - gap / 2f - barW, cy - barH / 2f),
            size = Size(barW, barH),
            cornerRadius = corner,
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = fill,
            topLeft = Offset(cx + gap / 2f, cy - barH / 2f),
            size = Size(barW, barH),
            cornerRadius = corner,
            blendMode = BlendMode.Clear,
        )
    } else {
        val w = d * 0.32f
        val h = d * 0.34f
        val tri = Path().apply {
            moveTo(cx - w * 0.40f, cy - h / 2f)
            lineTo(cx - w * 0.40f, cy + h / 2f)
            lineTo(cx + w * 0.60f, cy)
            close()
        }
        drawPath(tri, color = fill, blendMode = BlendMode.Clear)
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
