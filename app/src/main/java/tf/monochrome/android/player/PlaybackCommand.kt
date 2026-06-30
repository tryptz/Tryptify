package tf.monochrome.android.player

import androidx.media3.common.MediaItem

sealed interface PlaybackCommand {
    val requestId: Long

    data class PlayCurrentQueue(
        override val requestId: Long,
    ) : PlaybackCommand

    data class PlayResolvedTrack(
        override val requestId: Long,
        val trackId: String,
    ) : PlaybackCommand

    data class PlayMediaItem(
        override val requestId: Long,
        val mediaItem: MediaItem,
        val startPositionMs: Long = 0L,
    ) : PlaybackCommand

    data object TogglePlayPause : PlaybackCommand {
        override val requestId: Long = 0L
    }

    data object Pause : PlaybackCommand {
        override val requestId: Long = 0L
    }

    data object Stop : PlaybackCommand {
        override val requestId: Long = 0L
    }

    data object SkipNext : PlaybackCommand {
        override val requestId: Long = 0L
    }

    data object SkipPrevious : PlaybackCommand {
        override val requestId: Long = 0L
    }

    data class SeekTo(
        override val requestId: Long,
        val positionMs: Long,
    ) : PlaybackCommand
}
