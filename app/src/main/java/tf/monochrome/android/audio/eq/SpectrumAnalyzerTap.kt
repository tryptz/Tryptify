package tf.monochrome.android.audio.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.android.performance.PerformanceProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Passive FFT spectrum analyzer that taps the audio stream without altering it.
 *
 * - Runs a radix-2 Cooley-Tukey FFT on a mono-summed sliding window.
 * - Emits magnitudes as log-spaced bins at ~120 Hz, pink-noise compensated (+3.5 dB/oct)
 *   and re-centered so pink noise sits on the 0 dB line.
 * - Only runs analysis coroutine while [setActive] is true (to save CPU when the EQ editor is closed).
 */
@Singleton
@OptIn(UnstableApi::class)
class SpectrumAnalyzerTap @Inject constructor(
    performanceProfile: PerformanceProfile,
) : AudioProcessor {

    companion object {
        const val FFT_SIZE_4K = 4096
        const val FFT_SIZE_8K = 8192
        const val FFT_SIZE_16K = 16384
        // Legacy aliases (kept so existing call sites still resolve).
        const val FFT_SIZE_LOW = FFT_SIZE_8K
        const val FFT_SIZE_HIGH = FFT_SIZE_16K
        const val OUTPUT_BINS = 256
        private const val MIN_FREQ = 20f
        private const val MAX_FREQ = 20000f
        private const val PINK_SLOPE_DB_PER_OCT = 4.0f
        // Slow exponential smoothing → ~176 ms time constant @ 60 fps (SPAN-like Avg Time).
        private const val SMOOTH_ATTACK = 0.55f
        private const val SMOOTH_RELEASE = 0.09f
    }

    // Frame cadence picked from the device tier: LOW=15 fps, MID=30 fps, HIGH=60 fps.
    // The visible smoothing constants above are tuned for 60 fps; slower tiers look
    // a touch more damped, which is fine — they also run on thermally-constrained
    // hardware where running an analyzer at 60 Hz would be the dominant cost.
    private val frameDelayMs: Long = (1000L / performanceProfile.spectrumFps.coerceAtLeast(1))

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var sampleRate = 48000

    // Active ring buffer (mono samples)
    @Volatile private var ring: FloatArray = FloatArray(FFT_SIZE_HIGH)
    @Volatile private var ringWrite = 0

    @Volatile var fftSize: Int = FFT_SIZE_8K
        set(value) {
            val clamped = when {
                value <= FFT_SIZE_4K -> FFT_SIZE_4K
                value <= FFT_SIZE_8K -> FFT_SIZE_8K
                else -> FFT_SIZE_16K
            }
            if (clamped != field) {
                field = clamped
                // Reallocate FFT work arrays lazily on next frame
                _analysisDirty = true
            }
        }

    @Volatile private var _analysisDirty = true
    @Volatile private var analysisActive = false
    private val subscriberCount = AtomicInteger(0)
    private val lifecycleLock = Any()

    private val _spectrumBins = MutableStateFlow(FloatArray(OUTPUT_BINS))
    val spectrumBins: StateFlow<FloatArray> = _spectrumBins.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var analysisJob: Job? = null

    /**
     * Reference-count subscribers. Multiple screens (Now Playing spectrum,
     * Parametric EQ, Parametric EQ Edit, Settings preview) can hold a stake
     * simultaneously; analysis only stops when every holder has released.
     *
     * Callers must pair each [acquire] with exactly one [release]. Wire from
     * a DisposableEffect so screen dispose always releases, even across nav
     * crossfades where the new screen mounts before the old one disposes.
     */
    fun acquire() {
        val count = subscriberCount.incrementAndGet()
        if (count == 1) {
            synchronized(lifecycleLock) {
                if (subscriberCount.get() > 0 && !analysisActive) {
                    startAnalysisLocked()
                }
            }
        }
    }

    fun release() {
        val count = subscriberCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (count == 0) {
            synchronized(lifecycleLock) {
                if (subscriberCount.get() == 0 && analysisActive) {
                    stopAnalysisLocked()
                }
            }
        }
    }

    private fun startAnalysisLocked() {
        analysisActive = true
        // Restart from a coherent state: a prior stop can leave `ring` frozen
        // mid-write (queueInput skips writes while inactive) and `_analysisDirty`
        // false, so the restarted coroutine would trust stale work arrays
        // against a stale ring. Force reallocation and refill so the first FFT
        // reads only post-re-enable audio.
        _analysisDirty = true
        ringWrite = 0
        java.util.Arrays.fill(ring, 0f)
        analysisJob?.cancel()
        analysisJob = scope.launch {
            // Local FFT work arrays (reallocated on fftSize change)
            var currentSize = fftSize
            var window = buildHannWindow(currentSize)
            var real = FloatArray(currentSize)
            var imag = FloatArray(currentSize)
            var twiddleCos = buildTwiddleCos(currentSize)
            var twiddleSin = buildTwiddleSin(currentSize)
            var smoothed = FloatArray(OUTPUT_BINS)
            var binMap = buildBinMap(currentSize, sampleRate, OUTPUT_BINS)

            while (isActive) {
                // Re-allocate work arrays if size changed
                if (_analysisDirty || currentSize != fftSize) {
                    currentSize = fftSize
                    window = buildHannWindow(currentSize)
                    real = FloatArray(currentSize)
                    imag = FloatArray(currentSize)
                    twiddleCos = buildTwiddleCos(currentSize)
                    twiddleSin = buildTwiddleSin(currentSize)
                    binMap = buildBinMap(currentSize, sampleRate, OUTPUT_BINS)
                    smoothed = FloatArray(OUTPUT_BINS)
                    _analysisDirty = false
                }

                // Copy last N samples from ring buffer
                val n = currentSize
                val ringLocal = ring
                val ringLen = ringLocal.size
                val writeIdx = ringWrite
                var startIdx = writeIdx - n
                if (startIdx < 0) startIdx += ringLen

                for (i in 0 until n) {
                    val idx = (startIdx + i) % ringLen
                    real[i] = ringLocal[idx] * window[i]
                    imag[i] = 0f
                }

                fft(real, imag, twiddleCos, twiddleSin)

                // Compute magnitudes for target log-frequency bins
                val newBins = FloatArray(OUTPUT_BINS)
                for (b in 0 until OUTPUT_BINS) {
                    val fftBin = binMap[b]
                    if (fftBin <= 0 || fftBin >= n / 2) continue
                    val re = real[fftBin]
                    val im = imag[fftBin]
                    val mag = sqrt(re * re + im * im) / (n / 2f)
                    // dBFS
                    val db = if (mag > 1e-9f) 20f * log10(mag) else -120f
                    newBins[b] = db
                }

                // Pink compensation & centering
                val sr = sampleRate
                for (b in 0 until OUTPUT_BINS) {
                    val freq = binFrequency(b, sr)
                    val tilt = PINK_SLOPE_DB_PER_OCT * log2(freq / 1000f)
                    newBins[b] += tilt
                }

                // Re-center so midband (200..2000 Hz) average sits at 0 dB
                var midSum = 0f
                var midCount = 0
                for (b in 0 until OUTPUT_BINS) {
                    val f = binFrequency(b, sr)
                    if (f in 200f..2000f) {
                        midSum += newBins[b]
                        midCount++
                    }
                }
                val centerOffset = if (midCount > 0) midSum / midCount else 0f
                for (b in 0 until OUTPUT_BINS) {
                    newBins[b] -= centerOffset
                }

                // Temporal smoothing — fast attack, slow release for SPAN-like
                // held-peak feel (~176 ms release time constant at 60 fps).
                for (b in 0 until OUTPUT_BINS) {
                    val target = newBins[b]
                    val prev = smoothed[b]
                    val coef = if (target > prev) SMOOTH_ATTACK else SMOOTH_RELEASE
                    smoothed[b] = prev + coef * (target - prev)
                }

                // Spatial smoothing — 7-tap gaussian across log-frequency bins.
                // With 256 bins / ~10 octaves this is ~1/12-octave smoothing,
                // matching the flowing envelope of SPAN / FabFilter Pro-Q.
                val out = FloatArray(OUTPUT_BINS)
                val g0 = 0.30f; val g1 = 0.22f; val g2 = 0.10f; val g3 = 0.03f
                for (b in 0 until OUTPUT_BINS) {
                    val l3 = smoothed[(b - 3).coerceAtLeast(0)]
                    val l2 = smoothed[(b - 2).coerceAtLeast(0)]
                    val l1 = smoothed[(b - 1).coerceAtLeast(0)]
                    val c = smoothed[b]
                    val r1 = smoothed[(b + 1).coerceAtMost(OUTPUT_BINS - 1)]
                    val r2 = smoothed[(b + 2).coerceAtMost(OUTPUT_BINS - 1)]
                    val r3 = smoothed[(b + 3).coerceAtMost(OUTPUT_BINS - 1)]
                    out[b] = g3 * (l3 + r3) + g2 * (l2 + r2) + g1 * (l1 + r1) + g0 * c
                }

                _spectrumBins.value = out

                delay(frameDelayMs)
            }
        }
    }

    private fun stopAnalysisLocked() {
        analysisActive = false
        analysisJob?.cancel()
        analysisJob = null
        // Reset bins so the UI doesn't show stale data on re-entry.
        _spectrumBins.value = FloatArray(OUTPUT_BINS)
    }

    // --- AudioProcessor (pure pass-through) ---

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
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * channels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        val byteCount = numFrames * frameSize
        val startPos = inputBuffer.position()

        if (analysisActive) {
            // Mono-sum into ring buffer via index-based reads — no duplicate() /
            // asFloatBuffer() / asShortBuffer() wrapper allocations per call.
            val ringLocal = ring
            val ringLen = ringLocal.size
            var w = ringWrite
            if (encoding == C.ENCODING_PCM_FLOAT) {
                if (channels == 1) {
                    for (i in 0 until numFrames) {
                        ringLocal[w] = inputBuffer.getFloat(startPos + i * 4)
                        w++
                        if (w >= ringLen) w = 0
                    }
                } else {
                    for (i in 0 until numFrames) {
                        val off = startPos + i * 8
                        val l = inputBuffer.getFloat(off)
                        val r = inputBuffer.getFloat(off + 4)
                        ringLocal[w] = (l + r) * 0.5f
                        w++
                        if (w >= ringLen) w = 0
                    }
                }
            } else {
                if (channels == 1) {
                    for (i in 0 until numFrames) {
                        ringLocal[w] = inputBuffer.getShort(startPos + i * 2).toFloat() / 32768f
                        w++
                        if (w >= ringLen) w = 0
                    }
                } else {
                    for (i in 0 until numFrames) {
                        val off = startPos + i * 4
                        val l = inputBuffer.getShort(off).toFloat() / 32768f
                        val r = inputBuffer.getShort(off + 2).toFloat() / 32768f
                        ringLocal[w] = (l + r) * 0.5f
                        w++
                        if (w >= ringLen) w = 0
                    }
                }
            }
            ringWrite = w
        }

        // Pass through without allocating a duplicate ByteBuffer wrapper.
        // Temporarily narrow the source limit so put(src) copies exactly the
        // slice we want, then restore. The relative `put(ByteBuffer)` call
        // advances both positions — inputBuffer ends at startPos + byteCount,
        // matching what DefaultAudioSink expects from an AudioProcessor.
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
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            val formatChanged = inputFormat == AudioFormat.NOT_SET
                || inputFormat.sampleRate != pendingFormat.sampleRate
                || inputFormat.encoding != pendingFormat.encoding
                || inputFormat.channelCount != pendingFormat.channelCount
            if (formatChanged) {
                inputFormat = pendingFormat
                sampleRate = inputFormat.sampleRate
                _analysisDirty = true
            }
            pendingFormat = AudioFormat.NOT_SET
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
    }

    // --- Helpers ---

    private fun buildHannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))).toFloat()
        }
        return w
    }

    /**
     * Precomputed cos twiddle factors: cos(-2pi*k/n) for k in 0 until n/2
     */
    private fun buildTwiddleCos(n: Int): FloatArray {
        val t = FloatArray(n / 2)
        for (k in 0 until n / 2) {
            t[k] = cos(-2.0 * PI * k / n).toFloat()
        }
        return t
    }

    /**
     * Precomputed sin twiddle factors: sin(-2pi*k/n) for k in 0 until n/2
     */
    private fun buildTwiddleSin(n: Int): FloatArray {
        val t = FloatArray(n / 2)
        for (k in 0 until n / 2) {
            t[k] = sin(-2.0 * PI * k / n).toFloat()
        }
        return t
    }

    private fun binFrequency(outBin: Int, sr: Int): Float {
        val logMin = log10(MIN_FREQ)
        val logMax = log10(max(MIN_FREQ + 1f, minOf(MAX_FREQ, sr / 2f - 1f)))
        val t = outBin.toFloat() / (OUTPUT_BINS - 1).toFloat()
        val logF = logMin + t * (logMax - logMin)
        return Math.pow(10.0, logF.toDouble()).toFloat()
    }

    private fun buildBinMap(n: Int, sr: Int, outBins: Int): IntArray {
        val map = IntArray(outBins)
        val binWidth = sr.toFloat() / n.toFloat()
        for (b in 0 until outBins) {
            val freq = binFrequency(b, sr)
            val fftBin = (freq / binWidth).toInt().coerceIn(1, n / 2 - 1)
            map[b] = fftBin
        }
        return map
    }

    /**
     * In-place radix-2 iterative Cooley-Tukey FFT. Size must be a power of 2.
     * Twiddle tables must be precomputed for n (length n/2, indexing stride n/size per stage).
     */
    private fun fft(real: FloatArray, imag: FloatArray, tCos: FloatArray, tSin: FloatArray) {
        val n = real.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val tableStep = n / size
            var k = 0
            while (k < n) {
                var tIdx = 0
                for (m in 0 until half) {
                    val cosA = tCos[tIdx]
                    val sinA = tSin[tIdx]
                    val iEven = k + m
                    val iOdd = k + m + half
                    val tre = real[iOdd] * cosA - imag[iOdd] * sinA
                    val tim = real[iOdd] * sinA + imag[iOdd] * cosA
                    real[iOdd] = real[iEven] - tre
                    imag[iOdd] = imag[iEven] - tim
                    real[iEven] = real[iEven] + tre
                    imag[iEven] = imag[iEven] + tim
                    tIdx += tableStep
                }
                k += size
            }
            size = size shl 1
        }
    }
}
