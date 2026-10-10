package tf.monochrome.android.audio.usb

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Checks that PCM declared as float really is float, at the head of the hi-res
 * chain, and reads it as 16-bit when it is not.
 *
 * A decoder asked for float output can report float and write 16-bit samples
 * anyway. Samsung's c2.sec.flac.decoder does exactly that for a 16-bit FLAC
 * (see SourceDepthMediaCodecAdapterFactory, which no longer asks it to). Taken
 * at its word, every pair of 16-bit samples is read as one float. The bit
 * pattern of a negative sample makes a value far past full scale (or NaN or
 * infinity), and that of a positive one makes a value near zero. What reaches
 * the speaker is full-scale static, and every buffer counts as half its real
 * length, so the play position runs at twice the real rate.
 *
 * Real float PCM from a decoder stays within a few units of full scale and is
 * never NaN. 16-bit audio read as float fails both tests on roughly half its
 * samples, quiet passages included, because a small negative sample reads as
 * NaN. So the first buffer with real content gives the answer, before any of
 * it is played. From then on the stream is converted from 16-bit, which also
 * puts its frame count, and so the timing, right again.
 *
 * Once a second of audible audio has passed the check, the stream is trusted
 * and no longer scanned. Digital silence passes the check either way (zero is
 * zero in both readings), so it is never what decides. The verdict lasts until
 * the next configure, which is the next stream. A flush on its own is a seek,
 * still the same decoder.
 */
@UnstableApi
internal class FloatPcmGuard(
    /** Where the one-per-stream detection is reported; a no-op in JVM tests. */
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : AudioProcessor {

    private enum class Verdict { UNVERIFIED, FLOAT, INT16 }

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var verdict = Verdict.UNVERIFIED
    private var audibleFramesPassed = 0L

    /** The one output allocation, kept across blocks (see StretchAudioProcessor). */
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    /** True once this stream has been found to be 16-bit audio declared as float. */
    val readingAsInt16: Boolean get() = verdict == Verdict.INT16

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT || inputAudioFormat.channelCount <= 0) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            // The stream it judged is over, so is its verdict: kept, a 16-bit
            // verdict went on doubling floatBytes for the integer stream after.
            verdict = Verdict.UNVERIFIED
            audibleFramesPassed = 0
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    /**
     * Looks at [input] without consuming it, while the stream is still
     * unverified. Called from [queueInput]; also callable ahead of it by a sink
     * that needs [floatBytes] for a buffer before the chain runs. That look
     * ahead passes [countTrust] false: [queueInput] sees the same buffer next,
     * and counting it twice trusted a stream after half the second promised.
     */
    fun classify(input: ByteBuffer, countTrust: Boolean = true) {
        if (verdict != Verdict.UNVERIFIED || inputFormat == AudioFormat.NOT_SET) return
        val start = input.position()
        val samples = input.remaining() / FLOAT_BYTES
        var implausible = 0
        var peak = 0f
        for (i in 0 until samples) {
            val v = input.getFloat(start + i * FLOAT_BYTES)
            val magnitude = abs(v)
            if (!v.isFinite() || magnitude > MAX_PLAUSIBLE) {
                implausible++
            } else if (magnitude > peak) {
                peak = magnitude
            }
        }
        if (implausible >= MIN_IMPLAUSIBLE && implausible * 100L >= samples) {
            verdict = Verdict.INT16
            log(
                "PCM declared float is not float: $implausible of $samples samples NaN, " +
                    "infinite or beyond ±$MAX_PLAUSIBLE — the decoder wrote 16-bit; reading it as such",
            )
            return
        }
        if (countTrust && implausible == 0 && peak >= AUDIBLE_PEAK) {
            audibleFramesPassed += samples / inputFormat.channelCount
            if (audibleFramesPassed >= inputFormat.sampleRate.toLong() * TRUST_SECONDS) {
                verdict = Verdict.FLOAT
            }
        }
    }

    /**
     * How many bytes of float [rawBytes] of input become. Twice as many once
     * the stream is read as 16-bit, the same otherwise.
     */
    fun floatBytes(rawBytes: Int): Int = if (verdict == Verdict.INT16) rawBytes * 2 else rawBytes

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputFormat
        if (format == AudioFormat.NOT_SET || !inputBuffer.hasRemaining()) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        classify(inputBuffer)
        val channels = format.channelCount
        val start = inputBuffer.position()
        if (verdict == Verdict.INT16) {
            val frames = inputBuffer.remaining() / (INT16_BYTES * channels)
            val samples = frames * channels
            val out = ensureOutput(samples * FLOAT_BYTES)
            for (i in 0 until samples) {
                out.putFloat(i * FLOAT_BYTES, inputBuffer.getShort(start + i * INT16_BYTES) / 32768f)
            }
            out.limit(samples * FLOAT_BYTES)
            // Whole frames are converted; a stray part-frame is dropped (see below).
            inputBuffer.position(inputBuffer.limit())
            outputBuffer = out
            return
        }
        // Float as declared: handed on unchanged, whole frames only.
        val frameBytes = FLOAT_BYTES * channels
        val bytes = inputBuffer.remaining() / frameBytes * frameBytes
        val out = ensureOutput(bytes)
        val savedLimit = inputBuffer.limit()
        inputBuffer.limit(start + bytes)
        out.put(inputBuffer)
        inputBuffer.limit(savedLimit)
        // Real float output is whole frames. A part-frame left over means the
        // data is not what it was declared as (16-bit with an odd frame count
        // that has not been caught yet, e.g. digital silence). Left in the
        // buffer it was never consumed, the sink waited on it forever and the
        // track stalled at its end; under one frame, dropping it is inaudible.
        inputBuffer.position(savedLimit)
        out.flip()
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
            // A configure came first: a new stream, perhaps a new decoder.
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
            verdict = Verdict.UNVERIFIED
            audibleFramesPassed = 0
        }
    }

    override fun reset() {
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        verdict = Verdict.UNVERIFIED
        audibleFramesPassed = 0
        buffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
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

    private companion object {
        const val TAG = "FloatPcmGuard"
        const val FLOAT_BYTES = 4
        const val INT16_BYTES = 2

        /**
         * Far above anything a decoder emits as float: lossless decoders stay
         * within ±1, and a lossy decoder's overs on a clipped master rarely
         * pass 2. Read as float, the second sample of each 16-bit pair supplies
         * the sign and exponent. Every negative one quieter than about half of
         * full scale (-16000 and up) lands above 16 or is NaN, and so does every
         * positive one louder than about half (16768 and up).
         */
        const val MAX_PLAUSIBLE = 16f

        /** Absolute floor under the 1% rule, so one stray value never decides. */
        const val MIN_IMPLAUSIBLE = 4

        /** -60 dBFS: below it a block may be silence or dither, and proves nothing. */
        const val AUDIBLE_PEAK = 1e-3f

        const val TRUST_SECONDS = 1
    }
}
