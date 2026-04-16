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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import java.util.Locale

data class AlbumColors(val dominant: Color, val vibrant: Color)

@Composable
fun rememberAlbumColors(imageUrl: String?): AlbumColors {
    val context = LocalContext.current
    var dominant by remember(imageUrl) { mutableStateOf(Color(0xFF1B1B1B)) }
    var vibrant by remember(imageUrl) { mutableStateOf(Color(0xFF7EB6FF)) }

    Box(modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build(),
            contentDescription = null
        ) {
            val state = painter.state
            if (state is AsyncImagePainter.State.Success) {
                LaunchedEffect(state) {
                    val bitmap = state.result.image.toBitmap()
                    androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
                        dominant = palette?.dominantSwatch?.let { Color(it.rgb) }
                            ?: palette?.vibrantSwatch?.let { Color(it.rgb) }
                            ?: dominant
                        // Pick the brightest swatch for the spectrum overlay.
                        vibrant = palette?.lightVibrantSwatch?.let { Color(it.rgb) }
                            ?: palette?.vibrantSwatch?.let { Color(it.rgb) }
                            ?: palette?.lightMutedSwatch?.let { Color(it.rgb) }
                            ?: dominant
                    }
                }
            }
            SubcomposeAsyncImageContent()
        }
    }

    return AlbumColors(dominant, vibrant)
}

@Composable
fun rememberDominantColor(imageUrl: String?): Color = rememberAlbumColors(imageUrl).dominant

