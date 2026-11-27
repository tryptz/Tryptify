package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.ShelfForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import io.github.chronosauros.contour.core.FilterType as DacFilter

/**
 * The curve the DAC EQ page draws is the one the DAC is asked to play: the
 * app's own filter maths against Contour's, for every kind of band and for
 * both ways a DAC builds a shelf.
 */
class DacEqMappingTest {

    private val freqs = Dsp.logFreqs(200)
    private val contourGrid = Dsp.FreqGrid(freqs)
    private val appGrid = AutoEqEngine.ResponseGrid(FloatArray(freqs.size) { freqs[it].toFloat() }, DacEqMapping.DISPLAY_SAMPLE_RATE)

    private fun contour(band: EqBand, form: ShelfForm): DoubleArray {
        val sent = Band("b", DacEqMapping.toDac(band.type), band.freq.toDouble(), band.gain.toDouble(), DacEqMapping.dacQ(band, form))
        return Dsp.responseDb(listOf(sent), form, contourGrid)
    }

    private fun worst(a: DoubleArray, b: DoubleArray) = freqs.indices.maxOf { abs(a[it] - b[it]) }

    private val shelves = listOf(FilterType.LOWSHELF, FilterType.HIGHSHELF).flatMap { type ->
        listOf(0.5f, 0.7071f, 1.4f, 2.5f).flatMap { q ->
            listOf(-9f, -2f, 4f, 10f).map { gain -> EqBand(0, type, if (type == FilterType.LOWSHELF) 105f else 8000f, gain, q) }
        }
    }

    @Test
    fun `the display rate is Contour's`() {
        assertEquals(Dsp.FS, DacEqMapping.DISPLAY_SAMPLE_RATE.toDouble(), 0.0)
    }

    @Test
    fun `a shelf on a DAC that takes a Q is sent as it is`() {
        for (band in shelves) assertEquals(DacEqMapping.exact(band.q), DacEqMapping.dacQ(band, ShelfForm.Q), 0.0)
    }

    /** The conversion itself, in Contour's own maths: the two forms draw one curve. */
    @Test
    fun `a shelf on a DAC that takes a slope is sent as the slope that draws the Q's curve`() {
        for (band in shelves) {
            val distance = worst(contour(band, ShelfForm.SLOPE), contour(band, ShelfForm.Q))
            assertTrue("${band.type} Q ${band.q} at ${band.gain} dB: $distance dB", distance < 1e-9)
        }
    }

    /**
     * The page draws with the app's own filters (the ones its EQ plays, made
     * to follow the analog filter to the top of the band); Contour draws the
     * plain cookbook biquad at 96 kHz, which is also what the DACs that are
     * sent coefficients play. Below 2 kHz the two are one curve. Above, an
     * ordinary band is within a third of a dB, and a steep, strong high shelf
     * (Q over 1) up to a dB in the top octave. The page's curve is the EQ
     * asked for, not a measurement of the DAC.
     */
    @Test
    fun `the app's curve is Contour's, to a third of a dB for an ordinary band`() {
        val peaks = listOf(EqBand(0, FilterType.PEAKING, 60f, 5f, 0.8f), EqBand(0, FilterType.PEAKING, 2500f, -6.5f, 2.2f), EqBand(0, FilterType.PEAKING, 9000f, 8f, 4f))
        for (band in peaks + shelves) for (form in ShelfForm.entries) {
            val theirs = contour(band, form)
            val ours = appGrid.sum(listOf(band))
            val steepShelf = band.type != FilterType.PEAKING && band.q > 1f
            val distance = freqs.indices.maxOf { abs(theirs[it] - ours[it]) }
            assertTrue("${band.type} ${band.freq} Hz Q ${band.q} at ${band.gain} dB, $form: $distance dB", distance < if (steepShelf) 1.0 else 0.33)
            val below2k = freqs.indices.filter { freqs[it] < 2000.0 }.maxOf { abs(theirs[it] - ours[it]) }
            assertTrue("${band.type} ${band.freq} Hz, $form, below 2 kHz: $below2k dB", below2k < 0.05)
        }
    }

    /** What the conversion is for: the same number means two different shelves. */
    @Test
    fun `sending a Q as a slope unchanged would be another filter`() {
        val band = EqBand(0, FilterType.LOWSHELF, 105f, 8f, 2f)
        val unconverted = Band("b", DacFilter.LOW_SHELF, 105.0, 8.0, 2.0)
        val theirs = Dsp.responseDb(listOf(unconverted), ShelfForm.SLOPE, contourGrid)
        val ours = appGrid.sum(listOf(band))
        assertTrue(freqs.indices.maxOf { abs(theirs[it] - ours[it]) } > 1.0)
    }

