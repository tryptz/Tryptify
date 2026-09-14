package tf.monochrome.android.audio.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Widens 24- and 32-bit integer PCM to float at the head of the exclusive
 * chain, so the DSP stages behind it are willing to run.
 *
 * Every processor in this chain accepts 16-bit or float and nothing else —
 * MixBusProcessor throws UnhandledAudioFormatException outright for anything
 * else, and the rest follow. A 24-bit source therefore configured the chain
 * into oblivion, every stage skipped:
 *
 *     configure(AudioFormat[sampleRate=48000, channelCount=2, encoding=21])
 *       -> chain: (ChannelDetectorProcessor skipped), (MixBusProcessor skipped),
 *          (AutoEqProcessor skipped), (ParametricEqProcessor skipped),
 *          (SpectrumAnalyzerTap skipped), ...
 *
 * The audio still reached the DAC — the chain passes a buffer through
 * untouched when nothing is active — so it played, correctly, at full
 * resolution, and the only symptom was that the mixer, both EQs, the spectrum
 * and the visualizer's audio feed had all quietly stopped existing. A 24-bit
 * WAV was enough to do it.
 *
 * DefaultAudioSink never has this problem because Media3 puts its own PCM
 * conversion ahead of its pipeline. This chain, which we drive by hand, had no
 * equivalent; this is it.
 *
 * Float rather than 16-bit on purpose: a float carries a 24-bit mantissa, so
 * widening is exact, and the sink already knows how to pack float back down
 * into the DAC's 24-bit subslots. Nothing is lost on the way through.
 *
 * Inactive for 16-bit and float, which the chain already handles — those paths
 * behave exactly as they did.
 */
@UnstableApi
internal class ToFloatPcmAudioProcessor : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (bytesPerSample(inputAudioFormat.encoding) == 0) {
            // 16-bit, float, or something we have no business touching.
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT,
        )
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputFormat
        val stride = bytesPerSample(format.encoding)
        if (stride == 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val samples = inputBuffer.remaining() / stride
        if (samples <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val out = ensureCapacity(samples * 4)
        repeat(samples) { out.putFloat(readSample(inputBuffer, stride)) }
        out.flip()
        outputBuffer = out
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        if (pendingFormat != AudioFormat.NOT_SET) inputFormat = pendingFormat
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    private fun ensureCapacity(bytes: Int): ByteBuffer {
        var b = buffer
        if (b.capacity() < bytes) {
            b = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            buffer = b
        }
        b.clear()
        return b
    }
}

/**
 * Bytes per sample this processor converts from, or 0 for an encoding it
 * leaves alone (16-bit and float, which the chain already accepts).
 *
 * Carries its own opt-in: @UnstableApi on the class above covers the class
 * body, not a top-level declaration in the same file, and the C.ENCODING_*
 * constants are behind that marker.
 */
@OptIn(UnstableApi::class)
internal fun bytesPerSample(encoding: Int): Int = when (encoding) {
    C.ENCODING_PCM_24BIT -> 3
    C.ENCODING_PCM_32BIT -> 4
    else -> 0
}

/**
 * One little-endian signed sample, as a float in [-1, 1).
 *
 * Read a byte at a time rather than through ByteBuffer.getInt: the incoming
 * buffer's byte order belongs to whoever handed it over, and silently
 * depending on it — or worse, mutating it — is how this kind of code ends up
 * producing noise on one device and music on another.
 *
 * Scaled by 2^(n-1), not 2^(n-1) - 1, so the integer step maps exactly onto a
 * binary fraction and every value round-trips through the sink's repacking
 * without drifting by a bit.
 */
internal fun readSample(input: ByteBuffer, stride: Int): Float = when (stride) {
    3 -> {
        val b0 = input.get().toInt() and 0xFF
        val b1 = input.get().toInt() and 0xFF
        val b2 = input.get().toInt() // sign-extends: this is the high byte
        ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
    }
    else -> {
        val b0 = input.get().toInt() and 0xFF
        val b1 = input.get().toInt() and 0xFF
        val b2 = input.get().toInt() and 0xFF
        val b3 = input.get().toInt() // sign-extends
        ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) / 2_147_483_648f
    }
}
