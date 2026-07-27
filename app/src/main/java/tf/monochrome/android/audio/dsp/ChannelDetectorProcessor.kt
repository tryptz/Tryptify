package tf.monochrome.android.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10

/**
 * Passive channel detector that taps the decoded stream without altering it.
 *
 * Sits at the very HEAD of the AudioProcessor chain — before the Atmos
 * renderer and the stereo fold-down — so it sees the source exactly as the
 * decoder emits it. Two jobs:
 *
 *  1. Format identification (always on, free): every reconfigure publishes
 *     the incoming channel count, the assumed layout name ("5.1", "7.1",
 *     "9.1.6", …) and per-channel names matching [DownmixProcessor]'s
 *     order tables, plus sample rate / encoding.
 *  2. Per-channel activity metering (on demand): while at least one UI
 *     subscriber holds an [acquire], the audio thread tracks a running
 *     peak per input channel and a ~10 Hz coroutine publishes decayed
 *     dBFS peaks. That answers "is this 16-ch file REALLY 9.1.6, and
 *     which channels actually carry signal?" — Media3 only reports a
 *     count, never a mask, so measuring is the only way to know.
 *
 * Pass-through follows SpectrumAnalyzerTap: index-based reads (no
 * asFloatBuffer()/asShortBuffer() view allocations on the audio thread)
 * and a reused direct output buffer. Metering costs one abs()+max per
 * sample per channel and only while the detector UI is visible.
 */
@Singleton
@OptIn(UnstableApi::class)
class ChannelDetectorProcessor @Inject constructor() : AudioProcessor {

    /** Snapshot of the detected input. [peaksDb] is per-channel dBFS (decayed). */
    data class ChannelState(
        val channelCount: Int,
        val sampleRate: Int,
        val isFloat: Boolean,
        val layoutName: String,
        val channelNames: List<String>,
        val peaksDb: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ChannelState && other.channelCount == channelCount &&
                other.sampleRate == sampleRate && other.isFloat == isFloat &&
                other.layoutName == layoutName && other.channelNames == channelNames &&
                other.peaksDb.contentEquals(peaksDb)

        override fun hashCode(): Int = 31 * channelCount + peaksDb.contentHashCode()
    }

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Linear running peaks, one slot per input channel. Written on the audio
    // thread (monotonic max), read + decayed by the publisher coroutine. The
    // unguarded float races are benign for metering.
    @Volatile private var peaks = FloatArray(0)
    @Volatile private var meterActive = false

    private val subscriberCount = AtomicInteger(0)
    private val lifecycleLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var publishJob: Job? = null

    private val _state = MutableStateFlow<ChannelState?>(null)
    val state: StateFlow<ChannelState?> = _state.asStateFlow()

    /**
     * Reference-count meter subscribers (same contract as
     * SpectrumAnalyzerTap): pair each [acquire] with exactly one [release],
     * wired from a DisposableEffect. Format identification stays live either
     * way; only the per-channel peak scan is gated.
     */
    fun acquire() {
        val count = subscriberCount.incrementAndGet()
        if (count == 1) {
            synchronized(lifecycleLock) {
                if (subscriberCount.get() > 0 && !meterActive) startMeterLocked()
            }
        }
    }

    fun release() {
        val count = subscriberCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (count == 0) {
            synchronized(lifecycleLock) {
                if (subscriberCount.get() == 0 && meterActive) stopMeterLocked()
            }
        }
    }

    private fun startMeterLocked() {
        meterActive = true
        java.util.Arrays.fill(peaks, 0f)
        publishJob?.cancel()
        publishJob = scope.launch {
            while (isActive) {
                publishState()
                // Read-then-decay so a steady tone holds and silence falls
                // ~14 dB/s (0.72^10 ≈ −14 dB over ten 100 ms ticks).
                val p = peaks
                for (c in p.indices) p[c] *= 0.72f
                delay(100)
            }
        }
    }

