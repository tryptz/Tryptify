package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.visualizer.ProjectMEngineRepository

/** Visual treatment of the hero artwork area. */
enum class PlayerHeroStyle { Square, CircularProgress, Visualizer }

/**
 * Hero artwork area for the main player. Defaults to a large rounded-square
 * album cover; supports a circular progress-ring variant and a full-bleed
 * projectM visualizer. The visualizer mode keeps the original overlay controls.
 */
@Composable
fun PlayerHero(
    modifier: Modifier = Modifier,
    style: PlayerHeroStyle,
    isFullscreen: Boolean = false,
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    albumColors: AlbumColors,
    visualizerSensitivity: Int,
    visualizerBrightness: Int,
    visualizerEngineStatus: VisualizerEngineStatus,
    visualizerEngineEnabled: Boolean,
    visualizerShowFps: Boolean,
    visualizerRepository: ProjectMEngineRepository,
    visualizerTouchWaveform: Boolean,
    currentVisualizerPreset: VisualizerPreset?,
    visualizerAutoShuffle: Boolean,
    onToggleVisualizerShuffle: (Boolean) -> Unit,
    onNextPreset: () -> Unit,
    onOpenPresetBrowser: () -> Unit,
    isPresetFavorite: Boolean,
    onTogglePresetFavorite: () -> Unit,
    visualizerCompact: Boolean = false,
    onToggleCompact: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    spectrumBins: FloatArray = FloatArray(0),
    spectrumColor: Color = PlayerGlowBlue,
    showSpectrum: Boolean = true,
    onToggleShowSpectrum: () -> Unit = {},
    onEnterVisualizer: () -> Unit = {},
    onExitVisualizer: () -> Unit = {},
) {
    if (style == PlayerHeroStyle.Visualizer) {
        VisualizerHero(
            modifier = modifier,
            isFullscreen = isFullscreen,
            isPlaying = isPlaying,
            track = track,
            visualizerSensitivity = visualizerSensitivity,
            visualizerBrightness = visualizerBrightness,
            visualizerEngineStatus = visualizerEngineStatus,
            visualizerEngineEnabled = visualizerEngineEnabled,
            visualizerShowFps = visualizerShowFps,
            visualizerRepository = visualizerRepository,
            visualizerTouchWaveform = visualizerTouchWaveform,
            currentVisualizerPreset = currentVisualizerPreset,
            visualizerAutoShuffle = visualizerAutoShuffle,
            onToggleVisualizerShuffle = onToggleVisualizerShuffle,
            onNextPreset = onNextPreset,
            onOpenPresetBrowser = onOpenPresetBrowser,
            isPresetFavorite = isPresetFavorite,
            onTogglePresetFavorite = onTogglePresetFavorite,
            visualizerCompact = visualizerCompact,
            onToggleCompact = onToggleCompact,
            onToggleFullscreen = onToggleFullscreen,
            spectrumBins = spectrumBins,
            spectrumColor = spectrumColor,
            showSpectrum = showSpectrum,
            onToggleShowSpectrum = onToggleShowSpectrum,
            onExitVisualizer = onExitVisualizer,
        )
        return
    }

    Crossfade(
        targetState = style,
        animationSpec = tween(durationMillis = 600),
        label = "HeroStyleCrossfade",
        modifier = modifier,
    ) { targetStyle ->
        when (targetStyle) {
            PlayerHeroStyle.CircularProgress -> CircularProgressHero(
                track = track,
                progress = progress,
                accent = albumColors.vibrant,
                onEnterVisualizer = onEnterVisualizer,
            )
            else -> SquareArtHero(
                track = track,
                isPlaying = isPlaying,
                spectrumBins = spectrumBins,
                spectrumColor = spectrumColor,
                showSpectrum = showSpectrum,
                onToggleShowSpectrum = onToggleShowSpectrum,
                onEnterVisualizer = onEnterVisualizer,
            )
        }
    }
}

@Composable
private fun SquareArtHero(
    track: Track?,
    isPlaying: Boolean,
    spectrumBins: FloatArray,
    spectrumColor: Color,
    showSpectrum: Boolean,
    onToggleShowSpectrum: () -> Unit,
    onEnterVisualizer: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PlayerDesignTokens.AlbumArtAspectRatio)
            .shadow(
                elevation = 14.dp,
                shape = RectangleShape,
                spotColor = Color.Black,
                ambientColor = Color.Black,
            ),
        shape = RectangleShape,
        color = Color.Transparent,
    ) {
        HeroCoverArt(
            track = track,
            isPlaying = isPlaying,
            spectrumBins = spectrumBins,
            spectrumColor = spectrumColor,
            showSpectrum = showSpectrum,
            onToggleShowSpectrum = onToggleShowSpectrum,
            quality = track?.audioQuality,
            onEnterVisualizer = onEnterVisualizer,
        )
    }
}

