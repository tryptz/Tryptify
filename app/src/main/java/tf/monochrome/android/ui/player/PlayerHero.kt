package tf.monochrome.android.ui.player

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
            .aspectRatio(1f)
            .shadow(
                elevation = 14.dp,
                shape = RectangleShape,
                spotColor = Color.Black,
                ambientColor = Color.Black,
            ),
        shape = RectangleShape,
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HeroCoverArt(
                track = track,
                isPlaying = isPlaying,
                spectrumBins = spectrumBins,
                spectrumColor = spectrumColor,
                showSpectrum = showSpectrum,
                onToggleShowSpectrum = onToggleShowSpectrum,
            )
            track?.audioQuality?.let { quality ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            BouncePill(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                onClick = onEnterVisualizer,
            ) {
                Text(
                    text = "Visualizer",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
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
    Surface(
        modifier = if (isFullscreen) modifier else modifier.aspectRatio(1f),
        shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(PlayerDesignTokens.HeroCorner),
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
        modifier = modifier.padding(16.dp)
            .liquidGlass(shape = RoundedCornerShape(24.dp), tintAlpha = 0.26f),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = currentPreset?.displayName ?: "Bundled projectM presets",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = engineStatus.badge,
                        style = MaterialTheme.typography.bodySmall,
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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
) {
    val spectrumEnabled = showSpectrum
    var spectrumSpeed by remember { mutableStateOf(SpectrumSpeed.NORMAL) }

    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "controlsFade",
    )

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
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(enabled = controlsAlpha > 0.5f) {
                        onToggleShowSpectrum()
                        controlsVisible = true
                    }
                    .liquidGlass(
                        shape = RoundedCornerShape(999.dp),
                        tintAlpha = 0.15f,
                        borderAlpha = 0.12f,
                    ),
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                contentColor = Color.White,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (spectrumEnabled) Icons.Default.Equalizer else Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (spectrumEnabled) "Spectrum ON" else "Spectrum OFF",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (spectrumEnabled) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = controlsAlpha > 0.5f) {
                            spectrumSpeed = spectrumSpeed.next()
                            controlsVisible = true
                        }
                        .liquidGlass(
                            shape = RoundedCornerShape(999.dp),
                            tintAlpha = 0.15f,
                            borderAlpha = 0.12f,
                        ),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = spectrumSpeed.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
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
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
