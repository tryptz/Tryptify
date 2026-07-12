package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFxSettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `defaults reproduce the shipped look`() {
        val d = LyricsFxSettings.DEFAULT
        assertEquals(23f, d.fontSizeSp, 0f)
        assertEquals(12f, d.rotationDegrees, 0f)
        assertEquals(0.22f, d.wavePhaseStep, 0f)
        assertEquals(0.8f, d.bassReact, 0f)
        assertEquals(8, d.rayCount)
    }

    @Test
    fun `bounce maps to a decreasing damping ratio within safe bounds`() {
        val stiff = LyricsFxSettings(bounce = 0f).springDampingRatio
        val rubber = LyricsFxSettings(bounce = 1f).springDampingRatio
        assertTrue("stiff should damp more than rubber", stiff > rubber)
        assertTrue(stiff in 0.15f..0.9f)
        assertTrue(rubber in 0.15f..0.9f)
    }

    @Test
    fun `clamped coerces out-of-range values`() {
        val c = LyricsFxSettings(
            fontSizeSp = 999f,
            rayCount = 100,
            bassReact = -2f,
            raySpinDegPerSec = 500f,
        ).clamped()
        assertEquals(34f, c.fontSizeSp, 0f)
        assertEquals(24, c.rayCount)
        assertEquals(0f, c.bassReact, 0f)
        assertEquals(60f, c.raySpinDegPerSec, 0f)
    }

    @Test
    fun `clamped replaces non-finite values with defaults`() {
        val c = LyricsFxSettings(
            waveSpeed = Float.NaN,
            pumpAmount = Float.POSITIVE_INFINITY,
        ).clamped()
        assertEquals(LyricsFxSettings.DEFAULT.waveSpeed, c.waveSpeed, 0f)
        assertEquals(LyricsFxSettings.DEFAULT.pumpAmount, c.pumpAmount, 0f)
    }

    @Test
    fun `serialization round-trips every field`() {
        val original = LyricsFxSettings(
            fontSizeSp = 27f,
            rotationDegrees = 5f,
            bassReact = 0.5f,
            rayCount = 8,
            raySpinDegPerSec = -20f,
            glowBrightness = 0.4f,
        )
        val decoded = json.decodeFromString<LyricsFxSettings>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown future keys decode without failing`() {
        val body = """{"fontSizeSp":25.0,"someBrandNewFxKnob":3.0}"""
        val decoded = json.decodeFromString<LyricsFxSettings>(body)
        assertEquals(25f, decoded.fontSizeSp, 0f)
        // Missing fields fall back to their defaults.
        assertEquals(LyricsFxSettings.DEFAULT.rayCount, decoded.rayCount)
    }

    @Test
    fun `presets are all valid after clamping`() {
        LyricsFxSettings.PRESETS.forEach { (name, preset) ->
            assertEquals("$name preset should already be within range", preset, preset.clamped())
        }
    }
}
