// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music links' own spectrum: each link hears its band and nothing else,
 * at the level the mixer's knob links measure (a band's RMS in dBFS).
 */
class LinkBandAnalysisTest {
    private val rate = 48_000
    private val analysis = LinkBandAnalysis()
    private val power = FloatArray(LinkBandAnalysis.BINS)

    private fun tones(vararg tones: Pair<Float, Float>): FloatArray =
        FloatArray(LinkBandAnalysis.SIZE) { i ->
            tones.fold(0f) { sum, (hz, amp) -> sum + amp * sin(2.0 * PI * hz * i / rate).toFloat() }
        }

    private fun band(samples: FloatArray, lo: Float, hi: Float): Float {
        analysis.powerSpectrum(samples, power)
        return LinkBandAnalysis.bandDb(power, rate, lo, hi)
    }

    @Test
    fun `a sine in the band reads its RMS in dBFS`() {
        // Full scale: RMS −3 dBFS. Half scale: 6 dB less.
        assertEquals(-3f, band(tones(90f to 1f), 30f, 150f), 0.5f)
        assertEquals(-9f, band(tones(90f to 0.5f), 30f, 150f), 0.5f)
        assertEquals(-3f, band(tones(10_000f to 1f), 6_000f, 16_000f), 0.5f)
    }

    @Test
    fun `a band hears nothing outside it`() {
        // The kick band's level is the kick's, however loud the vocals get:
        // the display spectrum, re-centred on the mids, pulled it down.
        val kickAlone = band(tones(90f to 0.3f), 30f, 150f)
        val withVocals = band(tones(90f to 0.3f, 1_000f to 1f, 2_500f to 0.8f), 30f, 150f)
        assertEquals(kickAlone, withVocals, 0.3f)
        // And the hats band does not hear the kick.
        assertTrue(band(tones(90f to 1f), 6_000f, 16_000f) < -70f)
        // Two links on two bands of one sound each read their own.
        val both = tones(90f to 1f, 10_000f to 0.1f)
        val kick = band(both, 30f, 150f)
        val hats = band(both, 6_000f, 16_000f)
        assertTrue("kick $kick hats $hats", abs((kick - hats) - 20f) < 1f)
    }

    @Test
    fun `silence and no spectrum read silent`() {
        assertEquals(LinkBandAnalysis.SILENT_DB, band(FloatArray(LinkBandAnalysis.SIZE), 30f, 150f), 0f)
        assertEquals(LinkBandAnalysis.SILENT_DB, LinkBandAnalysis.bandDb(FloatArray(0), rate, 30f, 150f), 0f)
    }

    @Test
    fun `a band narrower than a bin reads the bin nearest its middle`() {
        // 23.4 Hz a bin at 48 kHz: 100–110 Hz falls between two centres.
        val level = band(tones(105f to 1f), 100f, 110f)
        assertTrue("level $level", level > -12f)
    }
}
