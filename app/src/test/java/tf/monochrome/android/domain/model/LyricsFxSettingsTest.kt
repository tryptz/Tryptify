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
        // Reactive glow default: a soft bloom behind the active line.
        assertEquals(44f, d.glowRadiusDp, 0f)
        assertEquals(0.22f, d.glowBrightness, 0f)
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
            glowRadiusDp = 999f,
            bassReact = -2f,
            glowBrightness = 5f,
        ).clamped()
        assertEquals(34f, c.fontSizeSp, 0f)
        assertEquals(160f, c.glowRadiusDp, 0f)
        assertEquals(0f, c.bassReact, 0f)
        assertEquals(0.6f, c.glowBrightness, 0f)
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
            glowRadiusDp = 88f,
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
        assertEquals(LyricsFxSettings.DEFAULT.glowRadiusDp, decoded.glowRadiusDp, 0f)
    }

    @Test
    fun `presets are all valid after clamping`() {
        LyricsFxSettings.PRESETS.forEach { (name, preset) ->
            assertEquals("$name preset should already be within range", preset, preset.clamped())
        }
    }

    @Test
    fun `clamped coerces the glass and anti-aliasing fields`() {
        val c = LyricsFxSettings(
            glassRefraction = 5f,
            glassDispersion = -1f,
            glassSampleRings = 9,
            fxaaStrength = 3f,
        ).clamped()
        assertEquals(0.4f, c.glassRefraction, 0f)
        assertEquals(0f, c.glassDispersion, 0f)
        assertEquals(3, c.glassSampleRings)
        assertEquals(1f, c.fxaaStrength, 0f)
    }

    @Test
    fun `new fields survive serialization`() {
        val original = LyricsFxSettings(
            customFont = true,
            customFontPath = "/data/user/0/app/files/custom_fonts/MyFont.ttf",
            glassBodyOpacity = 0.5f,
            glassRefraction = 0.2f,
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
        assertEquals(theme.rotationDegrees, applied.rotationDegrees, 0f)
        // …and the chip-selection helper recognises the match despite the carry-over.
        assertTrue(applied.matchesPreset(theme))
    }

    @Test
    fun `preset code round-trips through encode and decode`() {
        val preset = LyricsFxPreset("My Look", LyricsFxSettings(fontSizeSp = 30f, rotationDegrees = 20f))
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
            """{"name":"Evil","settings":{"fontSizeSp":9999.0,"glowRadiusDp":9999.0}}"""
        val decoded = LyricsFxPreset.decode(evil)!!
        assertEquals(34f, decoded.settings.fontSizeSp, 0f)
        assertEquals(160f, decoded.settings.glowRadiusDp, 0f)
    }
}
