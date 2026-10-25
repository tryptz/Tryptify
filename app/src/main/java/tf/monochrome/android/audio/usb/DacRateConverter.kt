package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.audio.resample.VariRateAudioProcessor
import java.nio.ByteBuffer

/**
 * Converts the exclusive USB stream to a rate the DAC's clock can run, for the
 * DACs that cannot run the song's own.
 *
 * Some only have a crystal for one family: the Nothing Ear (1) runs 48 and
 * 96 kHz and refuses 44.1, so every CD-rate song fell off exclusive mode onto
 * Android's mixer, which resamples anyway and mixes on top. Here the song is
 * resampled once, by the same windowed-sinc kernel the varispeed uses, and
 * still reaches the DAC on its own.
 *
 * Off unless [LibusbAudioSink] sets [targetRate] after the DAC refused the
 * song's rate, so a rate the DAC takes stays bit-perfect. The last stage of
 * the chain, after the DSP and the speed stages, so those run at the song's
 * rate as ever, and its output format carries the DAC's: everything that
 * times the stream from the chain's output rate is then on the DAC's clock.
 *
 * Its own resampler, not the shared varispeed: that one belongs to the speed
 * control, and to DefaultAudioSink's pipeline as well. Costs the kernel's
 * half-width in delay, 32 frames at the song's rate (0.7 ms at 44.1 kHz), and
 * holds the level: the passband is unity, cut off below the lower Nyquist.
 */
@UnstableApi
internal class DacRateConverter : AudioProcessor {

    private val resampler = VariRateAudioProcessor()

    /** The DAC's rate to convert to; 0 for none. Read at the next configure. */
    var targetRate = 0

    private var active = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        active = false
        val target = targetRate
        if (target <= 0 || target == inputAudioFormat.sampleRate) return AudioFormat.NOT_SET
        if (!supports(inputAudioFormat)) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        resampler.configure(inputAudioFormat)
        // Input frames per output frame: below 1 brings a song up to the
        // DAC's rate, above 1 down to it.
        resampler.setRatio(inputAudioFormat.sampleRate.toFloat() / target)
        active = true
        return AudioFormat(target, inputAudioFormat.channelCount, inputAudioFormat.encoding)
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) = resampler.queueInput(inputBuffer)

    override fun getOutput(): ByteBuffer = resampler.output

    override fun queueEndOfStream() = resampler.queueEndOfStream()

    override fun isEnded(): Boolean = resampler.isEnded

    override fun flush() = resampler.flush()

    override fun reset() {
        resampler.reset()
        active = false
    }

    companion object {
        /** Rates tried inside a continuous range the DAC advertises. */
        private val STANDARD_RATES = intArrayOf(
            44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000,
        )

        /** The PCM the resampler takes: 16-bit or float, up to its channel limit. */
        fun supports(format: AudioFormat): Boolean =
            (format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT) &&
                format.channelCount in 1..MAX_CHANNELS &&
                format.sampleRate > 0

        /**
         * The rate to run the DAC at when it refused [sourceRate], from the
         * rates it advertised for that depth: the lowest at or above the
         * song's, so nothing of it is filtered away, else the highest it has.
         *
         * Null when the list is empty or holds [sourceRate]: the DAC did not
         * refuse the rate itself, and another rate would not start either.
         */
        fun pickRate(sourceRate: Int, ranges: List<ClockRateRange>): Int? {
            if (sourceRate <= 0 || ranges.isEmpty()) return null
            if (ranges.any { it.covers(sourceRate) }) return null
            val candidates = ranges.flatMap { r ->
                if (r.isDiscrete) listOf(r.minHz)
                else STANDARD_RATES.filter { r.covers(it) } + listOf(r.minHz, r.maxHz)
            }.filter { it >= MIN_RATE }.distinct()
            return candidates.filter { it >= sourceRate }.minOrNull() ?: candidates.maxOrNull()
        }

        private fun ClockRateRange.covers(hz: Int): Boolean =
            hz in minHz..maxHz && (isDiscrete || resHz <= 0 || (hz - minHz) % resHz == 0)

        private const val MIN_RATE = 8_000

        /** VariRateAudioProcessor's own limit; past it, it declines the stream. */
        private const val MAX_CHANNELS = 16
    }
}
