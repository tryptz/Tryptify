package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // Crepuscular god-ray default: a downward fan of soft shafts.
        assertEquals(14, d.rayCount)
        assertEquals(150f, d.raySpreadDeg, 0f)
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

    @Test
    fun `clamped coerces the new ray and glass fields`() {
        val c = LyricsFxSettings(
            rayAngleDeg = 500f,
            raySpreadDeg = -30f,
            rayHueShift = 400f,
            rayPulseAmount = 5f,
            glassSampleRings = 9,
            fxaaStrength = 3f,
        ).clamped()
        assertEquals(360f, c.rayAngleDeg, 0f)
        assertEquals(0f, c.raySpreadDeg, 0f)
        assertEquals(180f, c.rayHueShift, 0f)
        assertEquals(1f, c.rayPulseAmount, 0f)
        assertEquals(3, c.glassSampleRings)
        assertEquals(1f, c.fxaaStrength, 0f)
    }

    @Test
    fun `new fields survive serialization`() {
        val original = LyricsFxSettings(
            customFont = true,
            customFontPath = "/data/user/0/app/files/custom_fonts/MyFont.ttf",
            rayFixedDirection = true,
            rayAngleDeg = 135f,
            raySpreadDeg = 90f,
            rayHueShift = -45f,
            glassSampleRings = 3,
            fxaa = true,
            fxaaStrength = 0.4f,
        )
        val decoded = json.decodeFromString<LyricsFxSettings>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `applying a preset keeps the personal font and device settings`() {
        val personal = LyricsFxSettings(
            customFont = true,
            customFontPath = "/fonts/Mine.ttf",
            bluetoothDelayMs = 220f,
            glassSampleRings = 1,
            fxaa = true,
            fxaaStrength = 0.9f,
        )
        val theme = LyricsFxSettings.PRESETS.first { it.first == "Neon" }.second
        val applied = theme.withPersonalFrom(personal)
        assertTrue(applied.customFont)
        assertEquals("/fonts/Mine.ttf", applied.customFontPath)
        assertEquals(220f, applied.bluetoothDelayMs, 0f)
        assertEquals(1, applied.glassSampleRings)
        assertTrue(applied.fxaa)
        assertEquals(0.9f, applied.fxaaStrength, 0f)
        // Aesthetic fields still come from the theme.
        assertEquals(theme.rayCount, applied.rayCount)
        // …and the chip-selection helper recognises the match despite the carry-over.
        assertTrue(applied.matchesPreset(theme))
    }

    @Test
    fun `preset code round-trips through encode and decode`() {
        val preset = LyricsFxPreset("My Look", LyricsFxSettings(fontSizeSp = 30f, rayCount = 20))
        val code = LyricsFxPreset.encode(preset)
        assertTrue("code carries the marker prefix", code.startsWith(LyricsFxPreset.CODE_PREFIX))
        val back = LyricsFxPreset.decode(code)
        assertEquals(preset.copy(settings = preset.settings.clamped()), back)
    }

    @Test
    fun `decode tolerates a missing prefix and rejects junk`() {
        val preset = LyricsFxPreset("Shared", LyricsFxSettings())
        val withoutPrefix = LyricsFxPreset.encode(preset).removePrefix(LyricsFxPreset.CODE_PREFIX)
        assertEquals("Shared", LyricsFxPreset.decode(withoutPrefix)?.name)
        assertNull(LyricsFxPreset.decode("just some random text"))
        assertNull(LyricsFxPreset.decode(""))
    }

    @Test
    fun `decode re-clamps hostile out-of-range values`() {
        val evil = LyricsFxPreset.CODE_PREFIX +
            """{"name":"Evil","settings":{"fontSizeSp":9999.0,"rayCount":500}}"""
        val decoded = LyricsFxPreset.decode(evil)!!
        assertEquals(34f, decoded.settings.fontSizeSp, 0f)
        assertEquals(24, decoded.settings.rayCount)
    }
}
