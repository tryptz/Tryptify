package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import tf.monochrome.android.ui.main.LocalImmersiveFullScreen
import tf.monochrome.android.ui.main.SystemBarsHidden
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openArtist
import tf.monochrome.android.ui.theme.ColorBlend
import tf.monochrome.android.audio.PitchRatio
import kotlin.math.roundToInt
import java.util.Locale
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool

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
    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val miniGlass by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    val currentUnified by playerViewModel.currentUnifiedTrack.collectAsStateWithLifecycle()
    val queue by playerViewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by playerViewModel.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by playerViewModel.isBuffering.collectAsStateWithLifecycle()
    // Held as State, never read with `.value` in this composable: the play head
    // ticks four times a second, and reading it here recomposed the entire
    // player — hero, glass, artwork and all — for a number only the scrubber
    // and the progress ring consume. They read it themselves, further down.
    val positionState = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val durationState = playerViewModel.durationMs.collectAsStateWithLifecycle()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerViewModel.repeatMode.collectAsStateWithLifecycle()
    val isLiked by playerViewModel.isCurrentTrackLiked.collectAsStateWithLifecycle()
    val downloadState by playerViewModel.currentTrackDownloadState.collectAsStateWithLifecycle()
    val isDownloadedRemote by playerViewModel.isCurrentTrackDownloaded.collectAsStateWithLifecycle()
    val isLocalTrack by playerViewModel.isCurrentTrackLocal.collectAsStateWithLifecycle()
    // A local file is already on disk — show it as on-device rather than
    // offering a download that would try to fetch it from the catalog.
    val isDownloaded = isDownloadedRemote || isLocalTrack
    val lyrics by playerViewModel.currentLyrics.collectAsStateWithLifecycle()
    val isLyricsLoading by playerViewModel.isLyricsLoading.collectAsStateWithLifecycle()
    val viewMode by playerViewModel.nowPlayingViewMode.collectAsStateWithLifecycle()
    val blurredBackground by playerViewModel.playerBlurredBackground.collectAsStateWithLifecycle()
    val playbackSpeed by playerViewModel.playbackSpeed.collectAsStateWithLifecycle()
    val preservePitch by playerViewModel.preservePitch.collectAsStateWithLifecycle()
    val pitchSemitones by playerViewModel.pitchSemitones.collectAsStateWithLifecycle()
    val speedUnitSemitones by playerViewModel.speedUnitSemitones.collectAsStateWithLifecycle()
    val compressorEnabled by playerViewModel.compressorEnabled.collectAsStateWithLifecycle()
    val inflatorEnabled by playerViewModel.inflatorEnabled.collectAsStateWithLifecycle()
    val crossfeedEnabled by playerViewModel.crossfeedEnabled.collectAsStateWithLifecycle()
    val autoEqEnabled by playerViewModel.autoEqEnabled.collectAsStateWithLifecycle()
    val systemWideAutoEqEnabled by playerViewModel.systemWideAutoEqEnabled.collectAsStateWithLifecycle()
    val toneControls by playerViewModel.toneControls.collectAsStateWithLifecycle()

    val visualizerSensitivity by playerViewModel.visualizerSensitivity.collectAsStateWithLifecycle()
    val visualizerBrightness by playerViewModel.visualizerBrightness.collectAsStateWithLifecycle()
    val visualizerFullscreen by playerViewModel.visualizerFullscreen.collectAsStateWithLifecycle()
    val visualizerTouchWaveform by playerViewModel.visualizerTouchWaveform.collectAsStateWithLifecycle()
    val visualizerShowFps by playerViewModel.visualizerShowFps.collectAsStateWithLifecycle()
    val visualizerEngineStatus by playerViewModel.visualizerEngineStatus.collectAsStateWithLifecycle()
    val visualizerEngineEnabled by playerViewModel.visualizerEngineEnabled.collectAsStateWithLifecycle()
    val visualizerAutoShuffle by playerViewModel.visualizerAutoShuffle.collectAsStateWithLifecycle()
    val currentVisualizerPreset by playerViewModel.currentVisualizerPreset.collectAsStateWithLifecycle()
    val visualizerPresets by playerViewModel.visualizerPresets.collectAsStateWithLifecycle()
    val visualizerFavoritePresetIds by playerViewModel.visualizerFavoritePresetIds.collectAsStateWithLifecycle()
    val visualizerCompact by playerViewModel.visualizerCompact.collectAsStateWithLifecycle()
    val spectrumBins by playerViewModel.spectrumAnalyzer.spectrumBins.collectAsStateWithLifecycle()
    val spectrumAnalyzerEnabled by playerViewModel.spectrumAnalyzerEnabled.collectAsStateWithLifecycle()
    val spectrumShowOnNowPlaying by playerViewModel.spectrumShowOnNowPlaying.collectAsStateWithLifecycle()
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
    val sleepMinutes by playerViewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
    val sleepRemainingMs by playerViewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()

    // Expanded lyrics: the SAME hero lyric surface grows to full-bleed while
    // MainPlayerScreen collapses the player chrome — no separate overlay.
    // Synced-only: lyrics without timestamps never expand, and losing sync
    // (track change, unsynced source) or leaving lyrics mode collapses.
    val legacyPlayer = tf.monochrome.android.performance.LocalLowPerformance.current.legacyPlayer
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    // The legacy layout has no expanded-lyrics state — it never collapses its
    // chrome — so expansion is disabled there rather than left to flip an
    // invisible flag. Without this the hero still toggled it: nothing moved on
    // screen, but the BackHandler below then swallowed a back press and the
    // player wouldn't close.
    val lyricsCanExpand = lyrics?.isSynced == true && !legacyPlayer
    LaunchedEffect(viewMode, lyricsCanExpand) {
        if (viewMode != NowPlayingViewMode.LYRICS || !lyricsCanExpand) lyricsExpanded = false
    }
    BackHandler(enabled = lyricsExpanded) { lyricsExpanded = false }

    LaunchedEffect(isPlaying) { playerViewModel.setVisualizerPlaybackPaused(!isPlaying) }

    // Surface stream-resolution failures (offline / dead instance) that the
    // ViewModel now reports instead of silently looping.
    val playbackError by playerViewModel.playbackError.collectAsStateWithLifecycle()
    val playbackErrorContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(playbackError) {
        playbackError?.let {
            android.widget.Toast.makeText(playbackErrorContext, it, android.widget.Toast.LENGTH_SHORT).show()
            playerViewModel.clearPlaybackError()
        }
    }

    val lyricsFx by playerViewModel.lyricsFx.collectAsStateWithLifecycle()
    val playerGlass by playerViewModel.playerGlass.collectAsStateWithLifecycle()
    val playerDynamicColor by playerViewModel.playerDynamicColor.collectAsStateWithLifecycle()
    val dynamicColors by playerViewModel.dynamicColors.collectAsStateWithLifecycle()

    val extractedColors = rememberAlbumColors(currentTrack?.coverUrl)
    // Player tint follows album art only when BOTH the master "Dynamic Colors"
    // switch and the player-specific toggle are on — so turning off Dynamic
    // Colors makes the whole player static (background, glow, accents,
    // glass), not just the app-wide theme. Otherwise the theme primary drives
    // the same pipeline.
    val themeAccent = MaterialTheme.colorScheme.primary
    val albumColors = if (dynamicColors && playerDynamicColor) {
        extractedColors
    } else {
        AlbumColors(dominant = themeAccent, vibrant = themeAccent)
    }
    // Background wash and every accent that reads `vibrant` — hero ring,
    // spectrum, glass tint — cross over together, over the "Blend Between
    // Tracks" length. Both used to run on fixed tweens (1800ms and 1300ms),
    // which meant the player finished repainting while a 6s blend was still
    // half the previous track. Linear for the same reason the palette is: it
    // is pacing an audio crossfade, not decorating a tap.
    val blendSeconds by playerViewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val colorTransitionMs by playerViewModel.colorTransitionMs.collectAsStateWithLifecycle()
    val colorBlendMs = tf.monochrome.android.ui.theme.motionMillis(
        ColorBlend.millisFor(blendSeconds, colorTransitionMs)
    )
    // Lets the artwork tell a skip from a song ending; see MorphingCoverArt.
    val userTrackChanges by playerViewModel.userTrackChanges.collectAsStateWithLifecycle()
    val animatedDominant by androidx.compose.animation.animateColorAsState(
        targetValue = albumColors.dominant,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = colorBlendMs,
            easing = androidx.compose.animation.core.LinearEasing,
        ),
        label = "playerBackground",
    )
    val animatedVibrant by androidx.compose.animation.animateColorAsState(
        targetValue = albumColors.vibrant,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = colorBlendMs,
            easing = androidx.compose.animation.core.LinearEasing,
        ),
        label = "playerAccent",
    )
    val blendedColors = AlbumColors(animatedDominant, animatedVibrant)
    val spectrumColor = MaterialTheme.colorScheme.primary

    val isFullscreenActive = viewMode == NowPlayingViewMode.VISUALIZER && visualizerFullscreen
    // OR'd with the app-wide setting so leaving the visualiser doesn't hand the
    // status bar back to someone who asked for full screen everywhere.
    SystemBarsHidden(isFullscreenActive || LocalImmersiveFullScreen.current)
    PlayerSystemBarAppearance(blendedColors.dominant)

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
            onSettingsClick = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) },
            onDismiss = { showPresetSheet = false },
        )
    }
    // The speed panel is NOT called here with its siblings. It is handed to
    // MainPlayerScreen's `overlay` slot below so it renders inside the player's
    // own window, next to the haze source — the only place a pane can actually
    // blur this screen. See SpeedPanel.
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
        isLiveStream = currentUnified?.source is
            tf.monochrome.android.domain.model.PlaybackSource.RadioStream,
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
        albumColors = blendedColors,
        colorBlendMs = colorBlendMs,
        visualizerActive = viewMode == NowPlayingViewMode.VISUALIZER,
        waveformActive = showNpSpectrum,
        compressorEnabled = compressorEnabled,
        inflatorEnabled = inflatorEnabled,
        crossfeedEnabled = crossfeedEnabled,
        autoEqEnabled = autoEqEnabled,
        systemWideAutoEqEnabled = systemWideAutoEqEnabled,
        toneControls = toneControls,
    )

    // Bass-reactive lyrics: one shared pulse (single analyzer stake) drives
    // both the active line's pump and the full-screen glow layer; the line
    // registry carries the active line's screen bounds to that layer, so the
    // glow draws with NO clipping ancestor and can never be cut by a canvas.
    val glyphAnchors = remember { LyricGlyphAnchors() }
    val albumArtAnchor = remember { LyricGlyphAnchors() }
    val lyricsBeatOn = viewMode == NowPlayingViewMode.LYRICS && lyricsFx.bassReact > 0.01f
    // The same reactive glow can bloom behind the album cover in cover-art view
    // when the Studio toggle is on. Cover-art and lyrics views are mutually
    // exclusive, so both share ONE pulse / analyzer stake — never two FFT taps.
    val albumGlowOn = lyricsFx.glowBehindArt && lyricsFx.bassReact > 0.01f &&
        viewMode == NowPlayingViewMode.COVER_ART &&
        // Only MainPlayerScreen is handed the fxUnderlay that draws this glow.
        // Without the guard the legacy layout still staked the FFT tap and woke
        // on every frame to compute a pulse nothing would ever draw — the exact
        // cost the legacy player exists to avoid.
        !legacyPlayer
    val beatOn = lyricsBeatOn || albumGlowOn
    val beatPulse: androidx.compose.runtime.State<Float>? =
        if (beatOn) rememberBassPulse(playerViewModel.spectrumAnalyzer, lyricsFx) else null
    androidx.compose.runtime.LaunchedEffect(beatOn) {
        LyricsDebug.log("beat engine ${if (beatOn) "acquired (FFT analyzer staked)" else "released"}")
        if (!beatOn) { glyphAnchors.reset(); albumArtAnchor.reset() }
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
            dominant = blendedColors.dominant,
            secondary = blendedColors.vibrant,
        ),
        // The transport buttons' refractive glass parameters (Studio › Player Glass).
        LocalPlayerGlass provides playerGlass,
    ) {
    // topBar and hero are content, not chrome — the artwork, lyrics, queue and
    // visualizer are the same whichever layout is drawing around them. Hoisted
    // into slots so both the current and the legacy screen are handed one copy
    // instead of the hero being forked along with the chrome.
    val topBarSlot: @Composable () -> Unit = {
        PlayerTopBar(
            speedLabel = state.speedLabel,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            isDownloaded = isDownloaded,
            downloadState = downloadState,
            heroStyle = heroStyle,
            onCollapse = { navController.popBackStack() },
            onOutputClick = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) },
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
            onOpenEqualizer = { navController.navigateTool(Screen.Equalizer) },
            onOpenLyricsStudio = { navController.navigateTool(Screen.LyricsFxStudio) },
            onOpenSettings = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) },
            onGoToArtist = currentTrack?.artist?.id?.let { artistId ->
                { navController.navigateSafe(Screen.ArtistDetail.createRoute(artistId)) }
            },
            onGoToAlbum = currentTrack?.album?.id?.let { albumId ->
                { navController.navigateSafe(Screen.AlbumDetail.createRoute(albumId)) }
            },
        )
    }
    val heroSlot: @Composable (Modifier) -> Unit = { heroModifier ->
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

        // Horizontal swipe across the hero skips tracks, matching the
        // gesture (and the 50px threshold) the mini player already uses.
        //
        // detectHorizontalDragGestures, not detectDragGestures: the
        // latter consumes vertical drags too, which would swallow the
        // pull-up that opens the audio-tools sheet and the lyric list's
        // own scrolling. Suppressed entirely in visualizer mode, where
        // horizontal drags belong to the touch waveform.
        val swipeSkipEnabled = viewMode != NowPlayingViewMode.VISUALIZER
        // Art offset, in px, driven by the finger and then animated out
        // and back in. An Animatable rather than a plain float so the
        // release animation and a mid-flight new drag can't fight: a
        // fresh snapTo cancels whatever animation is running.
        val heroOffset = remember { androidx.compose.animation.core.Animatable(0f) }
        val heroScope = rememberCoroutineScope()
        val trackSwipe = Modifier.pointerInput(swipeSkipEnabled) {
            if (!swipeSkipEnabled) return@pointerInput
            val width = size.width.toFloat().coerceAtLeast(1f)
            // Commit distance. Compose's own touch slop only decides
            // when a drag *starts*; this is how far it has to travel
            // before it counts as a skip. Scaled off the art's width
            // (~22%) rather than a fixed pixel count, so it asks for the
            // same proportion of a gesture on any screen density.
            val skipThreshold = width * 0.22f
            detectHorizontalDragGestures(
                onDragEnd = {
                    heroScope.launch {
                        val dx = heroOffset.value
                        // Carry the outgoing art the rest of the way off,
                        // switch track, then bring the incoming one in
                        // from the opposite edge — so the direction of
                        // travel matches the direction of the swipe.
                        when {
                            dx < -skipThreshold -> {
                                heroOffset.animateTo(-width, tween(140))
                                playerViewModel.skipToNext()
                                heroOffset.snapTo(width)
                                heroOffset.animateTo(0f, tween(260))
                            }
                            dx > skipThreshold -> {
                                heroOffset.animateTo(width, tween(140))
                                playerViewModel.skipToPrevious()
                                heroOffset.snapTo(-width)
                                heroOffset.animateTo(0f, tween(260))
                            }
                            // Under the threshold: spring back, no skip.
                            else -> heroOffset.animateTo(0f, spring())
                        }
                    }
                },
                onDragCancel = { heroScope.launch { heroOffset.animateTo(0f, spring()) } },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    heroScope.launch { heroOffset.snapTo(heroOffset.value + amount) }
                },
            )
        }

        Box(
            modifier = heroModifier.then(trackSwipe),
            contentAlignment = Alignment.Center,
        ) {
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
                val artMod = (if (lyricsSlotWide) {
                    Modifier.fillMaxWidth().aspectRatio(1f)
                } else {
                    Modifier.fillMaxSize()
                }).let { base ->
                    // Report the cover's screen bounds so the reactive glow
                    // can bloom behind it (only while that toggle is active).
                    if (albumGlowOn) base.onGloballyPositioned { coords ->
                        albumArtAnchor.lineCenter = coords.boundsInRoot().center
                        albumArtAnchor.lineHalf =
                            Size(coords.size.width / 2f, coords.size.height / 2f)
                    } else base
                }
                PlayerHero(
                    modifier = artMod.graphicsLayer {
                        // Follows the finger, then rides the release
                        // animation out and the next cover in.
                        translationX = heroOffset.value
                        // Fade with distance so the swap happens while
                        // the art is already dim, hiding the instant at
                        // which the cover actually changes. Read in the
                        // draw phase, so a drag costs no recomposition.
                        val travelled =
                            (abs(heroOffset.value) / size.width.coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                        alpha = (1f - lyricsProgress) * (1f - travelled * 0.85f)
                    },
                    style = effectiveStyle,
                    isFullscreen = isFullscreenActive,
                    track = currentTrack,
                    isPlaying = isPlaying,
                    progress = {
                        val d = durationState.value
                        if (d > 0) (positionState.value.toFloat() / d).coerceIn(0f, 1f) else 0f
                    },
                    albumColors = blendedColors,
                    blendMillis = colorBlendMs,
                    userTrackChanges = userTrackChanges,
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
                    albumColors = blendedColors,
                    positionMs = playerViewModel.positionMs,
                    // One element, two states: compact taps expand
                    // (synced lyrics only); expanded line taps seek and
                    // gap taps collapse.
                    onSeekTo = { timeMs ->
                        if (lyricsExpanded) playerViewModel.seekTo(timeMs)
                        else if (lyricsCanExpand) lyricsExpanded = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lyricsProgress }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = lyricsCanExpand,
                        ) { lyricsExpanded = !lyricsExpanded },
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (legacyPlayer) {
            // Settings › System › Performance › "Legacy player" — the pre-glass
            // layout, recovered from history. Same state, same slots; no shader,
            // no frame clock, no gravity sensor. The blurred cover layer and the
            // beat-FX underlay are not passed because neither existed then.
            tf.monochrome.android.ui.player.legacy.LegacyMainPlayerScreen(
                state = state,
                positionState = positionState,
                durationState = durationState,
                isFullscreen = isFullscreenActive,
                formatTime = playerViewModel::formatTime,
                onToggleLike = playerViewModel::toggleLikeCurrentTrack,
                onArtistClick = { artistId ->
                    navController.openArtist(currentUnified?.sourceType ?: SourceType.API, artistId)
                },
                onSeekCommit = playerViewModel::seekToFraction,
                onPrevious = playerViewModel::skipToPrevious,
                onRewind10 = playerViewModel::rewind10,
                onPlayPause = playerViewModel::togglePlayPause,
                onForward10 = playerViewModel::forward10,
                onNext = playerViewModel::skipToNext,
                onTimer = { showSleepSheet = true },
                onMixer = { navController.navigateTool(Screen.Mixer) },
                onPlaylist = { showQueueSheet = true },
                onOutput = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) },
                onSound = { navController.navigateTool(Screen.Equalizer) },
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
                onCrossfeedToggle = playerViewModel::setCrossfeedEnabled,
                onAutoEqToggle = playerViewModel::setAutoEqEnabled,
                onLyrics = {
                    playerViewModel.setNowPlayingViewMode(
                        if (viewMode == NowPlayingViewMode.LYRICS) NowPlayingViewMode.COVER_ART
                        else NowPlayingViewMode.LYRICS
                    )
                },
                topBar = topBarSlot,
                hero = heroSlot,
            )
        } else {
            MainPlayerScreen(
                miniGlass = miniGlass,
                state = state,
                positionState = positionState,
                durationState = durationState,
                isFullscreen = isFullscreenActive,
                formatTime = playerViewModel::formatTime,
                onToggleLike = playerViewModel::toggleLikeCurrentTrack,
                onArtistClick = { artistId, artistName ->
                    // Source-aware so a local song's artist opens the local artist
                    // page; the name rides along because a catalogue row can
                    // arrive with an id of 0 and nothing but what it is called.
                    navController.openArtist(
                        currentUnified?.sourceType ?: SourceType.API, artistId, artistName,
                    )
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
                onMixer = { navController.navigateTool(Screen.Mixer) },
                onPlaylist = { showQueueSheet = true },
                onOutput = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) },
                onSound = { navController.navigateTool(Screen.Equalizer) },
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
                onCrossfeedToggle = playerViewModel::setCrossfeedEnabled,
                onCompressorOpen = { navController.navigateTool(Screen.Oxford, Screen.Oxford.createRoute(tab = 0)) },
                onInflatorOpen = { navController.navigateTool(Screen.Oxford, Screen.Oxford.createRoute(tab = 1)) },
                onCrossfeedOpen = { navController.navigateTool(Screen.Crossfeed) },
                onAutoEqToggle = playerViewModel::setAutoEqEnabled,
                onSystemWideAutoEqToggle = playerViewModel::setSystemWideAutoEq,
                onToneControlsChange = playerViewModel::setToneControls,
                topBar = topBarSlot,
                hero = heroSlot,
                fxUnderlay = {
                    if (beatPulse != null) {
                        // Cover-art view uses the album anchor with an edge-hugging
                        // bloom; lyrics view keeps the line-anchored glow. Only one is
                        // ever active (the views are mutually exclusive).
                        LyricsFxLayer(
                            anchors = if (albumGlowOn) albumArtAnchor else glyphAnchors,
                            pulse = beatPulse,
                            accent = albumColors.vibrant,
                            fx = lyricsFx,
                            edgeHug = albumGlowOn,
                        )
                    }
                },
                lyricsExpanded = lyricsExpanded,
                // Slot stays the full-width rectangle for the whole dissolve, not just
                // while viewMode==LYRICS, so leaving lyrics doesn't snap it to square.
                lyricsMode = lyricsSlotWide,
                blurredBackground = blurredBackground,
                overlay = {
                    SpeedPanel(
                        visible = showSpeedSheet,
                        speed = playbackSpeed,
                        preservePitch = preservePitch,
                        pitchSemitones = pitchSemitones,
                        onPitchSemitonesChange = playerViewModel::setPitchSemitones,
                        speedUnitSemitones = speedUnitSemitones,
                        onSpeedUnitChange = playerViewModel::setSpeedUnitSemitones,
                        onSpeedChange = playerViewModel::setPlaybackSpeed,
                        onPreservePitchChange = playerViewModel::setPreservePitch,
                        onDismiss = { showSpeedSheet = false },
                    )
                },
            )
        }
        // The legacy layout has no `overlay` slot and no haze source of its own,
        // so the panel hangs here instead. LocalPlayerHaze is null on that path
        // and GlassPanel falls back to plain translucent glass — the same pane
        // the modal sheet used to give everyone.
        if (legacyPlayer) {
            SpeedPanel(
                visible = showSpeedSheet,
                speed = playbackSpeed,
                preservePitch = preservePitch,
                pitchSemitones = pitchSemitones,
                onPitchSemitonesChange = playerViewModel::setPitchSemitones,
                speedUnitSemitones = speedUnitSemitones,
                onSpeedUnitChange = playerViewModel::setSpeedUnitSemitones,
                onSpeedChange = playerViewModel::setPlaybackSpeed,
                onPreservePitchChange = playerViewModel::setPreservePitch,
                onDismiss = { showSpeedSheet = false },
            )
        }
    }
    }
}