private val PlayerGlowBlue = Color(0xFF7EB6FF)
private val PlayerGlowPink = Color(0xFFFF7EB3)
private val PlayerGlowMint = Color(0xFF6EF0C2)
private val PlayerGlowGold = Color(0xFFFFC857)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel
) {
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val queue by playerViewModel.queue.collectAsState()
    val currentIndex by playerViewModel.currentIndex.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val positionMs by playerViewModel.positionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsState()
    val repeatMode by playerViewModel.repeatMode.collectAsState()
    val isLiked by playerViewModel.isCurrentTrackLiked.collectAsState()
    val lyrics by playerViewModel.currentLyrics.collectAsState()
    val isLyricsLoading by playerViewModel.isLyricsLoading.collectAsState()
    val viewMode by playerViewModel.nowPlayingViewMode.collectAsState()
    val visualizerSensitivity by playerViewModel.visualizerSensitivity.collectAsState()
    val visualizerBrightness by playerViewModel.visualizerBrightness.collectAsState()
    val visualizerFullscreen by playerViewModel.visualizerFullscreen.collectAsState()
    val visualizerTouchWaveform by playerViewModel.visualizerTouchWaveform.collectAsState()
    val visualizerShowFps by playerViewModel.visualizerShowFps.collectAsState()
    val playbackSpeed by playerViewModel.playbackSpeed.collectAsState()
    val visualizerEngineStatus by playerViewModel.visualizerEngineStatus.collectAsState()
    val visualizerEngineEnabled by playerViewModel.visualizerEngineEnabled.collectAsState()
    val visualizerAutoShuffle by playerViewModel.visualizerAutoShuffle.collectAsState()
    val currentVisualizerPreset by playerViewModel.currentVisualizerPreset.collectAsState()
    val visualizerPresets by playerViewModel.visualizerPresets.collectAsState()
    val visualizerFavoritePresetIds by playerViewModel.visualizerFavoritePresetIds.collectAsState()
    val visualizerCompact by playerViewModel.visualizerCompact.collectAsState()
    val spectrumBins by playerViewModel.spectrumAnalyzer.spectrumBins.collectAsState()

    // Power the FFT tap only while this screen is on-screen. The tap itself
    // is always wired into the audio pipeline (passive), but its analysis
    // coroutine sleeps when nobody is listening.
    DisposableEffect(Unit) {
        playerViewModel.setSpectrumActive(true)
        onDispose { playerViewModel.setSpectrumActive(false) }
    }

    var speedText by remember(playbackSpeed) {
        mutableStateOf(String.format(Locale.US, "%.2f", playbackSpeed))
    }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showPresetSheet by remember { mutableStateOf(false) }
    var showSpeedPanel by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val progressFraction = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
    val displayFraction = if (isSeeking) seekPosition else progressFraction
    val upcomingTracks = remember(queue, currentIndex) {
        queue.drop((currentIndex + 1).coerceAtLeast(0)).take(4)
    }

    if (showLyricsSheet) {
        LyricsSheet(
            lyrics = lyrics,
            isLoading = isLyricsLoading,
            positionMs = playerViewModel.positionMs,
            onSeekTo = playerViewModel::seekTo,
            onDismiss = { showLyricsSheet = false }
        )
    }

    if (showQueueSheet) {
        QueueSheet(
            playerViewModel = playerViewModel,
            onDismiss = { showQueueSheet = false }
        )
    }

    if (showPresetSheet) {
        VisualizerPresetSheet(
            presets = visualizerPresets,
            selectedPresetId = currentVisualizerPreset?.id,
            favoritePresetIds = visualizerFavoritePresetIds,
            onPresetSelected = playerViewModel::selectVisualizerPreset,
            onToggleFavorite = playerViewModel::toggleVisualizerFavoritePreset,
            onSettingsClick = { navController.navigate(tf.monochrome.android.ui.navigation.Screen.Settings.createRoute()) },
            onDismiss = { showPresetSheet = false }
        )
    }

    LaunchedEffect(isPlaying) {
        playerViewModel.setVisualizerPlaybackPaused(!isPlaying)
    }

    LaunchedEffect(viewMode) {
        if (viewMode == NowPlayingViewMode.VISUALIZER) {
            playerViewModel.setNowPlayingViewMode(NowPlayingViewMode.VISUALIZER)
        }
    }

    val albumColors = rememberAlbumColors(currentTrack?.coverUrl)
    val animatedBackground by androidx.compose.animation.animateColorAsState(
        targetValue = albumColors.dominant,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 800)
    )
    val animatedVibrant by androidx.compose.animation.animateColorAsState(
        targetValue = albumColors.vibrant,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 800)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        animatedBackground.copy(alpha = 0.5f),
                        animatedBackground.copy(alpha = 0.2f),
                        Color.Black
                    )
                )
            )
    ) {
        // Subtle animated glow in background
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedBackground.copy(alpha = 0.3f), Color.Transparent)
                ),
                radius = size.width * 0.8f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.3f)
            )
        }

        val isFullscreenActive = viewMode == NowPlayingViewMode.VISUALIZER && visualizerFullscreen

        val view = androidx.compose.ui.platform.LocalView.current
        val window = (view.context as? android.app.Activity)?.window
        LaunchedEffect(isFullscreenActive) {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                if (isFullscreenActive) {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (isFullscreenActive) 0.dp else 16.dp)
        ) {
            if (!isFullscreenActive) {
                TopAppBar(
                    title = { Text("Now Playing") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isFullscreenActive) 0.dp else 24.dp),
                verticalArrangement = if (isFullscreenActive) Arrangement.Top else Arrangement.spacedBy(8.dp)
            ) {
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                @Suppress("ConfigurationScreenWidthHeight") // containerSize unavailable in current Compose BOM
                val screenAspectRatio = configuration.screenHeightDp.toFloat() / configuration.screenWidthDp.toFloat()
                val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

                val heroModifier = if (isFullscreenActive) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth(1f).aspectRatio(1f)
                }

                // Hero area — takes remaining vertical space, art centered within
                Box(
                    modifier = if (isFullscreenActive) Modifier.fillMaxSize() else Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    NowPlayingHero(
                        modifier = heroModifier.then(
                            if (!isFullscreenActive) {
                                Modifier.shadow(
                                    elevation = 60.dp,
                                    shape = RoundedCornerShape(34.dp),
                                    ambientColor = animatedBackground.copy(alpha = 0.8f),
                                    spotColor = Color.Black.copy(alpha = 0.5f)
                                )
                            } else Modifier
                        ),
                        isFullscreen = isFullscreenActive,
                        track = currentTrack,
                        isPlaying = isPlaying,
                        viewMode = viewMode,
                        lyrics = lyrics,
                        isLyricsLoading = isLyricsLoading,
                        queuePreview = upcomingTracks,
                        visualizerSensitivity = visualizerSensitivity,
                        visualizerBrightness = visualizerBrightness,
                        visualizerEngineStatus = visualizerEngineStatus,
                        visualizerEngineEnabled = visualizerEngineEnabled,
                        visualizerShowFps = visualizerShowFps,
                        visualizerRepository = playerViewModel.visualizerRepository,
                        visualizerTouchWaveform = visualizerTouchWaveform,
                        currentVisualizerPreset = currentVisualizerPreset,
                        visualizerAutoShuffle = visualizerAutoShuffle,
                        onToggleVisualizerShuffle = playerViewModel::setVisualizerShuffle,
                        onNextPreset = playerViewModel::nextVisualizerPreset,
                        onOpenPresetBrowser = { showPresetSheet = true },
                        onCycleMode = playerViewModel::cycleNowPlayingViewMode,
                        isPresetFavorite = currentVisualizerPreset?.id?.let { it in visualizerFavoritePresetIds } ?: false,
                        onTogglePresetFavorite = { currentVisualizerPreset?.id?.let { playerViewModel.toggleVisualizerFavoritePreset(it) } },
                        visualizerCompact = visualizerCompact,
                        onToggleCompact = playerViewModel::toggleVisualizerCompact,
                        onToggleFullscreen = playerViewModel::toggleVisualizerFullscreen,
                        spectrumBins = spectrumBins,
                        spectrumColor = animatedVibrant
                    )
                }

                if (!isFullscreenActive) {
                    // Track info glass card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlass(
                                shape = RoundedCornerShape(22.dp),
                                tintAlpha = 0.18f,
                                borderAlpha = 0.12f
                            )
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = currentTrack?.title ?: "No track playing",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            MetaChip(label = currentTrack?.audioQuality ?: "HD", accent = Color.White.copy(alpha = 0.5f))
                        }
                        Text(
                            text = currentTrack?.displayArtist?.ifBlank { "Unknown" } ?: "Unknown",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = currentTrack?.artist?.id != null) {
                                currentTrack?.artist?.id?.let { artistId ->
                                    navController.navigate(tf.monochrome.android.ui.navigation.Screen.ArtistDetail.createRoute(artistId))
                                }
                            }
                        )
                    }

                    // Actions row glass card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlass(
                                shape = RoundedCornerShape(22.dp),
                                tintAlpha = 0.12f,
                                borderAlpha = 0.08f
                            )
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = playerViewModel::toggleLikeCurrentTrack) {
                            Icon(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like", tint = if (isLiked) PlayerGlowPink else Color.White)
                        }
                        IconButton(onClick = { showSpeedPanel = !showSpeedPanel }) {
                            Icon(Icons.Default.GraphicEq, "Audio/Speed", tint = Color.White)
                        }
                        IconButton(onClick = { currentTrack?.let { playerViewModel.downloadTrack(it) } }) {
                            Icon(Icons.Default.LibraryMusic, "Download", tint = Color.White)
                        }
                        IconButton(onClick = { showLyricsSheet = true }) {
                            Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Lyrics", tint = Color.White)
                        }
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = Color.White)
                        }
                    }

                    // Up Next glass strip
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlass(
                                shape = RoundedCornerShape(16.dp),
                                tintAlpha = 0.10f,
                                borderAlpha = 0.08f
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "UP NEXT:",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            color = Color.White.copy(alpha = 0.4f)
                        )
                        val nextTrack = upcomingTracks.firstOrNull()
                        Text(
                            text = nextTrack?.let { "${it.title} • ${it.displayArtist}" } ?: "End of queue",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Progress Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val displayPositionMs = if (isSeeking) (seekPosition * durationMs).toLong() else positionMs
                        Text(
                            text = playerViewModel.formatTime(displayPositionMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Slider(
                            value = displayFraction,
                            onValueChange = { value ->
                                isSeeking = true
                                seekPosition = value
                            },
                            onValueChangeFinished = {
                                playerViewModel.seekToFraction(seekPosition)
                                isSeeking = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = PlayerGlowPink,
                                activeTrackColor = PlayerGlowPink.copy(alpha = 0.7f),
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                            )
                        )
                        Text(
                            text = playerViewModel.formatTime(durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }

                    // Play Controls glass card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlass(
                                shape = RoundedCornerShape(28.dp),
                                tintAlpha = 0.16f,
                                borderAlpha = 0.10f
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerToggleIcon(
                            icon = Icons.Default.Shuffle,
                            active = shuffleEnabled,
                            onClick = playerViewModel::toggleShuffle,
                            contentDescription = "Shuffle"
                        )
                        IconButton(onClick = playerViewModel::skipToPrevious) {
                            Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp), tint = Color.White)
                        }
                        FilledIconButton(
                            onClick = playerViewModel::togglePlayPause,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(38.dp))
                        }
                        IconButton(onClick = playerViewModel::skipToNext) {
                            Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp), tint = Color.White)
                        }
                        PlayerToggleIcon(
                            icon = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            active = repeatMode != RepeatMode.OFF,
                            onClick = playerViewModel::cycleRepeatMode,
                            contentDescription = "Repeat"
                        )
                    }
                }

                if (showSpeedPanel) {
                    Surface(
                        modifier = Modifier.liquidGlass(
                            shape = RoundedCornerShape(22.dp),
                            tintAlpha = 0.18f
                        ),
                        shape = RoundedCornerShape(22.dp),
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = PlayerGlowMint
                            )
                            Slider(
                                value = playbackSpeed,
                                onValueChange = { newSpeed ->
                                    val rounded = Math.round(newSpeed * 100f) / 100f
                                    speedText = String.format(Locale.US, "%.2f", rounded)
                                    playerViewModel.setPlaybackSpeed(rounded)
                                },
                                valueRange = 0.25f..3.0f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = PlayerGlowMint,
                                    activeTrackColor = PlayerGlowMint,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                                )
                            )
                            OutlinedTextField(
                                value = speedText,
                                onValueChange = { input ->
                                    speedText = input
                                    input.toFloatOrNull()?.let { parsed ->
                                        playerViewModel.setPlaybackSpeed(parsed.coerceIn(0.25f, 3.0f))
                                    }
                                },
                                modifier = Modifier.width(84.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(
                                onClick = {
                                    playerViewModel.setPlaybackSpeed(1.0f)
                                    speedText = "1.00"
                                }
                            ) {
                                Text("Reset", color = Color.White)
                            }
                        }
                    }
                }

                ModeSelector(
                    selectedMode = viewMode,
                    onModeSelected = playerViewModel::setNowPlayingViewMode,
                    onDspMixClick = { navController.navigate(tf.monochrome.android.ui.navigation.Screen.Mixer.route) }
                )
            }
        }
    }
}

