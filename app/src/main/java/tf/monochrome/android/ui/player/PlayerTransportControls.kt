package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R

/**
 * Primary transport row: previous · play/pause · next. The icons are solid glyph
 * shapes and carry the refractive [playerGlass] treatment (tunable in the
 * Studio's Player Glass tab), so the buttons read as 3D liquid glass like the
 * lyrics.
 */
@Composable
fun PlayerTransportControls(
    isPlaying: Boolean,
    accent: Color,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalPlayerGlass.current
    // Button glass tint: a custom colour chosen in the Studio, or the album
    // accent when none is set (tintColor == 0).
    val tint = if (glass.tintColor != 0) Color(glass.tintColor) else accent
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(
            painterResource(R.drawable.ic_glass_skip_previous), "Previous", tint, onPrevious,
            size = PlayerDesignTokens.SkipIconSize,
        )

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.92f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "playScale",
        )
        // Play/pause is a SOLID round glass disc with the play/pause symbol
        // punched out (hollow), so the backdrop shows through the glyph and the
        // shader bevels both the disc rim and the cut-out edges.
        Box(
            modifier = Modifier
                .size(PlayerDesignTokens.PlayButtonSize)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center,
        ) {
            // Drop shadow drawn by us as a blurred, tinted circle — a TRUE round
            // shadow. The platform elevation shadow facets CircleShape into an
            // octagon on some GPUs; a blurred circle stays perfectly round.
            // Depth = darkness, softness = blur radius + offset, tint = black ->
            // accent glow (all tunable in the Studio).
            GlassDropShadow(
                color = androidx.compose.ui.graphics.lerp(Color.Black, tint, glass.shadowTint)
                    .copy(alpha = 0.28f + 0.55f * glass.shadowDepth),
                softness = glass.shadowSoftness,
                depth = glass.shadowDepth,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                        .playerGlass(tint = tint)
                        // Own offscreen layer so the punch-out (BlendMode.Clear) is
                        // contained here and can't clear the player behind it — needed
                        // when the glass effect is off or below API 33.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    drawGlassPlayPauseDisc(isPlaying = isPlaying, fill = tint)
                }
            }
        }

        TransportIcon(
            painterResource(R.drawable.ic_glass_skip_next), "Next", tint, onNext,
            size = PlayerDesignTokens.SkipIconSize,
        )
    }
}

/**
 * Draws the play/pause round button: a solid [fill] disc with the play triangle
 * or pause bars *punched out* (cleared to transparent) so the glyph reads as a
 * clean hollow cut-out of the glass — a single beveled glass edge, no double
 * outline. Meant to be drawn inside a layer carrying the [playerGlass] render
 * effect, which bevels the disc edge and the cut-out edges into refractive 3D
 * glass.
 */
internal fun DrawScope.drawGlassPlayPauseDisc(isPlaying: Boolean, fill: Color) {
    val d = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawCircle(color = fill, radius = d / 2f)
    // One clean punch → the shader bevels a single glass edge around the hollow.
    drawPlayPauseSymbol(isPlaying, cx, cy, d, scale = 1f, color = fill, blend = BlendMode.Clear)
}

/** One play/pause glyph, scaled about the button centre, for the layered cut. */
private fun DrawScope.drawPlayPauseSymbol(
    isPlaying: Boolean,
    cx: Float,
    cy: Float,
    d: Float,
    scale: Float,
    color: Color,
    blend: BlendMode,
) {
    if (isPlaying) {
        val barW = d * 0.11f * scale
        val barH = d * 0.34f * scale
        val gap = d * 0.10f * scale
        val corner = CornerRadius(d * 0.046f * scale, d * 0.046f * scale)
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - gap / 2f - barW, cy - barH / 2f),
            size = Size(barW, barH),
            cornerRadius = corner,
            blendMode = blend,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(cx + gap / 2f, cy - barH / 2f),
            size = Size(barW, barH),
            cornerRadius = corner,
            blendMode = blend,
        )
    } else {
        val w = d * 0.32f * scale
        val h = d * 0.34f * scale
        val tcx = cx - d * 0.32f * 0.06f // optical-centre (scale-independent so passes stay concentric)
        val tri = Path().apply {
            moveTo(tcx - w * 0.40f, cy - h / 2f)
            lineTo(tcx - w * 0.40f, cy + h / 2f)
            lineTo(tcx + w * 0.60f, cy)
            close()
        }
        drawPath(tri, color = color, blendMode = blend)
    }
}

/**
 * A soft drop shadow for a [shape] surface (the play disc, the dock slab) — a
 * blurred, tinted shape we draw ourselves instead of the platform elevation
 * shadow (which facets a CircleShape into a visible octagon on some GPUs).
 * [depth] offsets + darkens it, [softness] sets the blur radius. Below API 31
 * blur is a no-op, so it degrades to a hard (still true-shaped) fill.
 */
@Composable
internal fun BoxScope.GlassDropShadow(
    color: Color,
    softness: Float,
    depth: Float,
    shape: Shape = CircleShape,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { translationY = (2f + depth * 6f).dp.toPx() }
            .blur(
                radius = (5f + softness * 22f).dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded,
            )
            .background(color, shape),
    )
}

@Composable
internal fun TransportIcon(
    painter: Painter,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    size: Dp = PlayerDesignTokens.TransportIconSize,
) {
    val glass = LocalPlayerGlass.current
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            // Shape-accurate drop shadow: a blurred, tinted copy of the SAME glyph
            // behind the glass icon, so the shadow traces the icon's real outline
            // (a circle shadow can't fit a triangle/arrow). Tracks the Studio's
            // shadow depth / softness / tint, matching the play button.
            if (glass.enabled) {
                val shadowColor = androidx.compose.ui.graphics.lerp(Color.Black, tint, glass.shadowTint)
                    .copy(alpha = 0.30f + 0.5f * glass.shadowDepth)
                Icon(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier
                        .requiredSize(size)
                        .graphicsLayer { translationY = (1.5f + glass.shadowDepth * 4f).dp.toPx() }
                        .blur(
                            radius = (2f + glass.shadowSoftness * 12f).dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        ),
                    tint = shadowColor,
                )
            }
            Icon(
                painter = painter,
                contentDescription = description,
                modifier = Modifier
                    .requiredSize(size)
                    .playerGlass(tint = tint),
                tint = tint,
            )
        }
    }
}
