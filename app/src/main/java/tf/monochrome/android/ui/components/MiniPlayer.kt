package tf.monochrome.android.ui.components

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.MANUAL_MORPH_MS
import tf.monochrome.android.ui.player.MorphingCoverArt
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.theme.glassTint
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.abs
import tf.monochrome.android.ui.player.playerFrostTint

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
    hazeState: HazeState? = null,
    // The bar's tint already crosses over at the blend length (it reads the
    // album palette); the cover has to move with it or the two disagree for
    // the length of every transition. See MorphingCoverArt.
    blendMillis: Int = MANUAL_MORPH_MS,
    userTrackChanges: Int = 0,
) {
    if (track == null) return

    // The tunable player glass (AGSL) only exists on API 33+ and when the user
    // hasn't turned button glass off. Below that, keep the old haze bar + Material
    // icons — a punched slab with no shader would be an opaque block.
    val glass = LocalPlayerGlass.current
    val useGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && glass.enabled

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
        // ── Legacy fallback (API < 33 or glass off): haze glass + Material icons ──
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
            MiniPlayerContent(track, progressProvider, blendMillis, userTrackChanges) {
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

    // ── Glass path (API 33+): one tunable player-glass slab with the play/skip
    // icons punched out as see-through holes, and a smooth press-bulge under the
    // pressed control — the same shader treatment as the player action dock. ──
    val tint = glassTint(glass.tintColor)
    val playPainter = painterResource(if (isPlaying) R.drawable.ic_glass_pause else R.drawable.ic_glass_play)
    val skipPainter = painterResource(R.drawable.ic_glass_skip_next)

    // Press-bulge, mirroring PlayerActionDock: swell the glass under whichever
    // control is held (spring in, tween out); the bulge centre follows it.
    val playSource = remember { MutableInteractionSource() }
    val skipSource = remember { MutableInteractionSource() }
    val playPressed by playSource.collectIsPressedAsState()
    val skipPressed by skipSource.collectIsPressedAsState()
    val anyPressed = playPressed || skipPressed
    var lastControl by remember { mutableIntStateOf(1) }   // 0 = play, 1 = skip
    LaunchedEffect(playPressed) { if (playPressed) lastControl = 0 }
    LaunchedEffect(skipPressed) { if (skipPressed) lastControl = 1 }
    val bulgeAmt by animateFloatAsState(
        targetValue = if (anyPressed) 1f else 0f,
        animationSpec = if (anyPressed) {
            spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(durationMillis = 260)
        },
        label = "miniBulge",
    )

    // The bar itself is a button too — tapping anywhere but the two controls
    // opens the player — and it was the one press on this surface that did
    // nothing. It shares the slab with the controls, so it shares their dome:
    // one bulge uniform, whichever of the three was pressed last.
    val barPress = rememberGlassPress()

    val density = LocalDensity.current
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val controlCenter = remember(barSize, lastControl) {
        if (barSize.width == 0 || barSize.height == 0) {
            Offset(0.85f, 0.5f)
        } else with(density) {
            val cell = MiniControlCell.toPx()
            val pad = MonoDimens.spacingMd.toPx()
            val cyPx = MiniProgressHeight.toPx() + MonoDimens.spacingSm.toPx() + cell / 2f
            val cx = barSize.width - pad - (if (lastControl == 1) 0.5f else 1.5f) * cell
            Offset((cx / barSize.width).coerceIn(0f, 1f), (cyPx / barSize.height).coerceIn(0f, 1f))
        }
    }

    // One dome for the whole slab. A control press keeps its tight, cell-sized
    // swell centred on the icon; a press anywhere else on the bar raises a
    // broader one under the finger. Whichever is live wins — they cannot both
    // be, since the controls sit above the bar's own tap target.
    val bulgeCenter = if (anyPressed) controlCenter else barPress.center
    val bulge = if (anyPressed) bulgeAmt else barPress.amount
    val bulgeSpread = if (anyPressed) 0f else GlassPressDefaults.BULGE

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { barSize = it }
            .clip(RoundedCornerShape(MiniCorner))
            .glassSqueeze(press = barPress, onClick = onClick)
            .then(swipeGestures)
    ) {
        // Frosted backdrop UNDER the glass slab. The slab body can be nearly
        // transparent (bodyOpacity goes down to 0.2), and without this layer
        // whatever scrolls behind the bar — list rows, titles — reads through
        // sharply and fights the mini player's own text. Haze gaussian-blurs
        // the backdrop first; the AGSL glass then relights on top, and the
        // punched play/skip holes reveal the frosted backdrop instead of raw
        // rows. Same LOW-tier gate as the legacy path: budget SoCs skip it.
        //
        // Style note: backgroundColor and at least one tint must be REAL
        // colours — a Transparent background with an empty tint list resolves
        // to a no-op and the effect silently draws nothing. The neutral
        // black/white tint (picked by theme luminance) lightens the frost on
        // light themes and deepens it on dark ones. Haze's default noise
        // (0.15) is disabled: over a dark backdrop it reads as visible grain
        // rather than frost.
        val profile = LocalPerformanceProfile.current
        if (hazeState != null && profile.allowHazeBlur && glass.hazeBlurDp > 0f) {
            val frostBg = MaterialTheme.colorScheme.background
            val isDark = frostBg.luminance() <= 0.5f
            val frostTint = playerFrostTint(glass, isDark)
            Box(
                Modifier
                    .matchParentSize()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = frostBg,
                            blurRadius = glass.hazeBlurDp.dp,
                            tints = listOf(HazeTint(frostTint)),
                            noiseFactor = 0f,
                        ),
                    )
            )
        }

        // The glass slab with the two controls carved out of it. One offscreen
        // layer so the DstOut punch clears only the glyph shapes (revealing the
        // app behind the bar), not the whole rectangle.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .playerGlass(
                    tint = tint,
                    bulgeCenter = bulgeCenter,
                    bulgeAmount = { bulge },
                    bulgeRadiusFraction = bulgeSpread,
                )
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

            // AA punch + whole-pixel glyph placement — same hygiene as the
            // action dock: default Paint() punches with hard stair-stepped
            // edges, and fractional offsets resample the small glyphs soft.
            val punch = Paint().apply {
                blendMode = BlendMode.DstOut
                isAntiAlias = true
            }
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), punch)
            listOf(playCx to playPainter, skipCx to skipPainter).forEach { (cx, painter) ->
                translate(
                    kotlin.math.round(cx - iconPx / 2f),
                    kotlin.math.round(cy - iconPx / 2f),
                ) {
                    with(painter) { draw(Size(iconPx, iconPx)) }
                }
            }
            canvas.restore()
        }

        // Transparent content overlay: progress, cover, text, and the two tap
        // targets sitting exactly over the punched holes (same trailing cells).
        // No ripple indication — the glass press-bulge is the feedback.
        MiniPlayerContent(track, progressProvider, blendMillis, userTrackChanges) {
            Box(
                modifier = Modifier
                    .size(MiniControlCell)
                    .clickable(
                        interactionSource = playSource,
                        indication = null,
                        onClickLabel = if (isPlaying) "Pause" else "Play",
                        onClick = onPlayPauseClick,
                    )
            )
            Box(
                modifier = Modifier
                    .size(MiniControlCell)
                    .clickable(
                        interactionSource = skipSource,
                        indication = null,
                        onClickLabel = "Skip next",
                        onClick = onSkipNextClick,
                    )
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
    progressProvider: () -> Float,
    blendMillis: Int,
    userTrackChanges: Int,
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
            MorphingCoverArt(
                trackKey = track.id,
                coverUrl = track.coverUrl,
                contentDescription = track.title,
                blendMillis = blendMillis,
                userTrackChanges = userTrackChanges,
                modifier = Modifier.size(MonoDimens.coverMini),
                shape = RoundedCornerShape(MonoDimens.radiusSm),
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
