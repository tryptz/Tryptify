package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.log10
import kotlin.math.pow

/**
 * [AutoEqEngine.ResponseGrid] exists purely to stop the graph re-designing a
 * filter once per pixel. It is only a legitimate substitution if it returns
 * exactly what the per-point [AutoEqEngine.calculateBiquadResponse] returns,
 * so that equality is the thing pinned here — a divergence would silently
 * misdraw the correction curve the user is tuning against.
 */
class AutoEqResponseGridTest {

    private val sampleRate = 48000f

    /** A log-spaced 20 Hz–20 kHz axis, the shape the graph actually evaluates on. */
    private fun logAxis(n: Int = 200): FloatArray {
        val lo = log10(20f)
        val hi = log10(20000f)
        return FloatArray(n) { i -> 10f.pow(lo + i.toFloat() / (n - 1) * (hi - lo)) }
    }

    private val bands = listOf(
        EqBand(0, FilterType.PEAKING, 60f, 6f, 1.0f, true),
        EqBand(1, FilterType.LOWSHELF, 120f, -4.5f, 0.707f, true),
        EqBand(2, FilterType.PEAKING, 3200f, -9f, 3.5f, true),
        EqBand(3, FilterType.HIGHSHELF, 10000f, 7.25f, 0.707f, true),
        EqBand(4, FilterType.PEAKING, 18000f, 12f, 1f, true),
    )

    @Test
    fun `per-band response matches the per-point calculation exactly`() {
        val freqs = logAxis()
        val grid = AutoEqEngine.ResponseGrid(freqs, sampleRate)
        for (band in bands) {
            val batched = grid.response(band)
            for (i in freqs.indices) {
                assertEquals(
                    "band ${band.id} (${band.type}) at ${freqs[i]} Hz",
                    AutoEqEngine.calculateBiquadResponse(freqs[i], band, sampleRate),
                    batched[i],
                    0f,
                )
            }
        }
    }

    @Test
    fun `summed response matches point-wise summation`() {
        val freqs = logAxis()
        val grid = AutoEqEngine.ResponseGrid(freqs, sampleRate)
        val summed = grid.sum(bands)
        for (i in freqs.indices) {
            var expected = 0f
            for (band in bands) {
                expected += AutoEqEngine.calculateBiquadResponse(freqs[i], band, sampleRate)
            }
            assertEquals("sum at ${freqs[i]} Hz", expected, summed[i], 1e-3f)
        }
    }

    @Test
    fun `disabled bands contribute nothing`() {
        val freqs = logAxis(32)
        val grid = AutoEqEngine.ResponseGrid(freqs, sampleRate)
        val off = EqBand(9, FilterType.PEAKING, 1000f, 12f, 1f, enabled = false)
        for (g in grid.response(off)) assertEquals(0f, g, 0f)
        // An off band must also drop out of a sum rather than skewing it.
        val withOff = grid.sum(bands + off)
        val without = grid.sum(bands)
        for (i in freqs.indices) assertEquals(without[i], withOff[i], 0f)
    }

    @Test
    fun `accumulate adds onto the caller's array rather than replacing it`() {
        val freqs = logAxis(16)
        val grid = AutoEqEngine.ResponseGrid(freqs, sampleRate)
        val out = FloatArray(grid.size) { 1f }
        grid.accumulate(bands[0], out)
        for (i in freqs.indices) {
            assertEquals(
                1f + AutoEqEngine.calculateBiquadResponse(freqs[i], bands[0], sampleRate),
                out[i],
                0f,
            )
        }
    }

    @Test
    fun `grid size follows the axis it was built from`() {
        assertEquals(200, AutoEqEngine.ResponseGrid(logAxis(200), sampleRate).size)
        assertEquals(0, AutoEqEngine.ResponseGrid(FloatArray(0), sampleRate).size)
    }
}
