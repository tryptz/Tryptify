package tf.monochrome.android.audio.tempo

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Passive tap that works out the playing track's tempo ([TempoEstimator]).
 *
 * First in the chain, so it hears the track itself: before the mixer, and
 * before the speed change, which runs after every app processor. [bpm] is
 * therefore the song's own tempo; what the listener hears is that times the
 * playback speed, which the UI applies.
 *
 * Audio passes through untouched. The track's channels are summed to mono
 * for the estimate. Every second a new estimate joins the last few, and the
 * median of them is published, so a fill or a breakdown does not make the
 * number jump. [newTrack] starts over; a seek keeps what it has, since the
 * song's tempo did not change.
 */
@Singleton
@OptIn(UnstableApi::class)
class BpmTapProcessor @Inject constructor() : AudioProcessor {

    private val _bpm = MutableStateFlow<Float?>(null)

    /** The track's own tempo in BPM, or null while unknown. */
    val bpm: StateFlow<Float?> = _bpm.asStateFlow()

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var scratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var estimator: TempoEstimator? = null
    private var estimatorRate = 0
    private var mono = FloatArray(0)
    private var sinceEstimate = 0
    private val recent = ArrayDeque<Float>()

    @Volatile private var trackChanged = false

    /** A new track: forget the old tempo at the next block. */
    fun newTrack() {
        trackChanged = true
        _bpm.value = null
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputFormat.channelCount
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val frames = if (channels > 0) inputBuffer.remaining() / frameSize else 0
        if (frames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val start = inputBuffer.position()
        val byteCount = frames * frameSize

        val est = estimator
        if (est != null) {
            if (trackChanged) {
                trackChanged = false
                est.reset()
                recent.clear()
                sinceEstimate = 0
            }
            if (mono.size < frames) mono = FloatArray(frames)
            val scale = 1f / channels
            for (i in 0 until frames) {
                val base = start + i * frameSize
                var sum = 0f
                for (c in 0 until channels) {
                    sum += if (isFloat) inputBuffer.getFloat(base + c * 4)
                    else inputBuffer.getShort(base + c * 2) / 32768f
                }
                mono[i] = sum * scale
            }
            est.feed(mono, frames)
            sinceEstimate += frames
            if (sinceEstimate >= estimatorRate) {
                sinceEstimate = 0
                publish(est.estimate())
            }
        }

        // Pass through, into one reused buffer (getOutput hands it on and the
        // pipeline reads it before the next block).
        if (scratch.capacity() < byteCount) {
            scratch = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        val savedLimit = inputBuffer.limit()
        inputBuffer.limit(start + byteCount)
        scratch.put(inputBuffer)
        inputBuffer.limit(savedLimit)
        scratch.flip()
        outputBuffer = scratch
    }

    private fun publish(estimate: Float?) {
        if (estimate == null) return
        // A new reading an octave from the settled one is the same pulse
        // counted differently; fold it back rather than let it flip the
        // display between 87 and 174.
        val settled = _bpm.value
        val folded = if (settled == null) estimate else when {
            abs(estimate * 2f - settled) < settled * OCTAVE_TOLERANCE -> estimate * 2f
            abs(estimate / 2f - settled) < settled * OCTAVE_TOLERANCE -> estimate / 2f
            else -> estimate
        }
        recent.addLast(folded)
        while (recent.size > MEDIAN_OF) recent.removeFirst()
        val sorted = recent.sorted()
        val median = sorted[sorted.size / 2]
        _bpm.value = (median * 10f).roundToInt() / 10f
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
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
        val rate = inputFormat.sampleRate
        if (rate <= 0) return
        if (estimator == null || estimatorRate != rate) {
            estimator = TempoEstimator(rate)
            estimatorRate = rate
            recent.clear()
        } else {
            // A seek or a new pipeline mid-song: same tempo, but the next
            // step must not read the jump as a beat.
            estimator?.discontinuity()
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        estimator = null
        estimatorRate = 0
        recent.clear()
    }

    private companion object {
        const val MEDIAN_OF = 5
        const val OCTAVE_TOLERANCE = 0.04f
    }
}
