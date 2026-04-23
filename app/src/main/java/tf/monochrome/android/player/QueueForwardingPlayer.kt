package tf.monochrome.android.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Wraps an [androidx.media3.exoplayer.ExoPlayer] so the Media3-managed
 * foreground notification and lock-screen controls can show working
 * next / previous buttons even though the underlying player only ever holds
 * one [androidx.media3.common.MediaItem] at a time.
 *
 * The app resolves stream URLs one-track-at-a-time (TIDAL DASH MPDs have short
 * TTLs; batch-resolving a long queue up front wastes requests and ages
 * stream URLs before they're used). ExoPlayer's own playlist is therefore
 * never the source of truth for queue position — [QueueManager] is.
 *
 * This forwarder:
 *  - Always advertises SEEK_TO_NEXT / SEEK_TO_PREVIOUS as available so the
 *    notification UI enables the buttons and the session doesn't silently
 *    drop the command when the raw player's playlist is empty. End-of-queue
 *    is handled inside `onNext` / `onPrev` (they simply stop playback).
 *  - Re-routes `seekToNext*` / `seekToPrevious*` calls to [onNext] / [onPrev]
 *    (the service's skipToNext / skipToPrevious), which handle queue
 *    advancement plus stream-URL resolution.
 */
@OptIn(UnstableApi::class)
class QueueForwardingPlayer(
    delegate: Player,
    @Suppress("unused") private val queueManager: QueueManager,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
) : ForwardingPlayer(delegate) {

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return Player.Commands.Builder()
            .addAll(base)
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        // Unconditional so the notification button is always tappable and the
        // Media3 session never pre-filters the command. `onNext` / `onPrev`
        // decide what happens at end-of-queue (they can stop the player).
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNextMediaItem() {
        Log.i(TAG, "seekToNextMediaItem routed to QueueManager")
        onNext()
    }

    override fun seekToPreviousMediaItem() {
        Log.i(TAG, "seekToPreviousMediaItem routed to QueueManager")
        onPrev()
    }

    override fun seekToNext() {
        Log.i(TAG, "seekToNext routed to QueueManager")
        onNext()
    }

    override fun seekToPrevious() {
        Log.i(TAG, "seekToPrevious routed to QueueManager")
        onPrev()
    }

    private companion object {
        const val TAG = "QueueForwardingPlayer"
    }
}
