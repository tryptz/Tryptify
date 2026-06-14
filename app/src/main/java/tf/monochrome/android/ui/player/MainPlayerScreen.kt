package tf.monochrome.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track

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

/**
 * Pure, stateless layout for the redesigned main player. All playback state
 * arrives via [state]; the only state owned here is the transient scrubbing
 * position while the user drags the progress slider.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(dynamicPlayerBackground(state.albumColors.dominant)),
    ) {
        DynamicAlbumGlow(state.albumColors.dominant)

        if (isFullscreen) {
            // Visualizer fullscreen: hero owns the whole surface.
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
            topBar()

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                hero(Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(20.dp))
            PlayerTrackInfo(
                track = state.track,
                isLiked = state.isLiked,
                accent = accent,
                onToggleLike = onToggleLike,
                onArtistClick = onArtistClick,
            )

            Spacer(Modifier.height(16.dp))
            var isSeeking by remember { mutableStateOf(false) }
            var seekPosition by remember { mutableFloatStateOf(0f) }
            val displayFraction = if (isSeeking) seekPosition else state.progress
            val displayPositionMs =
                if (isSeeking) (seekPosition * state.durationMs).toLong() else state.positionMs
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

            Spacer(Modifier.height(20.dp))
            PlayerTransportControls(
                isPlaying = state.isPlaying,
                accent = accent,
                onPrevious = onPrevious,
                onRewind10 = onRewind10,
                onPlayPause = onPlayPause,
                onForward10 = onForward10,
                onNext = onNext,
            )

            Spacer(Modifier.height(20.dp))
            PlayerActionDock(
                isBookmarked = state.isLiked,
                onTimer = onTimer,
                onChapters = onChapters,
                onPlaylist = onPlaylist,
                onBookmark = onBookmark,
            )

            Spacer(Modifier.weight(0.4f))
            PlayerStatusGrid(
                outputLabel = state.outputLabel,
                soundLabel = state.soundLabel,
                speedLabel = state.speedLabel,
                sleepLabel = state.sleepTimerLabel,
                onOutput = onOutput,
                onSound = onSound,
                onSpeed = onSpeed,
                onSleep = onSleep,
            )
            Spacer(Modifier.height(12.dp))
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
