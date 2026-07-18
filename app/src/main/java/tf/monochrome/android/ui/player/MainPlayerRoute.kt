package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openArtist
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
    val currentUnified by playerViewModel.currentUnifiedTrack.collectAsState()
    val queue by playerViewModel.queue.collectAsState()
    val currentIndex by playerViewModel.currentIndex.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val isBuffering by playerViewModel.isBuffering.collectAsState()
    val positionMs by playerViewModel.positionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsState()
    val repeatMode by playerViewModel.repeatMode.collectAsState()
    val isLiked by playerViewModel.isCurrentTrackLiked.collectAsState()
    val downloadState by playerViewModel.currentTrackDownloadState.collectAsState()
    val isDownloadedRemote by playerViewModel.isCurrentTrackDownloaded.collectAsState()
    val isLocalTrack by playerViewModel.isCurrentTrackLocal.collectAsState()
    // A local file is already on disk — show it as on-device rather than
    // offering a download that would try to fetch it from the catalog.
    val isDownloaded = isDownloadedRemote || isLocalTrack
    val lyrics by playerViewModel.currentLyrics.collectAsState()
    val isLyricsLoading by playerViewModel.isLyricsLoading.collectAsState()
    val viewMode by playerViewModel.nowPlayingViewMode.collectAsState()
    val blurredBackground by playerViewModel.playerBlurredBackground.collectAsState()
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
        // Tie the FFT tap to the STARTED lifecycle, not just composition, so it
        // releases when the app is backgrounded (the Visualizer/Audio effect
        // otherwise kept sampling and draining battery while off-screen) and
        // re-acquires on return.
        LifecycleStartEffect(Unit) {
            playerViewModel.acquireSpectrum()
            onStopOrDispose { playerViewModel.releaseSpectrum() }
        }
    }

    // --- Local UI state owned by the route ---
    var heroStyle by rememberSaveable { mutableStateOf(PlayerHeroStyle.Square) }
    var showLyricsSheet by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showPresetSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSleepSheet by rememberSaveable { mutableStateOf(false) }
    // Sleep timer lives in PlayerViewModel (shared, nav-host-scoped) so the
    // countdown keeps running when this destination leaves composition.
    val sleepMinutes by playerViewModel.sleepTimerMinutes.collectAsState()
    val sleepRemainingMs by playerViewModel.sleepTimerRemainingMs.collectAsState()

    // Expanded lyrics: the SAME hero lyric surface grows to full-bleed while
    // MainPlayerScreen collapses the player chrome — no separate overlay.
    // Synced-only: lyrics without timestamps never expand, and losing sync
    // (track change, unsynced source) or leaving lyrics mode collapses.
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    val lyricsSynced = lyrics?.isSynced == true
    LaunchedEffect(viewMode, lyricsSynced) {
        if (viewMode != NowPlayingViewMode.LYRICS || !lyricsSynced) lyricsExpanded = false
    }
    BackHandler(enabled = lyricsExpanded) { lyricsExpanded = false }

    LaunchedEffect(isPlaying) { playerViewModel.setVisualizerPlaybackPaused(!isPlaying) }

    // Surface stream-resolution failures (offline / dead instance) that the
    // ViewModel now reports instead of silently looping.
    val playbackError by playerViewModel.playbackError.collectAsState()
    val playbackErrorContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(playbackError) {
        playbackError?.let {
            android.widget.Toast.makeText(playbackErrorContext, it, android.widget.Toast.LENGTH_SHORT).show()
            playerViewModel.clearPlaybackError()
        }
    }

    val lyricsFx by playerViewModel.lyricsFx.collectAsState()
    val playerGlass by playerViewModel.playerGlass.collectAsState()
    val playerDynamicColor by playerViewModel.playerDynamicColor.collectAsState()
    val dynamicColors by playerViewModel.dynamicColors.collectAsState()

    val extractedColors = rememberAlbumColors(currentTrack?.coverUrl)
    // Player tint follows album art only when BOTH the master "Dynamic Colors"
    // switch and the player-specific toggle are on — so turning off Dynamic
    // Colors makes the whole player static (background, glow, accents, rays,
    // glass), not just the app-wide theme. Otherwise the theme primary drives
    // the same pipeline.
    val themeAccent = MaterialTheme.colorScheme.primary
    val albumColors = if (dynamicColors && playerDynamicColor) {
        extractedColors
    } else {
        AlbumColors(dominant = themeAccent, vibrant = themeAccent)
    }
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
    val sleepRemainingMinutes = ((sleepRemainingMs + 59_999) / 60_000).toInt()
    if (showSleepSheet) {
        SleepTimerSheet(
            activeMinutes = sleepMinutes,
            remainingMinutes = sleepRemainingMinutes,
            onSelect = { playerViewModel.setSleepTimer(it) },
            onDismiss = { showSleepSheet = false },
        )
    }

    val queueLabel = if (queue.isNotEmpty()) {
        "${(currentIndex + 1).coerceAtLeast(1)} / ${queue.size}"
    } else ""

    val state = MainPlayerUiState(
        track = currentTrack,
        sourceType = currentUnified?.sourceType,
        artists = currentUnified?.artists ?: emptyList(),
        qualityBadge = currentUnified?.qualityBadge,
        channelBadge = currentUnified?.channelBadge ?: currentTrack?.channelBadge,
        isThxSpatialAudio = currentUnified?.isThxSpatialAudio ?: currentTrack?.isThxSpatialAudio ?: false,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
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
        sleepTimerLabel = if (sleepMinutes > 0) "$sleepRemainingMinutes min" else "Off",
        sleepTimerActive = sleepMinutes > 0,
        queueLabel = queueLabel,
        albumColors = AlbumColors(animatedDominant, albumColors.vibrant),
        visualizerActive = viewMode == NowPlayingViewMode.VISUALIZER,
        waveformActive = showNpSpectrum,
        compressorEnabled = compressorEnabled,
        inflatorEnabled = inflatorEnabled,
    )

    // Bass-reactive lyrics: one shared pulse (single analyzer stake) drives
    // both the active line's pump and the full-screen ray layer; the glyph
    // registry carries each active letter's screen position to that layer, so
    // the rays draw with NO clipping ancestor and can never be cut by a canvas.
    val glyphAnchors = remember { LyricGlyphAnchors() }
    val lyricsBeatOn = viewMode == NowPlayingViewMode.LYRICS && lyricsFx.bassReact > 0.01f
    val beatPulse: androidx.compose.runtime.State<Float>? =
        if (lyricsBeatOn) rememberBassPulse(playerViewModel.spectrumAnalyzer, lyricsFx) else null
    androidx.compose.runtime.LaunchedEffect(lyricsBeatOn) {
        LyricsDebug.log("beat engine ${if (lyricsBeatOn) "acquired (FFT analyzer staked)" else "released"}")
        if (!lyricsBeatOn) glyphAnchors.reset()
    }
    // Log the active lyrics-FX configuration whenever it changes, and when the
    // lyrics view is entered/left — so the Debug Log shows exactly what the
    // lyric renderer is running.
    androidx.compose.runtime.LaunchedEffect(lyricsFx) { LyricsDebug.log(LyricsDebug.summary(lyricsFx)) }
    androidx.compose.runtime.LaunchedEffect(viewMode == NowPlayingViewMode.LYRICS) {
        LyricsDebug.log("view mode: ${if (viewMode == NowPlayingViewMode.LYRICS) "LYRICS active" else "lyrics inactive"}")
    }

    // Hero dissolve progress (album/visualizer <-> lyrics), hoisted so the slot
    // stays full-width for the WHOLE fade — otherwise leaving lyrics snapped the
    // still-visible lyric surface from full-width back to the square instantly.
    val lyricsProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (viewMode == NowPlayingViewMode.LYRICS) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 450),
        label = "lyricsHeroDissolve",
    )
    // Slot is the full-width lyric rectangle whenever the lyric surface is at all
    // visible (dissolving in or out). derivedStateOf flips only at the threshold.
    val lyricsSlotWide by remember { derivedStateOf { lyricsProgress > 0.001f } }

    CompositionLocalProvider(
        LocalLyricsFx provides lyricsFx,
        LocalLyricsSpectrum provides playerViewModel.spectrumAnalyzer,
        LocalLyricGlyphAnchors provides glyphAnchors.takeIf { lyricsBeatOn },
        LocalBeatPulse provides beatPulse,
        // Tell the lyric glass what's behind it so it can lens the real album
        // tones when the blurred album background is on (Apple-OS style).
        LocalPlayerBackdrop provides PlayerBackdrop(
            blurredArt = blurredBackground,
            dominant = albumColors.dominant,
            secondary = albumColors.vibrant,
        ),
        // The transport buttons' refractive glass parameters (Studio › Player Glass).
        LocalPlayerGlass provides playerGlass,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        MainPlayerScreen(
            state = state,
            isFullscreen = isFullscreenActive,
            formatTime = playerViewModel::formatTime,
            onToggleLike = playerViewModel::toggleLikeCurrentTrack,
            onArtistClick = { artistId ->
                // Source-aware so a local song's artist opens the local artist page.
                navController.openArtist(currentUnified?.sourceType ?: SourceType.API, artistId)
            },
            onSeekCommit = playerViewModel::seekToFraction,
            onPrevious = playerViewModel::skipToPrevious,
            onPlayPause = playerViewModel::togglePlayPause,
            onNext = playerViewModel::skipToNext,
            onLyrics = {
                playerViewModel.setNowPlayingViewMode(
                    if (viewMode == NowPlayingViewMode.LYRICS) NowPlayingViewMode.COVER_ART
                    else NowPlayingViewMode.LYRICS
                )
            },
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
                    onOpenLyricsStudio = { navController.navigate(Screen.LyricsFxStudio.route) },
                    onOpenSettings = { navController.navigate(Screen.Settings.createRoute()) },
                    onGoToArtist = currentTrack?.artist?.id?.let { artistId ->
                        { navController.navigate(Screen.ArtistDetail.createRoute(artistId)) }
                    },
                    onGoToAlbum = currentTrack?.album?.id?.let { albumId ->
                        { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                    },
                )
            },
            hero = { heroModifier ->
                // Manual dissolve between the album art / visualizer and the lyric
                // surface (lyricsProgress is hoisted above). The built-in Crossfade
                // snapped here; an explicit alpha animation is reliable, and it lets
                // the fading art stay a centred square while the lyrics fill the
                // full-width slot.
                // Compose each side only while it is at all visible — derivedStateOf
                // flips at the thresholds, not on every animation frame, so the
                // (expensive) art/visualizer doesn't recompose mid-dissolve.
                val showAlbumHero by remember { derivedStateOf { lyricsProgress < 0.999f } }
                val showLyricsHero by remember { derivedStateOf { lyricsProgress > 0.001f } }
                Box(modifier = heroModifier, contentAlignment = Alignment.Center) {
                    if (showAlbumHero) {
                        val effectiveStyle = if (viewMode == NowPlayingViewMode.VISUALIZER) {
                            PlayerHeroStyle.Visualizer
                        } else {
                            heroStyle
                        }
                        // Keep the art a centred square whenever the slot is the
                        // full-width lyric rectangle (i.e. any time lyrics are on
                        // screen, including the fade-out). Bound it by WIDTH so the
                        // now-taller lyric slot doesn't stretch the (dissolving) art
                        // vertically; otherwise it fills the slot.
                        val artMod = if (lyricsSlotWide) {
                            Modifier.fillMaxWidth().aspectRatio(1f)
                        } else {
                            Modifier.fillMaxSize()
                        }
                        PlayerHero(
                            modifier = artMod.graphicsLayer { alpha = 1f - lyricsProgress },
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
                    }
                    if (showLyricsHero) {
                        // Fx/spectrum/beat locals are provided once around the
                        // whole player (see the route-level provider). Rendered on
                        // top of the art so it fades in over it.
                        LyricsHeroBox(
                            lyrics = lyrics,
                            isLoading = isLyricsLoading,
                            albumColors = albumColors,
                            positionMs = playerViewModel.positionMs,
                            // One element, two states: compact taps expand
                            // (synced lyrics only); expanded line taps seek and
                            // gap taps collapse.
                            onSeekTo = { timeMs ->
                                if (lyricsExpanded) playerViewModel.seekTo(timeMs)
                                else if (lyricsSynced) lyricsExpanded = true
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = lyricsProgress }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = lyricsSynced,
                                ) { lyricsExpanded = !lyricsExpanded },
                        )
                    }
                }
            },
            fxUnderlay = {
                if (beatPulse != null) {
                    LyricsFxLayer(
                        anchors = glyphAnchors,
                        pulse = beatPulse,
                        accent = albumColors.vibrant,
                        fx = lyricsFx,
                    )
                }
            },
            lyricsExpanded = lyricsExpanded,
            // Slot stays the full-width rectangle for the whole dissolve, not just
            // while viewMode==LYRICS, so leaving lyrics doesn't snap it to square.
            lyricsMode = lyricsSlotWide,
            blurredBackground = blurredBackground,
        )
    }
    }
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
            // Cute one-tap Nightcore: 1.10x speed with pitch riding the tempo
            // (preserve-pitch off). Glows pink when active.
            val nightcoreActive = kotlin.math.abs(speed - 1.10f) < 0.01f && !preservePitch
            val nightcorePink = Color(0xFFFF4FD8)
            Surface(
                onClick = {
                    onSpeedChange(1.10f)
                    onPreservePitchChange(false)
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .shadow(
                        elevation = if (nightcoreActive) 20.dp else 10.dp,
                        shape = RoundedCornerShape(percent = 50),
                        ambientColor = nightcorePink,
                        spotColor = nightcorePink,
                        clip = false,
                    ),
                shape = RoundedCornerShape(percent = 50),
                color = if (nightcoreActive) nightcorePink.copy(alpha = 0.92f) else nightcorePink.copy(alpha = 0.14f),
                contentColor = if (nightcoreActive) Color.Black else nightcorePink,
                border = BorderStroke(1.dp, nightcorePink.copy(alpha = if (nightcoreActive) 1f else 0.55f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Nightcore",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(speed - preset) < 0.01f,
                        onClick = { onSpeedChange(preset) },
                        // %.2g rendered 1.25 as "1.2"; use a trimmed decimal so
                        // the chip label matches the value it actually sets.
                        label = { Text(String.format(Locale.US, if (preset == preset.toInt().toFloat()) "%.1fx" else "%.2fx", preset)) },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    remainingMinutes: Int,
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
                    "Playback will pause in $remainingMinutes minute${if (remainingMinutes == 1) "" else "s"}."
                } else {
                    "Sleep timer is off."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
