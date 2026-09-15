package tf.monochrome.android.audio.usb

import android.util.Log
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
        Log.i(TAG, "configure($input) -> $fmt; chain: ${membership()}")
        return fmt
    }

    /**
     * Which processors are in the chain right now, for the log.
     *
     * Membership is the thing that silently breaks here — a stage written out
     * at configure stays out, and the symptom is an effect that does nothing
     * with no error anywhere. Naming the skipped ones makes that visible in a
     * bug report instead of only in a debugger.
     */
    private fun membership(): String = processors.indices.joinToString(", ") { i ->
        val name = processors[i].javaClass.simpleName
        if (active[i]) name else "($name skipped)"
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

    /**
     * Re-evaluates which processors are in the chain.
     *
     * Membership is otherwise decided once, in [configure]. That is the same
     * contract Media3's own `AudioProcessingPipeline` keeps — it consults
     * `isActive` at configure and again at flush, and at no other time — and
     * it works there because DefaultAudioSink re-flushes the pipeline whenever
     * the playback parameters change. Nothing re-flushes this one.
     *
     * So a processor whose activity tracks a live control never joins:
     * [tf.monochrome.android.audio.resample.VariRateAudioProcessor] is active
     * only while its ratio is away from 1, and a track configured at 1.00x had
     * already written it out of the chain. A later speed change then set a
     * ratio on a processor this chain was skipping, and nothing happened.
     *
     * Only the processors that just joined are flushed. Flushing the whole
     * chain would reset the mixer's DSP for a change that has nothing to do
     * with it.
     */
    fun refreshActive() {
        var changed = false
        for (i in processors.indices) {
            val nowActive = processors[i].isActive
            if (nowActive == active[i]) continue
            active[i] = nowActive
            changed = true
            if (nowActive) processors[i].flush()
        }
        if (changed) Log.i(TAG, "membership changed -> ${membership()}")
    }

    fun flush() {
        for (p in processors) p.flush()
    }

    fun reset() {
        for (p in processors) p.reset()
    }

    fun outputFormat(): AudioProcessor.AudioFormat = outputFormat

    fun anyActive(): Boolean = active.any { it }

    private companion object {
        const val TAG = "AudioProcessorChain"
    }
}
