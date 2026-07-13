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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R

// Vertical paddings shared between the label overlay and the punch geometry, so
// the hollow icons stay centred over their labels regardless of DPI.
private val DockRowVerticalPadding = 6.dp
private val DockItemVerticalPadding = 10.dp

/**
 * Compact tool row beneath the transport controls: Lyrics · Timer · Mixer/FX · Playlist.
 *
 * The whole rectangle is one liquid-glass slab with the four icons *hollowed out*
 * of it — exactly like the play button: a solid translucent slab, the icon shapes
 * punched to transparent (so the backdrop shows through the glyphs), and the
 * player-glass shader bevels the slab rim and every cut-out edge into refractive
 * 3D glass. The labels and tap targets sit as a transparent overlay aligned to
 * the holes. (Monitoring + effect controls live in the pull-up "Audio tools" panel.)
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
    val icons = listOf(
        painterResource(R.drawable.ic_glass_lyrics),
        painterResource(R.drawable.ic_glass_timer),
        painterResource(R.drawable.ic_glass_mixer),
        painterResource(R.drawable.ic_glass_playlist),
    )
    Box(modifier = modifier.fillMaxWidth()) {
        // The glass slab with the four icons carved out of it. Drawn in one
        // offscreen layer so the DstOut punch clears only the glyph shapes (not
        // the whole rectangle) and can't clear the player behind the dock.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .playerGlass(tint = accent)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val cornerPx = PlayerDesignTokens.GlassCornerLarge.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.20f),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
            )
            val iconPx = PlayerDesignTokens.ActionIconSize.toPx()
            val cy = (DockRowVerticalPadding + DockItemVerticalPadding).toPx() + iconPx / 2f
            // DstOut: dest * (1 - src.alpha) → the opaque icon pixels erase the
            // slab, leaving an icon-shaped hole; everything else is untouched.
            val punch = Paint().apply { blendMode = BlendMode.DstOut }
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), punch)
            icons.forEachIndexed { i, painter ->
                val cx = size.width * (i + 0.5f) / icons.size
                translate(cx - iconPx / 2f, cy - iconPx / 2f) {
                    with(painter) { draw(Size(iconPx, iconPx)) }
                }
            }
            canvas.restore()
        }
        // Transparent overlay: labels + tap targets, one weighted slot per hole.
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = DockRowVerticalPadding)) {
            DockLabel(Modifier.weight(1f), "Lyrics", accent, lyricsActive, onLyrics)
            DockLabel(Modifier.weight(1f), "Timer", accent, false, onTimer)
            DockLabel(Modifier.weight(1f), "Mixer/FX", accent, false, onMixer)
            DockLabel(Modifier.weight(1f), "Playlist", accent, false, onPlaylist)
        }
    }
}

@Composable
private fun DockLabel(
    modifier: Modifier,
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
        label = "dockLabelScale",
    )
    val labelTint = if (selected) accent else Color.White.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = DockItemVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Reserves the slot where the glass slab carries the hollow icon above.
        Spacer(Modifier.size(PlayerDesignTokens.ActionIconSize))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
