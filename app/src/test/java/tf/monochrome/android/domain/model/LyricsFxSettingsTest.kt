package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            artGlowRadiusDp = 90f,
            artGlowBrightness = 0.5f,
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
            glowBehindArt = true,
            artGlowRadiusDp = 120f,
            artGlowBrightness = 0.45f,
        )
        val theme = LyricsFxSettings.PRESETS.first { it.first == "Voltage" }.second
        val applied = theme.withPersonalFrom(personal)
        assertTrue(applied.customFont)
        assertEquals("/fonts/Mine.ttf", applied.customFontPath)
        assertEquals(220f, applied.bluetoothDelayMs, 0f)
        assertEquals(1, applied.glassSampleRings)
        assertTrue(applied.fxaa)
        assertEquals(0.9f, applied.fxaaStrength, 0f)
        // The album-glow toggle is a personal setting — a theme never clobbers it,
        // and neither the radius nor the brightness it gates.
        assertTrue(applied.glowBehindArt)
        assertEquals(120f, applied.artGlowRadiusDp!!, 0f)
        assertEquals(0.45f, applied.artGlowBrightness!!, 0f)
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

    @Test
    fun `the album glow follows the lyric glow until it is pinned`() {
        val d = LyricsFxSettings.DEFAULT
        // Unset by default, so an upgrade changes nothing on screen.
        assertNull(d.artGlowRadiusDp)
        assertNull(d.artGlowBrightness)
        assertEquals(d.glowRadiusDp, d.effectiveArtGlowRadiusDp, 0f)
        assertEquals(d.glowBrightness, d.effectiveArtGlowBrightness, 0f)

        // Someone on a big-glow preset keeps that halo, not a default one.
        val supernova = LyricsFxSettings(glowRadiusDp = 150f, glowBrightness = 0.55f)
        assertEquals(150f, supernova.effectiveArtGlowRadiusDp, 0f)
        assertEquals(0.55f, supernova.effectiveArtGlowBrightness, 0f)

        // Pinning one leaves the other following, and leaves the lyric glow alone.
        val pinned = supernova.copy(artGlowRadiusDp = 20f)
        assertEquals(20f, pinned.effectiveArtGlowRadiusDp, 0f)
        assertEquals(0.55f, pinned.effectiveArtGlowBrightness, 0f)
        assertEquals(150f, pinned.glowRadiusDp, 0f)
    }

    @Test
    fun `clamped coerces a pinned album glow but leaves an unpinned one null`() {
        val unpinned = LyricsFxSettings().clamped()
        assertNull("clamping must not turn following into a hard 0", unpinned.artGlowRadiusDp)
        assertNull(unpinned.artGlowBrightness)

        val c = LyricsFxSettings(artGlowRadiusDp = 999f, artGlowBrightness = 5f).clamped()
        assertEquals(160f, c.artGlowRadiusDp!!, 0f)
        assertEquals(0.6f, c.artGlowBrightness!!, 0f)

        val nan = LyricsFxSettings(artGlowRadiusDp = Float.NaN).clamped()
        assertEquals(LyricsFxSettings.DEFAULT.glowRadiusDp, nan.artGlowRadiusDp!!, 0f)
    }

    // ── Letter-glass optics and god rays ────────────────────────────────

    /** Every field the optics and the rays added, so "unchanged" can be checked whole. */
    private fun added(s: LyricsFxSettings) = listOf(
        s.glassRoundness, s.glassDepth, s.glassSurfaceMotion, s.glassReflection, s.glassGloss,
        s.glassTiltReactivity, s.glassLightAngleDeg, s.glassEdgeWidth, s.glassFrost, s.glassRayCatch,
        s.godRays, s.godRaySource, s.godRaysOnTop, s.godRaysFollowWord, s.godRaysAllLyrics,
        s.godRayExposure, s.godRayDensity,
        s.godRayDecay, s.godRayAzimuthDeg, s.godRayElevationDeg, s.godRaySunSize, s.godRayShimmer,
        s.godRayBeat, s.godRaySpinDps, s.godRaySway, s.godRayTilt,
    )

    @Test
    fun `the letter glass defaults are the uniforms the lyric shader used to pin`() {
        // LiquidGlass.kt set these by hand before the lyrics had knobs for
        // them; a default that moved would restyle every listener's lyrics.
        val d = LyricsFxSettings.DEFAULT
        assertEquals(1f, d.glassRoundness, 0f)
        assertEquals(1f, d.glassDepth, 0f)
        assertEquals(1f, d.glassSurfaceMotion, 0f)
        assertEquals(1f, d.glassReflection, 0f)
        assertEquals("uGloss = 20 + 240 * gloss was 90", 90f, 20f + 240f * d.glassGloss, 1e-3f)
        assertEquals(0.7f, d.glassTiltReactivity, 0f)
        assertEquals(135f, d.glassLightAngleDeg, 0f)
        assertEquals("uFresnelPower = 8 - 6 * edge was 5", 5f, 8f - 6f * d.glassEdgeWidth, 0f)
        assertEquals(0f, d.glassFrost, 0f)
        // Acts only with the rays on, which are off by default.
        assertEquals(0.7f, d.glassRayCatch, 0f)
    }

    @Test
    fun `god rays are off by default and every earlier preset is untouched`() {
        assertFalse(LyricsFxSettings.DEFAULT.godRays)
        // The seventeen presets that predate the optics and the rays must leave
        // every new field at its default, or a listener on one of those chips
        // would see their lyrics change and the chip go dark.
        val earlier = LyricsFxSettings.PRESETS.takeWhile { it.first != "Sunburst" }
        assertEquals(17, earlier.size)
        earlier.forEach { (name, preset) ->
            assertEquals("$name moved a new field", added(LyricsFxSettings.DEFAULT), added(preset))
        }
    }

    @Test
    fun `a blob saved before the optics and the rays decodes to the old look`() {
        val old = json.decodeFromString<LyricsFxSettings>("""{"fontSizeSp":25.0,"glassRefraction":0.2}""")
        assertEquals(added(LyricsFxSettings.DEFAULT), added(old))
        assertEquals(25f, old.fontSizeSp, 0f)
    }

    @Test
    fun `clamped coerces the letter glass and god ray fields`() {
        val c = LyricsFxSettings(
            glassRoundness = 9f, glassDepth = 0f, glassSurfaceMotion = -1f, glassReflection = 5f,
            glassGloss = 2f, glassTiltReactivity = 3f, glassLightAngleDeg = 400f, glassEdgeWidth = -2f,
            glassFrost = 7f, godRaySource = 5, godRayExposure = 9f, godRayDensity = 0f, godRayDecay = 0.5f,
            godRayAzimuthDeg = -10f, godRayElevationDeg = 120f, godRaySunSize = 1f, godRayShimmer = 2f,
            godRayBeat = -1f, godRaySpinDps = 100f, godRaySway = 3f, godRayTilt = 9f, godRayQuality = 0,
        ).clamped()
        assertEquals(2f, c.glassRoundness, 0f)
        assertEquals(0.5f, c.glassDepth, 0f)
        assertEquals(0f, c.glassSurfaceMotion, 0f)
        assertEquals(2f, c.glassReflection, 0f)
        assertEquals(1f, c.glassGloss, 0f)
        assertEquals(1.5f, c.glassTiltReactivity, 0f)
        assertEquals(360f, c.glassLightAngleDeg, 0f)
        assertEquals(0f, c.glassEdgeWidth, 0f)
        assertEquals(1f, c.glassFrost, 0f)
        assertEquals(LyricsFxSettings.GOD_RAYS_BACKLIGHT, c.godRaySource)
        assertEquals(1.5f, c.godRayExposure, 0f)
        assertEquals(0.2f, c.godRayDensity, 0f)
        assertEquals(0.85f, c.godRayDecay, 0f)
        assertEquals(0f, c.godRayAzimuthDeg, 0f)
        assertEquals(90f, c.godRayElevationDeg, 0f)
        assertEquals(0.3f, c.godRaySunSize, 0f)
        assertEquals(1f, c.godRayShimmer, 0f)
        assertEquals(0f, c.godRayBeat, 0f)
        assertEquals(45f, c.godRaySpinDps, 0f)
        assertEquals(1f, c.godRaySway, 0f)
        assertEquals(1.5f, c.godRayTilt, 0f)
        assertEquals(1, c.godRayQuality)
        assertEquals("4 is the Shadertoy's 50 samples, and the most", 4, LyricsFxSettings(godRayQuality = 9).clamped().godRayQuality)

        assertEquals(1f, LyricsFxSettings(glassRayCatch = 4f).clamped().glassRayCatch, 0f)
        assertEquals(0f, LyricsFxSettings(glassRayCatch = -1f).clamped().glassRayCatch, 0f)
        val nan = LyricsFxSettings(glassGloss = Float.NaN, godRayElevationDeg = Float.NaN).clamped()
        assertEquals(LyricsFxSettings.DEFAULT.glassGloss, nan.glassGloss, 0f)
        assertEquals(LyricsFxSettings.DEFAULT.godRayElevationDeg, nan.godRayElevationDeg, 0f)
    }

    @Test
    fun `the god ray quality is personal and a theme carries it over`() {
        val mine = LyricsFxSettings(godRayQuality = 1)
        val theme = LyricsFxSettings.PRESETS.first { it.first == "Cathedral" }.second
        val applied = theme.withPersonalFrom(mine)
        assertEquals(1, applied.godRayQuality)
        assertTrue(applied.godRays)
        assertTrue(applied.matchesPreset(theme))
    }

    @Test
    fun `the light presets light, and Spotlight follows the sung word`() {
        val byName = LyricsFxSettings.PRESETS.toMap()
        listOf("Sunburst", "Cathedral", "Eclipse", "Daybreak", "Searchlight", "Spotlight", "Crepuscular").forEach {
            assertTrue("$it should switch the god rays on", byName.getValue(it).godRays)
        }
        assertEquals(90f, byName.getValue("Sunburst").godRayElevationDeg, 0f)
        assertEquals(LyricsFxSettings.GOD_RAYS_BACKLIGHT, byName.getValue("Eclipse").godRaySource)
        assertTrue(byName.getValue("Spotlight").godRaysFollowWord)
        // Crepuscular is the Shadertoy as written: the whole image shining and
        // its wandering light, round the middle of the screen.
        val crepuscular = byName.getValue("Crepuscular")
        assertTrue(crepuscular.godRaysAllLyrics)
        assertEquals(LyricsFxSettings.GOD_RAYS_LETTERS, crepuscular.godRaySource)
        assertEquals(1f, crepuscular.godRaySway, 0f)
        assertEquals(90f, crepuscular.godRayElevationDeg, 0f)
        assertFalse(byName.getValue("Mercury").godRays)
    }

    @Test
    fun `preset names are unique and no two presets light the same chip`() {
        val presets = LyricsFxSettings.PRESETS
        assertEquals(presets.size, presets.map { it.first.lowercase() }.toSet().size)
        presets.forEachIndexed { i, (a, pa) ->
            presets.drop(i + 1).forEach { (b, pb) ->
                assertFalse("$a and $b are the same look", pa.matchesPreset(pb))
            }
        }
    }

    @Test
    fun `match player glass copies the shared optics but keeps the lyric body`() {
        val glass = PlayerGlassSettings.PRESETS.first { it.first == "Tilt" }.second
        val mine = LyricsFxSettings(liquidGlass = false, glassBodyOpacity = 0.7f, fontSizeSp = 28f)
        val matched = mine.withGlassOpticsFrom(glass)
        assertTrue("matching turns the letter glass on", matched.liquidGlass)
        assertEquals(glass.refraction, matched.glassRefraction, 0f)
        assertEquals(glass.rimBrightness, matched.glassRimBrightness, 0f)
        assertEquals(glass.dispersion, matched.glassDispersion, 0f)
        assertEquals(glass.roundness, matched.glassRoundness, 0f)
        assertEquals(glass.depth, matched.glassDepth, 0f)
        assertEquals(glass.surfaceMotion, matched.glassSurfaceMotion, 0f)
        assertEquals(glass.reflection, matched.glassReflection, 0f)
        assertEquals(glass.gloss, matched.glassGloss, 0f)
        assertEquals(glass.tiltReactivity, matched.glassTiltReactivity, 0f)
        assertEquals(glass.lightAngleDeg, matched.glassLightAngleDeg, 0f)
        assertEquals(glass.edgeWidth, matched.glassEdgeWidth, 0f)
        assertEquals(glass.frost, matched.glassFrost, 0f)
        // A pane is meant to be nearly clear; a letter has to stay readable.
        assertEquals(0.7f, matched.glassBodyOpacity, 0f)
        assertEquals(28f, matched.fontSizeSp, 0f)
        assertEquals(matched, matched.clamped())
    }

    @Test
    fun `match player glass stays in range from every glass theme`() {
        PlayerGlassSettings.PRESETS.forEach { (name, glass) ->
            val matched = LyricsFxSettings.DEFAULT.withGlassOpticsFrom(glass)
            assertEquals("$name produced an out-of-range lyric look", matched, matched.clamped())
        }
    }
}
