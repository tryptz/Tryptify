// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
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
 * Its sound goes where the [renderers]' audio sink sends it: the exclusive
 * USB DAC when one is claimed (`UsbGameAudio`), Android's output otherwise.
 */
class GlyphPreviewPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val renderers: RenderersFactory = DefaultRenderersFactory(context),
) {
    private var player: ExoPlayer? = null
    private var envelope: Job? = null
    private var clipStartSeconds = 0f
    private var clipLengthSeconds = DEFAULT_LENGTH_SECONDS

    /** Told when a preview starts or stops sounding, on the main thread: for whoever routes the volume keys. */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /** Told which song a preview was just prepared for: for the chain's per-song stages (ReplayGain). */
    var onPrepared: ((Uri) -> Unit)? = null

    /**
     * Where in the song the preview is now, or null when it is not playing:
     * what the options screen's playfield follows, so its arrows move with the
     * music. Main thread, as the player is.
     */
    fun songPositionSeconds(): Float? {
        val p = player ?: return null
        if (!p.isPlaying) return null
        return clipStartSeconds + p.currentPosition.coerceAtLeast(0L) / 1000f
    }

    /** The stretch being previewed, start and length in song seconds; null before any. */
    val clip: Pair<Float, Float>? get() = if (player == null) null else clipStartSeconds to clipLengthSeconds

    /**
     * Builds the player now, ahead of the first preview, so that one starts
     * as soon as the next: the song list calls it on opening.
     */
    fun warm() {
        if (player == null) player = build()
    }

    /** Play [uri] from [startSeconds] for [lengthSeconds], looping, until [stop]. */
    fun play(uri: Uri, startSeconds: Float, lengthSeconds: Float) {
        envelope?.cancel()
        val p = player ?: build().also { player = it }
        val startMs = (startSeconds.coerceAtLeast(0f) * 1000f).toLong()
        val lengthMs = (lengthSeconds.coerceIn(MIN_LENGTH_SECONDS, MAX_LENGTH_SECONDS) * 1000f).toLong()
        clipStartSeconds = startMs / 1000f
        clipLengthSeconds = lengthMs / 1000f
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
        onPrepared?.invoke(uri)
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
        onPlayingChanged?.invoke(false)
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    onPlayingChanged?.invoke(isPlaying)
                }
            })
        }

    companion object {
        /** StepMania's default #SAMPLELENGTH. */
        const val DEFAULT_LENGTH_SECONDS = 12f

        private const val MIN_LENGTH_SECONDS = 3f
        private const val MAX_LENGTH_SECONDS = 30f
        /**
         * A breath, not a swell: half a second from silence made the first
         * moment of every preview read as late. Long enough not to click.
         * The song list's picture comes up at the same speed.
         */
        const val FADE_IN_MILLIS = 120f
        private const val FADE_OUT_MILLIS = 1_500f
        /** How long a stopped preview takes to fade away, and the song list's picture with it. */
        const val STOP_FADE_MILLIS = 250L
        private const val ENVELOPE_TICK_MILLIS = 25L

        /** Volume at [positionMs] into a clip [lengthMs] long: up over the start, down into the end. */
        fun fadeAt(positionMs: Long, lengthMs: Long): Float {
            val rise = (positionMs / FADE_IN_MILLIS).coerceIn(0f, 1f)
            val fall = ((lengthMs - positionMs) / FADE_OUT_MILLIS).coerceIn(0f, 1f)
            return minOf(rise, fall)
        }
    }
}
