package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.performance.LocalPerformanceProfile
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
    val isBuffering: Boolean = false,
    /**
     * A live radio station rather than a recording. Carried explicitly instead
     * of inferred from a zero duration, because every ordinary track reports
     * zero for the first frames after the item is set and would flash LIVE.
     */
    val isLiveStream: Boolean = false,
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
    val sleepTimerActive: Boolean = false,
    val queueLabel: String,
    val albumColors: AlbumColors,
    /**
     * How long the backdrop takes to change track, in ms — [albumColors] is
     * already mid-blend by the time it arrives here, and the blurred artwork
     * has to move at the same speed or the two disagree for the length of the
     * transition. See `ColorBlend`.
     */
    val colorBlendMs: Int = tf.monochrome.android.ui.theme.ColorBlend.GAPLESS_MS,
    val visualizerActive: Boolean,
    val waveformActive: Boolean,
    val compressorEnabled: Boolean,
    val inflatorEnabled: Boolean,
    val crossfeedEnabled: Boolean = false,
    val autoEqEnabled: Boolean = false,
    val systemWideAutoEqEnabled: Boolean = false,
    val toneControls: tf.monochrome.android.domain.model.ToneControls =
        tf.monochrome.android.domain.model.ToneControls.DEFAULT,
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
    /**
     * Play head and track length, deliberately passed as [State] rather than as
     * fields on [MainPlayerUiState]. They change four times a second, and while
     * they lived on the state object every tick rebuilt it — re-executing this
     * whole screen, the hero and the glass around it, for a number only the
     * progress row and the ring actually read. Only the composables that read
     * `.value` recompose now; everything else sees a stable state object.
     */
    positionState: State<Long>,
    durationState: State<Long>,
    // The audio-tools sheet renders in the MINI player's glass material, not
    // the full player's — the sheet is chrome overlaying the player, exactly
    // the mini player's role, so it follows that Studio setting.
    miniGlass: tf.monochrome.android.domain.model.PlayerGlassSettings,
    isFullscreen: Boolean,
    formatTime: (Long) -> String,
    onToggleLike: () -> Unit,
    onArtistClick: (Long, String) -> Unit,
    onSeekCommit: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
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
    onCrossfeedToggle: (Boolean) -> Unit,
    // Long-pressing an effect row opens that tool's configuration page.
    onCompressorOpen: () -> Unit,
    onInflatorOpen: () -> Unit,
    onCrossfeedOpen: () -> Unit,
    onAutoEqToggle: (Boolean) -> Unit,
    onSystemWideAutoEqToggle: (Boolean) -> Unit,
    onToneControlsChange: (tf.monochrome.android.domain.model.ToneControls) -> Unit,
    topBar: @Composable () -> Unit,
    hero: @Composable (Modifier) -> Unit,
    // Full-screen, unclipped layer between the background/stain and the player
    // content — the bass-reactive glow blooms here behind the active line's
    // screen bounds, so the light can never be clipped by a canvas/container.
    fxUnderlay: @Composable () -> Unit = {},
    // Expanded lyrics are NOT a separate element: the same hero slot grows to
    // full-bleed while the player controls collapse away and the blurred
    // artwork stain fades in behind everything.
    lyricsExpanded: Boolean = false,
    // Collapsed lyrics render as a full-screen-width rectangle (album height) so
    // lines can reach the phone's edges; album art stays a padded square.
    lyricsMode: Boolean = false,
    // Full-screen blurred, stretched album-art background (Appearance setting).
    blurredBackground: Boolean = false,
    /**
     * Chrome the route wants drawn over the player, inside the player's own
     * window — a slot rather than the route rendering it itself, because *where*
     * is the whole point. It lands here as a sibling of the haze source, so a
     * pane in it can gaussian-blur the background the way the audio-tools sheet
     * does. A route-owned dialog or modal sheet is a separate window and can
     * only ever show the flat tint.
     *
     * Drawn last, above the audio-tools sheet and its scrim.
     */
    overlay: @Composable BoxScope.() -> Unit = {},
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

    // Frost source for the audio-tools sheet: the backdrop layers (gradient,
    // album wash, glow, stain) register as ONE haze source so the sheet can
    // gaussian-blur what's visually behind it — the same frost the mini
    // player gets from the nav host's source.
    val hazeState = rememberHazeState()
    // A SECOND source, for the panels that sit over the whole player rather
    // than inside it — the audio-tools sheet and the overlay slot's speed
    // panel. It captures the backdrop *and* the player's content, so those
    // panels frost the album art and transport actually behind them.
    //
    // It has to be a separate state, not a wider [hazeState]. The transport
    // tiles frost [hazeState] from INSIDE the content column, and a haze
    // effect cannot sample a layer it is drawn inside — folding the content
    // into the state those tiles read would break them. Keeping the two apart
    // means the tiles' behaviour is untouched and only the panels see more.
    //
    // Without this the panels were blurring the backdrop layers alone, which
    // is a gradient with nothing in it: the blur ran, and the result was a
    // flat wash that read as no blur at all.
    val panelHaze = rememberHazeState()
    // Publish the background as a haze source to the chrome over it. The tiles
    // that read this are siblings of the source box below, not descendants of
    // it, so they blur it rather than trying to sample a layer they are part of.
    androidx.compose.runtime.CompositionLocalProvider(
        tf.monochrome.android.ui.player.LocalPlayerHaze provides hazeState,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().hazeSource(hazeState).hazeSource(panelHaze)) {
        // Background on its own node so the dither layer wraps just the
        // gradient, not the whole screen's content.
        Box(
            Modifier
                .matchParentSize()
                .dithered()
                .background(dynamicPlayerBackground(state.albumColors.dominant)),
        )
        // Full-screen blurred, stretched album art (Apple-Music / Spotify style).
        // Shown whenever the toggle is on — in EVERY view (album art, visualizer,
        // and lyrics, collapsed or fullscreen), not just the lyrics views. Fades
        // in/out instead of popping.
        val blurBgAlpha by animateFloatAsState(
            targetValue = if (blurredBackground) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
            label = "blurredBg",
        )
        if (blurBgAlpha > 0.001f) {
            PlayerBlurredArtBackground(
                coverUrl = state.track?.coverUrl,
                albumColors = state.albumColors,
                blendMillis = state.colorBlendMs,
                alpha = { blurBgAlpha },
            )
        }
        DynamicAlbumGlow(state.albumColors.dominant)

        // Expanded-lyrics backdrop: the artwork as a blurred stain fading in
        // behind the player content — background only, not a new element. When
        // the always-on blurred album background is enabled it already covers
        // this (in every lyrics state), so the stain stands down to avoid
        // double-darkening.
        val stainAlpha by animateFloatAsState(
            targetValue = if (lyricsExpanded && !blurredBackground) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
            label = "lyricsStain",
        )
        if (stainAlpha > 0.001f) {
            LyricsBackdropStain(
                coverUrl = state.track?.coverUrl,
                albumColors = state.albumColors,
                blendMillis = state.colorBlendMs,
                alpha = { stainAlpha },
            )
        }
        }

        // Reactive glow — full-screen, above the stain, behind the
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
        // A short, wide window (a phone on its side) cannot run the portrait
        // stack: `side = min(width, height)` collapses the hero to a sliver
        // between the top bar and the controls, so the artwork disappears.
        // There the player lays out as an old-iTunes row instead — artwork on
        // the left, the whole chrome beside it. A tall window (tablet, unfolded
        // foldable) keeps the stack, which has the height it wants.
        val configuration = LocalConfiguration.current
        val horizontal = configuration.screenWidthDp > configuration.screenHeightDp &&
            configuration.screenHeightDp < PlayerDesignTokens.ShortWindowHeightDp

        // Lyrics and the visualizer want a wide rectangle rather than a square,
        // so in the row layout they take an equal share of the width instead of
        // the artwork's height-bound square.
        val heroWantsWidth = lyricsMode || state.visualizerActive

        // The chrome — track info, scrubber, transport, dock — hoisted into a
        // slot so the column and the row hand it the same content instead of
        // forking it. Landscape tightens the gaps: the same stack has to fit in
        // a window less than half as tall.
        val chrome: @Composable (Modifier) -> Unit = { chromeModifier ->
            // The row layout gives the chrome roughly half a phone's height to
            // stack four controls in. It fits at the tightened gaps below, but
            // a very short window (a split-screen half, a small cover display)
            // would clip the dock — so there it scrolls instead.
            val chromeScroll = rememberScrollState()
            Column(
                modifier = chromeModifier.then(
                    if (horizontal) Modifier.verticalScroll(chromeScroll) else Modifier
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!horizontal) Spacer(Modifier.height(14.dp))
                DevEditable("trackInfo", Modifier.fillMaxWidth()) {
                    PlayerTrackInfo(
                        track = state.track,
                        artists = state.artists,
                        isLiked = state.isLiked,
                        accent = accent,
                        onToggleLike = onToggleLike,
                        onArtistClick = onArtistClick,
                        isLiveStream = state.isLiveStream,
                    )
                }

                Spacer(Modifier.height(if (horizontal) 8.dp else 16.dp))
                DevEditable("progress", Modifier.fillMaxWidth()) {
                    // Its own composable so the position tick recomposes this
                    // and nothing else.
                    PlayerProgressSection(
                        positionState = positionState,
                        durationState = durationState,
                        centerLabel = state.queueLabel.ifBlank { state.audioQuality.orEmpty() },
                        accent = accent,
                        formatTime = formatTime,
                        onSeekCommit = onSeekCommit,
                        isLive = state.isLiveStream,
                    )
                }

                Spacer(Modifier.height(if (horizontal) 8.dp else 20.dp))
                DevEditable("transport", Modifier.fillMaxWidth()) {
                    PlayerTransportControls(
                        isPlaying = state.isPlaying,
                        accent = accent,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        isBuffering = state.isBuffering,
                    )
                }

                Spacer(Modifier.height(if (horizontal) 8.dp else 20.dp))
                DevEditable("actionDock", Modifier.fillMaxWidth()) {
                    PlayerActionDock(
                        accent = accent,
                        lyricsActive = state.viewMode == NowPlayingViewMode.LYRICS,
                        timerActive = state.sleepTimerActive,
                        onLyrics = onLyrics,
                        onTimer = onTimer,
                        onMixer = onMixer,
                        onPlaylist = onPlaylist,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Captured for the panels only (zIndex above the backdrop), so
                // they frost the hero and transport as well as the wash. Not a
                // source for [hazeState] — the tiles inside this column frost
                // that one and cannot sample a layer they belong to.
                .hazeSource(panelHaze, zIndex = 1f)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = screenPad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DevEditable("topBar", Modifier.fillMaxWidth()) { topBar() }

            if (horizontal) {
                // ── Old-iTunes row: artwork left, everything else beside it ──
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    // The square is bound by the row's HEIGHT, and capped at
                    // 45% of its width so the controls beside it keep a usable
                    // column on a very wide window.
                    val artSide = minOf(maxHeight, maxWidth * 0.45f)
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            if (lyricsExpanded) 0.dp else 20.dp
                        ),
                    ) {
                        // The sizing modifier goes on the direct row child:
                        // `weight` is parent data, so it is ignored if a
                        // wrapper (DevEditable's box) sits between it and the
                        // Row — the hero would then eat the whole row and
                        // leave the chrome nothing.
                        when {
                            // Expanded lyrics take the whole row; the chrome
                            // animates away beside them.
                            lyricsExpanded -> hero(Modifier.fillMaxSize())
                            // A lyric or visualizer surface is a rectangle, so
                            // it splits the row rather than sitting in a
                            // height-bound square.
                            heroWantsWidth -> Box(Modifier.weight(1f).fillMaxHeight()) {
                                hero(Modifier.fillMaxSize())
                            }
                            else -> DevEditable("heroLandscape", Modifier.size(artSide)) {
                                hero(Modifier.fillMaxSize())
                            }
                        }

                        AnimatedVisibility(
                            visible = !lyricsExpanded,
                            modifier = Modifier.weight(1f),
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally(),
                        ) {
                            chrome(Modifier.fillMaxWidth())
                        }
                    }
                }
                // Same thin strip as portrait, sized down: the row has half the
                // height to give away to the audio-tools pull handle.
                if (!lyricsExpanded) Spacer(Modifier.height(28.dp))
            } else {
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
                    // Full phone-screen width = the padded slot width plus the side
                    // padding the Column already subtracted.
                    val fullWidth = maxWidth + screenPad * 2
                    val heroW by animateDpAsState(
                        targetValue = if (lyricsExpanded) maxWidth else side,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
                        label = "heroW",
                    )
                    // Lyrics get the FULL hero region height (decoupled from the album
                    // square), so the 3D letters, their glass bevels and the reactive
                    // glow have vertical room instead of being corner-cut in a short
                    // album-height band. Album art alone stays the compact square.
                    val heroH by animateDpAsState(
                        targetValue = if (lyricsExpanded || lyricsMode) maxHeight else side,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
                        label = "heroH",
                    )
                    // Collapsed lyrics: a full-width rectangle spanning the whole hero
                    // region (requiredWidth overrides the padded slot; the centred Box
                    // lets it overflow to both edges), so lines reach the borders and
                    // have vertical breathing room. Album art keeps its padded square.
                    val heroMod = if (lyricsMode && !lyricsExpanded) {
                        Modifier.requiredWidth(fullWidth).height(heroH)
                    } else {
                        Modifier.size(heroW, heroH)
                    }
                    // Wrap only the square art (not the full-width slot) so the DevEdit
                    // highlight hugs the album-art ratio instead of a tall rectangle.
                    // The saved DevEdit offset/scale are tuned for the compact square;
                    // applied to the expanded/full-width lyric surface they push text
                    // past the screen borders — so those render 1:1, bypassing it.
                    if (lyricsExpanded || lyricsMode) {
                        hero(heroMod)
                    } else {
                        DevEditable("hero", Modifier) {
                            hero(heroMod)
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
                    chrome(Modifier.fillMaxWidth())
                }

                // Reserve only a thin strip below the dock for the audio-tools pull
                // handle (a bottom-edge overlay), and let the hero (weight 1) take all
                // the remaining height. This keeps the controls pinned to the bottom
                // and the hero filling the screen in BOTH modes — the lyric surface
                // when lyrics are on, and a big centred album square when they're off
                // — instead of splitting the free space 50/50 with dead bottom space
                // (which shrank the album and floated the controls up the screen).
                if (!lyricsExpanded) Spacer(Modifier.height(56.dp))
            }
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
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPlayerGlass provides miniGlass,
            ) {
            StatusOverlayPanel(
                accent = accent,
                hazeState = panelHaze,
                outputLabel = state.outputLabel,
                soundLabel = state.soundLabel,
                speedLabel = state.speedLabel,
                visualizerActive = state.visualizerActive,
                waveformActive = state.waveformActive,
                compressorEnabled = state.compressorEnabled,
                inflatorEnabled = state.inflatorEnabled,
                crossfeedEnabled = state.crossfeedEnabled,
                autoEqEnabled = state.autoEqEnabled,
                systemWideAutoEqEnabled = state.systemWideAutoEqEnabled,
                toneControls = state.toneControls,
                onOutput = onOutput,
                onSound = onSound,
                onSpeed = onSpeed,
                onMixer = onMixer,
                onVisualizer = onVisualizer,
                onWaveform = onWaveform,
                onCompressorToggle = onCompressorToggle,
                onInflatorToggle = onInflatorToggle,
                onCrossfeedToggle = onCrossfeedToggle,
                onCompressorOpen = onCompressorOpen,
                onInflatorOpen = onInflatorOpen,
                onCrossfeedOpen = onCrossfeedOpen,
                onAutoEqToggle = onAutoEqToggle,
                onSystemWideAutoEqToggle = onSystemWideAutoEqToggle,
                onToneControlsChange = onToneControlsChange,
                onDismiss = { animateRevealTo(0f, 0f) },
            )
            }
        }

        // The speed panel lives here and is a sibling of both sources, so it
        // gets the one that actually has the player in it.
        androidx.compose.runtime.CompositionLocalProvider(
            tf.monochrome.android.ui.player.LocalPlayerHaze provides panelHaze,
        ) {
            overlay()
        }
    }
    }
}

