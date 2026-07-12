package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.devedit.DevEditable
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.usecase.uiArtistRefs
import tf.monochrome.android.ui.components.ClickableArtists
import tf.monochrome.android.ui.components.liquidGlass

/** Flattened, design-ready snapshot of everything the main player renders. */
data class MainPlayerUiState(
    val track: Track?,
    val sourceType: SourceType? = null,
    val artists: List<UnifiedArtistRef> = emptyList(),
    val qualityBadge: String? = null,
    val channelBadge: String? = null,
    val isThxSpatialAudio: Boolean = false,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val progress: Float,
    val isLiked: Boolean,
    val playbackSpeed: Float,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val viewMode: NowPlayingViewMode,
    val audioQuality: String?,
    val outputLabel: String,
    val soundLabel: String,
    val speedLabel: String,
    val sleepTimerLabel: String,
    val queueLabel: String,
    val albumColors: AlbumColors,
    val visualizerActive: Boolean,
    val waveformActive: Boolean,
    val compressorEnabled: Boolean,
    val inflatorEnabled: Boolean,
)

/**
 * Pure, stateless layout for the redesigned main player. The audio-tools grid
 * (output / sound / speed / sleep) is hidden by default and revealed as an
 * animated overlay when the user swipes up from the lower half of the player.
 * The only state owned here is the transient scrub position and the overlay
 * expanded flag.
 */
