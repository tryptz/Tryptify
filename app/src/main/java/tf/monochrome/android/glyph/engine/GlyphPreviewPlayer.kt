// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The song list's preview, as StepMania's music wheel plays a song's sample:
 * a stretch of the song from its drop, looping, faded in and out at each end.
 *
 * Its own small player, like the gameplay transport and for the same reason —
 * a preview is not listening, and the now-playing queue, the notification and
 * scrobbling must not see it. Unlike the transport it takes audio focus, so
 * tapping a song pauses whatever the app was playing rather than talking over
 * it. Built on first use, so a visit that never previews costs nothing.
 */
class GlyphPreviewPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var player: ExoPlayer? = null
    private var envelope: Job? = null

    /** Play [uri] from [startSeconds] for [lengthSeconds], looping, until [stop]. */
    fun play(uri: Uri, startSeconds: Float, lengthSeconds: Float) {
        envelope?.cancel()
        val p = player ?: build().also { player = it }
        val startMs = (startSeconds.coerceAtLeast(0f) * 1000f).toLong()
        val lengthMs = (lengthSeconds.coerceIn(MIN_LENGTH_SECONDS, MAX_LENGTH_SECONDS) * 1000f).toLong()
        p.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(startMs + lengthMs)
                        .build(),
                )
                .build(),
        )
        p.volume = 0f
        p.prepare()
        p.playWhenReady = true
        // The volume follows the position inside the clip, so every loop fades
        // in from its start and out into its end, as StepMania's samples do.
        envelope = scope.launch {
            while (isActive) {
                val position = p.currentPosition.coerceAtLeast(0L)
                p.volume = fadeAt(position, lengthMs)
                delay(ENVELOPE_TICK_MILLIS)
            }
        }
    }

    /** Fade out and stop. Safe to call when nothing is playing. */
    fun stop() {
        envelope?.cancel()
        val p = player ?: return
        if (!p.isPlaying) {
            p.stop()
            return
        }
        envelope = scope.launch {
            val from = p.volume
            val steps = STOP_FADE_MILLIS / ENVELOPE_TICK_MILLIS
            for (i in 1..steps) {
                p.volume = from * (1f - i.toFloat() / steps)
                delay(ENVELOPE_TICK_MILLIS)
            }
            p.stop()
        }
    }

    fun release() {
        envelope?.cancel()
        player?.release()
        player = null
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply { repeatMode = Player.REPEAT_MODE_ONE }

    companion object {
        /** StepMania's default #SAMPLELENGTH. */
        const val DEFAULT_LENGTH_SECONDS = 12f

        private const val MIN_LENGTH_SECONDS = 3f
        private const val MAX_LENGTH_SECONDS = 30f
        private const val FADE_IN_MILLIS = 500f
        private const val FADE_OUT_MILLIS = 1_500f
        private const val STOP_FADE_MILLIS = 250L
        private const val ENVELOPE_TICK_MILLIS = 25L

        /** Volume at [positionMs] into a clip [lengthMs] long: up over the start, down into the end. */
        fun fadeAt(positionMs: Long, lengthMs: Long): Float {
            val rise = (positionMs / FADE_IN_MILLIS).coerceIn(0f, 1f)
            val fall = ((lengthMs - positionMs) / FADE_OUT_MILLIS).coerceIn(0f, 1f)
            return minOf(rise, fall)
        }
    }
}
