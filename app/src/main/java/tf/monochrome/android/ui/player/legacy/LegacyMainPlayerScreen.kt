package tf.monochrome.android.ui.player.legacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.devedit.DevEditable
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.player.DynamicAlbumGlow
import tf.monochrome.android.ui.player.MainPlayerUiState
import tf.monochrome.android.ui.player.PlayerDesignTokens
import tf.monochrome.android.ui.player.dynamicPlayerBackground
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

// Vertical drag distance (px) that commits a swipe-up / swipe-down on the
// audio-tools panel.
private const val SwipeThresholdPx = 48f

/**
 * The player as it looked before liquid glass — Settings › System ›
 * Performance › "Legacy player".
 *
 * Recovered from the pre-glass tree rather than written fresh, so it is the
 * real old design and not an impression of it: a plain Material 3 `Slider` for
 * the scrubber instead of the glass thermometer, `FilledIconButton` transport
 * instead of shader-lit chrome, and a swipe-up audio-tools panel instead of the
 * draggable glass sheet. Nothing here touches `RuntimeShader`, the gravity
 * sensor, or the shared frame clock.
 *
 * It takes the same [MainPlayerUiState] and the same `topBar` / `hero` slots as
 * the current screen, so `MainPlayerRoute` supplies both from one place and the
 * hero content — artwork, lyrics, queue, visualizer — is shared rather than
 * frozen at the old version.
 *
 * The glass on its own panels is the *old* `Modifier.liquidGlass` (Haze
 * glassmorphism, which predates the shader and is part of this design). It is
 * left in deliberately: "Remove liquid glass" flattens it through the shared
 * modifier, so the two switches compose instead of overlapping.
 */
