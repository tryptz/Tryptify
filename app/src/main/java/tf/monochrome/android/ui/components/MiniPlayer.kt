package tf.monochrome.android.ui.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.player.liquidGlassPanel
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.abs

// Geometry shared between the punched holes and the tap-target overlay so they
// stay aligned across DPI. The two controls are the rightmost fixed-size cells
// of the content row; the slab punches its holes at the matching centres.
private val MiniCorner = 16.dp
private val MiniControlCell = 48.dp
private val MiniGlassIcon = 26.dp
private val MiniProgressHeight = 2.dp

@Composable
fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    progressProvider: () -> Float,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    if (track == null) return

    // The panel glass (AGSL) only exists on API 33+. Below that, keep the old
    // haze bar + Material icons — a punched slab would be an opaque block there.
    val useGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val swipeGestures = Modifier.pointerInput(Unit) {
        var totalHorizontalDrag = 0f
        var totalVerticalDrag = 0f
        detectDragGestures(
            onDragStart = {
                totalHorizontalDrag = 0f
                totalVerticalDrag = 0f
            },
            onDragEnd = {
                if (abs(totalVerticalDrag) > abs(totalHorizontalDrag) && totalVerticalDrag < -50f) {
                    // Swipe Up
                    onClick()
                } else if (abs(totalVerticalDrag) > abs(totalHorizontalDrag) && totalVerticalDrag > 50f) {
                    // Swipe Down (Collapse logic, if any, could go here)
                } else if (totalHorizontalDrag > 50f) {
                    onSkipPreviousClick()
                } else if (totalHorizontalDrag < -50f) {
                    onSkipNextClick()
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                totalHorizontalDrag += dragAmount.x
                totalVerticalDrag += dragAmount.y
            }
        )
    }

    if (!useGlass) {
        // ── Legacy fallback (API < 33): haze glass + Material icon buttons ──
        Box(
            modifier = modifier
                .fillMaxWidth()
                .liquidGlass(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(MiniCorner)
                )
                .clickable(interactionSource = null, indication = null, onClick = onClick)
                .then(swipeGestures)
        ) {
            MiniPlayerContent(track, isPlaying, progressProvider) {
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onSkipNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip next",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        return
    }

    // ── Glass path (API 33+): one panel-glass slab with the play/skip icons
    // punched out as see-through holes, exactly like the player action dock. ──
    val tint = MaterialTheme.colorScheme.primary
    val playPainter = painterResource(if (isPlaying) R.drawable.ic_glass_pause else R.drawable.ic_glass_play)
    val skipPainter = painterResource(R.drawable.ic_glass_skip_next)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiniCorner))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .then(swipeGestures)
    ) {
        // The glass slab with the two controls carved out of it. One offscreen
        // layer so the DstOut punch clears only the glyph shapes (revealing the
        // app behind the bar), not the whole rectangle.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .liquidGlassPanel(tint = tint)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val cornerPx = MiniCorner.toPx()
            drawRoundRect(color = tint, cornerRadius = CornerRadius(cornerPx, cornerPx))

            val cellPx = MiniControlCell.toPx()
            val iconPx = MiniGlassIcon.toPx()
            val padPx = MonoDimens.spacingMd.toPx()
            // Row content centre = progress line + row top padding + half the
            // (48dp) control cell — the tallest child, so the row's content box.
            val cy = MiniProgressHeight.toPx() + MonoDimens.spacingSm.toPx() + cellPx / 2f
            // Rightmost cell is skip-next, the one before it is play/pause; both
            // inset from the right edge by the row's horizontal padding.
            val skipCx = size.width - padPx - cellPx * 0.5f
            val playCx = size.width - padPx - cellPx * 1.5f

            val punch = Paint().apply { blendMode = BlendMode.DstOut }
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), punch)
            listOf(playCx to playPainter, skipCx to skipPainter).forEach { (cx, painter) ->
                translate(cx - iconPx / 2f, cy - iconPx / 2f) {
                    with(painter) { draw(Size(iconPx, iconPx)) }
                }
            }
            canvas.restore()
        }

        // Transparent content overlay: progress, cover, text, and the two tap
        // targets sitting exactly over the punched holes (same trailing cells).
        MiniPlayerContent(track, isPlaying, progressProvider) {
            Box(
                modifier = Modifier
                    .size(MiniControlCell)
                    .clickable(onClickLabel = if (isPlaying) "Pause" else "Play", onClick = onPlayPauseClick)
            )
            Box(
                modifier = Modifier
                    .size(MiniControlCell)
                    .clickable(onClickLabel = "Skip next", onClick = onSkipNextClick)
            )
        }
    }
}

/**
 * The shared bar content: the thin progress line, cover, and title/artist, with
 * the two trailing control slots supplied by [controls] (glass holes' tap targets
 * on the glass path, Material icon buttons on the legacy path).
 */
@Composable
private fun MiniPlayerContent(
    track: Track,
    isPlaying: Boolean,
    progressProvider: () -> Float,
    controls: @Composable () -> Unit,
) {
    Column {
        LinearProgressIndicator(
            progress = { progressProvider().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(MiniProgressHeight),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline
        )
        Row(
            modifier = Modifier.padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = track.coverUrl,
                contentDescription = track.title,
                size = MonoDimens.coverMini,
                cornerRadius = MonoDimens.radiusSm
            )
            Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            controls()
        }
    }
}
