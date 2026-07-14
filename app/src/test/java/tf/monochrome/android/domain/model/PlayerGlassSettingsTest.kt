package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGlassSettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `defaults reproduce the shipped glass`() {
        val d = PlayerGlassSettings.DEFAULT
        assertTrue(d.enabled)
        assertEquals(0.5f, d.bodyOpacity, 0f)
        assertEquals(0.16f, d.refraction, 0f)
        assertEquals(1.3f, d.rimBrightness, 0f)
        assertEquals(2, d.sampleRings)
        assertTrue(d.progressGlass)
        // Colour fields default to "use the current album colour".
        assertEquals(0, d.tintColor)
        assertEquals(0, d.previewBg)
    }

    @Test
    fun `clamped coerces out-of-range values`() {
        val c = PlayerGlassSettings(
            bodyOpacity = 9f,
            refraction = -1f,
            dispersion = 99f,
            sampleRings = 100,
            lightAngleDeg = 999f,
        ).clamped()
        assertEquals(1f, c.bodyOpacity, 0f)
        assertEquals(0f, c.refraction, 0f)
        assertEquals(2f, c.dispersion, 0f)
        assertEquals(3, c.sampleRings)
        assertEquals(360f, c.lightAngleDeg, 0f)
    }

    @Test
    fun `clamped replaces non-finite values with defaults`() {
        val c = PlayerGlassSettings(
            reflection = Float.NaN,
            gloss = Float.POSITIVE_INFINITY,
        ).clamped()
        assertEquals(PlayerGlassSettings.DEFAULT.reflection, c.reflection, 0f)
        assertEquals(PlayerGlassSettings.DEFAULT.gloss, c.gloss, 0f)
    }

    @Test
    fun `serialization round-trips every field`() {
        val original = PlayerGlassSettings(
            enabled = false,
            bodyOpacity = 0.7f,
            refraction = 0.3f,
            gloss = 0.9f,
            surfaceMotion = 0.5f,
            tintColor = 0x8834AACC.toInt(),
            previewBg = 0xFF101010.toInt(),
            progressGlass = false,
        )
        val decoded = json.decodeFromString<PlayerGlassSettings>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown future keys decode without failing`() {
        val body = """{"bodyOpacity":0.6,"someBrandNewGlassKnob":3.0}"""
        val decoded = json.decodeFromString<PlayerGlassSettings>(body)
        assertEquals(0.6f, decoded.bodyOpacity, 0f)
        assertEquals(PlayerGlassSettings.DEFAULT.refraction, decoded.refraction, 0f)
    }

    @Test
    fun `presets are all valid after clamping`() {
        PlayerGlassSettings.PRESETS.forEach { (name, preset) ->
            assertEquals("$name preset should already be within range", preset, preset.clamped())
        }
    }

    @Test
    fun `presets never touch the user colour or preview background`() {
        PlayerGlassSettings.PRESETS.forEach { (name, preset) ->
            assertEquals("$name preset must leave tintColor at 0", 0, preset.tintColor)
            assertEquals("$name preset must leave previewBg at 0", 0, preset.previewBg)
        }
    }

    @Test
    fun `applying a theme keeps the personal colour and perf settings`() {
        val personal = PlayerGlassSettings(
            sampleRings = 1,
            tintColor = 0xFF22CCFF.toInt(),
            previewBg = 0xFF000000.toInt(),
        )
        val theme = PlayerGlassSettings.PRESETS.first { it.first == "Neon" }.second
        val applied = theme.withPersonalFrom(personal)
        assertEquals(1, applied.sampleRings)
        assertEquals(0xFF22CCFF.toInt(), applied.tintColor)
        assertEquals(0xFF000000.toInt(), applied.previewBg)
        // Material fields still come from the theme.
        assertEquals(theme.rimBrightness, applied.rimBrightness, 0f)
        assertEquals(theme.reflection, applied.reflection, 0f)
        // …and the chip-selection helper recognises the match despite the carry-over.
        assertTrue(applied.matchesPreset(theme))
    }

    @Test
    fun `matchesPreset is false for a different material`() {
        val chrome = PlayerGlassSettings.PRESETS.first { it.first == "Chrome" }.second
        val frosted = PlayerGlassSettings.PRESETS.first { it.first == "Frosted" }.second
        assertTrue(chrome.matchesPreset(chrome))
        assertTrue(!chrome.matchesPreset(frosted))
    }

    @Test
    fun `preset code round-trips through encode and decode`() {
        val preset = PlayerGlassPreset("My Glass", PlayerGlassSettings(bodyOpacity = 0.8f, gloss = 0.9f))
        val code = PlayerGlassPreset.encode(preset)
        assertTrue("code carries the marker prefix", code.startsWith(PlayerGlassPreset.CODE_PREFIX))
        val back = PlayerGlassPreset.decode(code)
        assertEquals(preset.copy(settings = preset.settings.clamped()), back)
    }

    @Test
    fun `decode tolerates a missing prefix and rejects junk`() {
        val preset = PlayerGlassPreset("Shared", PlayerGlassSettings())
        val withoutPrefix = PlayerGlassPreset.encode(preset).removePrefix(PlayerGlassPreset.CODE_PREFIX)
        assertEquals("Shared", PlayerGlassPreset.decode(withoutPrefix)?.name)
        assertNull(PlayerGlassPreset.decode("just some random text"))
        assertNull(PlayerGlassPreset.decode(""))
    }

    @Test
    fun `decode re-clamps hostile out-of-range values`() {
        val evil = PlayerGlassPreset.CODE_PREFIX +
            """{"name":"Evil","settings":{"bodyOpacity":9999.0,"sampleRings":500}}"""
        val decoded = PlayerGlassPreset.decode(evil)!!
        assertEquals(1f, decoded.settings.bodyOpacity, 0f)
        assertEquals(3, decoded.settings.sampleRings)
    }

    @Test
    fun `blank imported name falls back to Imported`() {
        val code = PlayerGlassPreset.CODE_PREFIX +
            """{"name":"   ","settings":{}}"""
        assertEquals("Imported", PlayerGlassPreset.decode(code)?.name)
    }
}
