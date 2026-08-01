package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import kotlin.math.pow

/**
 * Behavioural contract of the two fitting algorithms on synthetic
 * measurements with known deviations.
 */
class AutoEqAlgorithmTest {

    /** Log-spaced 20 Hz–20 kHz curve built from a shape function. */
    private fun curve(shape: (Float) -> Float): List<FrequencyPoint> =
        (0..200).map { i ->
            val f = 20f * (1000f).pow(i / 200f)  // 20 -> 20k
            FrequencyPoint(f, 75f + shape(f))
        }

    private val flatTarget = curve { 0f }

    @Test
    fun `four bands flatten a three-feature curve to under 40 percent RMS`() {
        // Bass tilt + mid bump + presence dip — three broad features against a
        // four-band budget. One-shot greedy fitting leaves substantially more
        // residual here; the refinement pass is what earns this bound.
        fun bump(f: Float, c: Float, w: Float, a: Float): Float {
            val x = kotlin.math.ln((f / c).toDouble()) / w
            return (a * kotlin.math.exp(-x * x)).toFloat()
        }
        val shape = { f: Float -> bump(f, 60f, 0.9f, -5f) + bump(f, 1000f, 0.5f, 6f) + bump(f, 4000f, 0.45f, -4f) }
        val meas = curve(shape)

        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = flatTarget, bandCount = 4,
            algorithm = AutoEqAlgorithm.PEAKING,
        )
        assertTrue(bands.size <= 4)
        assertTrue(bands.all { kotlin.math.abs(it.gain) >= 0.2f })

        // Weighted RMS of the error before vs after applying the fitted stack.
        fun norm(c: List<FrequencyPoint>): Float {
            val mid = c.filter { it.freq in 250f..2500f }
            return mid.map { it.gain }.sum() / mid.size
        }
        val offset = norm(flatTarget) - norm(meas)
        fun weight(f: Float) = when {
            f < 300f -> 1.5; f < 4000f -> 1.0; f < 8000f -> 0.5; else -> 0.25
        }
        var beforeSq = 0.0; var afterSq = 0.0
        for (p in meas) {
            val e = (p.gain + offset) - 75f  // target is flat 75
            var corr = e.toDouble()
            for (b in bands) corr += AutoEqEngine.calculateBiquadResponse(p.freq, b)
            val w = weight(p.freq)
            beforeSq += w * e * e
            afterSq += w * corr * corr
        }
        val ratio = kotlin.math.sqrt(afterSq / beforeSq)
        assertTrue("residual RMS ratio $ratio should be < 0.35", ratio < 0.35)
    }

    @Test
    fun `bass roll-off produces a boosting low shelf under SHELF_ENDS`() {
        // 6 dB quiet below 100 Hz — a broad tilt, the shelf's home turf.
        val meas = curve { f -> if (f < 100f) -6f else 0f }

        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = flatTarget, bandCount = 10,
            algorithm = AutoEqAlgorithm.SHELF_ENDS,
        )

        val shelf = bands.first { it.type == FilterType.LOWSHELF }
        assertEquals(105f, shelf.freq, 0.01f)
        assertTrue("expected boost, got ${shelf.gain}", shelf.gain > 2f)
        assertEquals(0.707f, shelf.q, 0.001f)
    }

    @Test
    fun `treble tilt produces a cutting high shelf under SHELF_ENDS`() {
        val meas = curve { f -> if (f > 9000f) 5f else 0f }

        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = flatTarget, bandCount = 10,
            algorithm = AutoEqAlgorithm.SHELF_ENDS,
        )

        val shelf = bands.first { it.type == FilterType.HIGHSHELF }
        assertTrue("expected cut, got ${shelf.gain}", shelf.gain < -1f)
    }

    @Test
    fun `a 31-band fit on a dense curve completes in interactive time`() {
        // squig/SeapEngine schedule their fit behind a 600 ms timeout; ours
        // must land comfortably inside interactive latency even at the max
        // band count on a dense (500-point) measurement. JVM here is slower
        // than ART+JIT on device for this arithmetic, so 600 ms is a
        // conservative ceiling.
        val meas = (0..500).map { i ->
            val f = 20f * (1000f).pow(i / 500f)
            FrequencyPoint(f, 75f + (kotlin.math.sin(i / 7.0) * 4).toFloat())
        }
        val target = (0..500).map { i ->
            val f = 20f * (1000f).pow(i / 500f)
            FrequencyPoint(f, 75f)
        }
        val t0 = System.nanoTime()
        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = target, bandCount = 31,
            algorithm = AutoEqAlgorithm.SHELF_ENDS,
        )
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(bands.isNotEmpty())
        assertTrue("fit took ${ms}ms", ms < 600)
    }

    @Test
    fun `PEAKING never emits shelves`() {
        val meas = curve { f -> if (f < 100f) -6f else if (f > 9000f) 5f else 0f }

        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = flatTarget, bandCount = 10,
            algorithm = AutoEqAlgorithm.PEAKING,
        )

        assertTrue(bands.all { it.type == FilterType.PEAKING })
    }

    @Test
    fun `shelves count against the band budget`() {
        val meas = curve { f -> if (f < 100f) -6f else if (f > 9000f) 5f else 0f }

        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = flatTarget, bandCount = 5,
            algorithm = AutoEqAlgorithm.SHELF_ENDS,
        )

        assertTrue("got ${bands.size} bands", bands.size <= 5)
    }

    @Test
    fun `flat ends spend no shelves`() {
        // Deviation only in the mids — inaudible-shelf gate must skip both ends.
        val meas = curve { f -> if (f in 900f..1100f) 6f else 0f }

        val bands = AutoEqEngine.runAutoEqAlgorithm(
            measurement = meas, target = flatTarget, bandCount = 10,
            algorithm = AutoEqAlgorithm.SHELF_ENDS,
        )

        assertTrue(bands.none { it.type != FilterType.PEAKING })
    }
}
