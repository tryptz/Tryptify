package tf.monochrome.android.audio.usb

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Tiny pipeline that drives Media3 [AudioProcessor]s manually so the
 * libusb output path can run the same DSP / EQ / tap chain that
 * DefaultAudioSink owns internally. Without this, switching to
 * exclusive USB DAC silently drops AutoEQ + parametric EQ + DSP
 * effects + spectrum FFT + ProjectM audio feed — because all of
 * those live inside DefaultAudioSink's processor chain we bypass.
 *
 * Lifecycle mirrors AudioProcessor:
 *   configure(inputFormat) → outputFormat
 *   process(input) → ByteBuffer (output)  // call repeatedly
 *   flush() / reset()
 *
 * Notes:
 *  - Each processor's getOutput() returns a buffer owned by the
 *    processor; we hand that buffer straight to the next stage.
 *  - queueInput consumes as much as the processor can take in one
 *    call; partial consumption is fine — the renderer retries on
 *    the next handleBuffer tick with the unconsumed remainder.
 *  - Inactive processors (configure returned NOT_SET or threw
 *    UnhandledAudioFormatException) are skipped — the buffer flows
 *    through unchanged.
 */
@UnstableApi
internal class AudioProcessorChain(
    private val processors: List<AudioProcessor>,
) {
    private val active = BooleanArray(processors.size)
    private var outputFormat: AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat.NOT_SET

    fun configure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        var fmt = input
        for (i in processors.indices) {
            val p = processors[i]
            try {
                val out = p.configure(fmt)
                active[i] = p.isActive
                if (active[i] && out != AudioProcessor.AudioFormat.NOT_SET) {
                    fmt = out
                }
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                active[i] = false
            }
            p.flush()
        }
        outputFormat = fmt
        return fmt
    }

    /**
     * Walks `input` through every active processor and returns the
     * final ByteBuffer. The returned buffer is owned by the last
     * processor in the chain — caller must consume before the next
     * call to [process] (the processor will overwrite it).
     *
     * Returns [AudioProcessor.EMPTY_BUFFER] when the chain produced
     * nothing this tick (a processor may have buffered the input
     * waiting for more before emitting). Caller treats that as
     * "no work to write yet".
     */
    fun process(input: ByteBuffer): ByteBuffer {
        var current = input
        for (i in processors.indices) {
            if (!active[i]) continue
            val p = processors[i]
            if (current.hasRemaining()) {
                p.queueInput(current)
            }
            current = p.getOutput()
        }
        return current
    }

    fun flush() {
        for (p in processors) p.flush()
    }

    fun reset() {
        for (p in processors) p.reset()
    }

    fun outputFormat(): AudioProcessor.AudioFormat = outputFormat

    fun anyActive(): Boolean = active.any { it }
}
