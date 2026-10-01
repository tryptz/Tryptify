package tf.monochrome.android.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
 * On the exclusive libusb path two sinks cannot share the DAC, so there the
 * tail does not play at all: it is processed into a mix point the main
 * stream adds into its own audio before packing it for the device
 * ([tf.monochrome.android.audio.usb.UsbCrossfadeMix]).
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
    /**
     * The exclusive USB stream's mix point while the DAC is ours, else null.
     * With one, the tail plays into it ([tf.monochrome.android.audio.usb.MixFeedAudioSink])
     * and the main stream mixes it in; without, the tail plays through
     * Android like any player.
     */
    private val usbMix: () -> tf.monochrome.android.audio.usb.UsbCrossfadeMix? = { null },
    /** Gain for the *incoming* track on the main player, 0f..1f. */
    private val onIncomingGain: (Float) -> Unit,
) {
    private var tail: ExoPlayer? = null
    private var tailChain: DspChain? = null
    private var tailMix: tf.monochrome.android.audio.usb.UsbCrossfadeMix? = null
    private var rampJob: Job? = null

    // Set from the tail's analytics when its audio is actually leaving the
    // device — not when ExoPlayer says it is playing, which is earlier by the
    // time it takes to fill a fresh AudioTrack.
    @Volatile private var tailSounding = false

    val isRunning: Boolean get() = rampJob?.isActive == true

    /**
     * How the main player is playing right now, so the tail plays the same
     * way: the outgoing track must not drop back to 1.0x or lose its
     * transposition the moment the blend begins.
     */
    data class Playback(
        val speed: Float = 1f,
        val pitch: Float = 1f,
        val semitones: Float = 0f,
        val engine: tf.monochrome.android.audio.stretch.PitchEngine? = null,
        val quality: tf.monochrome.android.audio.stretch.PitchQuality? = null,
    )

    /**
     * Starts a blend. It runs in stages, so the outgoing track never stops
     * before its tail is sounding:
     *
     *  1. **Prepare.** The tail player opens [item], seeks to [fadeFromMs] —
     *     the media position the blend begins at — and waits there, paused,
     *     while the main player keeps playing the track.
     *  2. **Hand off.** When the main player reaches [fadeFromMs] the tail
     *     starts, and once its audio is actually coming out [onHandOff] is
     *     called: the caller moves the main player on to the next track,
     *     silent. Until then nothing has changed for the listener.
     *  3. **Fade.** Held at "tail only" until [incomingSounding] — the next
     *     track's audio really playing, which after a change of codec, rate
     *     or channel count is a rebuilt pipeline later than "playing" — then
     *     equal-power over what is left of the tail, in heard time.
     *
     * If the tail cannot get ready before the main player passes the blend
     * point, the blend is abandoned before the hand-off and the track simply
     * ends as it would without one: a missed crossfade, never a gap.
     *
     * Returns false if the tail could not even be built.
     */
    fun start(
        item: MediaItem,
        fadeFromMs: Long,
        trackDurationMs: Long,
        crossfadeMs: Long,
        tailVolume: Float,
        playback: Playback,
        /** The main player's position in the outgoing track, media ms. */
        mainPositionMs: () -> Long,
        onHandOff: () -> Unit,
        incomingSounding: () -> Boolean,
    ): Boolean {
        cancel()
        val chain = runCatching { dspChainFactory() }.getOrNull() ?: return false
        val player = runCatching { buildTailPlayer(chain, playback) }.getOrNull() ?: run {
            chain.release()
            return false
        }

        return runCatching {
            tailChain = chain
            tail = player
            tailSounding = false
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                    tailSounding = true
                }
            })
            player.setMediaItem(item)
            player.seekTo(fadeFromMs)
            player.volume = tailVolume
            player.playbackParameters = PlaybackParameters(playback.speed, playback.pitch)
            player.playWhenReady = false
            player.prepare()

            rampJob = scope.launch {
                var target = fadeFromMs
                // ── 1. Ready and waiting at the blend point ─────────────────
                // One re-seek is allowed: if the tail was slow and the main
                // player has passed the point, aim a little further on.
                var retried = false
                while (true) {
                    while (isActive && player.playbackState != Player.STATE_READY &&
                        mainPositionMs() < target + LATE_MS
                    ) {
                        delay(ALIGN_TICK_MS)
                    }
                    if (player.playbackState == Player.STATE_READY && mainPositionMs() < target + LATE_MS) break
                    val retarget = mainPositionMs() + CrossfadeRamp.mediaMs(RETRY_LEAD_MS, playback.speed)
                    if (retried || CrossfadeRamp.heardMs(trackDurationMs - retarget, playback.speed) < CrossfadeRamp.MIN_BLEND_MS) {
                        abandon()
                        return@launch
                    }
                    retried = true
                    target = retarget
                    player.seekTo(target)
                }
                while (isActive && mainPositionMs() < target) delay(ALIGN_TICK_MS)

                // ── 2. Tail sounding, then hand the main player on ─────────
                player.play()
                // Through Android the tail is its own output and takes a
                // moment to start, so the main player waits for it. Over USB
                // it is mixed into the main stream — there is nothing to
                // start — and the main player's queued audio past this point
                // is exactly what the hand-off's flush drops, so it hands off
                // at once.
                var waited = if (tailMix != null) MAX_TAIL_START_MS else 0L
                while (isActive && !tailSounding && waited < MAX_TAIL_START_MS) {
                    delay(ALIGN_TICK_MS)
                    waited += ALIGN_TICK_MS
                }
                onIncomingGain(0f)
                onHandOff()

                // ── 3. Hold for the incoming track, then fade ─────────────
                // What is left of the tail in heard time is the most the
                // blend can last; the setting is the most it should.
                val tailLeftMs = CrossfadeRamp.heardMs(trackDurationMs - player.currentPosition, playback.speed)
                val blendMs = minOf(crossfadeMs, tailLeftMs).coerceAtLeast(TICK_MS)
                // Capped at half the blend: the tail only holds so much
                // audio, and waiting it out would end in a hard stop. Half is
                // the point past which a fade is worth more than a wait.
                val maxHoldMs = minOf(blendMs / 2, MAX_HOLD_MS)
                var held = 0L
                while (isActive && held < maxHoldMs && !incomingSounding()) {
                    delay(TICK_MS)
                    held += TICK_MS
                }
                // Whatever the wait cost comes off the ramp, not off the end of
                // the outgoing track — the blend still lands on its last sample.
                val rampMs = (blendMs - held).coerceAtLeast(TICK_MS)
                var elapsed = 0L
                while (isActive && elapsed < rampMs) {
                    val progress = CrossfadeRamp.progress(elapsed, rampMs)
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
     * Gives up before the hand-off: the main player never moved, so dropping
     * the tail is all it takes for the track to end the ordinary way.
     */
    private fun abandon() {
        rampJob = null
        cancel()
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
        // Whatever the tail left unplayed must not be mixed into the next song.
        tailMix?.close()
        tailMix = null
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
     *
     * The transport stages are the main player's own, not Media3's default:
     * [TryptifyAudioProcessorChain] with a fresh resampler and time-stretch
     * engine set to the same transposition. With the default chain a
     * vinyl-style speed would run through Sonic's linear interpolation and a
     * transposition would not happen at all, so the outgoing track would
     * change sound at the instant the blend began.
     */
    private fun buildTailPlayer(chain: DspChain, playback: Playback): ExoPlayer {
        fun transposer() = tf.monochrome.android.audio.stretch.StretchAudioProcessor().apply {
            setSemitones(playback.semitones)
            if (playback.engine != null && playback.quality != null) setEngine(playback.engine, playback.quality)
        }
        val mix = usbMix()
        tailMix = mix
        if (mix != null) {
            return ExoPlayer.Builder(
                context,
                object : NextRenderersFactory(context) {
                    override fun buildAudioSink(
                        context: android.content.Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = tf.monochrome.android.audio.usb.MixFeedAudioSink(
                        delegate = DefaultAudioSink.Builder(context).build(),
                        mix = mix,
                        dspProcessors = chain.processors.toList(),
                        transposer = transposer(),
                    )
                }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setHandleAudioBecomingNoisy(false)
                .build()
        }
        val stretch = transposer()
        val transport = tf.monochrome.android.audio.resample.TryptifyAudioProcessorChain(
            chain.processors,
            tf.monochrome.android.audio.resample.VariRateAudioProcessor(),
            stretch,
        )
        return ExoPlayer.Builder(
            context,
            object : NextRenderersFactory(context) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(transport)
                    .build()
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // The main player owns audio focus for the session; the tail is a
            // few seconds of the track that already had it, so it must not
            // request its own and risk being refused mid-blend.
            .setHandleAudioBecomingNoisy(false)
            .build()
    }

    private companion object {
        /** ~25 updates a second: smooth to the ear, negligible to the CPU. */
        const val TICK_MS = 40L

        /** How closely the hand-off follows the main player's position. */
        const val ALIGN_TICK_MS = 10L

        /** How far past the blend point the main player may get before the tail is too late. */
        const val LATE_MS = 120L

        /** Head start for the one re-seek, heard time. */
        const val RETRY_LEAD_MS = 600L

        /** Longest to wait for a started tail to sound before handing off anyway. */
        const val MAX_TAIL_START_MS = 400L

        /**
         * Ceiling on the wait for the incoming track, regardless of blend
         * length. Past this it isn't loading slowly, it isn't loading.
         */
        const val MAX_HOLD_MS = 3_000L
    }
}
