package tf.monochrome.android.ui.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint

/**
 * The EQ Studio's arithmetic: where things sit on its graph, and what its
 * touch controls do to a filter.
 */
class EqStudioMathTest {

    @Test
    fun `the axis runs 20 Hz to 20 kHz on a log scale and inverts`() {
        assertEquals(0f, StudioAxis.fractionOf(20f), 1e-6f)
        assertEquals(1f, StudioAxis.fractionOf(20000f), 1e-6f)
        // 632 Hz is the geometric middle of 20 Hz and 20 kHz.
        assertEquals(0.5f, StudioAxis.fractionOf(632.456f), 1e-4f)
        for (f in listOf(31f, 250f, 1000f, 8000f, 16000f)) {
            assertEquals(f, StudioAxis.freqAt(StudioAxis.fractionOf(f)), f * 1e-4f)
        }
        // Off the ends clamps rather than extrapolating.
        assertEquals(20f, StudioAxis.freqAt(-1f), 1e-3f)
        assertEquals(20000f, StudioAxis.freqAt(2f), 0.5f)
    }

    @Test
    fun `interpolation is on the log axis and flat past the ends`() {
        val curve = listOf(FrequencyPoint(100f, 0f), FrequencyPoint(1000f, 10f))
        // Half way in octaves, not in hertz.
        assertEquals(5f, StudioAxis.interpolate(curve, 316.23f), 1e-3f)
        assertEquals(0f, StudioAxis.interpolate(curve, 20f), 0f)
        assertEquals(10f, StudioAxis.interpolate(curve, 20000f), 0f)
        assertEquals(0f, StudioAxis.interpolate(emptyList(), 1000f), 0f)
    }

    @Test
    fun `the graph centres on the midband, as the classic graph does`() {
        val curve = listOf(
            FrequencyPoint(100f, 90f),
            FrequencyPoint(500f, 74f),
            FrequencyPoint(1000f, 76f),
            FrequencyPoint(10000f, 60f),
        )
        assertEquals(75f, StudioAxis.normalizationOffset(curve), 1e-4f)
    }

    @Test
    fun `a new filter is flat and lands in the widest gap`() {
        val bands = listOf(
            EqBand(id = 0, freq = 30f, gain = 3f),
            EqBand(id = 1, freq = 60f, gain = -2f),
            EqBand(id = 2, freq = 120f, gain = 1f),
        )
        val out = StudioBands.withNewBand(bands)
        assertEquals(4, out.size)
        val added = out.last()
        assertEquals(3, added.id)
        assertEquals(0f, added.gain, 0f)
        assertEquals(FilterType.PEAKING, added.type)
        // The widest gap is 120 Hz to 20 kHz, whose log middle is about 1.5 kHz.
        assertTrue("added at ${added.freq}", added.freq in 1400f..1700f)
    }

    @Test
    fun `an empty list gets its first filter in the middle of the axis`() {
        val added = StudioBands.withNewBand(emptyList()).single()
        assertEquals(0, added.id)
        assertEquals(632f, added.freq, 1f)
    }

    @Test
    fun `adding stops at the limit`() {
        val full = List(StudioBands.MAX_BANDS) { EqBand(id = it, freq = 100f + it, gain = 0f) }
        assertEquals(full, StudioBands.withNewBand(full))
    }

    @Test
    fun `ids are never reused, so a filter keeps its colour`() {
        val bands = listOf(EqBand(id = 0, freq = 100f, gain = 0f), EqBand(id = 5, freq = 1000f, gain = 0f))
        assertEquals(6, StudioBands.nextId(bands))
        val sorted = StudioBands.sortedByFrequency(listOf(bands[1], bands[0]))
        assertEquals(listOf(0, 5), sorted.map { it.id })
        assertEquals(listOf(5), StudioBands.without(bands, 0).map { it.id })
        assertNotEquals(studioFilterColor(0), studioFilterColor(1))
        assertEquals(studioFilterColor(5), studioFilterColor(5))
    }

    @Test
    fun `frequencies round to three significant figures`() {
        assertEquals(1230f, StudioBands.roundFreq(1234.5f), 1e-3f)
        assertEquals(98.8f, StudioBands.roundFreq(98.76f), 1e-3f)
        assertEquals(15800f, StudioBands.roundFreq(15834f), 1e-2f)
    }

    @Test
    fun `scrubbing moves in octaves and stops at the ends of the range`() {
        assertEquals(2000f, StudioBands.scrubFreq(1000f, 1f), 1e-2f)
        assertEquals(500f, StudioBands.scrubFreq(1000f, -1f), 1e-2f)
        assertEquals(20000f, StudioBands.scrubFreq(15000f, 5f), 0f)
        assertEquals(20f, StudioBands.scrubFreq(25f, -5f), 0f)
        assertEquals(2f, StudioBands.scrubQ(1f, 1f), 1e-5f)
        assertEquals(StudioBands.MAX_Q, StudioBands.scrubQ(8f, 4f), 0f)
        assertEquals(EqLimits.AUTOEQ_MAX_BAND_DB, StudioBands.clampGain(99f), 0f)
    }

    @Test
    fun `spreading two fingers widens the filter, which lowers its Q`() {
        assertEquals(1f, StudioBands.pinchQ(2f, 100f, 200f), 1e-5f)
        assertEquals(4f, StudioBands.pinchQ(2f, 200f, 100f), 1e-5f)
        assertEquals(StudioBands.MIN_Q, StudioBands.pinchQ(0.2f, 100f, 1000f), 0f)
        // A degenerate span leaves Q alone rather than dividing by zero.
        assertEquals(2f, StudioBands.pinchQ(2f, 0f, 100f), 0f)
    }

    @Test
    fun `a touch grabs the nearest handle within reach, or none`() {
        val handles = listOf(Triple(0, 100f, 100f), Triple(1, 130f, 100f))
        assertEquals(1, StudioBands.nearestHandle(handles, 120f, 100f, 28f))
        assertEquals(0, StudioBands.nearestHandle(handles, 105f, 100f, 28f))
        assertNull(StudioBands.nearestHandle(handles, 300f, 300f, 28f))
    }

    @Test
    fun `values read the way the export writes them`() {
        assertEquals("105 Hz", formatStudioFreq(105f))
        assertEquals("1.2 kHz", formatStudioFreq(1234f))
        assertEquals("16 kHz", formatStudioFreq(16000f))
        assertEquals("+3.5 dB", formatStudioGain(3.46f))
        assertEquals("−2.0 dB", formatStudioGain(-2f))
        assertEquals("0.0 dB", formatStudioGain(-0.01f))
        assertEquals("PK", FilterType.PEAKING.studioCode())
        assertEquals("LSC", FilterType.LOWSHELF.studioCode())
        assertEquals("HSC", FilterType.HIGHSHELF.studioCode())
    }
}