@Composable
fun LegacyMainPlayerScreen(
    state: MainPlayerUiState,
    // As on the current screen, kept as State rather than fields on the UI
    // state: they tick four times a second, and reading them here would rebuild
    // the whole player — hero included — for a number only the scrubber uses.
    positionState: State<Long>,
    durationState: State<Long>,
    isFullscreen: Boolean,
    formatTime: (Long) -> String,
    onToggleLike: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onSeekCommit: (Float) -> Unit,
    onPrevious: () -> Unit,
    onRewind10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onNext: () -> Unit,
    onTimer: () -> Unit,
    onMixer: () -> Unit,
    onPlaylist: () -> Unit,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onVisualizer: () -> Unit,
    onWaveform: () -> Unit,
    onCompressorToggle: (Boolean) -> Unit,
    onInflatorToggle: (Boolean) -> Unit,
    // Effects the player has grown since this layout was retired. Carried
    // through so "Legacy player" is an older look, not a feature downgrade.
    onCrossfeedToggle: (Boolean) -> Unit,
    onAutoEqToggle: (Boolean) -> Unit,
    onLyrics: () -> Unit,
    topBar: @Composable () -> Unit,
    hero: @Composable (Modifier) -> Unit,
    /** The USB DAC's volume bar in exclusive mode, else null; see MainPlayerScreen. */
    dacVolume: (@Composable () -> Unit)? = null,
    /** The hero is the lyric surface (or dissolving to or from it): give it the whole region. */
    lyricsMode: Boolean = false,
) {
    val accent = state.albumColors.vibrant
    var statusExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(dynamicPlayerBackground(state.albumColors.dominant)),
    ) {
        DynamicAlbumGlow(state.albumColors.dominant)

        if (isFullscreen) {
            hero(Modifier.fillMaxSize())
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = PlayerDesignTokens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DevEditable("legacy_topBar", Modifier.fillMaxWidth()) { topBar() }

            Spacer(Modifier.height(12.dp))
            // The hero takes every dp the controls do not, as on the current
            // player: the artwork is the largest square that fits, and the
            // lyrics get the whole region, edge to edge. Bound to the smaller
            // side so a full-width square can never overflow its slot into
            // the track info below it.
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                if (lyricsMode) {
                    // requiredWidth overrides the padded slot, so lines reach
                    // the screen edges, as on the current player.
                    hero(
                        Modifier
                            .requiredWidth(maxWidth + PlayerDesignTokens.ScreenPadding * 2)
                            .height(maxHeight),
                    )
                } else {
                    DevEditable("legacy_hero", Modifier) { hero(Modifier.size(side)) }
                }
            }

            Spacer(Modifier.height(20.dp))
            DevEditable("legacy_trackInfo", Modifier.fillMaxWidth()) {
                PlayerTrackInfo(
                    track = state.track,
                    isLiked = state.isLiked,
                    accent = accent,
                    onToggleLike = onToggleLike,
                    onArtistClick = { state.track?.artist?.id?.let(onArtistClick) },
                )
            }

            Spacer(Modifier.height(16.dp))
            DevEditable("legacy_progress", Modifier.fillMaxWidth()) {
                // Own composable so the 4 Hz play head is read here and nowhere
                // else — the hero and the transport row above it never see it.
                LegacyProgressSection(
                    positionState = positionState,
                    durationState = durationState,
                    centerLabel = state.queueLabel.ifBlank { state.audioQuality.orEmpty() },
                    accent = accent,
                    formatTime = formatTime,
                    onSeekCommit = onSeekCommit,
                )
            }

            Spacer(Modifier.height(20.dp))
            DevEditable("legacy_transport", Modifier.fillMaxWidth()) {
                LegacyPlayerTransportControls(
                    isPlaying = state.isPlaying,
                    accent = accent,
                    onPrevious = onPrevious,
                    onRewind10 = onRewind10,
                    onPlayPause = onPlayPause,
                    onForward10 = onForward10,
                    onNext = onNext,
                )
            }

            if (dacVolume != null) {
                Spacer(Modifier.height(12.dp))
                DevEditable("legacy_dacVolume", Modifier.fillMaxWidth()) { dacVolume() }
            }

            Spacer(Modifier.height(20.dp))
            DevEditable("legacy_actionDock", Modifier.fillMaxWidth()) {
                LegacyPlayerActionDock(
                    accent = accent,
                    lyricsActive = state.viewMode == NowPlayingViewMode.LYRICS,
                    onLyrics = onLyrics,
                    onTimer = onTimer,
                    onMixer = onMixer,
                    onPlaylist = onPlaylist,
                )
            }

            // Clearance for the audio-tools pull strip (an overlay, so it
            // reserves no height of its own) and nothing more, as on the
            // current player. This used to be a weighted spacer, which split
            // the free height with the hero: the controls floated up the
            // screen over a band of dead space, and the artwork shrank.
            Spacer(Modifier.height(56.dp))
        }

        // Thin bottom-edge pull strip — the only element that captures the
        // audio-tools pull gesture. Everything above it stays interactive when
        // the panel isn't pulled up. Hidden once the panel is open.
        if (!statusExpanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(44.dp)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { _, dy -> total += dy },
                            onDragEnd = { if (total < -SwipeThresholdPx) statusExpanded = true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                SwipeUpHandle(onClick = { statusExpanded = true })
            }
        }

        // Scrim behind the overlay.
        AnimatedVisibility(
            visible = statusExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { statusExpanded = false },
                    ),
            )
        }

        // Audio-tools overlay panel, sliding up over the player with a shadow.
        AnimatedVisibility(
            visible = statusExpanded,
            enter = slideInVertically(animationSpec = tween(280)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(animationSpec = tween(240)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            StatusOverlayPanel(
                state = state,
                onOutput = onOutput,
                onSound = onSound,
                onSpeed = onSpeed,
                onMixer = onMixer,
                onVisualizer = onVisualizer,
                onWaveform = onWaveform,
                onLyrics = onLyrics,
                onCompressorToggle = onCompressorToggle,
                onInflatorToggle = onInflatorToggle,
                onCrossfeedToggle = onCrossfeedToggle,
                onAutoEqToggle = onAutoEqToggle,
                onDismiss = { statusExpanded = false },
            )
        }
    }
}

/**
 * Scrubber row, isolated so the play head recomposes only this much of the
 * screen. The transient scrub position is owned here for the same reason: while
 * a drag is in flight the slider shows the finger, not the player.
 */
@Composable
private fun LegacyProgressSection(
    positionState: State<Long>,
    durationState: State<Long>,
    centerLabel: String,
    accent: Color,
    formatTime: (Long) -> String,
    onSeekCommit: (Float) -> Unit,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val durationMs = durationState.value
    // Clamped to the track: at a boundary the play head can briefly read past
    // the duration, and an unclamped label shows an elapsed time longer than
    // the total.
    val positionMs = positionState.value.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val playedFraction = if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f
    val displayFraction = if (isSeeking) seekPosition else playedFraction
    val displayPositionMs =
        if (isSeeking) (seekPosition * durationMs).toLong() else positionMs

    LegacyPlayerProgress(
        fraction = displayFraction,
        elapsedLabel = formatTime(displayPositionMs),
        totalLabel = formatTime(durationMs),
        centerLabel = centerLabel,
        accent = accent,
        onSeek = { value ->
            isSeeking = true
            seekPosition = value
        },
        onSeekFinished = { value ->
            onSeekCommit(value)
            isSeeking = false
        },
    )
}

