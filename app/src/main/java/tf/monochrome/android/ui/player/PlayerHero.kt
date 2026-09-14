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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
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
    /**
     * The play head as a 0..1 fraction, as a lambda rather than a value. Only
     * the progress ring reads it, and it reads it in the draw phase — passing
     * the number itself recomposed this whole hero (and the artwork inside it)
     * four times a second on a screen where nothing else had changed.
     */
    progress: () -> Float,
    albumColors: AlbumColors,
    // How long the artwork takes to change track, and whether the change was
    // asked for. See [MorphingCoverArt].
    blendMillis: Int = MANUAL_MORPH_MS,
    userTrackChanges: Int = 0,
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
    onToggleFullscreen: () -> Unit = {},
    spectrumBins: FloatArray = FloatArray(0),
    spectrumColor: Color = PlayerGlowBlue,
    showSpectrum: Boolean = true,
    onToggleShowSpectrum: () -> Unit = {},
    onEnterVisualizer: () -> Unit = {},
    onExitVisualizer: () -> Unit = {},
    /**
     * The ambient preset row is across the bottom of the art, so the
     * visualizer-entry button moves up out of its corner. See HeroCoverArt.
     */
    displaceVisualizerEntry: Boolean = false,
    /** Raised on a tap anywhere on the art, to bring that row back with it. */
    onArtTap: () -> Unit = {},
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
                blendMillis = blendMillis,
                userTrackChanges = userTrackChanges,
                onEnterVisualizer = onEnterVisualizer,
            )
            else -> SquareArtHero(
                track = track,
                isPlaying = isPlaying,
                spectrumBins = spectrumBins,
                spectrumColor = spectrumColor,
                showSpectrum = showSpectrum,
                onToggleShowSpectrum = onToggleShowSpectrum,
                blendMillis = blendMillis,
                userTrackChanges = userTrackChanges,
                onEnterVisualizer = onEnterVisualizer,
                displaceVisualizerEntry = displaceVisualizerEntry,
                onArtTap = onArtTap,
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
    blendMillis: Int,
    userTrackChanges: Int,
    onEnterVisualizer: () -> Unit,
    displaceVisualizerEntry: Boolean = false,
    onArtTap: () -> Unit = {},
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
            blendMillis = blendMillis,
            userTrackChanges = userTrackChanges,
            onEnterVisualizer = onEnterVisualizer,
            displaceVisualizerEntry = displaceVisualizerEntry,
            onArtTap = onArtTap,
        )
    }
}

