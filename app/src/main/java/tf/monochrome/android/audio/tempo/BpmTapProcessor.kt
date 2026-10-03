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
import java.util.concurrent.atomic.AtomicInteger
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
 * and always fed to the estimator, which is a few multiplies a sample, so a
 * measurement can start from the last ten seconds instead of waiting for them.
 *
 * It measures once and then holds still. A measurement takes one estimate a
 * second until [READINGS] of them have come in, publishes their median and
 * stops; nothing is published in between. It used to publish every second for
 * the whole song, and the number kept moving under the listener — a fill or a
 * breakdown nudged it even through the median. A new track measures once on
 * its own ([newTrack]); after that only [measure] (a tap on the number) or
 * [setTempo] (the listener typing the song's tempo) changes it. A seek keeps
 * what it has, since the song's tempo did not change.
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

    /**
     * The latest request from outside the audio thread, applied at the next
     * block. One slot, latest wins: a tempo typed just after a track change
     * must not be undone by that change's measurement, and the reverse.
     */
    private val request = AtomicInteger(REQUEST_NONE)

    /** Taking readings; touched only on the audio thread. */
    private var measuring = false

    /** A new track: forget the old tempo and measure the new one once. */
    fun newTrack() {
        trackChanged = true
        request.set(REQUEST_MEASURE)
        _bpm.value = null
    }

    /** Measure the playing track again, from the audio of the last few seconds on. */
    fun measure() {
        request.set(REQUEST_MEASURE)
        _bpm.value = null
    }

    /** The listener's own figure for the track's tempo; holds until the track changes. */
    fun setTempo(bpm: Float) {
        request.set(REQUEST_SET)
        _bpm.value = (bpm * 10f).roundToInt() / 10f
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
            }
            when (request.getAndSet(REQUEST_NONE)) {
                REQUEST_MEASURE -> {
                    measuring = true
                    recent.clear()
                    // Estimate at once: the envelope already holds what played.
                    sinceEstimate = estimatorRate
                }
                REQUEST_SET -> {
                    measuring = false
                    recent.clear()
                }
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
            if (measuring) {
                sinceEstimate += frames
                if (sinceEstimate >= estimatorRate) {
                    sinceEstimate = 0
                    takeReading(est.estimate())
                }
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

    /**
     * One reading of a measurement. No reading (too little audio yet, or no
     * steady pulse) does not count, so a measurement started in an intro
     * waits for the beat to arrive.
     */
    private fun takeReading(estimate: Float?) {
        if (estimate == null) return
        // A reading an octave from the first is the same pulse counted
        // differently; fold it back rather than let one stray 174 among 87s
        // move the median.
        val first = recent.firstOrNull()
        val folded = if (first == null) estimate else when {
            abs(estimate * 2f - first) < first * OCTAVE_TOLERANCE -> estimate * 2f
            abs(estimate / 2f - first) < first * OCTAVE_TOLERANCE -> estimate / 2f
            else -> estimate
        }
        recent.addLast(folded)
        if (recent.size < READINGS) return
        measuring = false
        val sorted = recent.sorted()
        val median = sorted[sorted.size / 2]
        recent.clear()
        // A request that arrived during this block wins over the reading it
        // would replace.
        if (request.get() == REQUEST_NONE) _bpm.value = (median * 10f).roundToInt() / 10f
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
            // A measurement under way starts its readings over at the new rate.
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
        /** Readings in one measurement, a second apart; their median is published. */
        const val READINGS = 5
        const val OCTAVE_TOLERANCE = 0.04f
        const val REQUEST_NONE = 0
        const val REQUEST_MEASURE = 1
        const val REQUEST_SET = 2
    }
}