    private fun stopMeterLocked() {
        meterActive = false
        publishJob?.cancel()
        publishJob = null
        java.util.Arrays.fill(peaks, 0f)
        publishState() // leave the format info up, peaks floored
    }

    private fun publishState() {
        val fmt = inputFormat
        if (fmt == AudioFormat.NOT_SET) {
            _state.value = null
            return
        }
        val p = peaks
        val db = FloatArray(fmt.channelCount) { c ->
            val v = if (c < p.size) p[c] else 0f
            if (v > 1e-6f) 20f * log10(v) else PEAK_FLOOR_DB
        }
        _state.value = ChannelState(
            channelCount = fmt.channelCount,
            sampleRate = fmt.sampleRate,
            isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT,
            layoutName = layoutName(fmt.channelCount),
            channelNames = channelNames(fmt.channelCount),
            peaksDb = db,
        )
    }

    /** Test/diagnostic hook: copy of the current linear per-channel peaks. */
    fun currentPeaks(): FloatArray = peaks.copyOf()

    // ── AudioProcessor (pure pass-through) ──────────────────────────────

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
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        if (channels < 1) return
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        val byteCount = numFrames * frameSize
        val startPos = inputBuffer.position()

        if (meterActive) {
            val p = peaks
            if (p.size >= channels) {
                for (i in 0 until numFrames) {
                    val base = startPos + i * frameSize
                    for (c in 0 until channels) {
                        val v = if (isFloat) {
                            abs(inputBuffer.getFloat(base + c * 4))
                        } else {
                            abs(inputBuffer.getShort(base + c * 2).toFloat()) / 32768f
                        }
                        if (v > p[c]) p[c] = v
                    }
                }
            }
        }

        // Pass through unmodified (same narrowed-limit bulk copy as
        // SpectrumAnalyzerTap — no duplicate() wrapper allocation).
        if (outputBuffer.capacity() < byteCount) {
            outputBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        val savedLimit = inputBuffer.limit()
        inputBuffer.limit(startPos + byteCount)
        outputBuffer.put(inputBuffer)
        inputBuffer.limit(savedLimit)
        outputBuffer.flip()
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
            val changed = inputFormat != pendingFormat
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
            if (changed) {
                peaks = FloatArray(inputFormat.channelCount)
                publishState()
            }
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        peaks = FloatArray(0)
        _state.value = null
    }

    companion object {
        /** Meter floor; also what an idle/silent channel reports. */
        const val PEAK_FLOOR_DB = -120f

        /** A channel above this (decayed) peak is considered active. */
        const val ACTIVE_THRESHOLD_DB = -60f

        /** Human name for a channel count, matching DownmixProcessor's tables. */
        fun layoutName(count: Int): String = when (count) {
            1 -> "Mono"
            2 -> "Stereo"
            3 -> "3.0"
            4 -> "Quad"
            5 -> "5.0"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            16 -> "9.1.6"
            else -> "$count ch"
        }

        /**
         * Assumed channel order per count — MUST stay in sync with
         * [DownmixProcessor]'s RAW_L_ROWS tables. Counts without a known
         * layout get generic "Ch n" labels.
         */
        fun channelNames(count: Int): List<String> = when (count) {
            1 -> listOf("M")
            2 -> listOf("FL", "FR")
            3 -> listOf("FL", "FR", "FC")
            4 -> listOf("FL", "FR", "BL", "BR")
            5 -> listOf("FL", "FR", "FC", "BL", "BR")
            6 -> listOf("FL", "FR", "FC", "LFE", "BL", "BR")
            7 -> listOf("FL", "FR", "FC", "LFE", "BC", "SL", "SR")
            8 -> listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
            16 -> listOf(
                "FL", "FR", "FC", "LFE", "BL", "BR", "BLC", "BRC",
                "SL", "SR", "TFL", "TFR", "TSL", "TSR", "TBL", "TBR",
            )
            else -> List(count) { "Ch ${it + 1}" }
        }
    }
}