@Composable
private fun CircularProgressHero(
    track: Track?,
    progress: () -> Float,
    accent: Color,
    blendMillis: Int,
    userTrackChanges: Int,
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
            MorphingCoverArt(
                trackKey = track?.id,
                coverUrl = track?.coverUrl,
                contentDescription = track?.title ?: "Album Art",
                blendMillis = blendMillis,
                userTrackChanges = userTrackChanges,
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
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
                sweepAngle = 360f * progress().coerceIn(0f, 1f),
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
    onToggleFullscreen: () -> Unit,
    spectrumBins: FloatArray,
    spectrumColor: Color,
    showSpectrum: Boolean,
    onToggleShowSpectrum: () -> Unit,
    onExitVisualizer: () -> Unit,
) {
    // Keyed on an interaction counter so each tap restarts the 2s countdown
    // (a tap just before the deadline used to give a near-zero window).
    var showOverlay by remember { mutableStateOf(true) }
    var overlayInteraction by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlayInteraction) {
        showOverlay = true
        delay(2000)
        showOverlay = false
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
                    onClick = { overlayInteraction++ },
                )
        ) {
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

            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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

/**
 * How long the ambient preset row stays up after the last interaction.
 *
 * The row exists so someone can change preset without leaving the atmosphere;
 * it is not a permanent chrome. Four seconds is long enough to read the three
 * glyphs and reach one, short enough that the visualizer is unobstructed the
 * rest of the time.
 */
private const val AMBIENT_CONTROLS_IDLE_MS = 4_000L

/**
 * Preset controls for ambient mode's "Remove album cover" state.
 *
 * With the cover gone the MilkDrop atmosphere is the whole player, but the
 * preset controls live on [VisualizerHeroOverlay] — which only exists in
 * VISUALIZER view mode, not here. This puts the three that matter into the
 * space the cover vacated: back, browse, forward.
 *
 * Laid out edge to edge rather than as one centred pill. Three glyphs in a
 * shared pill sat as a single object floating in the middle of an otherwise
 * empty field, which reads as a widget dropped on the artwork; pushed to the
 * margins they read as the frame around it, and the centre — the part of the
 * atmosphere worth looking at — is left clear. Browse stays in the middle
 * because it is the one that opens something, and because SpaceBetween puts
 * it exactly on the axis the transport below is already centred on.
 *
 * Individually glassed for the same reason: with the row spanning the width,
 * one pill would have to be a full-width bar.
 *
 * Fades itself out after [AMBIENT_CONTROLS_IDLE_MS] of no interaction, and a
 * tap anywhere on the vacated cover slot brings it back — the caller signals
 * that by bumping [revealKey]. [AnimatedVisibility] rather than an alpha
 * animation so the buttons leave the composition when they finish fading:
 * invisible tap targets parked over a fullscreen visualizer would swallow
 * taps meant for it.
 *
 * Deliberately small. The player's own language is bare outlined glyphs in
 * slim glass — the transport and the action dock carry no labels — so a
 * full-width slab with a preset-name banner and three captioned pills read as
 * a dialog rather than as part of the player. The current preset's name lives
 * in the browser this opens, which is where someone reading names is already
 * going.
 *
 * Track skip is deliberately absent. The transport is still on screen below,
 * and the hero slot's swipe-to-skip gesture keeps working over this region —
 * so these buttons are unambiguously about presets.
 *
 * @param revealKey bump to bring the row back and restart the idle timer.
 */
@Composable
internal fun AmbientPresetControls(
    canGoBack: Boolean,
    onPreviousPreset: () -> Unit,
    onNextPreset: () -> Unit,
    onOpenPresetBrowser: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    revealKey: Int,
    modifier: Modifier = Modifier,
) {
    // Bumped by the row's own buttons. Kept separate from revealKey so the
    // caller does not have to observe presses it has no other use for; both
    // key the same effect, so either one restarts the timer.
    var selfPoke by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(revealKey, selfPoke) {
        visible = true
        delay(AMBIENT_CONTROLS_IDLE_MS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        // Quick in so a reveal tap feels answered, slow out so the row does
        // not appear to be snatched away from a finger on its way to it.
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(520)),
        modifier = modifier,
    ) {
        // A Box with three aligned children rather than a four-way
        // SpaceBetween row. Browse has to stay on the centre axis the transport
        // below is centred on, and four evenly spaced buttons would push it off
        // it. Aligning the ends and the middle independently keeps browse
        // centred no matter how many buttons the right group grows.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            AmbientPresetButton(
                icon = Icons.Default.SkipPrevious,
                label = "Previous preset",
                enabled = canGoBack,
                onClick = { selfPoke++; onPreviousPreset() },
                modifier = Modifier.align(Alignment.CenterStart),
            )
            AmbientPresetButton(
                icon = Icons.Default.LibraryMusic,
                label = "Preset browser",
                accent = PlayerGlowGold,
                onClick = { selfPoke++; onOpenPresetBrowser() },
                modifier = Modifier.align(Alignment.Center),
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Same reading as the hero overlay's: filled and pink once the
                // preset is liked, outline and white until then.
                AmbientPresetButton(
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (isFavorite) "Unlike preset" else "Like preset",
                    accent = if (isFavorite) PlayerGlowPink else Color.White,
                    onClick = { selfPoke++; onToggleFavorite() },
                )
                AmbientPresetButton(
                    icon = Icons.Default.SkipNext,
                    label = "Next preset",
                    onClick = { selfPoke++; onNextPreset() },
                )
            }
        }
    }
}