@Composable
fun MainPlayerScreen(
    state: MainPlayerUiState,
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
    onLyrics: () -> Unit,
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
    topBar: @Composable () -> Unit,
    hero: @Composable (Modifier) -> Unit,
    // Full-screen, unclipped layer between the background/stain and the player
    // content — the bass-reactive god rays draw here at each glyph's screen
    // position, so the light can never be clipped by a canvas/container.
    fxUnderlay: @Composable () -> Unit = {},
    // Expanded lyrics are NOT a separate element: the same hero slot grows to
    // full-bleed while the player controls collapse away and the blurred
    // artwork stain fades in behind everything.
    lyricsExpanded: Boolean = false,
) {
    val accent = state.albumColors.vibrant

    // ── Audio-tools sheet drag-to-reveal (swipe up to pull it in) ───────
    // reveal 0 = hidden, 1 = fully open. A swipe up on the bottom handle (or a
    // swipe down on the panel) writes `reveal` synchronously (zero-lag), and on
    // release it settles to 0/1 by fling velocity (else position). `reveal` is
    // read ONLY inside graphicsLayer{} (draw phase) and derivedStateOf, so the
    // slide never recomposes the player content.
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableFloatStateOf(0f) }
    var panelH by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settleSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
    val animateRevealTo: (Float, Float) -> Unit = { target, initialVel ->
        settleJob?.cancel()
        settleJob = scope.launch {
            // Coerce: a fast fling into the critically-damped spring can overshoot
            // past the endpoint, which would expose a gap above the sheet.
            animate(reveal, target, initialVel, settleSpec) { value, _ -> reveal = value.coerceIn(0f, 1f) }
        }
    }
    val dragState = rememberDraggableState { delta ->
        // Up drag (negative delta) opens the sheet; down closes it.
        if (panelH > 0f) reveal = (reveal - delta / panelH).coerceIn(0f, 1f)
    }
    val onDragStarted: suspend CoroutineScope.(Offset) -> Unit = {
        settleJob?.cancel()
        dragging = true
    }
    val onDragStopped: suspend CoroutineScope.(Float) -> Unit = { velocity ->
        // reveal rises as the finger moves up, so reveal-velocity = -v / H.
        val vReveal = if (panelH > 0f) -velocity / panelH else 0f
        val target = when {
            vReveal > 0.8f -> 1f          // fling up → open
            vReveal < -0.8f -> 0f         // fling down → close
            reveal > 0.5f -> 1f
            else -> 0f
        }
        // Only carry velocity that agrees with the target (no reverse lurch).
        val settleVel = if ((target == 1f) == (vReveal > 0f)) vReveal else 0f
        dragging = false
        animateRevealTo(target, settleVel)
    }
    // Boundary-only flags (derivedStateOf recomposes only when the bool flips).
    val scrimVisible by remember { derivedStateOf { reveal > 0.001f || dragging } }
    // `|| dragging` keeps the open-handle mounted for the WHOLE gesture: disposing
    // the node that owns the active drag would cancel it (onDragStopped never
    // fires), stranding `dragging` true and the scrim alive — locking the player.
    val handleVisible by remember { derivedStateOf { reveal < 0.999f || dragging } }

    // Back closes the open sheet (it's modal once the scrim is up). Gated on the
    // boundary-only `scrimVisible` so Back falls through to normal navigation when
    // closed, and so we never read `reveal` in composition.
    BackHandler(enabled = scrimVisible) { animateRevealTo(0f, 0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background on its own node so the dither layer wraps just the
        // gradient, not the whole screen's content.
        Box(
            Modifier
                .matchParentSize()
                .dithered()
                .background(dynamicPlayerBackground(state.albumColors.dominant)),
        )
        DynamicAlbumGlow(state.albumColors.dominant)

        // Expanded-lyrics backdrop: the artwork as a blurred stain fading in
        // behind the player content — background only, not a new element.
        val stainAlpha by animateFloatAsState(
            targetValue = if (lyricsExpanded) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
            label = "lyricsStain",
        )
        if (stainAlpha > 0.001f) {
            LyricsBackdropStain(
                coverUrl = state.track?.coverUrl,
                albumColors = state.albumColors,
                alpha = { stainAlpha },
            )
        }

        // God rays / glow — full-screen, above the stain, behind the
        // (transparent) lyric text, so the light shows through uncut.
        fxUnderlay()

        if (isFullscreen) {
            hero(Modifier.fillMaxSize())
            return@Box
        }

        // Side padding relaxes for expanded lyrics so the (same) lyric surface
        // spans the page; a small margin stays so words never touch the
        // screen borders (the backdrop stain behind is full-bleed anyway).
        val screenPad by animateDpAsState(
            targetValue = if (lyricsExpanded) 8.dp else PlayerDesignTokens.ScreenPadding,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
            label = "lyricsPad",
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = screenPad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DevEditable("topBar", Modifier.fillMaxWidth()) { topBar() }

            Spacer(Modifier.height(12.dp))
            // Bound the hero to the smaller of the available width/height so a
            // full-width square can never overflow its slot and collide with the
            // track info below it. For expanded lyrics the same slot animates
            // out to fill everything the hidden controls freed up.
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                val heroW by animateDpAsState(
                    targetValue = if (lyricsExpanded) maxWidth else side,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
                    label = "heroW",
                )
                val heroH by animateDpAsState(
                    targetValue = if (lyricsExpanded) maxHeight else side,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
                    label = "heroH",
                )
                // Wrap only the square art (not the full-width slot) so the DevEdit
                // highlight hugs the album-art ratio instead of a tall rectangle.
                // The saved DevEdit offset/scale are tuned for the compact square;
                // applied to the expanded lyric surface they push text past the
                // screen borders — so expanded renders 1:1, bypassing the override.
                if (lyricsExpanded) {
                    hero(Modifier.size(heroW, heroH))
                } else {
                    DevEditable("hero", Modifier) {
                        hero(Modifier.size(heroW, heroH))
                    }
                }
            }

            // Everything between the hero and the bottom free space is the
            // player chrome; expanded lyrics collapse it away so the same
            // lyric surface can take the room (no separate overlay element).
            AnimatedVisibility(
                visible = !lyricsExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Spacer(Modifier.height(14.dp))
                // Source + format tag directly under the album art: which service the
                // audio streams from (colour-coded) and the codec/bitrate it's playing.
                DevEditable("sourceTag", Modifier.fillMaxWidth()) {
                    PlayerSourceFormatTag(
                        sourceType = state.sourceType,
                        qualityBadge = state.qualityBadge,
                    )
                }

                Spacer(Modifier.height(14.dp))
                DevEditable("trackInfo", Modifier.fillMaxWidth()) {
                    PlayerTrackInfo(
                        track = state.track,
                        artists = state.artists,
                        isLiked = state.isLiked,
                        accent = accent,
                        onToggleLike = onToggleLike,
                        onArtistClick = onArtistClick,
                    )
                }

                Spacer(Modifier.height(16.dp))
                var isSeeking by remember { mutableStateOf(false) }
                var seekPosition by remember { mutableFloatStateOf(0f) }
                val displayFraction = if (isSeeking) seekPosition else state.progress
                val displayPositionMs =
                    if (isSeeking) (seekPosition * state.durationMs).toLong() else state.positionMs
                DevEditable("progress", Modifier.fillMaxWidth()) {
                    PlayerProgress(
                        fraction = displayFraction,
                        elapsedLabel = formatTime(displayPositionMs),
                        totalLabel = formatTime(state.durationMs),
                        centerLabel = state.queueLabel.ifBlank { state.audioQuality.orEmpty() },
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

                Spacer(Modifier.height(20.dp))
                DevEditable("transport", Modifier.fillMaxWidth()) {
                    PlayerTransportControls(
                        isPlaying = state.isPlaying,
                        accent = accent,
                        onPrevious = onPrevious,
                        onRewind10 = onRewind10,
                        onPlayPause = onPlayPause,
                        onForward10 = onForward10,
                        onNext = onNext,
                    )
                }

                Spacer(Modifier.height(20.dp))
                DevEditable("actionDock", Modifier.fillMaxWidth()) {
                    PlayerActionDock(
                        accent = accent,
                        lyricsActive = state.viewMode == NowPlayingViewMode.LYRICS,
                        onLyrics = onLyrics,
                        onTimer = onTimer,
                        onMixer = onMixer,
                        onPlaylist = onPlaylist,
                    )
                }
                }
            }

            // Free, fully-interactive space below the dock. The audio-tools
            // pull gesture lives in a thin strip at the very bottom edge (added
            // as an overlay below), so anything placed in this area still works
            // when the panel isn't pulled up.
            if (!lyricsExpanded) Spacer(Modifier.weight(1f))
        }

        // Thin bottom-edge pull strip — captures the open gesture and fades out
        // as the sheet rises. Removed once the sheet is fully open.
        if (handleVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(44.dp)
                    .graphicsLayer { alpha = 1f - reveal }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = onDragStarted,
                        onDragStopped = onDragStopped,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SwipeUpHandle(onClick = { animateRevealTo(1f, 0f) })
            }
        }

        // Scrim behind the sheet — opacity tracks the drag (draw-phase read).
        if (scrimVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = reveal }
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { animateRevealTo(0f, 0f) },
                    ),
            )
        }

        // Audio-tools sheet — ALWAYS composed so its height is known for the
        // drag; translated to follow `reveal`; kept invisible until measured so
        // it never flashes at its rest position on first layout.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { panelH = it.height.toFloat() }
                .graphicsLayer {
                    // Travel an extra shadow-height when closing so the 32dp
                    // elevation shadow (which draws above the panel's top edge)
                    // is pushed fully off-screen too — no dark band at rest.
                    translationY = (1f - reveal) * (panelH + 32.dp.toPx())
                    alpha = if (panelH > 0f) 1f else 0f
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = onDragStarted,
                    onDragStopped = onDragStopped,
                ),
        ) {
            // Granular, stable params (not the whole `state`) so this always-
            // composed sheet is SKIPPED on every position tick during playback.
            StatusOverlayPanel(
                accent = accent,
                outputLabel = state.outputLabel,
                soundLabel = state.soundLabel,
                speedLabel = state.speedLabel,
                visualizerActive = state.visualizerActive,
                waveformActive = state.waveformActive,
                compressorEnabled = state.compressorEnabled,
                inflatorEnabled = state.inflatorEnabled,
                onOutput = onOutput,
                onSound = onSound,
                onSpeed = onSpeed,
                onMixer = onMixer,
                onVisualizer = onVisualizer,
                onWaveform = onWaveform,
                onCompressorToggle = onCompressorToggle,
                onInflatorToggle = onInflatorToggle,
                onDismiss = { animateRevealTo(0f, 0f) },
            )
        }
    }
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
                text = "Audio tools",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun StatusOverlayPanel(
    accent: Color,
    outputLabel: String,
    soundLabel: String,
    speedLabel: String,
    visualizerActive: Boolean,
    waveformActive: Boolean,
    compressorEnabled: Boolean,
    inflatorEnabled: Boolean,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onMixer: () -> Unit,
    onVisualizer: () -> Unit,
    onWaveform: () -> Unit,
    onCompressorToggle: (Boolean) -> Unit,
    onInflatorToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 32.dp, shape = shape, clip = false)
            .liquidGlass(shape = shape, tintAlpha = 0.22f, borderAlpha = 0.10f),
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
            // Tap the handle (or swipe the sheet down / tap the scrim) to close.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(999.dp)),
                )
            }
            PlayerStatusGrid(
                accent = accent,
                outputLabel = outputLabel,
                soundLabel = soundLabel,
                speedLabel = speedLabel,
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
                OverlayAction(Icons.Default.Animation, "Visualizer", accent, visualizerActive, onVisualizer)
                OverlayAction(Icons.Default.GraphicEq, "Waveform", accent, waveformActive, onWaveform)
            }

            // Effects toggles
            ToggleRow("Compressor", "Oxford dynamics", compressorEnabled, accent, onCompressorToggle)
            ToggleRow("Inflator", "Oxford loudness", inflatorEnabled, accent, onInflatorToggle)
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