@Composable
private fun SwipeUpHandle(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(999.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.audio_tools),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun StatusOverlayPanel(
    state: MainPlayerUiState,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onMixer: () -> Unit,
    onVisualizer: () -> Unit,
    onWaveform: () -> Unit,
    onLyrics: () -> Unit,
    onCompressorToggle: (Boolean) -> Unit,
    onInflatorToggle: (Boolean) -> Unit,
    onCrossfeedToggle: (Boolean) -> Unit,
    onAutoEqToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = state.albumColors.vibrant
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 32.dp, shape = shape, clip = false)
            .liquidGlass(shape = shape, tintAlpha = 0.22f, borderAlpha = 0.10f)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = { if (total > SwipeThresholdPx) onDismiss() },
                )
            },
        shape = shape,
        color = PlayerDesignTokens.BackgroundBlack.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = PlayerDesignTokens.ScreenPadding)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(999.dp)),
            )
            LegacyPlayerStatusGrid(
                accent = accent,
                outputLabel = state.outputLabel,
                soundLabel = state.soundLabel,
                speedLabel = state.speedLabel,
                mixerLabel = "FX",
                onOutput = onOutput,
                onSound = onSound,
                onSpeed = onSpeed,
                onMixer = onMixer,
            )

            // Monitoring row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OverlayAction(Icons.Default.Animation, stringResource(R.string.visualizer), accent, state.visualizerActive, onVisualizer)
                OverlayAction(Icons.Default.GraphicEq, stringResource(R.string.waveform), accent, state.waveformActive, onWaveform)
                OverlayAction(
                    Icons.Default.Lyrics,
                    stringResource(R.string.mode_lyrics),
                    accent,
                    state.viewMode == NowPlayingViewMode.LYRICS,
                    onLyrics,
                )
            }

            // Effects toggles
            ToggleRow(stringResource(R.string.compressor), stringResource(R.string.legacy_oxford_dynamics), state.compressorEnabled, accent, onCompressorToggle)
            ToggleRow("Inflator", stringResource(R.string.legacy_oxford_loudness), state.inflatorEnabled, accent, onInflatorToggle)
            ToggleRow(stringResource(R.string.crossfeed), stringResource(R.string.legacy_headphone_stage), state.crossfeedEnabled, accent, onCrossfeedToggle)
            ToggleRow("AutoEQ", stringResource(R.string.legacy_headphone_correction), state.autoEqEnabled, accent, onAutoEqToggle)
        }
    }
}

@Composable
private fun RowScope.OverlayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) accent else Color.White.copy(alpha = 0.85f)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(PlayerDesignTokens.GlassCornerSmall))
            .clickable(onClick = onClick)
            .liquidGlass(
                shape = RoundedCornerShape(PlayerDesignTokens.GlassCornerSmall),
                tintAlpha = if (active) PlayerDesignTokens.GlassTintStrong else PlayerDesignTokens.GlassTintSoft,
                borderAlpha = PlayerDesignTokens.GlassTintSoft,
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(PlayerDesignTokens.ActionIconSize),
            tint = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = accent,
            ),
        )
    }
}

@Composable
private fun PlayerTrackInfo(
    track: Track?,
    isLiked: Boolean,
    accent: Color,
    onToggleLike: () -> Unit,
    onArtistClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = track?.title ?: stringResource(R.string.no_track_playing),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track?.displayArtist?.ifBlank { stringResource(R.string.unknown) } ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    enabled = track?.artist?.id != null,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onArtistClick,
                ),
            )
            // Where it plays from, and "via" when that is another service
            // or the device — the same tag as the current player.
            tf.monochrome.android.ui.components.LocalPlayerSource.current?.let { (picked, via) ->
                tf.monochrome.android.ui.components.PlayerSourceTag(source = picked, via = via)
            }
        }
        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isLiked) stringResource(R.string.action_unlike) else stringResource(R.string.action_like),
                tint = if (isLiked) accent else Color.White,
            )
        }
    }
}
