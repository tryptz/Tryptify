package tf.monochrome.android.player

import androidx.media3.common.MediaItem
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackCoordinator @Inject constructor() {
    private val _commands = MutableSharedFlow<PlaybackCommand>(
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val commands: SharedFlow<PlaybackCommand> = _commands.asSharedFlow()

    suspend fun requestPlayCurrentQueue() {
        _commands.emit(PlaybackCommand.PlayCurrentQueue(newRequestId()))
    }

    suspend fun requestPlay(trackId: String) {
        _commands.emit(
            PlaybackCommand.PlayResolvedTrack(
                requestId = newRequestId(),
                trackId = trackId,
            )
        )
    }

    suspend fun requestPlay(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        _commands.emit(
            PlaybackCommand.PlayMediaItem(
                requestId = newRequestId(),
                mediaItem = mediaItem,
                startPositionMs = startPositionMs,
            )
        )
    }

    suspend fun togglePlayPause() {
        _commands.emit(PlaybackCommand.TogglePlayPause)
    }

    suspend fun pause() {
        _commands.emit(PlaybackCommand.Pause)
    }

    suspend fun stop() {
        _commands.emit(PlaybackCommand.Stop)
    }

    suspend fun skipNext() {
        _commands.emit(PlaybackCommand.SkipNext)
    }

    suspend fun skipPrevious() {
        _commands.emit(PlaybackCommand.SkipPrevious)
    }

    suspend fun seekTo(positionMs: Long) {
        _commands.emit(PlaybackCommand.SeekTo(newRequestId(), positionMs))
    }

    private fun newRequestId(): Long = System.nanoTime()

    private companion object {
        const val COMMAND_BUFFER_CAPACITY = 16
    }
}
