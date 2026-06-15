package tf.monochrome.android.ui.player

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.devedit.DevEditable
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.liquidGlass

/** Flattened, design-ready snapshot of everything the main player renders. */
data class MainPlayerUiState(
    val track: Track?,
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
)

// Vertical drag distance (px) that commits a swipe-up / swipe-down on the
// audio-tools panel.
private const val SwipeThresholdPx = 48f

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
    onArtistClick: () -> Unit,
    onSeekCommit: (Float) -> Unit,
    onPrevious: () -> Unit,
    onRewind10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onNext: () -> Unit,
    onTimer: () -> Unit,
    onChapters: () -> Unit,
    onPlaylist: () -> Unit,
    onBookmark: () -> Unit,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    topBar: @Composable () -> Unit,
    hero: @Composable (Modifier) -> Unit,
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
            DevEditable("topBar", Modifier.fillMaxWidth()) { topBar() }

            Spacer(Modifier.height(12.dp))
            // Bound the hero to the smaller of the available width/height so a
            // full-width square can never overflow its slot and collide with the
            // track info below it.
            DevEditable("hero", Modifier.fillMaxWidth().weight(1f)) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, maxHeight)
                    hero(Modifier.size(side))
                }
            }

            Spacer(Modifier.height(20.dp))
            DevEditable("trackInfo", Modifier.fillMaxWidth()) {
                PlayerTrackInfo(
                    track = state.track,
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
                    isBookmarked = state.isLiked,
                    onTimer = onTimer,
                    onChapters = onChapters,
                    onPlaylist = onPlaylist,
                    onBookmark = onBookmark,
                )
            }

            // Free, fully-interactive space below the dock. The audio-tools
            // pull gesture lives in a thin strip at the very bottom edge (added
            // as an overlay below), so anything placed in this area still works
            // when the panel isn't pulled up.
            Spacer(Modifier.weight(1f))
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
                onSleep = onSleep,
                onDismiss = { statusExpanded = false },
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
    state: MainPlayerUiState,
    onOutput: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            PlayerStatusGrid(
                accent = state.albumColors.vibrant,
                outputLabel = state.outputLabel,
                soundLabel = state.soundLabel,
                speedLabel = state.speedLabel,
                sleepLabel = state.sleepTimerLabel,
                onOutput = onOutput,
                onSound = onSound,
                onSpeed = onSpeed,
                onSleep = onSleep,
            )
        }
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
                text = track?.title ?: "No track playing",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track?.displayArtist?.ifBlank { "Unknown" } ?: "Unknown",
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
