package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.domain.model.FrequencyPoint

/**
 * Pins the SeapEngine `a7` port: radius = floor(percent / 2.5) points,
 * triangular weights 1 − |offset|/(radius+1), edges renormalized.
 */
class AutoEqSmoothingTest {

    private fun pts(vararg gains: Float) =
        gains.mapIndexed { i, g -> FrequencyPoint(20f + i * 10f, g) }

    @Test
    fun `zero percent and tiny curves are untouched`() {
        val p = pts(1f, 2f, 3f)
        assertEquals(p, AutoEqEngine.smoothCurve(p, 0f))
        assertEquals(pts(1f, 5f), AutoEqEngine.smoothCurve(pts(1f, 5f), 50f))
    }

    @Test
    fun `radius-1 triangular window matches hand-computed values`() {
        // pct 2.5 -> r=1, weights [0.5, 1, 0.5]
        val out = AutoEqEngine.smoothCurve(pts(0f, 0f, 10f, 0f, 0f), 2.5f)
        assertEquals(0f, out[0].gain, 1e-4f)     // edge: (0*1 + 0*0.5)/1.5
        assertEquals(2.5f, out[1].gain, 1e-4f)   // (0*0.5 + 0*1 + 10*0.5)/2
        assertEquals(5f, out[2].gain, 1e-4f)     // (0*0.5 + 10*1 + 0*0.5)/2
        assertEquals(2.5f, out[3].gain, 1e-4f)
        assertEquals(0f, out[4].gain, 1e-4f)
    }

    @Test
    fun `frequencies are preserved, only gains change`() {
        val input = pts(0f, 8f, -3f, 4f, 1f)
        val out = AutoEqEngine.smoothCurve(input, 60f)
        assertEquals(input.map { it.freq }, out.map { it.freq })
    }

    @Test
    fun `higher percent flattens harder`() {
        val jagged = pts(0f, 10f, -10f, 10f, -10f, 10f, 0f)
        fun ripple(c: List<FrequencyPoint>) =
            c.maxOf { it.gain } - c.minOf { it.gain }
        val lo = ripple(AutoEqEngine.smoothCurve(jagged, 5f))
        val hi = ripple(AutoEqEngine.smoothCurve(jagged, 100f))
        assert(hi < lo) { "expected 100% ($hi) flatter than 5% ($lo)" }
    }
}
