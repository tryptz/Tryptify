package tf.monochrome.android.audio.resample

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 * The resampler blends a frame's taps once and runs them over every channel.
 * It used to blend them again for each channel, which is the same arithmetic
 * repeated, so the output must not move by a single bit. Held here against a
 * copy of the old per-channel loop, across block sizes, ratio changes (and a
 * ratio coming back, which takes its kernel from the cache) and both
 * encodings.
 */
class VariRateBitExactTest {

    private val fs = 48000

    /** The old inner loop: the blend done inside the per-channel sum. */
    private class PerChannelBlend(private val channels: Int, ratio: Float) {
        private val halfWidth = 32
        private val histLen = 2 * halfWidth
        private var ratio = ratio
        private var kernel = SincKernel.design(ratio.toDouble(), halfWidth, 256)
        private val hist = Array(channels) { FloatArray(histLen) }
        private var pos = histLen.toDouble()

        fun setRatio(r: Float) {
            ratio = r
            kernel = SincKernel.design(r.toDouble(), halfWidth, 256)
        }

        /** [input] interleaved; returns the interleaved output samples. */
        fun process(input: FloatArray): FloatArray {
            val numFrames = input.size / channels
            val total = histLen + numFrames
            val work = Array(channels) { c ->
                FloatArray(total).also { w ->
                    System.arraycopy(hist[c], 0, w, 0, histLen)
                    for (n in 0 until numFrames) w[histLen + n] = input[n * channels + c]
                }
            }
            val maxOut = ceil(numFrames / ratio.toDouble()).toInt() + 2
            val out = ArrayList<Float>()
            val step = ratio.toDouble()
            val last = total - 1 - halfWidth
            var produced = 0
            while (floor(pos) <= last && produced < maxOut) {
                val i = floor(pos).toInt()
                val frac = pos - i
                for (c in 0 until channels) out += filter(work[c], i, frac)
                produced++
                pos += step
            }
            for (c in 0 until channels) System.arraycopy(work[c], total - histLen, hist[c], 0, histLen)
            pos -= numFrames
            return out.toFloatArray()
        }

        private fun filter(work: FloatArray, i: Int, frac: Double): Float {
            val width = 2 * kernel.halfWidth
            val base = i - kernel.halfWidth + 1
            val phasePos = frac * kernel.phases
            val p = phasePos.toInt().coerceIn(0, kernel.phases - 1)
            val blend = (phasePos - p).toFloat()
            val a = kernel.taps[p]
            val b = kernel.taps[p + 1]
            var acc = 0f
            for (k in 0 until width) {
                val tap = a[k] + (b[k] - a[k]) * blend
                acc += work[base + k] * tap
            }
            return acc
        }
    }

    private fun toPcm16(v: Float): Int = (v * 32768f).toInt().coerceIn(-32768, 32767)

    /**
     * Runs [blocks] of [channels]-channel audio through both, setting the
     * ratio from [ratios] before each block, and asserts every sample matches.
     */
    private fun compare(channels: Int, encoding: Int, blocks: List<Int>, ratios: List<Float>) {
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val random = Random(channels * 31 + blocks.size)
        val p = VariRateAudioProcessor()
        p.configure(AudioFormat(fs, channels, encoding))
        p.setRatio(ratios[0])
        p.flush()
        val reference = PerChannelBlend(channels, ratios[0])
        var compared = 0
        for ((index, frames) in blocks.withIndex()) {
            val ratio = ratios[index % ratios.size]
            p.setRatio(ratio)
            reference.setRatio(ratio)
            val samples = frames * channels
            val shorts = IntArray(samples) { random.nextInt(-32768, 32768) }
            val floats = FloatArray(samples) { random.nextFloat() * 1.8f - 0.9f }
            val buf = ByteBuffer.allocateDirect(samples * (if (isFloat) 4 else 2)).order(ByteOrder.nativeOrder())
            for (s in 0 until samples) {
                if (isFloat) buf.putFloat(floats[s]) else buf.putShort(shorts[s].toShort())
            }
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            val expected = reference.process(
                if (isFloat) floats else FloatArray(samples) { shorts[it].toShort().toFloat() / 32768f },
            )
            val got = o.remaining() / (if (isFloat) 4 else 2)
            assertEquals("block $index sample count", expected.size, got)
            for (s in 0 until got) {
                if (isFloat) {
                    val v = o.getFloat(o.position() + s * 4)
                    assertEquals(
                        "block $index sample $s",
                        java.lang.Float.floatToRawIntBits(expected[s]),
                        java.lang.Float.floatToRawIntBits(v),
                    )
                } else {
                    val v = o.getShort(o.position() + s * 2).toInt()
                    assertEquals("block $index sample $s", toPcm16(expected[s]), v)
                }
            }
            compared += got
        }
        assertTrue("something was compared", compared > 0)
    }

    @Test
    fun `a 5_1 float stream matches the per-channel blend to the bit`() {
        compare(
            channels = 6,
            encoding = C.ENCODING_PCM_FLOAT,
            blocks = listOf(997, 1500, 64, 4096, 1024, 333, 2048, 1024),
            ratios = listOf(1.37f, 1.37f, 0.73f, 0.73f, 1.37f, 2.5f, 1.0001f, 0.5f),
        )
    }

    @Test
    fun `stereo 16-bit matches the per-channel blend to the bit`() {
        compare(
            channels = 2,
            encoding = C.ENCODING_PCM_16BIT,
            blocks = listOf(1024, 1024, 777, 4096, 512),
            ratios = listOf(1.25f, 0.8f, 1.25f, 1.6f, 0.8f),
        )
    }

    @Test
    fun `mono float matches the per-channel blend to the bit`() {
        compare(
            channels = 1,
            encoding = C.ENCODING_PCM_FLOAT,
            blocks = listOf(4096, 100, 4096),
            ratios = listOf(1.059463f, 0.943874f, 1.059463f),
        )
    }
}
