package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerProgressSection
import tf.monochrome.android.ui.player.PlayerViewModel

/**
 * The player's timeline at the foot of the mixer: the same glass tube, labels
 * and seek as the main player, so you can scrub the song you are mixing without
 * leaving the console. Nothing is drawn with nothing playing.
 *
 * The mixer route sits outside the player, so [LocalPlayerGlass] here would be
 * the app default rather than the listener's player glass; it is provided from
 * the player's own settings, with the glass tube forced on, because the glass
 * tube is what this row is.
 */
@Composable
fun MixerTimeline(
    playerViewModel: PlayerViewModel,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    if (currentTrack == null) return
    val currentUnified by playerViewModel.currentUnifiedTrack.collectAsStateWithLifecycle()
    val queue by playerViewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by playerViewModel.currentIndex.collectAsStateWithLifecycle()
    val playerGlass by playerViewModel.playerGlass.collectAsStateWithLifecycle()
    // As State, read only inside the section: a position tick recomposes the
    // timeline, not the console around it.
    val positionState = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val durationState = playerViewModel.durationMs.collectAsStateWithLifecycle()

    val queueLabel = if (queue.isNotEmpty()) {
        "${(currentIndex + 1).coerceAtLeast(1)} / ${queue.size}"
    } else ""

    CompositionLocalProvider(
        LocalPlayerGlass provides playerGlass.copy(enabled = true, progressGlass = true),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            PlayerProgressSection(
                positionState = positionState,
                durationState = durationState,
                centerLabel = queueLabel,
                accent = accent,
                formatTime = playerViewModel::formatTime,
                onSeekCommit = playerViewModel::seekToFraction,
                isLive = currentUnified?.source is PlaybackSource.RadioStream,
            )
        }
    }
}
