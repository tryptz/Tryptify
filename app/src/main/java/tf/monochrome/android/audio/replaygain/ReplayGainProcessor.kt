// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * ReplayGain's stage: the song's gain ([ReplayGain.linear]), applied to the
 * samples themselves, early in each chain (after the taps, before the mixer),
 * so the mixer, the EQs and their preamps all see songs at one loudness.
 *
 * In the samples rather than the volume because the volume stops at 1.0 on
 * both outputs (Media3's and the USB DAC's), and a quiet song with room to
 * spare is turned up.
 *
 * At unity (ReplayGain off, an untagged song, a 0 dB gain) the samples are
 * copied through exactly, so bit-perfect output stays bit-perfect. A new gain
 * glides in over [RAMP_MS] rather than stepping, except at the start of a
 * stream (a flush: a skip, a seek), which starts at it.
 *
 * Realtime: [setGain] is one volatile store from any thread; the audio thread
 * reads it once a buffer, and neither locks nor allocates per buffer.
 * 16-bit and float PCM only; any other encoding passes by inactive.
 */
@Singleton
@OptIn(UnstableApi::class)
class ReplayGainProcessor @Inject constructor() : AudioProcessor {

    /** The gain wanted, linear. Written from the service, read on the audio thread. */
    @Volatile private var target = 1f

    /** The gain the audio thread is at, gliding to [target]. Audio thread only. */
    private var current = 1f

    // The glide under way: where to and by how much a frame, fixed when it
    // starts, so it lands on the goal exactly (a step worked out afresh each
    // buffer from what is left would only ever approach it, and a gain that
    // never quite gets back to 1.0 is never bit-perfect again). Audio thread only.
    private var rampGoal = 1f
    private var rampStep = 0f

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    /** Sets the gain, linear (1.0 = unchanged). Any thread. */
    fun setGain(linear: Float) {
        target = if (linear.isFinite() && linear > 0f) linear else 1f
    }

    /** The gain last set, for seeding a copy of this stage (the blend's tail). */
    val gain: Float get() = target

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.channelCount > 0 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        pendingFormat = if (supported) inputAudioFormat else AudioFormat.NOT_SET
        return pendingFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputFormat
        if (format == AudioFormat.NOT_SET || !inputBuffer.hasRemaining()) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val bytes = inputBuffer.remaining()
        val out = ensureOutput(bytes)
        val goal = target
        if (current == goal && goal == 1f) {
            // Unity, settled: an exact copy.
            out.put(inputBuffer)
            out.flip()
            outputBuffer = out
            return
        }
        val channels = format.channelCount
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val sampleBytes = if (float) FLOAT_BYTES else INT16_BYTES
        val frames = bytes / (sampleBytes * channels)
        val start = inputBuffer.position()
        // A linear glide to the goal over RAMP_MS, then flat.
        if (goal != rampGoal) {
            rampGoal = goal
            rampStep = (goal - current) / maxOf(1f, format.sampleRate * RAMP_MS / 1000f)
        }
        var g = current
        var offset = 0
        for (frame in 0 until frames) {
            if (g != goal) {
                g += rampStep
                if ((rampStep > 0f && g >= goal) || (rampStep < 0f && g <= goal) || rampStep == 0f) g = goal
            }
            for (c in 0 until channels) {
                if (float) {
                    out.putFloat(offset, inputBuffer.getFloat(start + offset) * g)
                } else {
                    val v = (inputBuffer.getShort(start + offset) * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(offset, v.toShort())
                }
                offset += sampleBytes
            }
        }
        current = g
        // Whole frames are processed; a stray part-frame (never from a decoder) is dropped.
        inputBuffer.position(inputBuffer.limit())
        out.limit(offset)
        outputBuffer = out
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
        }
        // A new stream starts at its gain: a glide here would be a fade-in.
        current = target
        rampGoal = current
    }

    override fun reset() {
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        current = target
        rampGoal = current
    }

    /** Position 0, limit at capacity; the caller sets the limit it filled to. */
    private fun ensureOutput(bytes: Int): ByteBuffer {
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        return buffer
    }

    companion object {
        /** How long a change of gain takes: long enough not to click, short enough to go unheard. */
        const val RAMP_MS = 50f
        private const val FLOAT_BYTES = 4
        private const val INT16_BYTES = 2
    }
}
