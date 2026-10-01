package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs
import tf.monochrome.android.audio.resample.FloatSonicAudioProcessor
import tf.monochrome.android.audio.resample.VariRateAudioProcessor

/**
 * The sink a crossfade's tail plays into while exclusive USB output owns the
 * DAC: instead of a device it feeds [mix], which the main stream's
 * [LibusbAudioSink] adds into its own audio.
 *
 * It runs the same stages the USB path does — the tail's DSP copy, then the
 * varispeed resampler, transposition and time-stretch, in float — and the resampler does
 * one more job here: it brings the tail to the DAC's *current* rate. The DAC
 * follows the playing track's rate (that is what bit-perfect means), so when
 * the next song is at another rate the DAC switches under the tail; its
 * remaining seconds are resampled to the new rate rather than played at the
 * wrong pitch.
 *
 * Its clock is what the DAC has actually played of it ([UsbCrossfadeMix.playedUs]),
 * times the speed: the tail is heard in real time, so that is media time. The
 * same is what reports [AudioSink.Listener.onPositionAdvancing] — the blend's
 * "the tail is sounding" signal.
 *
 * [delegate] is never configured or fed; it is there because a
 * [ForwardingAudioSink] needs one for the calls nothing here overrides.
 */
@UnstableApi
class MixFeedAudioSink(
    delegate: AudioSink,
    private val mix: UsbCrossfadeMix,
    dspProcessors: List<AudioProcessor>,
    /** The semitone transposition, set as the main player's is; null for none. */
    transposer: AudioProcessor? = null,
) : ForwardingAudioSink(delegate) {

    private val stretch = FloatSonicAudioProcessor()
    private val resampler = VariRateAudioProcessor()
    // The USB path's order: DSP, then resampler, transposition, time-stretch.
    private val chain = AudioProcessorChain(
        listOf<AudioProcessor>(ToFloatPcmAudioProcessor()) + dspProcessors +
            listOfNotNull(resampler, transposer, stretch),
    )

    private var listener: AudioSink.Listener? = null
    private var params = PlaybackParameters.DEFAULT
    private var sourceRate = 0
    private var appliedConsumerRate = 0
    private var pending: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var startUs = C.TIME_UNSET
    private var endRequested = false
    private var advancingReported = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sourceRate = inputFormat.sampleRate
        val out = chain.configure(
            AudioProcessor.AudioFormat(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding),
        )
        mix.open(out.channelCount.coerceAtLeast(1))
        applyRates()
        pending = AudioProcessor.EMPTY_BUFFER
        startUs = C.TIME_UNSET
        endRequested = false
        advancingReported = false
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
        // The DAC changed rate under us (the next song is at another one).
        if (mix.consumerRate != appliedConsumerRate) applyRates()
        // Keeps the DAC fed with the tail while the main stream is between
        // tracks; a no-op while the main stream is writing (it mixes us in).
        mix.idlePump?.invoke()
        if (pending.hasRemaining()) {
            mix.offer(pending)
            if (pending.hasRemaining()) return false
        }
        if (!buffer.hasRemaining()) return true
        val out = chain.process(buffer)
        if (out.hasRemaining()) {
            pending = out
            mix.offer(out)
        }
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        if (!endRequested) {
            endRequested = true
            chain.queueEndOfStream()
        }
        // Drain what the stages still hold (the resampler's history, the
        // stretcher's window) into the mix as it makes room.
        mix.idlePump?.invoke()
        while (true) {
            if (pending.hasRemaining()) {
                mix.offer(pending)
                if (pending.hasRemaining()) return
            }
            if (chain.isEnded()) return
            val out = chain.process(AudioProcessor.EMPTY_BUFFER)
            if (!out.hasRemaining()) return
            pending = out
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (startUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = mix.playedUs()
        if (!advancingReported && played > 0) {
            advancingReported = true
            listener?.onPositionAdvancing(System.currentTimeMillis())
        }
        return startUs + (played * params.speed).toLong()
    }

    override fun hasPendingData(): Boolean = pending.hasRemaining() || mix.pending() > 0

    override fun isEnded(): Boolean = endRequested && chain.isEnded() && !hasPendingData()

    /** The blend's fade-out arrives as the player's volume. */
    override fun setVolume(volume: Float) {
        mix.gain = volume
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        params = playbackParameters
        applyRates()
    }

    override fun getPlaybackParameters(): PlaybackParameters = params

    override fun play() {
        mix.playing = true
    }

    override fun pause() {
        mix.playing = false
    }

    override fun flush() {
        pending = AudioProcessor.EMPTY_BUFFER
        mix.clear()
        startUs = C.TIME_UNSET
        endRequested = false
        val fmt = chain.outputFormat()
        if (fmt != AudioProcessor.AudioFormat.NOT_SET) chain.refreshActive()
    }

    override fun reset() {
        flush()
        mix.close()
    }

    override fun release() {
        mix.close()
        super.release()
    }

    /**
     * Speed as the USB path splits it — pitch riding the tempo to the
     * resampler, everything else to the stretcher — with the resampler also
     * converting the track's rate to the DAC's.
     */
    private fun applyRates() {
        val speed = params.speed
        val ridesTempo = abs(params.pitch - speed) < SPEED_TOLERANCE && abs(speed - 1f) >= SPEED_TOLERANCE
        stretch.setSpeed(if (ridesTempo) 1f else speed)
        stretch.setPitch(if (ridesTempo) 1f else params.pitch)
        val consumer = mix.consumerRate
        val conversion = if (consumer > 0 && sourceRate > 0) sourceRate.toFloat() / consumer else 1f
        resampler.setRatio((if (ridesTempo) speed else 1f) * conversion)
        appliedConsumerRate = consumer
        if (chain.outputFormat() != AudioProcessor.AudioFormat.NOT_SET) chain.refreshActive()
    }

    private companion object {
        const val SPEED_TOLERANCE = 0.0001f
    }
}
