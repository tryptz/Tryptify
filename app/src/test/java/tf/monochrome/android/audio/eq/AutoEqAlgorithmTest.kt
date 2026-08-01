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
