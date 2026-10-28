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

/**
 * ReplayGain's stage: the song's gain ([ReplayGain.linear]), applied to the
 * samples themselves, early in each chain (after the taps, before the mixer),
 * so the mixer, the EQs and their preamps all see songs at one loudness.
 *
 * In the samples rather than the volume because the volume stops at 1.0 on
 * both outputs (Media3's and the USB DAC's), and a quiet song with room to
 * spare is turned up.
 *
 * The samples are worked in C++, by the C++ DSP chain's ReplayGainStage
 * (cpp/dsp/replaygain/replay_gain.h, through [ReplayGainNative]), which is
 * tested there against the mixer and every effect after it. This is its
 * Media3 shell: formats, buffers and the stream's start and end. It stays
 * here, ahead of the mixer, rather than inside the mixer's engine, so it
 * works with the mixer off, on every output and in a blend's tail.
 *
 * At unity (ReplayGain off, an untagged song, a 0 dB gain) the samples are
 * copied through exactly, so bit-perfect output stays bit-perfect. A new gain
 * glides in over [RAMP_MS] rather than stepping, except at the start of a
 * stream (a flush: a skip, a seek), which starts at it.
 *
 * Realtime: [setGain] is one volatile store from any thread; the audio thread
 * reads it once a buffer and hands it over with the buffer. Nothing locks or
 * allocates per buffer once the output buffer has grown to size.
 * 16-bit and float PCM only; any other encoding passes by inactive.
 */
@Singleton
@OptIn(UnstableApi::class)
class ReplayGainProcessor @Inject constructor() : AudioProcessor {

    /** The gain wanted, linear. Written from the service, read on the audio thread. */
    @Volatile private var target = 1f

    /** The native stage, made when a stream of a format it works first starts. Audio thread only. */
    private var native = 0L

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /** A copy of a buffer Media3 handed over on the heap, for the native side. Rare. */
    private var directInput: ByteBuffer = AudioProcessor.EMPTY_BUFFER
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
        if (format == AudioFormat.NOT_SET || native == 0L || !inputBuffer.hasRemaining()) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = (if (float) FLOAT_BYTES else INT16_BYTES) * format.channelCount
        // Whole frames are processed; a stray part-frame (never from a decoder) is dropped.
        val frames = inputBuffer.remaining() / frameBytes
        val bytes = frames * frameBytes
        val out = ensureOutput(bytes)
        val source = if (inputBuffer.isDirect) inputBuffer else directCopyOf(inputBuffer, bytes)
        val offset = if (source === inputBuffer) inputBuffer.position() else 0
        ReplayGainNative.nativeProcess(native, source, offset, out, frames, float, target)
        inputBuffer.position(inputBuffer.limit())
        out.position(0)
        out.limit(bytes)
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
        val format = inputFormat
        if (format == AudioFormat.NOT_SET) return
        if (native == 0L) native = ReplayGainNative.nativeCreate()
        ReplayGainNative.nativePrepare(native, format.sampleRate, format.channelCount)
        // A new stream starts at its gain: a glide here would be a fade-in.
        ReplayGainNative.nativeReset(native, target)
    }

    override fun reset() {
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        directInput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (native != 0L) {
            ReplayGainNative.nativeDestroy(native)
            native = 0L
        }
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

    private fun directCopyOf(input: ByteBuffer, bytes: Int): ByteBuffer {
        if (directInput.capacity() < bytes) {
            directInput = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            directInput.clear()
        }
        val slice = input.duplicate()
        slice.limit(slice.position() + bytes)
        directInput.put(slice)
        directInput.flip()
        return directInput
    }

    companion object {
        /** How long a change of gain takes (ReplayGainStage::kRampMs). */
        const val RAMP_MS = 50f
        private const val FLOAT_BYTES = 4
        private const val INT16_BYTES = 2
    }
}
