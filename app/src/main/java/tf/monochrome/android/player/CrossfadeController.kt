package tf.monochrome.android.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import tf.monochrome.android.audio.dsp.DspChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays the tail of the outgoing track so the main player can move on early,
 * giving a real overlap between one track and the next.
 *
 * The main [ExoPlayer] carries everything — the session, the DSP console,
 * AutoEQ, the ProjectM tap, the USB sink — and is the only thing the rest of
 * the app knows about. Duplicating that chain for a second player is not
 * possible: the native DSP engine and its processors are single instances and
 * two players feeding them would corrupt both.
 *
 * So the split is asymmetric. At `duration - crossfade` the *current* track is
 * handed to this secondary player, seeked to where the main player had got to,
 * and faded out; the main player immediately starts the *next* track and fades
 * in. The main player therefore always holds what the UI calls "now playing",
 * and only the last few seconds of the outgoing track come from here.
 *
 * The tail is fully processed. It gets its own [DspChain] — a second native
 * DSP engine plus its own AutoEQ and Parametric EQ — seeded from the live
 * settings so it sounds identical to what was already playing, and destroyed
 * when the blend ends. Sharing the main chain is not an option: a processor
 * holds per-stream filter state and the native engine holds one sample rate,
 * so two streams through one chain would corrupt both.
 *
 * One consequence remains: this player uses the ordinary Android audio sink,
 * so [PlaybackService] skips crossfading entirely while the exclusive libusb
 * bit-perfect path is streaming — two sinks cannot share that device, and
 * switching it mid-track would be worse than the gap.
 */
@UnstableApi
class CrossfadeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dataSourceFactory: DataSource.Factory,
    /**
     * Builds a processing chain for the tail, already seeded from the live
     * settings. Called once per blend; the result is released with the blend.
     */
    private val dspChainFactory: () -> DspChain,
    /** Gain for the *incoming* track on the main player, 0f..1f. */
    private val onIncomingGain: (Float) -> Unit,
) {
    private var tail: ExoPlayer? = null
    private var tailChain: DspChain? = null
    private var rampJob: Job? = null

    val isRunning: Boolean get() = rampJob?.isActive == true

    /**
     * Starts the blend: [item] resumes on the tail player from [fromPositionMs]
     * and fades out over [durationMs] while the caller's incoming track fades
     * in. Returns false if the tail couldn't be started, in which case the
     * caller should carry on without a crossfade rather than lose the audio.
     */
    fun start(
        item: MediaItem,
        fromPositionMs: Long,
        durationMs: Long,
        tailVolume: Float,
    ): Boolean {
        cancel()
        val chain = runCatching { dspChainFactory() }.getOrNull() ?: return false
        val player = runCatching { buildTailPlayer(chain) }.getOrNull() ?: run {
            chain.release()
            return false
        }

        return runCatching {
            tailChain = chain
            tail = player
            player.setMediaItem(item)
            player.seekTo(fromPositionMs)
            player.volume = tailVolume
            player.prepare()
            player.play()

            rampJob = scope.launch {
                var elapsed = 0L
                while (isActive && elapsed < durationMs) {
                    val progress = CrossfadeRamp.progress(elapsed, durationMs)
                    player.volume = tailVolume * CrossfadeRamp.fadeOut(progress)
                    onIncomingGain(CrossfadeRamp.fadeIn(progress))
                    delay(TICK_MS)
                    elapsed += TICK_MS
                }
                // Land exactly on the endpoints rather than wherever the last
                // tick fell, so the incoming track always reaches full gain.
                onIncomingGain(1f)
                cancel()
            }
            true
        }.getOrElse {
            cancel()
            false
        }
    }

    /**
     * Stops the tail, destroys its DSP session and restores the main player to
     * full gain. Ordering matters: the player is released first so nothing is
     * still draining through the chain when its native engine goes away.
     */
    fun cancel() {
        rampJob?.cancel()
        rampJob = null
        tail?.let { player ->
            runCatching {
                player.stop()
                player.release()
            }
        }
        tail = null
        tailChain?.let { chain -> runCatching { chain.release() } }
        tailChain = null
        onIncomingGain(1f)
    }

    fun release() {
        cancel()
    }

    /**
     * FFmpeg extension renderers so ALAC and the other formats the main player
     * handles still decode, and an audio sink running [chain] so the tail keeps
     * the user's DSP and EQ. No Atmos tap and no visualiser taps — those follow
     * the current track and belong to the main player. The shared data source
     * factory keeps `qobuz://` working, so a tail can be read from the same
     * partially-downloaded cache file.
     */
    private fun buildTailPlayer(chain: DspChain): ExoPlayer =
        ExoPlayer.Builder(
            context,
            object : NextRenderersFactory(context) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(chain.processors)
                    .build()
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // The main player owns audio focus for the session; the tail is a
            // few seconds of the track that already had it, so it must not
            // request its own and risk being refused mid-blend.
            .setHandleAudioBecomingNoisy(false)
            .build()

    private companion object {
        /** ~25 updates a second: smooth to the ear, negligible to the CPU. */
        const val TICK_MS = 40L
    }
}
