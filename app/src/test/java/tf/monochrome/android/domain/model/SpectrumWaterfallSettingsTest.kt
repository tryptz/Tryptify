package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.audio.eq.WaterfallNative

/**
 * The waterfall's settings against cpp/dsp/scope/spectrum_waterfall.h, which
 * clamps them again on its side. The two ranges have to agree, or a slider
 * would offer values the native side silently refuses — or the reverse — and
 * the array sizes have to agree or the draw reads past what was written.
 */
class SpectrumWaterfallSettingsTest {

    private val header = java.io.File("src/main/cpp/dsp/scope/spectrum_waterfall.h").readText()

    private fun constant(name: String): Float =
        Regex("""\b$name\s*=\s*(-?[\d.]+)f?\b""").find(header)!!.groupValues[1].toFloat()

    @Test
    fun `ranges match the native clamp`() {
        assertEquals(constant("kMinAngle"), SpectrumWaterfallSettings.MIN_ANGLE_DEG)
        assertEquals(constant("kMaxAngle"), SpectrumWaterfallSettings.MAX_ANGLE_DEG)
        assertEquals(constant("kMinDepth"), SpectrumWaterfallSettings.MIN_DEPTH_SECONDS)
        assertEquals(constant("kMaxDepth"), SpectrumWaterfallSettings.MAX_DEPTH_SECONDS)
        assertEquals(constant("kMaxFadeStart"), SpectrumWaterfallSettings.MAX_FADE_START)
    }

    @Test
    fun `array sizes match the native layout`() {
        val rows = constant("kRows").toInt()
        val points = constant("kPoints").toInt()
        assertEquals(rows, SpectrumWaterfallSettings.LINES)
        assertEquals(rows + 1, WaterfallNative.MAX_LINES)
        assertEquals((points - 1) * 4, WaterfallNative.FLOATS_PER_LINE)
        assertEquals(constant("kMetaPerRow").toInt(), WaterfallNative.META_PER_LINE)
    }

    @Test
    fun `out-of-range values are clamped, defaults are left alone`() {
        val wild = SpectrumWaterfallSettings(depthSeconds = 99f, fadeStart = -1f, angleDeg = 400f).clamped()
        assertEquals(SpectrumWaterfallSettings.MAX_DEPTH_SECONDS, wild.depthSeconds)
        assertEquals(0f, wild.fadeStart)
        assertEquals(SpectrumWaterfallSettings.MAX_ANGLE_DEG, wild.angleDeg)
        assertEquals(SpectrumWaterfallSettings.DEFAULT, SpectrumWaterfallSettings.DEFAULT.clamped())
    }

    @Test
    fun `solid seconds is the part before the fade`() {
        assertEquals(1f, SpectrumWaterfallSettings(depthSeconds = 4f, fadeStart = 0.25f).solidSeconds, 1e-6f)
    }
}
