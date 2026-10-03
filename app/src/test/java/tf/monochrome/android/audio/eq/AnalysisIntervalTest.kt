package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The analyzer's Overlap: the hop between FFT windows, never faster than the display. */
class AnalysisIntervalTest {

    private val frameMs = 16L

    @Test
    fun `the default overlap analyses as often as before at every window size`() {
        for (size in listOf(4096, 8192, 16384)) {
            for (rate in listOf(44100, 48000)) {
                val ms = SpectrumAnalyzerTap.analysisIntervalMs(size, rate, SpectrumAnalyzerTap.DEFAULT_OVERLAP, frameMs)
                assertEquals("$size at $rate Hz", frameMs, ms)
            }
        }
    }

    @Test
    fun `half overlap hops half a window`() {
        // 8192 samples at 48 kHz is 170.7 ms; half of it is 85 ms.
        assertEquals(85L, SpectrumAnalyzerTap.analysisIntervalMs(8192, 48000, 0.5f, frameMs))
    }

    @Test
    fun `less overlap never analyses more often`() {
        var last = 0L
        for (pct in 99 downTo 50) {
            val ms = SpectrumAnalyzerTap.analysisIntervalMs(16384, 48000, pct / 100f, frameMs)
            assertTrue(ms >= last)
            last = ms
        }
    }

    @Test
    fun `overlap outside the range is clamped`() {
        assertEquals(
            SpectrumAnalyzerTap.analysisIntervalMs(8192, 48000, 0.5f, frameMs),
            SpectrumAnalyzerTap.analysisIntervalMs(8192, 48000, 0.1f, frameMs),
        )
    }
}