/**
 * One glyph in [AmbientPresetControls]. Sized to the transport's own icons
 * rather than to a labelled pill, and carrying its own glass disc now that
 * the row has no shared pill to sit in. The 40dp tap target is the whole
 * disc, so the smaller glyph does not make it harder to hit.
 */
@Composable
private fun AmbientPresetButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    // Sized by the button, not by the Surface. IconButton carries
    // minimumInteractiveComponentSize (48dp), which a 40dp Surface would be
    // fighting; letting the glass wrap a 40dp IconButton is the same shape
    // the shared pill used to get and keeps the disc exactly on the glyph.
    Surface(
        modifier = modifier.liquidGlass(shape = CircleShape, tintAlpha = 0.22f),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) accent.copy(alpha = 0.92f) else accent.copy(alpha = 0.30f),
                modifier = Modifier.size(19.dp),
            )
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
    blendMillis: Int = MANUAL_MORPH_MS,
    userTrackChanges: Int = 0,
    onEnterVisualizer: (() -> Unit)? = null,
    /**
     * The ambient preset row is sitting across the bottom of this art, so the
     * visualizer-entry button moves up to join the other icon-only controls
     * rather than share a corner with "next preset".
     */
    displaceVisualizerEntry: Boolean = false,
    /** Also raised on a tap anywhere on the art, so that row can come back with these. */
    onArtTap: () -> Unit = {},
) {
    val spectrumEnabled = showSpectrum
    var spectrumSpeed by remember { mutableStateOf(SpectrumSpeed.NORMAL) }

    // Controls show briefly on tap, then disappear quickly. When idle there are
    // no tags/labels on the art at all — the buttons are small and icon-only.
    // Keyed on an interaction counter (bumped by showControls()) so every tap
    // RESTARTS the 1.2s countdown — controls used to fade 1.2s after first
    // appearing even while the user was actively cycling a button.
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    val showControls = { controlsInteraction++ }
    LaunchedEffect(controlsInteraction) {
        controlsVisible = true
        delay(1200)
        controlsVisible = false
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
            ) { showControls(); onArtTap() }
    ) {
        MorphingCoverArt(
            trackKey = track?.id,
            coverUrl = track?.coverUrl,
            contentDescription = track?.title ?: "Album Art",
            blendMillis = blendMillis,
            userTrackChanges = userTrackChanges,
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
            // Small, icon-only controls (top-left): spectrum toggle + speed.
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeroIconButton(
                    icon = if (spectrumEnabled) Icons.Default.Equalizer else Icons.Default.Album,
                    contentDescription = if (spectrumEnabled) "Show album art" else "Show spectrum",
                    enabled = interactive,
                    onClick = { onToggleShowSpectrum(); showControls() },
                )
                if (spectrumEnabled) {
                    HeroIconButton(
                        icon = Icons.Default.Speed,
                        contentDescription = "Spectrum speed",
                        enabled = interactive,
                        onClick = { spectrumSpeed = spectrumSpeed.next(); showControls() },
                    )
                }
                if (onEnterVisualizer != null && displaceVisualizerEntry) {
                    HeroIconButton(
                        icon = Icons.Default.GraphicEq,
                        contentDescription = "Open visualizer",
                        enabled = interactive,
                        onClick = { onEnterVisualizer(); showControls() },
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

            // Visualizer entry (bottom-right) — small, icon-only. Moves into
            // the row above when the preset row has the bottom of the art.
            if (onEnterVisualizer != null && !displaceVisualizerEntry) {
                HeroIconButton(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    icon = Icons.Default.GraphicEq,
                    contentDescription = "Open visualizer",
                    enabled = interactive,
                    onClick = { onEnterVisualizer(); showControls() },
                )
            }
        }
    }
}

/** Small circular glass icon button used for the album-art overlay controls. */
@Composable
private fun HeroIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            // 32dp glass disc, but reserve the 48dp accessibility minimum so
            // the icon-only overlay control is actually hittable.
            .minimumInteractiveComponentSize()
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .liquidGlass(shape = CircleShape, tintAlpha = 0.18f, borderAlpha = 0.12f),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
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