/**
 * The scrubber and its time labels — the only part of the player that needs the
 * play head. Split out so a position tick recomposes this and nothing above it.
 */
@Composable
private fun PlayerProgressSection(
    positionState: State<Long>,
    durationState: State<Long>,
    centerLabel: String,
    accent: androidx.compose.ui.graphics.Color,
    formatTime: (Long) -> String,
    onSeekCommit: (Float) -> Unit,
    isLive: Boolean = false,
) {
    // A live stream has no end, so it has no fraction and no total. What it does
    // have is how long you have been listening, which is the only honest number
    // on the row — so it takes the slot the total would have had.
    if (isLive) {
        PlayerProgress(
            fraction = 0f,
            elapsedLabel = "LIVE",
            totalLabel = formatTime(positionState.value),
            centerLabel = centerLabel,
            accent = accent,
            onSeek = {},
            onSeekFinished = {},
            showScrubber = false,
        )
        return
    }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    val positionMs = positionState.value
    val durationMs = durationState.value
    val fraction = if (isSeeking) {
        seekPosition
    } else if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val displayPositionMs = if (isSeeking) (seekPosition * durationMs).toLong() else positionMs
    PlayerProgress(
        fraction = fraction,
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
    hazeState: HazeState?,
    outputLabel: String,
    soundLabel: String,
    speedLabel: String,
    visualizerActive: Boolean,
    waveformActive: Boolean,
    compressorEnabled: Boolean,
    inflatorEnabled: Boolean,
    crossfeedEnabled: Boolean,
    autoEqEnabled: Boolean,
    systemWideAutoEqEnabled: Boolean,
    toneControls: tf.monochrome.android.domain.model.ToneControls,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onMixer: () -> Unit,
    onVisualizer: () -> Unit,
    onWaveform: () -> Unit,
    onCompressorToggle: (Boolean) -> Unit,
    onInflatorToggle: (Boolean) -> Unit,
    onCrossfeedToggle: (Boolean) -> Unit,
    onCompressorOpen: () -> Unit,
    onInflatorOpen: () -> Unit,
    onCrossfeedOpen: () -> Unit,
    onAutoEqToggle: (Boolean) -> Unit,
    onSystemWideAutoEqToggle: (Boolean) -> Unit,
    onToneControlsChange: (tf.monochrome.android.domain.model.ToneControls) -> Unit,
    onDismiss: () -> Unit,
) {
    // The speed panel's shape and inset, so the two panes read as the same
    // sheet of glass: GlassPanel insets 12dp and clips to MonoDimens.shapeLg,
    // and this sheet used to be a full-bleed slab with only its top corners
    // rounded, sitting visibly wider than the panel it sits beside.
    val shape = tf.monochrome.android.ui.theme.MonoDimens.shapeLg
    val g = LocalPlayerGlass.current
    val useGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        g.enabled
    val glassTint = if (g.tintColor != 0) Color(g.tintColor) else accent
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .shadow(elevation = 32.dp, shape = shape, clip = false)
            .liquidGlass(shape = shape, tintAlpha = 0.22f, borderAlpha = 0.10f),
        shape = shape,
        // The old 92%-opaque black painted OVER the liquidGlass modifier and
        // buried it — the sheet read as a flat slab. Glass mode keeps only a
        // readability wash (the AGSL slab is nearly transparent at low
        // bodyOpacity, and the tiles sit over bright album art); the opaque
        // fill remains solely as the pre-Tiramisu / glass-off fallback.
        //
        // Do NOT draw the player's blurred artwork in here. It was tried, to
        // carry that toggle onto this sheet, and PlayerBlurredArtBackground
        // fills the maximum size it is offered — inside this Surface that is the
        // whole screen, so the sheet stopped wrapping its content and stretched
        // to full height with the controls stranded at the top. The frost is how
        // this sheet relates to what is behind it.
        color = if (useGlass) PlayerDesignTokens.BackgroundBlack.copy(alpha = 0.45f)
                else PlayerDesignTokens.BackgroundBlack.copy(alpha = 0.92f),
    ) {
        androidx.compose.foundation.layout.Box {
        // Frosted backdrop UNDER the slab — the mini player's exact recipe.
        val profile = LocalPerformanceProfile.current
        if (useGlass && hazeState != null && profile.allowHazeBlur && g.hazeBlurDp > 0f) {
            val frostBg = MaterialTheme.colorScheme.background
            val isDark = frostBg.luminance() <= 0.5f
            val frostTint = playerFrostTint(g, isDark)
            androidx.compose.foundation.layout.Box(
                Modifier
                    .matchParentSize()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = frostBg,
                            blurRadius = g.hazeBlurDp.dp,
                            tints = listOf(HazeTint(frostTint)),
                            noiseFactor = 0f,
                        ),
                    )
            )
        }
        if (useGlass) {
            // The mini player's slab: a tint rectangle relit by the playerGlass
            // shader (bevel, refraction, rim — all from the Studio's mini
            // player settings). Offscreen so the shader only sees the slab.
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .playerGlass(tint = glassTint)
                    .graphicsLayer {
                        compositingStrategy =
                            androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    },
            ) {
                drawRect(color = glassTint)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // The panel itself now insets 12dp, so this sheds 12dp to
                // leave the content exactly where it was on screen — and
                // level with the speed panel's, which does the same.
                .padding(horizontal = PlayerDesignTokens.ScreenPadding - 12.dp)
                .padding(top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            // Top of the audio options: the AutoEQ headphone correction. The
            // Wavelet-style system-wide variant is a sub-toggle that only
            // appears while AutoEQ itself is on (and turning AutoEQ off also
            // clears it, so the global effect never runs hidden).
            Column(modifier = Modifier.fillMaxWidth()) {
                ToggleRow(
                    "AutoEQ",
                    "Headphone EQ correction",
                    autoEqEnabled,
                    accent,
                    onAutoEqToggle,
                )
                AnimatedVisibility(visible = autoEqEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 10.dp),
                    ) {
                        ToggleRow(
                            "System-wide",
                            "Apply to all device audio",
                            systemWideAutoEqEnabled,
                            accent,
                            onSystemWideAutoEqToggle,
                        )
                    }
                }
            }
            // Bass/treble tone shelves (independent of the system-wide toggle —
            // applied in-app, or via the global effect when system-wide is on).
            ToneControlsPanel(
                tone = toneControls,
                accent = accent,
                onChange = onToneControlsChange,
            )
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayAction(Icons.Default.Animation, "Visualizer", accent, visualizerActive, onVisualizer)
                OverlayAction(Icons.Default.GraphicEq, "Waveform", accent, waveformActive, onWaveform)
            }

            // Effects toggles — long-press a row to open that tool's page.
            ToggleRow("Compressor", "Oxford dynamics. Hold to configure", compressorEnabled, accent,
                onCompressorToggle, onLongPress = onCompressorOpen)
            ToggleRow("Inflator", "Oxford loudness. Hold to configure", inflatorEnabled, accent,
                onInflatorToggle, onLongPress = onInflatorOpen)
            ToggleRow("Crossfeed", "Speaker simulation. Hold to configure", crossfeedEnabled, accent,
                onCrossfeedToggle, onLongPress = onCrossfeedOpen)
        }
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
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
@OptIn(ExperimentalFoundationApi::class)
private fun ToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val rowModifier = if (onLongPress != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onCheckedChange(!checked) },
                onLongClick = onLongPress,
            )
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
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
    artists: List<UnifiedArtistRef>,
    isLiked: Boolean,
    accent: Color,
    onToggleLike: () -> Unit,
    onArtistClick: (Long, String) -> Unit,
    isLiveStream: Boolean = false,
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
                    // The name goes with the id, because the id is sometimes
                    // 0: a catalogue row can reach the player identified only
                    // by what it is called, and the artist page can search for
                    // that. Passing the id alone made this link a dead end.
                    onArtistClick = { ref -> onArtistClick(ref.id ?: 0L, ref.name) },
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
        // No heart for a station: there is no track to favourite, and the
        // globe's own panel is where a station is kept.
        if (!isLiveStream) {
            IconButton(onClick = onToggleLike) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) accent else Color.White,
                )
            }
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
