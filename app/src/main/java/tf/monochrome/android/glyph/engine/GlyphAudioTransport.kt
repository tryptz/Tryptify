// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import tf.monochrome.android.glyph.training.GlyphLoopSegment

/**
 * The mode's own audio transport.
 *
 * A separate ExoPlayer rather than the app's playback service, deliberately.
 * Gameplay needs to seek to a loop point on demand, change rate without
 * touching the listener's saved speed, and stop dead on pause — all of which
 * would be visible in the now-playing queue, the notification, scrobbles and
 * Discord presence if it went through the shared player. A practice loop is not
 * listening, and the rest of the app should not think it is.
 *
 * The transport owns *time*, and [GlyphClock] is how anything else asks about
 * it: [pump] takes a real position from the player and hands it to the clock,
 * which interpolates between pumps. Nothing else may write the clock.
 */
class GlyphAudioTransport(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // No repeat: looping is done by seeking, because ExoPlayer's own repeat
        // restarts the item rather than a segment inside it.
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    val clock = GlyphClock()

    /** The practice segment, or null to play straight through. */
    var loop: GlyphLoopSegment? = null

    /** Pitch follows speed by default, matching the sampler's linked mode. */
    var pitchFollowsSpeed: Boolean = true
        private set

    var isPrepared: Boolean = false
        private set

    val durationSeconds: Float
        get() = player.duration.takeIf { it > 0 }?.let { it / 1000f } ?: 0f

    val isPlaying: Boolean get() = player.isPlaying

    fun prepare(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        isPrepared = true
        clock.reset()
    }

    fun play() {
        player.playWhenReady = true
        clock.sync(player.currentPosition / 1000f, System.nanoTime())
        clock.start(System.nanoTime())
    }

    fun pause() {
        player.playWhenReady = false
        clock.pause(System.nanoTime())
    }

    fun seekTo(seconds: Float) {
        val target = seconds.coerceAtLeast(0f)
        player.seekTo((target * 1000f).toLong())
        clock.seekTo(target, System.nanoTime())
    }

    /**
     * Set the practice rate.
     *
     * [pitchFollowsSpeed] is the sampler's linked/unlinked distinction, kept
     * here rather than reinvented: linked is a plain rate change and sounds
     * like a tape slowing down; unlinked holds the pitch and is what a player
     * practising a riff at 0.7× actually wants, so the notes stay where their
     * ears expect them.
     */
    fun setSpeed(speed: Float, linkPitch: Boolean = pitchFollowsSpeed) {
        val safe = speed.coerceIn(GlyphClock.MIN_SPEED, GlyphClock.MAX_SPEED)
        pitchFollowsSpeed = linkPitch
        player.playbackParameters = PlaybackParameters(safe, if (linkPitch) safe else 1f)
        // The clock has to be told, or it keeps extrapolating at the old rate
        // and the notes drift away from the music within seconds.
        clock.setSpeed(safe, System.nanoTime())
    }

    /**
     * Take a real position reading and wrap the loop if one is set.
     *
     * Called from the gameplay tick. The wrap happens here, on an authoritative
     * reading, rather than in the renderer: a loop point crossed by an
     * extrapolated position would seek on a guess.
     *
     * @return the song position to play at, after any wrap.
     */
    fun pump(): Float {
        val now = System.nanoTime()
        val actual = player.currentPosition / 1000f

        val segment = loop
        if (segment != null && actual >= segment.endSeconds) {
            // Seek rather than let the position run on: the audio has to move
            // too, and the clock follows the audio.
            seekTo(segment.startSeconds)
            return segment.startSeconds
        }

        clock.syncSmooth(actual, now)
        return clock.positionAt(now)
    }

    /** The interpolated position, for a frame that is not pumping. */
    fun positionNow(): Float = clock.positionAt(System.nanoTime())

    fun release() {
        player.release()
        isPrepared = false
    }
}