@Composable
private fun CircularProgressHero(
    track: Track?,
    progress: Float,
    accent: Color,
    onEnterVisualizer: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ringStroke = 8.dp
        Box(
            modifier = Modifier
                .fillMaxSize(0.86f)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onEnterVisualizer,
                ),
        ) {
            CoverImage(
                url = track?.coverUrl,
                contentDescription = track?.title ?: "Album Art",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = ringStroke.toPx()
            val inset = stroke / 2f
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun VisualizerHero(
    modifier: Modifier,
    isFullscreen: Boolean,
    isPlaying: Boolean,
    track: Track?,
    visualizerSensitivity: Int,
    visualizerBrightness: Int,
    visualizerEngineStatus: VisualizerEngineStatus,
    visualizerEngineEnabled: Boolean,
    visualizerShowFps: Boolean,
    visualizerRepository: ProjectMEngineRepository,
    visualizerTouchWaveform: Boolean,
    currentVisualizerPreset: VisualizerPreset?,
    visualizerAutoShuffle: Boolean,
    onToggleVisualizerShuffle: (Boolean) -> Unit,
    onNextPreset: () -> Unit,
    onOpenPresetBrowser: () -> Unit,
    isPresetFavorite: Boolean,
    onTogglePresetFavorite: () -> Unit,
    visualizerCompact: Boolean,
    onToggleCompact: () -> Unit,
    onToggleFullscreen: () -> Unit,
    spectrumBins: FloatArray,
    spectrumColor: Color,
    showSpectrum: Boolean,
    onToggleShowSpectrum: () -> Unit,
    onExitVisualizer: () -> Unit,
) {
    var showOverlay by remember { mutableStateOf(true) }
    LaunchedEffect(showOverlay) {
        if (showOverlay) {
            delay(2000)
            showOverlay = false
        }
    }
    // In fullscreen the system bars are hidden and the exit button lives in
    // the auto-hiding overlay — without this, Back pops the whole player and
    // (fullscreen being a persisted pref) reopening lands right back here.
    BackHandler(enabled = isFullscreen) { onToggleFullscreen() }
    Surface(
        // Outside fullscreen the visualizer is locked to the album-art aspect
        // ratio so its dimensions match the cover artwork exactly.
        modifier = if (isFullscreen) modifier else modifier.aspectRatio(PlayerDesignTokens.AlbumArtAspectRatio),
        // Square corners (matching the album-art hero) in both states.
        shape = RectangleShape,
        color = Color.Black,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { showOverlay = true },
                )
        ) {
            if (visualizerCompact) {
                Box(modifier = Modifier.fillMaxSize()) {
                    HeroCoverArt(
                        track = track,
                        isPlaying = isPlaying,
                        spectrumBins = spectrumBins,
                        spectrumColor = spectrumColor,
                        showSpectrum = showSpectrum,
                        onToggleShowSpectrum = onToggleShowSpectrum,
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(120.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black,
                        shadowElevation = 8.dp,
                    ) {
                        VisualizerComponent(
                            isPlaying = isPlaying,
                            sensitivity = visualizerSensitivity,
                            brightness = visualizerBrightness,
                            modifier = Modifier.fillMaxSize(),
                            engineStatus = visualizerEngineStatus,
                            engineEnabled = visualizerEngineEnabled,
                            showFps = false,
                            isFullscreen = false,
                            touchWaveformEnabled = visualizerTouchWaveform,
                            repository = visualizerRepository,
                        )
                    }
                }
            } else {
                VisualizerComponent(
                    isPlaying = isPlaying,
                    sensitivity = visualizerSensitivity,
                    brightness = visualizerBrightness,
                    modifier = Modifier.fillMaxSize(),
                    engineStatus = visualizerEngineStatus,
                    engineEnabled = visualizerEngineEnabled,
                    showFps = visualizerShowFps,
                    isFullscreen = isFullscreen,
                    touchWaveformEnabled = visualizerTouchWaveform,
                    repository = visualizerRepository,
                )
            }

            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = onToggleCompact,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(999.dp)),
                    ) {
                        Icon(
                            if (visualizerCompact) Icons.Default.Fullscreen else Icons.Default.GraphicEq,
                            contentDescription = if (visualizerCompact) "Expand Visualizer" else "Window Mode",
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(999.dp)),
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = onExitVisualizer,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(999.dp)),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Exit Visualizer", tint = Color.White)
                    }

                    VisualizerHeroOverlay(
                        currentPreset = currentVisualizerPreset,
                        autoShuffle = visualizerAutoShuffle,
                        engineStatus = visualizerEngineStatus,
                        onToggleShuffle = onToggleVisualizerShuffle,
                        onNextPreset = onNextPreset,
                        onOpenPresetBrowser = onOpenPresetBrowser,
                        isFavorite = isPresetFavorite,
                        onToggleFavorite = onTogglePresetFavorite,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualizerHeroOverlay(
    currentPreset: VisualizerPreset?,
    autoShuffle: Boolean,
    engineStatus: VisualizerEngineStatus,
    onToggleShuffle: (Boolean) -> Unit,
    onNextPreset: () -> Unit,
    onOpenPresetBrowser: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(10.dp)
            .liquidGlass(shape = RoundedCornerShape(18.dp), tintAlpha = 0.26f),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = currentPreset?.displayName ?: "Bundled projectM presets",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = engineStatus.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (autoShuffle) PlayerGlowMint.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.1f),
                    contentColor = if (autoShuffle) PlayerGlowMint else Color.White.copy(alpha = 0.72f),
                ) {
                    Text(
                        text = if (autoShuffle) "Shuffle" else "Manual",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Shuffle,
                    label = if (autoShuffle) "Shuffle" else "Manual",
                    accent = if (autoShuffle) PlayerGlowMint else Color.White,
                    onClick = { onToggleShuffle(!autoShuffle) },
                )
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (isFavorite) "Liked" else "Like",
                    accent = if (isFavorite) PlayerGlowPink else Color.White,
                    onClick = onToggleFavorite,
                )
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.SkipNext,
                    label = "Next",
                    accent = PlayerGlowBlue,
                    onClick = onNextPreset,
                )
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.LibraryMusic,
                    label = "Presets",
                    accent = PlayerGlowGold,
                    onClick = onOpenPresetBrowser,
                )
            }
        }
    }
}