/**
 * Hand the status and navigation bars to the player's own background.
 *
 * The app sets their appearance once, from the theme's background. That is
 * right for every other screen and wrong for this one: the player does not use
 * the theme's background, it paints the album colour washed into black. On a
 * light theme the system went on drawing dark icons, and the home indicator
 * vanished into the bottom of the gradient.
 *
 * Each bar is judged against the pixels actually under it rather than one
 * verdict for the screen, because the two ends of [dynamicPlayerBackground] are
 * nothing like each other: the top carries the album wash, the bottom is flat
 * [PlayerDesignTokens.BackgroundBlack]. Both stops are translucent, so they are
 * composited over that same black before being measured — reading the stop
 * colour neat would call a half-transparent wash far brighter than it lands.
 *
 * The blurred-artwork background, when it is on, only ever darkens both ends
 * further (black at 0.58 over the top, 0.72 over the bottom), so the gradient
 * alone is the bright case and deciding from it cannot leave a bar too light.
 *
 * Restored on the way out rather than left set, or backing out to a light theme
 * would keep the player's icons and the rest of the app would lose its own.
 */
@Composable
private fun PlayerSystemBarAppearance(albumDominant: Color) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window

    val ground = PlayerDesignTokens.BackgroundBlack
    val wash = lerp(albumDominant, Color.Black, 0.5f)
    // `lightBars` in the platform's sense: dark icons, for a light background.
    val lightStatus = lerp(ground, wash, wash.alpha * 0.5f).luminance() > 0.5f
    val lightNav = ground.luminance() > 0.5f

    DisposableEffect(lightStatus, lightNav, window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val hadLightStatus = controller?.isAppearanceLightStatusBars
        val hadLightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = lightStatus
        controller?.isAppearanceLightNavigationBars = lightNav
        onDispose {
            hadLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
            hadLightNav?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

/**
 * Playback speed, on glass that actually frosts the player behind it.
 *
 * This was a [ModalBottomSheet], and that is why it never hazed. A modal sheet
 * is its own window; the player's backdrop was captured into a layer belonging
 * to the window behind it, and a haze effect cannot sample a layer from another
 * window — handing the state across yields a pane frosting a picture it cannot
 * read, which paints its own base colour and reads as a solid slab. There is no
 * setting that fixes that; the pane has to move.
 *
 * So it lives in the player's window now, handed to [MainPlayerScreen]'s
 * `overlay` slot, where it is a sibling of the haze source exactly as the
 * audio-tools sheet is, and [LocalPlayerHaze] is a real backdrop to blur.
 *
 * The scrim, the slide and the swipe are the price of leaving [ModalBottomSheet]
 * behind. The panel stays mounted while [visible] is false so the exit animation
 * has something to play on — dropping it the instant it is dismissed would make
 * it vanish rather than leave.
 *
 * **Swipe to close.** A pane that arrives by sliding up from the bottom edge is
 * expected to leave by being pushed back down, and this one could only be closed
 * by the scrim or by Back — on a tall phone the scrim is a thin strip at the top
 * of the screen, which is a long reach for the gesture the thumb is already
 * making. [dragY] follows the finger downward (never up: there is nothing above
 * to reveal), the scrim thins out with it so the player shows through as the
 * panel goes, and letting go past a third of the panel's height, or with any
 * real downward flick, dismisses. Anything short of that springs back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.SpeedPanel(
    visible: Boolean,
    speed: Float,
    preservePitch: Boolean,
    pitchSemitones: Float,
    onPitchSemitonesChange: (Float) -> Unit,
    speedUnitSemitones: Boolean,
    onSpeedUnitChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPreservePitchChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Ahead of the player's own Back handling while the panel is up, and out of
    // the way entirely when it is not.
    BackHandler(enabled = visible) { onDismiss() }

    val dragScope = rememberCoroutineScope()
    // How far the finger has pushed the panel down, in pixels. An Animatable
    // rather than a plain float so the spring back has something to run on.
    val dragY = remember { Animatable(0f) }
    var panelHeight by remember { mutableFloatStateOf(0f) }
    // Reset on the way IN, not on the way out: dismissing mid-drag should let
    // the exit slide continue from wherever the finger left the panel, and only
    // the next opening needs it flush with the bottom edge again.
    LaunchedEffect(visible) { if (visible) dragY.snapTo(0f) }
    val dragState = rememberDraggableState { delta ->
        dragScope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Draw-phase read, so thinning the scrim under the drag costs no
                // recomposition of anything behind it.
                .graphicsLayer {
                    alpha = if (panelHeight > 0f) {
                        (1f - dragY.value / panelHeight).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                }
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(
            modifier = Modifier
                .onSizeChanged { panelHeight = it.height.toFloat() }
                .graphicsLayer { translationY = dragY.value },
        ) {
        GlassPanel(
            // The real thing at last: the player's background layer, which this
            // pane is a sibling of rather than a descendant.
            hazeState = LocalPlayerHaze.current,
            // The mini player's material, like every other floating pane in the
            // app that isn't the transport itself.
            glass = LocalMiniPlayerGlass.current,
            avoidNavigationBar = false,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The swipe lives on the content, not on the box around the
                // panel. [GlassPanel] floors its own bottom sibling that
                // consumes every pointer event the panel's children did not
                // want, so a gesture handler outside the panel is handed
                // nothing but already-consumed changes and never crosses touch
                // slop. Inside, it sits above that backstop and is hit first.
                //
                // The sliders below are unaffected: they claim horizontal
                // movement and consume it, and this claims vertical, so
                // whichever way the finger goes first takes the gesture.
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val far = panelHeight > 0f && dragY.value > panelHeight * 0.3f
                        // Velocity is px/s and positive downward. A flick closes
                        // from anywhere; a slow drag has to clear the distance.
                        if (far || velocity > 900f) {
                            onDismiss()
                        } else {
                            dragY.animateTo(0f, spring(stiffness = 400f))
                        }
                    },
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The panel takes its accents from the active theme. These were
            // PlayerGlowMint and a fixed magenta, which read as two neon
            // imports on every theme that isn't dark-and-cool — mint sitting
            // on a warm amber panel being the case that prompted this.
            val speedAccent = MaterialTheme.colorScheme.primary
            val muted = MaterialTheme.colorScheme.onSurfaceVariant

            // Grab bar. Half affordance, half instruction: the swipe below is
            // invisible without it, and this is the shape every sheet on the
            // platform uses to say "push me down".
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .background(muted.copy(alpha = 0.45f), RoundedCornerShape(percent = 50)),
            )

            // Title, readout, reset. The panel used to offer four ways to set
            // the same number at once — a slider, a semitone stepper, a row of
            // five presets and a Nightcore pill — stacked over a second engine
            // with a stepper of its own, and the whole thing ran most of the
            // screen. One control per unit now: the multiplier is a slider,
            // semitones are a stepper, and this row says where both stand.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    tint = speedAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Speed",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                // Whichever unit is selected leads; the other trails in small
                // type, because the two answer different questions — "how much
                // faster" and "how much higher" — and one control drives both.
                Text(
                    text = if (speedUnitSemitones) {
                        "${PitchRatio.formatSemitones(PitchRatio.nearestSemitone(speed))} st"
                    } else {
                        String.format(Locale.US, "%.2fx", speed)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = speedAccent,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (speedUnitSemitones) {
                        String.format(Locale.US, "%.2fx", speed)
                    } else if (PitchRatio.isOnSemitone(speed)) {
                        "${PitchRatio.formatSemitones(PitchRatio.nearestSemitone(speed))} st"
                    } else {
                        String.format(Locale.US, "%+.2f st", PitchRatio.semitonesFor(speed))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                // The reset was a "Reset to 1.0x" text button on a line of its
                // own. It is one tap either way, and as an icon it costs the
                // panel nothing — disabled at 1.0x, so the row does not reflow
                // when there is nothing to undo.
                IconButton(
                    onClick = { onSpeedChange(1f) },
                    enabled = abs(speed - 1f) > 0.001f,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset speed to 1.0x",
                        tint = speedAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Unit toggle, and the one preset worth a button. Not just a
            // relabelling: in semitones the panel steps whole intervals at
            // exact 2^(n/12) ratios, so every value it can reach is in tune;
            // in multiplier units the slider stays continuous, for the speeds
            // that are not intervals at all.
            //
            // Nightcore rides on the same line rather than filling one with a
            // glowing pill: 1.10x with pitch following the tempo. The
            // segmented buttons drop their selected-state checkmark to make
            // room — the filled segment already says which unit is live, and
            // the check was 24dp of nothing on a row that now has to fit
            // three controls on a 360dp screen.
            val nightcoreActive = abs(speed - 1.10f) < 0.01f && !preservePitch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = !speedUnitSemitones,
                        onClick = { onSpeedUnitChange(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = {},
                        label = { Text("Multiplier", maxLines = 1) },
                    )
                    SegmentedButton(
                        selected = speedUnitSemitones,
                        onClick = { onSpeedUnitChange(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = {},
                        label = { Text("Semitones", maxLines = 1) },
                    )
                }
                FilterChip(
                    selected = nightcoreActive,
                    onClick = {
                        onSpeedChange(1.10f)
                        onPreservePitchChange(false)
                    },
                    label = { Text("Nightcore", maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            // One control, chosen by the unit. Semitones step exactly, because
            // that is the only way to hit an interval by hand; the multiplier
            // slides, because every value between two intervals is a real
            // speed there.
            if (speedUnitSemitones) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StepButton(
                        label = "-1 st",
                        accent = speedAccent,
                        modifier = Modifier.weight(1f),
                        onClick = { onSpeedChange(PitchRatio.step(speed, -1)) },
                    )
                    StepButton(
                        label = "+1 st",
                        accent = speedAccent,
                        modifier = Modifier.weight(1f),
                        onClick = { onSpeedChange(PitchRatio.step(speed, 1)) },
                    )
                }
            } else {
                Slider(
                    value = speed,
                    // Was Math.round(it * 100f) / 100f, which quantised the ratio
                    // to a 0.01 grid — up to 13.5 cents off an equal-tempered
                    // interval. Full precision now, snapped onto an exact
                    // semitone only when the drag already lands near one.
                    onValueChange = { onSpeedChange(PitchRatio.snap(it)) },
                    valueRange = PitchRatio.MIN_SPEED..PitchRatio.MAX_SPEED,
                    colors = SliderDefaults.colors(
                        thumbColor = speedAccent,
                        activeTrackColor = speedAccent,
                    ),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
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
                        color = muted,
                    )
                }
                Switch(
                    checked = preservePitch,
                    onCheckedChange = onPreservePitchChange,
                )
            }

            // Transposition without tempo. A different engine from the speed
            // control above: that one resamples (exact ratio, tempo follows),
            // this runs a phase vocoder (tempo stays put, and pitch lands
            // within 0.18 Hz -- the analysis block is sized for that). It costs
            // about 350 ms of latency, so it is only engaged off zero.
            //
            // The rule separates the two engines; the whole section is one row
            // now — label, readout, both steppers — where it used to be a
            // header and a stepper row of its own, which made the panel read
            // as one long list with the speed control repeated at the bottom.
            HorizontalDivider(color = muted.copy(alpha = 0.18f))
            // Six dp of gap and a readout at its natural width, not a weighted
            // one: label, value, both steppers and the reset have to share a
            // 320dp content width on a small phone, and "-24 st" given the
            // leftovers would ellipsize rather than push the row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = speedAccent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Pitch",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${PitchRatio.formatSemitones(pitchSemitones.roundToInt())} st",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = speedAccent,
                )
                StepButton(
                    label = "-1 st",
                    accent = speedAccent,
                    onClick = {
                        onPitchSemitonesChange((pitchSemitones.roundToInt() - 1).coerceAtLeast(-24).toFloat())
                    },
                )
                StepButton(
                    label = "+1 st",
                    accent = speedAccent,
                    onClick = {
                        onPitchSemitonesChange((pitchSemitones.roundToInt() + 1).coerceAtMost(24).toFloat())
                    },
                )
                IconButton(
                    onClick = { onPitchSemitonesChange(0f) },
                    enabled = pitchSemitones != 0f,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset pitch",
                        tint = speedAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        }
        }
    }
}

/**
 * One press of the semitone steppers, on both engines.
 *
 * A bordered pill rather than the bare [TextButton] these were: as plain text
 * they read as links in a panel that already had several, with nothing to say
 * they were the buttons that move the value. The border is the accent at low
 * alpha so they belong to the control above them without competing with it.
 *
 * The speed pair takes a weight so the two of them split the row; the pitch
 * pair sits at its intrinsic width beside the readout.
 */
@Composable
private fun StepButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
