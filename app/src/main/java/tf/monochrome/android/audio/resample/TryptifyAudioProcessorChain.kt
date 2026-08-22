package tf.monochrome.android.audio.resample

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Replaces Media3's `DefaultAudioProcessorChain` so the two halves of a speed
 * change go to whichever engine can actually do them.
 *
 * The default chain hands everything to one Sonic instance, which splits the
 * work as `s = speed / pitch` and `r = rate * pitch`:
 *
 *  - **Preserve-pitch on** (`pitch == 1`): `s == speed`, so Sonic time-stretches
 *    with its WSOLA/AMDF path and `r == 1`, no resampling. Kept as-is here —
 *    time-stretching is a genuinely different problem from resampling.
 *  - **Pitch riding the tempo** (`pitch == speed`): `s` is exactly 1.0, which
 *    falls inside Sonic's `0.99999..1.00001` dead zone, so *no* stretch runs
 *    and the entire effect is `adjustRate` — two-point linear interpolation.
 *    That case is routed to [VariRateAudioProcessor] instead.
 *
 * Only one of the two is ever active: the other is left at unity, which makes
 * `isActive()` false for it and drops it out of the pipeline on the next flush.
 */
@UnstableApi
@OptIn(UnstableApi::class)
class TryptifyAudioProcessorChain(
    appProcessors: Array<AudioProcessor>,
    private val resampler: VariRateAudioProcessor,
) : AudioProcessorChain {

    private val silenceSkipping = SilenceSkippingAudioProcessor()
    private val sonic = SonicAudioProcessor()

    /**
     * The app's own processors first, in their existing order, then the
     * transport stages — the same shape `DefaultAudioProcessorChain` builds,
     * with the resampler added ahead of Sonic.
     *
     * Keeping the transport stages last preserves the ordering the rest of the
     * app is written against: AutoEQ still runs upstream of the pitch change,
     * so its pre-warp is still both necessary and correct. It also still covers
     * the case where the sink hands speed to AudioTrack's own playback params
     * instead of to this chain, which no processor ordering can reach.
     */
    private val processors: Array<AudioProcessor> =
        appProcessors + arrayOf<AudioProcessor>(silenceSkipping, resampler, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        val speed = playbackParameters.speed
        val pitch = playbackParameters.pitch
        val ridesTempo = abs(pitch - speed) < TOLERANCE && abs(speed - 1f) >= TOLERANCE
        if (ridesTempo) {
            resampler.setRatio(speed)
            sonic.setSpeed(1f)
            sonic.setPitch(1f)
        } else {
            resampler.setRatio(1f)
            sonic.setSpeed(speed)
            sonic.setPitch(pitch)
        }
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    /**
     * Media time consumed by [playoutDuration] of output.
     *
     * Resampling by `ratio` consumes exactly `ratio` input frames per output
     * frame, so the mapping is the plain product; when Sonic is the active
     * stage its own accounting is authoritative and is used instead.
     */
    override fun getMediaDuration(playoutDuration: Long): Long {
        val ratio = resampler.getRatio()
        return if (abs(ratio - 1f) >= TOLERANCE) {
            (playoutDuration * ratio.toDouble()).roundToLong()
        } else {
            sonic.getMediaDuration(playoutDuration)
        }
    }

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames

    private companion object {
        /** Sonic's own threshold for treating a factor as unity. */
        const val TOLERANCE = 1e-4f
    }
}
