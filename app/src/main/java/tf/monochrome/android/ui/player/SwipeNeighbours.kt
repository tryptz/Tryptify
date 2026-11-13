package tf.monochrome.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track

/**
 * The songs a swipe on the player's cover would go to: [next] for a swipe to
 * the left, [previous] for one to the right. Their covers ride in beside the
 * playing one, so the listener sees where the swipe goes before letting go.
 *
 * As the queue moves (QueueManager.next and previous): under Repeat All the
 * ends wrap round; under Repeat One the next song is this one again, as a
 * skip replays it; at an end with no repeat there is none (null), and the
 * swipe goes on as before, into the next list or back to this song's start,
 * with no cover to show for it.
 */
internal data class SwipeNeighbours(val previous: Track?, val next: Track?) {
    companion object {
        val NONE = SwipeNeighbours(null, null)

        fun of(queue: List<Track>, index: Int, repeat: RepeatMode): SwipeNeighbours {
            if (index !in queue.indices) return NONE
            val next = when (repeat) {
                RepeatMode.ONE -> queue[index]
                RepeatMode.ALL -> queue[(index + 1) % queue.size]
                RepeatMode.OFF -> queue.getOrNull(index + 1)
            }
            val previous = when {
                index > 0 -> queue[index - 1]
                repeat == RepeatMode.ALL -> queue.last()
                else -> null
            }
            return SwipeNeighbours(previous, next)
        }
    }
}

/**
 * A neighbouring song's cover, drawn where the playing cover is and in its
 * shape ([style]), with the playing cover's shading, so the one sliding in
 * is the one that stays.
 */
@Composable
internal fun NeighbourCover(track: Track, style: PlayerHeroStyle, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        if (style == PlayerHeroStyle.CircularProgress) {
            // CircularProgressHero's geometry: a square, padded, the cover a circle within it.
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Cover(track, Modifier.fillMaxSize(0.86f).clip(CircleShape), shaded = false)
            }
        } else {
            // SquareArtHero's: the full width, square.
            Cover(track, Modifier.fillMaxWidth().aspectRatio(PlayerDesignTokens.AlbumArtAspectRatio), shaded = true)
        }
    }
}

@Composable
private fun Cover(track: Track, modifier: Modifier, shaded: Boolean) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // The fallback behind the picture, as MorphingCoverArt has it.
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.5f),
        )
        track.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
            AsyncImage(model = url, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (shaded) {
            // HeroCoverArt's shading over the square cover.
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.52f)))
                )
            )
        }
    }
}
