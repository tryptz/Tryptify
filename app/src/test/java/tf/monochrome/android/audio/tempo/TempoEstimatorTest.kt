package tf.monochrome.android.audio.tempo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tempo from synthetic tracks with a known pulse: a kick on every beat and a
 * hat on the off-beat, the shape most of the music this is for has.
 */
class TempoEstimatorTest {

    private val rate = 44100

    /** [seconds] of a kick on the beat and a hat between, at [bpm]. */
    private fun beat(bpm: Float, seconds: Float, seed: Int = 1): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        val period = 60.0 * rate / bpm
        val random = Random(seed)
        var k = 0
        while (true) {
            val at = (k * period).toInt()
            if (at >= n) break
            // Kick: a 55 Hz thump with a fast decay.
            for (i in 0 until (0.15 * rate).toInt()) {
                if (at + i >= n) break
                val t = i.toDouble() / rate
                out[at + i] += (0.8 * sin(2 * PI * 55 * t) * exp(-t * 30)).toFloat()
            }
            // Hat on the off-beat: a short burst of noise.
            val off = (at + period / 2).toInt()
            for (i in 0 until (0.03 * rate).toInt()) {
                if (off + i >= n) break
                val t = i.toDouble() / rate
                out[off + i] += ((random.nextFloat() - 0.5f) * 0.3f * exp(-t * 120)).toFloat()
            }
            k++
        }
        return out
    }

    private fun estimateOf(audio: FloatArray): Float? {
        val est = TempoEstimator(rate)
        // In playback-sized blocks, as the tap feeds it.
        val block = FloatArray(4096)
        var i = 0
        while (i < audio.size) {
            val n = minOf(block.size, audio.size - i)
            System.arraycopy(audio, i, block, 0, n)
            est.feed(block, n)
            i += n
        }
        return est.estimate()
    }

    @Test
    fun `house at 128`() {
        val bpm = estimateOf(beat(128f, 14f))
        assertNotNull(bpm)
        assertEquals(128f, bpm!!, 1f)
    }

    @Test
    fun `drum and bass at 174 is not read at half time`() {
        val bpm = estimateOf(beat(174f, 14f))
        assertNotNull(bpm)
        assertEquals(174f, bpm!!, 1.5f)
    }

    @Test
    fun `hip-hop at 90`() {
        val bpm = estimateOf(beat(90f, 14f))
        assertNotNull(bpm)
        assertEquals(90f, bpm!!, 1f)
    }

    @Test
    fun `a fractional tempo resolves finer than one envelope step`() {
        val bpm = estimateOf(beat(123.4f, 14f))
        assertNotNull(bpm)
        assertEquals(123.4f, bpm!!, 0.6f)
    }

    @Test
    fun `no answer before there is enough audio`() {
        assertNull(estimateOf(beat(128f, 3f)))
    }

    @Test
    fun `no pulse, no tempo`() {
        val random = Random(9)
        assertNull(estimateOf(FloatArray(rate * 12) { (random.nextFloat() - 0.5f) * 0.4f }))
        assertNull(estimateOf(FloatArray(rate * 12)))
    }

    @Test
    fun `a new track starts over`() {
        val est = TempoEstimator(rate)
        val first = beat(128f, 12f)
        est.feed(first, first.size)
        assertNotNull(est.estimate())
        est.reset()
        assertNull(est.estimate())
    }
}
