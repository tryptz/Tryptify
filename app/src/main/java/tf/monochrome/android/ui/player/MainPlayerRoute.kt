package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.ui.navigation.Screen
import java.util.Locale

/**
 * Stateful entry point for the main player. Collects every flow from
 * [PlayerViewModel], builds a flattened [MainPlayerUiState], owns the modal
 * sheets and the sleep timer, then hands a pure layout to [MainPlayerScreen].
 */
@Composable
fun MainPlayerRoute(
    navController: NavController,
    playerViewModel: PlayerViewModel,
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
    val downloadState by playerViewModel.currentTrackDownloadState.collectAsState()
    val isDownloaded by playerViewModel.isCurrentTrackDownloaded.collectAsState()
    val lyrics by playerViewModel.currentLyrics.collectAsState()
    val isLyricsLoading by playerViewModel.isLyricsLoading.collectAsState()
    val viewMode by playerViewModel.nowPlayingViewMode.collectAsState()
    val playbackSpeed by playerViewModel.playbackSpeed.collectAsState()
    val preservePitch by playerViewModel.preservePitch.collectAsState()
    val compressorEnabled by playerViewModel.compressorEnabled.collectAsState()
    val inflatorEnabled by playerViewModel.inflatorEnabled.collectAsState()

    val visualizerSensitivity by playerViewModel.visualizerSensitivity.collectAsState()
    val visualizerBrightness by playerViewModel.visualizerBrightness.collectAsState()
    val visualizerFullscreen by playerViewModel.visualizerFullscreen.collectAsState()
    val visualizerTouchWaveform by playerViewModel.visualizerTouchWaveform.collectAsState()
    val visualizerShowFps by playerViewModel.visualizerShowFps.collectAsState()
    val visualizerEngineStatus by playerViewModel.visualizerEngineStatus.collectAsState()
    val visualizerEngineEnabled by playerViewModel.visualizerEngineEnabled.collectAsState()
    val visualizerAutoShuffle by playerViewModel.visualizerAutoShuffle.collectAsState()
    val currentVisualizerPreset by playerViewModel.currentVisualizerPreset.collectAsState()
    val visualizerPresets by playerViewModel.visualizerPresets.collectAsState()
    val visualizerFavoritePresetIds by playerViewModel.visualizerFavoritePresetIds.collectAsState()
    val visualizerCompact by playerViewModel.visualizerCompact.collectAsState()
    val spectrumBins by playerViewModel.spectrumAnalyzer.spectrumBins.collectAsState()
    val spectrumAnalyzerEnabled by playerViewModel.spectrumAnalyzerEnabled.collectAsState()
    val spectrumShowOnNowPlaying by playerViewModel.spectrumShowOnNowPlaying.collectAsState()
    val showNpSpectrum = spectrumAnalyzerEnabled && spectrumShowOnNowPlaying

    if (showNpSpectrum) {
        DisposableEffect(Unit) {
            playerViewModel.acquireSpectrum()
            onDispose { playerViewModel.releaseSpectrum() }
        }
    }

    // --- Local UI state owned by the route ---
    var heroStyle by rememberSaveable { mutableStateOf(PlayerHeroStyle.Square) }
    var showLyricsSheet by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showPresetSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSleepSheet by rememberSaveable { mutableStateOf(false) }
    var sleepMinutes by rememberSaveable { mutableIntStateOf(0) }

    // Self-contained sleep timer: pause playback once the chosen interval
    // elapses, then reset. Re-keys (and restarts) whenever the user changes it.
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            if (playerViewModel.isPlaying.value) playerViewModel.togglePlayPause()
            sleepMinutes = 0
        }
    }

    LaunchedEffect(isPlaying) { playerViewModel.setVisualizerPlaybackPaused(!isPlaying) }

    val albumColors = rememberAlbumColors(currentTrack?.coverUrl)
    val animatedDominant by androidx.compose.animation.animateColorAsState(
        targetValue = albumColors.dominant,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 800),
        label = "playerBackground",
    )
    val spectrumColor = MaterialTheme.colorScheme.primary

    val isFullscreenActive = viewMode == NowPlayingViewMode.VISUALIZER && visualizerFullscreen
    HandleFullscreenInsets(isFullscreenActive)

    // --- Sheets ---
    if (showLyricsSheet) {
        LyricsSheet(
            lyrics = lyrics,
            isLoading = isLyricsLoading,
            positionMs = playerViewModel.positionMs,
            onSeekTo = playerViewModel::seekTo,
            onDismiss = { showLyricsSheet = false },
        )
    }
    if (showQueueSheet) {
        QueueSheet(playerViewModel = playerViewModel, onDismiss = { showQueueSheet = false })
    }
    if (showPresetSheet) {
        VisualizerPresetSheet(
            presets = visualizerPresets,
            selectedPresetId = currentVisualizerPreset?.id,
            favoritePresetIds = visualizerFavoritePresetIds,
            onPresetSelected = playerViewModel::selectVisualizerPreset,
            onToggleFavorite = playerViewModel::toggleVisualizerFavoritePreset,
            onSettingsClick = { navController.navigate(Screen.Settings.createRoute()) },
            onDismiss = { showPresetSheet = false },
        )
    }
    if (showSpeedSheet) {
        SpeedSheet(
            speed = playbackSpeed,
            preservePitch = preservePitch,
            onSpeedChange = playerViewModel::setPlaybackSpeed,
            onPreservePitchChange = playerViewModel::setPreservePitch,
            onDismiss = { showSpeedSheet = false },
        )
    }
    if (showSleepSheet) {
        SleepTimerSheet(
            activeMinutes = sleepMinutes,
            onSelect = { sleepMinutes = it },
            onDismiss = { showSleepSheet = false },
        )
    }

    val queueLabel = if (queue.isNotEmpty()) {
        "${(currentIndex + 1).coerceAtLeast(1)} / ${queue.size}"
    } else ""

    val state = MainPlayerUiState(
        track = currentTrack,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
        isLiked = isLiked,
        playbackSpeed = playbackSpeed,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        viewMode = viewMode,
        audioQuality = currentTrack?.audioQuality,
        outputLabel = "Default",
        soundLabel = "AutoEQ",
        speedLabel = String.format(Locale.US, "%.2fx", playbackSpeed),
        sleepTimerLabel = if (sleepMinutes > 0) "$sleepMinutes min" else "Off",
        queueLabel = queueLabel,
        albumColors = AlbumColors(animatedDominant, albumColors.vibrant),
        visualizerActive = viewMode == NowPlayingViewMode.VISUALIZER,
        waveformActive = showNpSpectrum,
        compressorEnabled = compressorEnabled,
        inflatorEnabled = inflatorEnabled,
    )

    MainPlayerScreen(
        state = state,
        isFullscreen = isFullscreenActive,
        formatTime = playerViewModel::formatTime,
        onToggleLike = playerViewModel::toggleLikeCurrentTrack,
        onArtistClick = {
            currentTrack?.artist?.id?.let { artistId ->
                navController.navigate(Screen.ArtistDetail.createRoute(artistId))
            }
        },
        onSeekCommit = playerViewModel::seekToFraction,
        onPrevious = playerViewModel::skipToPrevious,
        onRewind10 = playerViewModel::rewind10,
        onPlayPause = playerViewModel::togglePlayPause,
        onForward10 = playerViewModel::forward10,
        onNext = playerViewModel::skipToNext,
        onTimer = { showSleepSheet = true },
        onMixer = { navController.navigate(Screen.Mixer.route) },
        onPlaylist = { showQueueSheet = true },
        onOutput = { navController.navigate(Screen.Settings.createRoute()) },
        onSound = { navController.navigate(Screen.Equalizer.route) },
        onSpeed = { showSpeedSheet = true },
        onVisualizer = {
            playerViewModel.setNowPlayingViewMode(
                if (viewMode == NowPlayingViewMode.VISUALIZER) NowPlayingViewMode.COVER_ART
                else NowPlayingViewMode.VISUALIZER
            )
        },
        onWaveform = { playerViewModel.setSpectrumShowOnNowPlaying(!spectrumShowOnNowPlaying) },
        onCompressorToggle = playerViewModel::setCompressorEnabled,
        onInflatorToggle = playerViewModel::setInflatorEnabled,
        topBar = {
            PlayerTopBar(
                speedLabel = state.speedLabel,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                isDownloaded = isDownloaded,
                downloadState = downloadState,
                heroStyle = heroStyle,
                onCollapse = { navController.popBackStack() },
                onOutputClick = { navController.navigate(Screen.Settings.createRoute()) },
                onSpeedClick = { showSpeedSheet = true },
                onToggleShuffle = playerViewModel::toggleShuffle,
                onCycleRepeat = playerViewModel::cycleRepeatMode,
                onDownload = { currentTrack?.let { playerViewModel.downloadTrack(it) } },
                onCycleHeroStyle = {
                    heroStyle = if (heroStyle == PlayerHeroStyle.Square) {
                        PlayerHeroStyle.CircularProgress
                    } else {
                        PlayerHeroStyle.Square
                    }
                },
                onOpenVisualizer = { playerViewModel.setNowPlayingViewMode(NowPlayingViewMode.VISUALIZER) },
                onOpenEqualizer = { navController.navigate(Screen.Equalizer.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.createRoute()) },
            )
        },
        hero = { heroModifier ->
            val effectiveStyle = if (viewMode == NowPlayingViewMode.VISUALIZER) {
                PlayerHeroStyle.Visualizer
            } else {
                heroStyle
            }
            PlayerHero(
                modifier = heroModifier,
                style = effectiveStyle,
                isFullscreen = isFullscreenActive,
                track = currentTrack,
                isPlaying = isPlaying,
                progress = state.progress,
                albumColors = albumColors,
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
                isPresetFavorite = currentVisualizerPreset?.id?.let { it in visualizerFavoritePresetIds } ?: false,
                onTogglePresetFavorite = {
                    currentVisualizerPreset?.id?.let { playerViewModel.toggleVisualizerFavoritePreset(it) }
                },
                visualizerCompact = visualizerCompact,
                onToggleCompact = playerViewModel::toggleVisualizerCompact,
                onToggleFullscreen = playerViewModel::toggleVisualizerFullscreen,
                spectrumBins = spectrumBins,
                spectrumColor = spectrumColor,
                showSpectrum = showNpSpectrum,
                onToggleShowSpectrum = {
                    playerViewModel.setSpectrumShowOnNowPlaying(!spectrumShowOnNowPlaying)
                },
                onEnterVisualizer = { playerViewModel.setNowPlayingViewMode(NowPlayingViewMode.VISUALIZER) },
                onExitVisualizer = { playerViewModel.setNowPlayingViewMode(NowPlayingViewMode.COVER_ART) },
            )
        },
    )
}