    @Test
    fun `slope and Q meet at 0_7071 whatever the gain`() {
        for (gain in listOf(-12.0, -1.0, 0.0, 3.0, 12.0)) {
            assertEquals(1.0, DacEqMapping.slopeForQ(0.70710678, gain), 1e-6)
            assertEquals(0.70710678, DacEqMapping.qForSlope(1.0, gain)!!, 1e-6)
        }
    }

    @Test
    fun `a slope comes back as the Q it was sent from`() {
        for (q in listOf(0.3, 0.7, 1.0, 3.0, 8.0)) for (gain in listOf(-10.0, 0.5, 6.0)) {
            assertEquals(q, DacEqMapping.qForSlope(DacEqMapping.slopeForQ(q, gain), gain)!!, 1e-9)
        }
    }

    @Test
    fun `a shelf no Q describes says so`() {
        // A slope well over 1 on a strong boost has no real response.
        assertNull(DacEqMapping.qForSlope(4.0, 18.0))
    }

    @Test
    fun `a band's numbers are sent as they read, not as a float holds them`() {
        assertEquals(0.6, DacEqMapping.exact(0.6f), 0.0)
        assertEquals(1.41, DacEqMapping.exact(1.41f), 0.0)
        assertEquals(-2.5, DacEqMapping.exact(-2.5f), 0.0)
        assertEquals(16000.0, DacEqMapping.exact(16000f), 0.0)
        assertTrue(0.6f.toDouble() != 0.6)
        val profile = DacEqMapping.profile("x", listOf(EqBand(0, FilterType.PEAKING, 1234.5f, 4.6f, 0.6f)), null, DeviceTarget.KA15, now = 0L)
        assertEquals(Band("b0", DacFilter.PEAK, 1234.5, 4.6, 0.6), profile.bands.single())
    }

    @Test
    fun `a DAC with no preamp to write is known`() {
        assertTrue(DacEqMapping.writesPreamp(DeviceTarget.KA15))
        assertTrue(DacEqMapping.writesPreamp(DeviceTarget.MICRO))
        assertTrue(DacEqMapping.writesPreamp(DeviceTarget.DAWN_PRO_2))
        // No preamp at all, and a preamp Contour does not write.
        assertTrue(!DacEqMapping.writesPreamp(DeviceTarget.ALLEGRO_MINI))
        assertTrue(!DacEqMapping.writesPreamp(DeviceTarget.MOONDROP_DUSK))
        assertTrue(!DacEqMapping.writesPreamp(DeviceTarget.EPZ_TP65))
    }

    @Test
    fun `only the bands that are on are sent, in order`() {
        val bands = listOf(
            EqBand(0, FilterType.LOWSHELF, 105f, 4f, 0.7f),
            EqBand(1, FilterType.PEAKING, 1000f, -3f, 1.4f, enabled = false),
            EqBand(2, FilterType.PEAKING, 3000f, 2f, 2f),
        )
        val profile = DacEqMapping.profile("HD 600", bands, -4.0, DeviceTarget.KA15, now = 5L)
        assertEquals(listOf(105.0, 3000.0), profile.bands.map { it.freqHz })
        assertEquals(listOf(DacFilter.LOW_SHELF, DacFilter.PEAK), profile.bands.map { it.type })
        assertEquals(-4.0, profile.preampDb!!, 0.0)
        assertTrue(profile.bands.all { it.enabled })
    }

    @Test
    fun `a name the DAC cannot spell is not sent`() {
        assertEquals("HD 600", DacEqMapping.slotName("HD 600"))
        assertEquals(DacEqMapping.FALLBACK_NAME, DacEqMapping.slotName("配置 4"))
        assertEquals(DacEqMapping.FALLBACK_NAME, DacEqMapping.slotName("—"))
    }

    @Test
    fun `pass filters on the DAC are counted and left out`() {
        val held = ImportedEq(
            bands = listOf(
                Band("a", DacFilter.HIGH_PASS, 30.0, 0.0, 0.7),
                Band("b", DacFilter.PEAK, 1000.0, -2.0, 1.0),
                Band("c", DacFilter.HIGH_SHELF, 9000.0, 3.0, 0.7, enabled = false),
            ),
            preampDb = -3.0,
        )
        val read = DacEqMapping.fromDac(held, DeviceTarget.KA15)
        assertEquals(1, read.passFilters)
        assertEquals(listOf(FilterType.PEAKING, FilterType.HIGHSHELF), read.bands.map { it.type })
        assertEquals(listOf(true, false), read.bands.map { it.enabled })
        assertEquals(listOf(0, 1), read.bands.map { it.id })
        assertEquals(-3f, read.preampDb)
    }
}