private enum class SpectrumSpeed(val label: String, val attack: Float, val release: Float) {
    SLOW("SLOW", 0.12f, 0.03f),
    NORMAL("NORMAL", 0.55f, 0.12f),
    FAST("FAST", 0.85f, 0.35f),
    HYPER("HYPER", 1.0f, 0.70f);

    fun next(): SpectrumSpeed = entries[(ordinal + 1) % entries.size]
}

@Composable
private fun HeroCoverArt(
    track: Track?,
    isPlaying: Boolean,
    spectrumBins: FloatArray = FloatArray(0),
    spectrumColor: Color = PlayerGlowBlue,
    showSpectrum: Boolean = true,
    onToggleShowSpectrum: () -> Unit = {},
    quality: String? = null,
    onEnterVisualizer: (() -> Unit)? = null,
) {
    val spectrumEnabled = showSpectrum
    var spectrumSpeed by remember { mutableStateOf(SpectrumSpeed.NORMAL) }

    // Controls show briefly on tap, then disappear quickly. When idle there are
    // no tags/labels on the art at all — the buttons are small and icon-only.
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(1200)
            controlsVisible = false
        }
    }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "controlsFade",
    )
    val interactive = controlsAlpha > 0.5f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = true }
    ) {
        CoverImage(
            url = track?.coverUrl,
            contentDescription = track?.title ?: "Album Art",
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 0.dp,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.12f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.52f),
                        )
                    )
                )
        )

        if (spectrumEnabled && spectrumBins.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                SpectrumOverlay(
                    bins = spectrumBins,
                    color = spectrumColor,
                    modifier = Modifier.fillMaxWidth(),
                    height = maxHeight * 0.35f,
                    attack = spectrumSpeed.attack,
                    release = spectrumSpeed.release,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = controlsAlpha }) {
            // Small, icon-only controls (top-left): spectrum toggle + speed.
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeroIconButton(
                    icon = if (spectrumEnabled) Icons.Default.Equalizer else Icons.Default.Album,
                    enabled = interactive,
                    onClick = { onToggleShowSpectrum(); controlsVisible = true },
                )
                if (spectrumEnabled) {
                    HeroIconButton(
                        icon = Icons.Default.Speed,
                        enabled = interactive,
                        onClick = { spectrumSpeed = spectrumSpeed.next(); controlsVisible = true },
                    )
                }
            }

            // Quality badge (top-right) — also fades out when idle.
            if (quality != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .liquidGlass(
                            shape = RoundedCornerShape(999.dp),
                            tintAlpha = 0.18f,
                            borderAlpha = 0.12f,
                        ),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    contentColor = Color.White,
                ) {
                    Text(
                        text = quality,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Visualizer entry (bottom-right) — small, icon-only.
            if (onEnterVisualizer != null) {
                HeroIconButton(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    icon = Icons.Default.GraphicEq,
                    enabled = interactive,
                    onClick = { onEnterVisualizer(); controlsVisible = true },
                )
            }
        }
    }
}

/** Small circular glass icon button used for the album-art overlay controls. */
@Composable
private fun HeroIconButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .liquidGlass(shape = CircleShape, tintAlpha = 0.18f, borderAlpha = 0.12f),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BouncePill(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bouncePillScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .liquidGlass(
                shape = RoundedCornerShape(999.dp),
                tintAlpha = 0.15f,
                borderAlpha = 0.12f,
            )
    ) {
        content()
    }
}

@Composable
private fun VisualizerActionPill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "vizActionScale",
    )
    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