@Composable
private fun HandleFullscreenInsets(isFullscreenActive: Boolean) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    LaunchedEffect(isFullscreenActive) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (isFullscreenActive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
    speed: Float,
    preservePitch: Boolean,
    onSpeedChange: (Float) -> Unit,
    onPreservePitchChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = PlayerGlowMint)
                Text(
                    text = "  Playback speed",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "  ${String.format(Locale.US, "%.2fx", speed)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = PlayerGlowMint,
                )
            }
            Slider(
                value = speed,
                onValueChange = { onSpeedChange(Math.round(it * 100f) / 100f) },
                valueRange = 0.25f..3.0f,
                colors = SliderDefaults.colors(
                    thumbColor = PlayerGlowMint,
                    activeTrackColor = PlayerGlowMint,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(speed - preset) < 0.01f,
                        onClick = { onSpeedChange(preset) },
                        label = { Text(String.format(Locale.US, "%.2gx", preset)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Preserve pitch", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (preservePitch) {
                            "Tempo changes, pitch stays natural"
                        } else {
                            "Pitch shifts with speed (vinyl-style)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = preservePitch,
                    onCheckedChange = onPreservePitchChange,
                )
            }
            TextButton(onClick = { onSpeedChange(1.0f) }) { Text("Reset to 1.0x") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    activeMinutes: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Sleep timer", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 45, 60).forEach { minutes ->
                    FilterChip(
                        selected = activeMinutes == minutes,
                        onClick = { onSelect(minutes); onDismiss() },
                        label = { Text(if (minutes == 0) "Off" else "$minutes min") },
                    )
                }
            }
            Text(
                text = if (activeMinutes > 0) {
                    "Playback will pause in $activeMinutes minutes."
                } else {
                    "Sleep timer is off."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}
