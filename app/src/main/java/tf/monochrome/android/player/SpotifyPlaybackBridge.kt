package tf.monochrome.android.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tf.monochrome.android.data.cache.SpotifyShadowUri
import tf.monochrome.android.data.spotify.SpotifyAppRemoteClient
import kotlin.math.abs

/**
 * Keeps the Spotify app in step with the main ExoPlayer while a Spotify track
 * (a `spotify-shadow://` silent stand-in, see [SpotifyShadowUri]) is current.
 *
 * ExoPlayer stays the one authority — queue position, play state, seeking and
 * end-of-track all live there, which is why the notification, lock screen,
 * scrubber and auto-advance need no Spotify-specific code. This class only
 * *mirrors*:
 *
 *  - forward, ExoPlayer -> Spotify: the shadow becoming current starts the song
 *    in Spotify; play/pause and user seeks are repeated there; leaving the
 *    shadow (next track, stop, end of queue) pauses Spotify.
 *  - back, Spotify -> ExoPlayer: a pause or resume made in the Spotify app is
 *    applied here, a different song chosen in Spotify disengages the bridge
 *    and pauses here, and a position drift beyond [DRIFT_MS] is corrected by
 *    moving ExoPlayer's (silent) play head onto Spotify's.
 *
 * Changes this class applies to ExoPlayer on Spotify's behalf are made inside
 * [fromSpotify], so the listener callbacks they trigger are not echoed back to
 * Spotify as fresh commands. Media3 delivers those callbacks synchronously on
 * the application thread, which is what makes a plain flag sufficient.
 *
 * Audio focus: while the shadow is current ExoPlayer must not hold focus. The
 * Spotify app requests it when it starts, and an ExoPlayer that handled focus
 * would pause on losing it — which this class would then mirror as a pause to
 * Spotify, and neither would ever play.
 *
 * All methods run on the main thread (the player's application thread).
 */
@OptIn(UnstableApi::class)
class SpotifyPlaybackBridge(
    private val player: ExoPlayer,
    private val remote: SpotifyAppRemoteClient,
    private val scope: CoroutineScope,
    private val audioAttributes: AudioAttributes,
    private val onPlayFailed: (Throwable) -> Unit,
) : Player.Listener {

    /** The Spotify URI the Spotify app was told to play and is mirroring; null when disengaged. */
    private var engagedUri: String? = null

    /** Commands to Spotify run strictly in order: a pause must never overtake the play it follows. */
    private var lastCommand: Job? = null

    /** Uptime until which Spotify's reported state is treated as still settling after a command. */
    private var settleUntil = 0L

    private var applyingFromSpotify = false
    private var focusHandled = true
    private var stateWatcher: Job? = null

    fun start() {
        player.addListener(this)
        stateWatcher = scope.launch { remote.playerState.collect { onSpotifyState(it) } }
        sync()
    }

    fun release() {
        player.removeListener(this)
        stateWatcher?.cancel()
        lastCommand?.cancel()
        engagedUri = null
        remote.disconnect()
    }

    // --- ExoPlayer -> Spotify ------------------------------------------------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = sync()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = sync()

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Ended or stopped on the shadow: the song is over as far as this app
        // is concerned. Pause Spotify so its own autoplay doesn't roll on into
        // something nobody queued; if the queue has a next track, its
        // transition re-engages through sync().
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            disengage()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || applyingFromSpotify) return
        val shadow = currentShadow() ?: return
        if (engagedUri != shadow.spotifyUri) return
        val target = newPosition.positionMs
        issue("seek") { remote.seekTo(target) }
    }

    private fun sync() {
        val shadow = currentShadow()
        setFocusHandling(shadow == null)
        if (applyingFromSpotify) return

        if (shadow == null) {
            disengage()
            return
        }
        if (!player.playWhenReady) {
            if (engagedUri == shadow.spotifyUri) issue("pause") { remote.pause() }
            return
        }
        if (engagedUri == shadow.spotifyUri) {
            issue("resume") { remote.resume() }
            return
        }

        // A new song for Spotify (or the same one after a disengage).
        engagedUri = shadow.spotifyUri
        val uri = shadow.spotifyUri
        val startAt = player.currentPosition
        issue("play") {
            remote.play(uri).mapCatching {
                // Restored sessions and "resume where I left off" start
                // mid-track; Spotify always starts from zero.
                if (startAt > RESTART_THRESHOLD_MS) remote.seekTo(startAt).getOrThrow()
            }.onFailure { error ->
                if (engagedUri == uri) {
                    engagedUri = null
                    fromSpotify { player.pause() }
                    onPlayFailed(error)
                }
            }
        }
    }

    private fun disengage() {
        if (engagedUri == null) return
        engagedUri = null
        issue("pause") { remote.pause() }
    }

    // --- Spotify -> ExoPlayer ------------------------------------------------

    private fun onSpotifyState(state: com.spotify.protocol.types.PlayerState?) {
        val engaged = engagedUri ?: return
        state ?: return
        if (currentShadow()?.spotifyUri != engaged) return
        val settling = SystemClock.uptimeMillis() < settleUntil
        val spotifyUri = state.track?.uri

        if (spotifyUri != engaged) {
            // Right after our own play() Spotify still reports the previous
            // song, so a mismatch means nothing until it has settled.
            if (settling) return
            // In the last seconds of the song this is Spotify's autoplay moving
            // on by itself; ExoPlayer's own end-of-track is about to advance
            // the queue. Anywhere else the user picked something in Spotify:
            // stop mirroring and let this app go quiet.
            val duration = player.duration
            val nearEnd = duration != C.TIME_UNSET && duration - player.currentPosition < END_SLACK_MS
            if (!nearEnd) {
                engagedUri = null
                fromSpotify { player.pause() }
            }
            return
        }

        if (!settling) {
            if (state.isPaused && player.playWhenReady) {
                fromSpotify { player.pause() }
                return
            }
            if (!state.isPaused && !player.playWhenReady) {
                fromSpotify { player.play() }
            }
        }

        // Drift is corrected even while settling: the state that arrives when
        // Spotify has actually started sounding is the one that says how far
        // behind the silent play head it is.
        if (!state.isPaused) {
            val drift = state.playbackPosition - player.currentPosition
            if (abs(drift) > DRIFT_MS) {
                val duration = player.duration
                val target = if (duration != C.TIME_UNSET) {
                    state.playbackPosition.coerceIn(0L, duration)
                } else {
                    state.playbackPosition.coerceAtLeast(0L)
                }
                fromSpotify { player.seekTo(target) }
            }
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private fun currentShadow(): SpotifyShadowUri.Request? =
        player.currentMediaItem?.localConfiguration?.uri?.toString()?.let(SpotifyShadowUri::parse)

    private fun setFocusHandling(handle: Boolean) {
        if (handle == focusHandled) return
        focusHandled = handle
        player.setAudioAttributes(audioAttributes, handle)
    }

    private inline fun fromSpotify(block: () -> Unit) {
        applyingFromSpotify = true
        try {
            block()
        } finally {
            applyingFromSpotify = false
        }
    }

    private fun issue(what: String, command: suspend () -> Result<Unit>) {
        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS
        val previous = lastCommand
        lastCommand = scope.launch {
            previous?.join()
            command().onFailure { Log.w(TAG, "Spotify $what failed", it) }
        }
    }

    private companion object {
        const val TAG = "SpotifyBridge"
        const val SETTLE_MS = 2_500L
        const val DRIFT_MS = 2_000L
        const val END_SLACK_MS = 3_000L
        const val RESTART_THRESHOLD_MS = 1_500L
    }
}
