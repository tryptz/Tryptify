package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.DeviceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType

/** An EQ of the app's, made to fit every DAC in Contour's catalog. */
class DacEqFitTest {

    private val every: List<DeviceTarget> = DacEqCatalog.all

    /** A typical AutoEQ result: ten peaks. */
    private val tenPeaks = listOf(
        31f to 4.5f, 62f to 3.0f, 125f to -1.5f, 250f to -2.5f, 500f to 1.0f,
        1000f to -1.0f, 2000f to 2.5f, 4000f to -4.0f, 8000f to 3.0f, 12000f to -2.0f,
    ).mapIndexed { i, (f, g) -> EqBand(i, FilterType.PEAKING, f, g, 1.41f) }

    private fun DeviceTarget.send(bands: List<EqBand>) =
        issues(DacEqMapping.profile("Test", bands, null, this, now = 0L))

    @Test
    fun `an EQ that fits is not touched`() {
        val eq = tenPeaks.take(5)
        val fitted = DacEqFit.fit(eq, DeviceTarget.KA15)
        assertTrue(fitted.asItIs)
        assertEquals(eq, fitted.bands)
        assertEquals(0f, fitted.maxErrorDb)
    }

    @Test
    fun `bands that are off or do nothing are not counted against the DAC`() {
        val eq = tenPeaks.mapIndexed { i, b -> if (i % 2 == 0) b.copy(enabled = false) else b } +
            EqBand(10, FilterType.PEAKING, 700f, 0f, 1f)
        val fitted = DacEqFit.fit(eq, DeviceTarget.MICRO)
        assertFalse(fitted.refit)
        assertEquals(5, fitted.bands.size)
        assertEquals(5, fitted.sourceBands)
    }

    @Test
    fun `too many bands are fitted again, inside the DAC's number`() {
        val small = every.filter { it.caps.bands < tenPeaks.size }
        assertTrue(small.isNotEmpty())
        for (dac in small) {
            val fitted = DacEqFit.fit(tenPeaks, dac)
            assertTrue(dac.caps.name, fitted.refit)
            assertFalse(fitted.fromMeasurement)
            assertTrue("${dac.caps.name}: ${fitted.bands.size} bands", fitted.bands.size in 1..dac.caps.bands)
            // Fewer filters cannot draw the same curve; they must stay near it.
            assertTrue("${dac.caps.name}: ${fitted.maxErrorDb} dB away", fitted.maxErrorDb < 3.5f)
        }
    }

    @Test
    fun `whatever the EQ, what comes out is something the DAC takes`() {
        val wild = tenPeaks + listOf(
            EqBand(10, FilterType.LOWSHELF, 12f, 18f, 0.2f),
            EqBand(11, FilterType.HIGHSHELF, 19_500f, -17f, 9f),
            EqBand(12, FilterType.PEAKING, 5f, 14f, 30f),
        )
        for (dac in every.filter { !it.readOnly }) for (eq in listOf(tenPeaks, tenPeaks.take(3), wild)) {
            val fitted = DacEqFit.fit(eq, dac)
            // The preamp is the page's to settle, so only the bands are judged here.
            val refused = dac.send(fitted.bands).filterNot { "pregain" in it.lowercase() || "preamp" in it.lowercase() || "needs" in it }
            assertEquals("${dac.caps.name}: $refused", emptyList<String>(), refused)
        }
    }

    @Test
    fun `a gain past the DAC's range is brought to its edge and counted`() {
        val dac = DeviceTarget.KA15
        val top = dac.caps.gainMaxDb.toFloat()
        val fitted = DacEqFit.fit(listOf(EqBand(0, FilterType.PEAKING, 1000f, top + 6f, 1f), EqBand(1, FilterType.PEAKING, 3000f, 2f, 1f)), dac)
        assertFalse(fitted.refit)
        assertEquals(1, fitted.limited)
        assertEquals(top, fitted.bands[0].gain)
        assertEquals(6f, fitted.maxErrorDb, 0.05f)
    }

    @Test
    fun `a kind of filter the DAC lacks is fitted with the ones it has`() {
        val peaksOnly = every.filter { DacEqMapping.toDac(FilterType.LOWSHELF) !in it.caps.types }
        val eq = listOf(EqBand(0, FilterType.LOWSHELF, 105f, 5f, 0.7f), EqBand(1, FilterType.PEAKING, 3000f, -3f, 2f))
        for (dac in peaksOnly) {
            val fitted = DacEqFit.fit(eq, dac)
            assertTrue(dac.caps.name, fitted.refit)
            assertTrue(dac.caps.name, fitted.bands.all { it.type == FilterType.PEAKING })
        }
    }

    @Test
    fun `a shelf the DAC has is kept when the peaks are fitted again`() {
        val dac = every.first { it.caps.bands < 10 && DacEqMapping.toDac(FilterType.LOWSHELF) in it.caps.types }
        val shelf = EqBand(0, FilterType.LOWSHELF, 105f, 4f, 0.7071f)
        val fitted = DacEqFit.fit(listOf(shelf) + tenPeaks.drop(1).map { it.copy(id = it.id + 1) } + EqBand(20, FilterType.PEAKING, 16000f, 2f, 2f), dac)
        assertTrue(fitted.refit)
        assertTrue(fitted.bands.any { it.type == FilterType.LOWSHELF && it.freq == 105f && it.gain == 4f })
    }
}
