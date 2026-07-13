package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
    // Press-bulge: one shared interaction source per slot so the parent knows
    // which button is held and can swell the glass under it. The bulge grows on
    // press (spring) and recedes on release (tween); its centre follows the last
    // pressed slot.
    val sources = remember { List(icons.size) { MutableInteractionSource() } }
    val pressed = sources.map { it.collectIsPressedAsState() }
    val pressedIndex = pressed.indexOfFirst { it.value }
    val bulgeSlot = remember { mutableIntStateOf(0) }
    LaunchedEffect(pressedIndex) { if (pressedIndex >= 0) bulgeSlot.intValue = pressedIndex }
    val bulgeAmt by animateFloatAsState(
        targetValue = if (pressedIndex >= 0) 1f else 0f,
        animationSpec = if (pressedIndex >= 0) {
            spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(durationMillis = 260)
        },
        label = "dockBulge",
    )
    val bulgeCenter = Offset((bulgeSlot.intValue + 0.5f) / icons.size, 0.42f)
    Box(modifier = modifier.fillMaxWidth()) {
        // Button glass tint: a custom colour chosen in the Studio, or the album
        // accent when none is set (tintColor == 0).
        val g = LocalPlayerGlass.current
        val glassTint = if (g.tintColor != 0) Color(g.tintColor) else accent
        // Soft rounded-rect drop shadow lifting the whole glass slab off the
        // player, matching the play button + transport icons (Studio-tunable).
        if (g.enabled) {
            GlassDropShadow(
                color = androidx.compose.ui.graphics.lerp(Color.Black, glassTint, g.shadowTint)
                    .copy(alpha = 0.26f + 0.5f * g.shadowDepth),
                softness = g.shadowSoftness,
                depth = g.shadowDepth,
                shape = RoundedCornerShape(PlayerDesignTokens.GlassCornerLarge),
            )
        }
        // The glass slab with the four icons carved out of it. Drawn in one
        // offscreen layer so the DstOut punch clears only the glyph shapes (not
        // the whole rectangle) and can't clear the player behind the dock.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .playerGlass(tint = glassTint, bulgeCenter = bulgeCenter, bulgeAmount = { bulgeAmt })
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val cornerPx = PlayerDesignTokens.GlassCornerLarge.toPx()
            // Same solid glass-tint fill as the play-button disc, so the slab reads
            // as the same coloured glass — the shader's body opacity makes it
            // see-through, matching the transport buttons exactly.
            drawRoundRect(
                color = glassTint,
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
            DockLabel(Modifier.weight(1f), "Lyrics", accent, lyricsActive, sources[0], onLyrics)
            DockLabel(Modifier.weight(1f), "Timer", accent, false, sources[1], onTimer)
            DockLabel(Modifier.weight(1f), "Mixer/FX", accent, false, sources[2], onMixer)
            DockLabel(Modifier.weight(1f), "Playlist", accent, false, sources[3], onPlaylist)
        }
    }
}

@Composable
private fun DockLabel(
    modifier: Modifier,
    label: String,
    accent: Color,
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
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
