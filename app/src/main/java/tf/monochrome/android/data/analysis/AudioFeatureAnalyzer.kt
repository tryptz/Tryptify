package tf.monochrome.android.data.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Objective, measured audio features for one track (no ML/proprietary estimates). */
data class MeasuredFeatures(
    val tempoBpm: Float,
    val loudnessDb: Float,
    val energy: Float,
    val musicalKey: Int,
    val mode: Int,
    val brightnessHz: Float,
    val durationMs: Long,
)

/**
 * On-device audio-feature analyzer. Decodes a bounded central excerpt of a
 * track to mono PCM via MediaExtractor+MediaCodec (works for local files and
 * progressive HTTP streams alike), then computes objective DSP features:
 * tempo (onset-envelope autocorrelation), loudness/energy (frame RMS),
 * spectral brightness (centroid), and key/mode (chroma + Krumhansl-Schmuckler).
 *
 * Features are near-stationary, so a ~90 s excerpt skipping the intro gives the
 * same numbers as the whole file at a fraction of the decode+FFT cost — which
 * matters when "every song" includes streamed tracks.
 */
@Singleton
class AudioFeatureAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun analyze(uri: Uri): MeasuredFeatures? = withContext(Dispatchers.Default) {
        val decoded = runCatching { decodeMono(uri) }.getOrNull() ?: return@withContext null
        if (decoded.samples.size < FFT_SIZE) return@withContext null
        computeFeatures(decoded.samples, decoded.sampleRate, decoded.durationMs)
    }

    private class Decoded(val samples: FloatArray, val sampleRate: Int, val durationMs: Long)

    /** Primitive, growable float buffer — avoids boxing millions of PCM samples. */
    private class FloatVec(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(1024))
        var size = 0; private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private fun decodeMono(uri: Uri): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L

            // Skip the intro on long tracks; analyse the central excerpt.
            if (durationMs > (SKIP_MS + MIN_TAIL_MS)) {
                extractor.seekTo(SKIP_MS * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val out = FloatVec(sampleRate * 10)
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            var collectedSamples = 0L
            val maxSamples = sampleRate.toLong() * (MAX_ANALYSIS_MS / 1000)

            while (!sawOutputEnd && collectedSamples < maxSamples) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep pumping */ }
                    else -> if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            collectedSamples += appendMono(outBuf, info, channels, out)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }

            if (out.size == 0) return null
            return Decoded(out.toArray(), sampleRate, durationMs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Reads 16-bit PCM, down-mixes to mono floats in [-1,1], returns frames added. */
    private fun appendMono(buf: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int, out: FloatVec): Long {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(info.offset)
        val shorts = buf.asShortBuffer()
        val n = info.size / 2
        var i = 0
        var frames = 0L
        while (i + channels <= n) {
            var sum = 0
            for (c in 0 until channels) sum += shorts.get(i + c).toInt()
            out.add((sum.toFloat() / channels) / 32768f)
            i += channels
            frames++
        }
        return frames
    }

    private fun computeFeatures(x: FloatArray, sampleRate: Int, durationMs: Long): MeasuredFeatures {
        val window = hann(FFT_SIZE)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val half = FFT_SIZE / 2
        val prevMag = DoubleArray(half)
        val chroma = DoubleArray(12)
        val onset = ArrayList<Double>((x.size / HOP) + 1)

        var rmsSum = 0.0
        var rmsFrames = 0
        var centroidSum = 0.0
        var centroidWeight = 0.0

        var pos = 0
        var firstFrame = true
        while (pos + FFT_SIZE <= x.size) {
            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = x[pos + i]
                energy += s.toDouble() * s
                re[i] = s * window[i]
                im[i] = 0.0
            }
            rmsSum += sqrt(energy / FFT_SIZE)
            rmsFrames++

            fft(re, im)

            var flux = 0.0
            for (k in 1 until half) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val freq = k.toDouble() * sampleRate / FFT_SIZE
                centroidSum += freq * mag
                centroidWeight += mag
                if (freq in CHROMA_MIN_HZ..CHROMA_MAX_HZ && mag > 0) {
                    val pc = ((12.0 * log2(freq / 440.0) + 69.0).roundToInt() % 12 + 12) % 12
                    chroma[pc] += mag
                }
                val d = mag - prevMag[k]
                if (d > 0) flux += d
                prevMag[k] = mag
            }
            if (!firstFrame) onset.add(flux)
            firstFrame = false
            pos += HOP
        }

        val meanRms = if (rmsFrames > 0) rmsSum / rmsFrames else 0.0
        val loudnessDb = if (meanRms > 1e-6) 20.0 * (ln(meanRms) / LN10) else -90.0
        val energy = ((loudnessDb + 60.0) / 60.0).coerceIn(0.0, 1.0)
        val brightness = if (centroidWeight > 0) (centroidSum / centroidWeight) else 0.0
        val tempo = estimateTempo(onset, sampleRate)
        val (key, mode) = estimateKey(chroma)

        return MeasuredFeatures(
            tempoBpm = tempo.toFloat(),
            loudnessDb = loudnessDb.toFloat(),
            energy = energy.toFloat(),
            musicalKey = key,
            mode = mode,
            brightnessHz = brightness.toFloat(),
            durationMs = durationMs,
        )
    }

    private fun estimateTempo(onset: List<Double>, sampleRate: Int): Double {
        if (onset.size < 16) return 0.0
        val mean = onset.average()
        val o = DoubleArray(onset.size) { (onset[it] - mean).coerceAtLeast(0.0) }
        val frameRate = sampleRate.toDouble() / HOP
        val minLag = (60.0 * frameRate / MAX_BPM).roundToInt().coerceAtLeast(1)
        val maxLag = (60.0 * frameRate / MIN_BPM).roundToInt().coerceAtMost(o.size - 1)
        if (maxLag <= minLag) return 0.0
        var bestLag = -1
        var bestVal = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until o.size - lag) sum += o[i] * o[i + lag]
            if (sum > bestVal) { bestVal = sum; bestLag = lag }
        }
        return if (bestLag > 0) 60.0 * frameRate / bestLag else 0.0
    }

    private fun estimateKey(chroma: DoubleArray): Pair<Int, Int> {
        if (chroma.sum() <= 0.0) return -1 to -1
        var bestKey = -1
        var bestMode = -1
        var bestCorr = -2.0
        for (rot in 0 until 12) {
            val maj = DoubleArray(12) { KS_MAJOR[(it - rot + 12) % 12] }
            val min = DoubleArray(12) { KS_MINOR[(it - rot + 12) % 12] }
            val cMaj = pearson(chroma, maj)
            val cMin = pearson(chroma, min)
            if (cMaj > bestCorr) { bestCorr = cMaj; bestKey = rot; bestMode = 1 }
            if (cMin > bestCorr) { bestCorr = cMin; bestKey = rot; bestMode = 0 }
        }
        return bestKey to bestMode
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        val ma = a.average()
        val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma; val xb = b[i] - mb
            num += xa * xb; da += xa * xa; db += xb * xb
        }
        val den = sqrt(da * db)
        return if (den > 0) num / den else 0.0
    }

    private fun hann(n: Int) = DoubleArray(n) { 0.5 * (1 - cos(2.0 * Math.PI * it / (n - 1))) }

    /** In-place iterative radix-2 Cooley-Tukey FFT; [re].size must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang); val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]; val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe; im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe; im[i + k + len / 2] = aIm - bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val FFT_SIZE = 2048
        private const val HOP = 1024
        private const val MAX_ANALYSIS_MS = 90_000L
        private const val SKIP_MS = 20_000L
        private const val MIN_TAIL_MS = 30_000L
        private const val TIMEOUT_US = 10_000L
        private const val MIN_BPM = 60.0
        private const val MAX_BPM = 200.0
        private const val CHROMA_MIN_HZ = 55.0
        private const val CHROMA_MAX_HZ = 5000.0
        private val LN10 = ln(10.0)
        // Krumhansl-Schmuckler key profiles.
        private val KS_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val KS_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        private val KEY_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

        /** Human label like "A minor", or null when key is undetected. */
        fun keyLabel(key: Int, mode: Int): String? {
            if (key !in 0..11) return null
            val quality = when (mode) { 1 -> "major"; 0 -> "minor"; else -> "" }
            return (KEY_NAMES[key] + " " + quality).trim()
        }
    }
}