/**
 * Tag shown directly under the album art: a colour-coded chip for the streaming
 * service (Local = green, Qobuz = blue, TIDAL = pink, Collection = purple) plus the
 * codec/bitrate currently playing. Renders nothing when neither is known.
 */
@Composable
private fun PlayerSourceFormatTag(
    sourceType: SourceType?,
    qualityBadge: String?,
    channelBadge: String? = null,
    isThxSpatialAudio: Boolean = false,
) {
    if (sourceType == null && qualityBadge.isNullOrBlank() && channelBadge.isNullOrBlank() && !isThxSpatialAudio) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sourceType != null) {
            val color = sourceTagColor(sourceType)
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = color.copy(alpha = 0.18f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Text(
                        text = sourceTagLabel(sourceType),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }
        }
        // Highlighted THX Spatial Audio chip — solid fill so it reads stronger
        // than the translucent source/quality/channel chips on this screen.
        if (isThxSpatialAudio) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "THX Spatial Audio" },
            ) {
                Text(
                    text = "THX",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black.copy(alpha = 0.85f),
                )
            }
        }
        if (!qualityBadge.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = Color.White.copy(alpha = 0.12f),
            ) {
                Text(
                    text = qualityBadge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
        // Multichannel layout chip ("5.1"/"7.1") — separate from the codec
        // chip so it stays visible whichever quality string is playing.
        if (!channelBadge.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = Color.White.copy(alpha = 0.12f),
            ) {
                Text(
                    text = channelBadge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

private fun sourceTagColor(sourceType: SourceType): Color = when (sourceType) {
    SourceType.LOCAL -> Color(0xFF34C759)      // green
    SourceType.QOBUZ -> Color(0xFF2F80ED)      // blue
    SourceType.API -> Color(0xFFEC4899)        // pink (TIDAL)
    SourceType.COLLECTION -> Color(0xFFA855F7)  // purple
}

private fun sourceTagLabel(sourceType: SourceType): String = when (sourceType) {
    SourceType.LOCAL -> "Local"
    SourceType.QOBUZ -> "Qobuz"
    SourceType.API -> "TIDAL"
    SourceType.COLLECTION -> "Collection"
}

@Composable
private fun PlayerTrackInfo(
    track: Track?,
    artists: List<UnifiedArtistRef>,
    isLiked: Boolean,
    accent: Color,
    onToggleLike: () -> Unit,
    onArtistClick: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = track?.title ?: "No track playing",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track != null) {
                // Prefer the UnifiedTrack credits (carry per-artist ids incl. local
                // artist ids); fall back to the legacy Track when unknown.
                val refs = artists.ifEmpty { track.uiArtistRefs() }
                ClickableArtists(
                    artists = refs,
                    fallbackName = track.displayArtist.ifBlank { "Unknown" },
                    onArtistClick = { ref -> ref.id?.let { onArtistClick(it) } },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                    linkColor = Color.White.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    text = "Unknown",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isLiked) "Unlike" else "Like",
                tint = if (isLiked) accent else Color.White,
            )
        }
    }
}

@Composable
internal fun MetaChip(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