@Composable
private fun PlayerBackgroundGlow() {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.95f }
    ) {
        val w = size.width
        val h = size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PlayerGlowBlue.copy(alpha = 0.24f), Color.Transparent)
            ),
            radius = w * 0.56f,
            center = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.14f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PlayerGlowPink.copy(alpha = 0.16f), Color.Transparent)
            ),
            radius = w * 0.52f,
            center = androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.22f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PlayerGlowMint.copy(alpha = 0.12f), Color.Transparent)
            ),
            radius = w * 0.68f,
            center = androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.78f)
        )
    }
}

@Composable
private fun NowPlayingHero(
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    track: Track?,
    isPlaying: Boolean,
    viewMode: NowPlayingViewMode,
    lyrics: Lyrics?,
    isLyricsLoading: Boolean,
    queuePreview: List<Track>,
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
    onCycleMode: () -> Unit,
    isPresetFavorite: Boolean,
    onTogglePresetFavorite: () -> Unit,
    visualizerCompact: Boolean = false,
    onToggleCompact: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    spectrumBins: FloatArray = FloatArray(0),
    spectrumColor: Color = Color(0xFF7EB6FF)
) {
    var showOverlay by androidx.compose.runtime.remember(viewMode) {
        androidx.compose.runtime.mutableStateOf(viewMode == NowPlayingViewMode.VISUALIZER) 
    }

    androidx.compose.runtime.LaunchedEffect(showOverlay) {
        if (showOverlay) {
            kotlinx.coroutines.delay(2000)
            showOverlay = false
        }
    }
    Surface(
        modifier = modifier,
        shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(34.dp),
        color = if (isFullscreen) Color.Black else MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
    ) {
        val interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (viewMode == NowPlayingViewMode.VISUALIZER) {
                            showOverlay = true
                        }
                    }
                )
        ) {
            if (viewMode == NowPlayingViewMode.VISUALIZER && visualizerCompact) {
                // Compact mode: cover art with small visualizer window
                Box(modifier = Modifier.fillMaxSize()) {
                    HeroCoverArt(track = track, isPlaying = isPlaying, spectrumBins = spectrumBins, spectrumColor = spectrumColor)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(120.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black,
                        shadowElevation = 8.dp
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
                            repository = visualizerRepository
                        )
                    }
                }
            } else {
                androidx.compose.animation.Crossfade(
                    targetState = viewMode,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                    label = "HeroCrossfade"
                ) { targetMode ->
                    when (targetMode) {
                        NowPlayingViewMode.COVER_ART -> HeroCoverArt(
                            track = track,
                            isPlaying = isPlaying,
                            spectrumBins = spectrumBins,
                            spectrumColor = spectrumColor
                        )
                        NowPlayingViewMode.VISUALIZER -> VisualizerComponent(
                            isPlaying = isPlaying,
                            sensitivity = visualizerSensitivity,
                            brightness = visualizerBrightness,
                            modifier = Modifier.fillMaxSize(),
                            engineStatus = visualizerEngineStatus,
                            engineEnabled = visualizerEngineEnabled,
                            showFps = visualizerShowFps,
                            isFullscreen = isFullscreen,
                            touchWaveformEnabled = visualizerTouchWaveform,
                            repository = visualizerRepository
                        )
                        NowPlayingViewMode.LYRICS -> LyricsHeroPanel(
                            lyrics = lyrics,
                            isLoading = isLyricsLoading
                        )
                        NowPlayingViewMode.QUEUE -> QueueHeroPanel(queuePreview = queuePreview)
                    }
                }
            }

            if (viewMode == NowPlayingViewMode.VISUALIZER) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showOverlay,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Compact/Window toggle button (top-left)
                        IconButton(
                            onClick = onToggleCompact,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(999.dp))
                        ) {
                            Icon(
                                if (visualizerCompact) Icons.Default.Fullscreen else Icons.Default.GraphicEq,
                                contentDescription = if (visualizerCompact) "Expand Visualizer" else "Window Mode",
                                tint = Color.White
                            )
                        }

                        // Fullscreen toggle (top-center)
                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(999.dp))
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                                tint = Color.White
                            )
                        }

                        // Close button (top-right)
                        IconButton(
                            onClick = onCycleMode,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(999.dp))
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
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            } else {
                BouncePill(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    onClick = onCycleMode
                ) {
                    Text(
                        text = "Visualizer",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
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
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(16.dp)
            .liquidGlass(
                shape = RoundedCornerShape(24.dp),
                tintAlpha = 0.26f
            ),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = currentPreset?.displayName ?: "Bundled projectM presets",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = engineStatus.badge,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (autoShuffle) PlayerGlowMint.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.1f),
                    contentColor = if (autoShuffle) PlayerGlowMint else Color.White.copy(alpha = 0.72f)
                ) {
                    Text(
                        text = if (autoShuffle) "Shuffle" else "Manual",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Shuffle,
                    label = if (autoShuffle) "Shuffle" else "Manual",
                    accent = if (autoShuffle) PlayerGlowMint else Color.White,
                    onClick = { onToggleShuffle(!autoShuffle) }
                )
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (isFavorite) "Liked" else "Like",
                    accent = if (isFavorite) PlayerGlowPink else Color.White,
                    onClick = onToggleFavorite
                )
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.SkipNext,
                    label = "Next",
                    accent = PlayerGlowBlue,
                    onClick = onNextPreset
                )
                VisualizerActionPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.LibraryMusic,
                    label = "Presets",
                    accent = PlayerGlowGold,
                    onClick = onOpenPresetBrowser
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
    spectrumColor: Color = Color(0xFF7EB6FF)
) {
    var spectrumEnabled by remember { mutableStateOf(true) }
    var spectrumSpeed by remember { mutableStateOf(SpectrumSpeed.NORMAL) }

    // Controls auto-hide after 3 seconds, reappear on tap.
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
        label = "controlsFade"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = true }
    ) {
        CoverImage(
            url = track?.coverUrl,
            contentDescription = track?.title ?: "Album Art",
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.12f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.52f)
                        )
                    )
                )
        )

        // Live spectrum overlay anchored to the bottom edge of the artwork.
        if (spectrumEnabled && spectrumBins.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                SpectrumOverlay(
                    bins = spectrumBins,
                    color = spectrumColor,
                    modifier = Modifier.fillMaxWidth(),
                    height = maxHeight * 0.35f,
                    attack = spectrumSpeed.attack,
                    release = spectrumSpeed.release
                )
            }
        }

        // Overlay controls — fade out after 3 s, tap artwork to bring back.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = controlsAlpha }
        ) {
            // Top-left: tap to toggle the spectrum overlay on/off.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(enabled = controlsAlpha > 0.5f) {
                        spectrumEnabled = !spectrumEnabled
                        controlsVisible = true
                    }
                    .liquidGlass(
                        shape = RoundedCornerShape(999.dp),
                        tintAlpha = 0.15f,
                        borderAlpha = 0.12f
                    ),
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (spectrumEnabled) Icons.Default.Equalizer else Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (spectrumEnabled) "Spectrum ON" else "Spectrum OFF",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Top-right: tap to cycle spectrum speed (only shown when spectrum is on).
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
                            borderAlpha = 0.12f
                        ),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = spectrumSpeed.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsHeroPanel(
    lyrics: Lyrics?,
    isLoading: Boolean
) {
    val previewLines = lyrics?.lines?.take(5).orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF111421),
                        Color(0xFF0F2430),
                        Color(0xFF15111D)
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
            when {
                isLoading -> {
                    Text(
                        text = "Loading synced lines...",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }

                previewLines.isEmpty() -> {
                    Text(
                        text = "No lyrics for this track yet.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }

                else -> {
                    previewLines.forEachIndexed { index, line ->
                        Text(
                            text = line.text.ifBlank { "..." },
                            style = if (index == 0) {
                                MaterialTheme.typography.headlineSmall
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = Color.White.copy(alpha = if (index == 0) 1f else 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueHeroPanel(queuePreview: List<Track>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF11151F),
                        Color(0xFF171327),
                        Color(0xFF101A1C)
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Up Next",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
            if (queuePreview.isEmpty()) {
                Text(
                    text = "Queue is empty.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
            } else {
                queuePreview.forEachIndexed { index, track ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${index + 1}. ${track.title}",
                            style = if (index == 0) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = Color.White
                        )
                        Text(
                            text = track.displayArtist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: NowPlayingViewMode,
    onModeSelected: (NowPlayingViewMode) -> Unit,
    onDspMixClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Album,
            label = "Art",
            selected = selectedMode == NowPlayingViewMode.COVER_ART,
            onClick = { onModeSelected(NowPlayingViewMode.COVER_ART) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Equalizer,
            label = "Visual",
            selected = selectedMode == NowPlayingViewMode.VISUALIZER,
            onClick = { onModeSelected(NowPlayingViewMode.VISUALIZER) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
            label = "Lyrics",
            selected = selectedMode == NowPlayingViewMode.LYRICS,
            onClick = { onModeSelected(NowPlayingViewMode.LYRICS) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "Queue",
            selected = selectedMode == NowPlayingViewMode.QUEUE,
            onClick = { onModeSelected(NowPlayingViewMode.QUEUE) }
        )
        ModePill(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Tune,
            label = "DSP Mix",
            selected = false,
            onClick = onDspMixClick
        )
    }
}

@Composable
private fun ModePill(
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
            .padding(vertical = 12.dp, horizontal = 8.dp),
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
                color = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun TrackSummaryCard(
    track: Track?,
    isLiked: Boolean,
    onToggleLike: () -> Unit
) {
    Surface(
        modifier = Modifier.liquidGlass(shape = RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track?.title ?: "No track playing",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track?.displayArtist?.ifBlank { "Play something to get started" }
                            ?: "Play something to get started",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledIconButton(
                    onClick = onToggleLike,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isLiked) {
                            PlayerGlowPink.copy(alpha = 0.92f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (isLiked) Color.Black else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiked) "Unlike" else "Like"
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(
                    label = track?.audioQuality ?: "Streaming",
                    accent = PlayerGlowBlue
                )
                MetaChip(
                    label = track?.formattedDuration ?: "0:00",
                    accent = PlayerGlowMint
                )
            }
        }
    }
}

@Composable
private fun MetaChip(
    label: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun PlayerToggleIcon(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) PlayerGlowMint else Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SecondaryActionPill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "secondaryPillScale"
    )

    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) {
            Color.White.copy(alpha = 0.12f)
        } else {
            Color.White.copy(alpha = 0.06f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.42f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f)
            )
        }
    }
}

@Composable
private fun BouncePill(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bouncePillScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .liquidGlass(
                shape = RoundedCornerShape(999.dp),
                tintAlpha = 0.15f,
                borderAlpha = 0.12f
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
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "vizActionScale"
    )

    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
