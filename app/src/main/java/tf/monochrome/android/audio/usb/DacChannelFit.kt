package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fits the exclusive USB stream to a width the DAC has a format for, for the
 * DACs that have none of the song's own.
 *
 * The driver starts a stream only at a width one of the DAC's formats
 * carries, exactly: a 5.1 song on a 7.1 interface (stereo and eight-channel
 * formats, nothing of six) did not start, and fell off exclusive mode onto
 * Android's mixer. So did a stereo song on an interface with an eight-channel
 * format alone. Here the song's channels go out as the first of the DAC's and
 * the rest are silence: channels are in the same order at every width
 * ([tf.monochrome.android.audio.dsp.ChannelLayout]: FL FR FC LFE, then the
 * surrounds), which is the order USB Audio numbers them in too, so a 5.1
 * song's six land on a 7.1 format's first six and its side pair stays quiet.
 * Samples are copied, never touched: what plays is bit for bit the song.
 *
 * Narrowing is not done here. A song wider than every format the DAC has is
 * folded earlier in the chain (the multichannel fold), or is left to Android.
 *
 * Off unless [LibusbAudioSink] sets [targetChannels], so a width the DAC
 * takes passes through unseen. The last stage of the chain: its output
 * format is the DAC's.
 */
@UnstableApi
internal class DacChannelFit : AudioProcessor {

    /** The DAC's width to fit to; 0 for none. Read at the next configure. */
    var targetChannels = 0

    private var active = false
    private var inChannels = 0
    private var outChannels = 0
    private var bytesPerSample = 0
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        active = false
        val target = targetChannels
        if (target <= 0 || target == inputAudioFormat.channelCount) return AudioFormat.NOT_SET
        val bytes = bytesPerSample(inputAudioFormat.encoding)
        if (bytes == 0 || target < inputAudioFormat.channelCount || target > MAX_CHANNELS) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inChannels = inputAudioFormat.channelCount
        outChannels = target
        bytesPerSample = bytes
        active = true
        return AudioFormat(inputAudioFormat.sampleRate, target, inputAudioFormat.encoding)
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFrame = inChannels * bytesPerSample
        val frames = inputBuffer.remaining() / inFrame
        if (frames <= 0) return
        val outFrame = outChannels * bytesPerSample
        val need = frames * outFrame
        if (buffer.capacity() < need) {
            buffer = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
        }
        buffer.clear()
        val start = inputBuffer.position()
        val pad = outFrame - inFrame
        for (f in 0 until frames) {
            val from = start + f * inFrame
            for (b in 0 until inFrame) buffer.put(inputBuffer.get(from + b))
            // Silence is all zero bytes in every PCM encoding here.
            for (b in 0 until pad) buffer.put(0)
        }
        inputBuffer.position(start + frames * inFrame)
        buffer.flip()
        output = buffer
    }

    override fun getOutput(): ByteBuffer {
        val out = output
        output = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun isEnded(): Boolean = ended && output === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        output = AudioProcessor.EMPTY_BUFFER
        ended = false
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        active = false
    }

    companion object {
        /** The widest stream the pipeline carries: a 9.1.6 bed. */
        const val MAX_CHANNELS = 16

        /**
         * The DAC width a [source]-channel stream goes out at, from the
         * [widths] its playback formats carry: its own where there is one,
         * else the narrowest that holds it. Null when the DAC has none that
         * wide (the stream must be folded first), or told no widths at all.
         */
        fun pickWidth(source: Int, widths: List<Int>): Int? {
            if (source <= 0) return null
            if (source in widths) return source
            return widths.filter { it in (source + 1)..MAX_CHANNELS }.minOrNull()
        }

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }
}
