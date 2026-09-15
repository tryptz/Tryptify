package tf.monochrome.android.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ambient overlay's arithmetic, which is otherwise a string nobody can run.
 *
 * There is no device in this build and no GL context in a JVM test, so the
 * fragment shader cannot be executed here. These functions are the same maths
 * written in Kotlin; testing them is how the shader's *intent* gets checked,
 * and any change to one has to be mirrored in the other by hand.
 */
class AmbientVisualizerTest {

    private val eps = 1e-4f

    // ── Alpha ───────────────────────────────────────────────────────────

    @Test
    fun `black disappears completely`() {
        assertEquals(0f, ambientAlpha(0f, 0f, 0f, blackPoint = 0.02f, opacity = 1f), eps)
    }

    @Test
    fun `white survives at the full requested opacity`() {
        assertEquals(1f, ambientAlpha(1f, 1f, 1f, blackPoint = 0.02f, opacity = 1f), eps)
        assertEquals(0.5f, ambientAlpha(1f, 1f, 1f, blackPoint = 0.02f, opacity = 0.5f), eps)
    }

    @Test
    fun `opacity zero silences the layer whatever is on it`() {
        assertEquals(0f, ambientAlpha(1f, 1f, 1f, blackPoint = 0.02f, opacity = 0f), eps)
    }

    @Test
    fun `a saturated blue survives even though its luma is far below the threshold`() {
        // This is the whole reason the peak term exists. Pure blue has a
        // Rec.709 luma of 0.0722 — under a plain luminance rule with any
        // useful threshold it would vanish, taking most of MilkDrop's deep
        // blues, reds and purples with it.
        val blue = ambientAlpha(0f, 0f, 1f, blackPoint = 0.10f, opacity = 1f)
        assertTrue("saturated blue was erased (alpha=$blue)", blue > 0.9f)

        // And a luminance-only rule really would have erased it.
        val lumOnly = smoothstep(0.10f, 0.10f + AMBIENT_KNEE, luminance(0f, 0f, 1f))
        assertEquals(0f, lumOnly, eps)
    }

    @Test
    fun `white is never dimmer than a saturated colour`() {
        var i = 0
        while (i <= 100) {
            val bp = i / 100f
            val blue = ambientAlpha(0f, 0f, 1f, bp, opacity = 1f)
            val white = ambientAlpha(1f, 1f, 1f, bp, opacity = 1f)
            assertTrue("white came out dimmer than blue at blackPoint=$bp", white >= blue - eps)
            i++
        }
    }

    @Test
    fun `the peak term is scaled so a saturated colour is not mistaken for white`() {
        // Across the range the settings actually allow (up to 25%) the 0.8
        // scale makes no difference — a saturated blue is fully opaque there,
        // which is exactly what it is for. The scale shows up further up: past
        // a threshold of 0.8 white is still fully opaque and blue is not, so
        // the two are not simply the same signal.
        val high = 0.70f
        assertTrue(
            "without the 0.8 scale these would be identical everywhere",
            ambientAlpha(1f, 1f, 1f, high, opacity = 1f) >
                ambientAlpha(0f, 0f, 1f, high, opacity = 1f),
        )
        // And within the usable range they deliberately agree.
        val usable = AmbientVisualizerSettings.MAX_BLACK_POINT / 100f
        assertEquals(
            ambientAlpha(1f, 1f, 1f, usable, opacity = 1f),
            ambientAlpha(0f, 0f, 1f, usable, opacity = 1f),
            eps,
        )
    }

    @Test
    fun `alpha rises monotonically with brightness`() {
        var previous = -1f
        var step = 0
        while (step <= 100) {
            val v = step / 100f
            val a = ambientAlpha(v, v, v, blackPoint = 0.02f, opacity = 1f)
            assertTrue("alpha went backwards at $v", a >= previous - eps)
            previous = a
            step++
        }
        assertEquals(1f, previous, eps)
    }

    @Test
    fun `raising the black point hides more of the dark end`() {
        val dim = 0.08f
        val low = ambientAlpha(dim, dim, dim, blackPoint = 0.01f, opacity = 1f)
        val high = ambientAlpha(dim, dim, dim, blackPoint = 0.20f, opacity = 1f)
        assertTrue("a higher black point must hide more, not less", high < low)
        assertEquals(0f, high, eps)
    }

    @Test
    fun `the curve lifts faint trails rather than crushing them`() {
        // pow(0.8) is a lift: at the midpoint of the ramp the result must sit
        // above the raw smoothstep, or dim trails would disappear.
        val mid = 0.02f + AMBIENT_KNEE / 2f
        val a = ambientAlpha(mid, mid, mid, blackPoint = 0.02f, opacity = 1f)
        val raw = smoothstep(0.02f, 0.02f + AMBIENT_KNEE, mid)
        assertTrue("the lift is missing ($a vs $raw)", a > raw)
    }

    // ── Blending ────────────────────────────────────────────────────────

    @Test
    fun `screen leaves the backdrop untouched where the preset is black`() {
        // The property that makes Screen the right default for MilkDrop.
        listOf(0f, 0.25f, 0.5f, 0.9f, 1f).forEach { bg ->
            assertEquals(bg, blendChannel(bg, 0f, VisualizerBlendMode.SCREEN), eps)
        }
    }

    @Test
    fun `screen never darkens and never overflows`() {
        listOf(0f, 0.3f, 0.7f, 1f).forEach { bg ->
            listOf(0f, 0.3f, 0.7f, 1f).forEach { fx ->
                val out = blendChannel(bg, fx, VisualizerBlendMode.SCREEN)
                assertTrue("screen darkened $bg with $fx", out >= bg - eps)
                assertTrue("screen overflowed", out <= 1f + eps)
            }
        }
    }

    @Test
    fun `additive clamps instead of wrapping`() {
        assertEquals(1f, blendChannel(0.8f, 0.9f, VisualizerBlendMode.ADDITIVE), eps)
    }

    @Test
    fun `soft light leaves a mid-grey preset alone`() {
        // 0.5 is soft-light's neutral: it must not shift the backdrop at all.
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { bg ->
            assertEquals(bg, blendChannel(bg, 0.5f, VisualizerBlendMode.SOFT_LIGHT), 1e-3f)
        }
    }

    @Test
    fun `soft light darkens below neutral and lightens above it`() {
        val bg = 0.5f
        assertTrue(blendChannel(bg, 0.2f, VisualizerBlendMode.SOFT_LIGHT) < bg)
        assertTrue(blendChannel(bg, 0.8f, VisualizerBlendMode.SOFT_LIGHT) > bg)
    }

    @Test
    fun `every mode stays inside the unit range`() {
        VisualizerBlendMode.entries.forEach { mode ->
            var i = 0
            while (i <= 10) {
                var j = 0
                while (j <= 10) {
                    val out = blendChannel(i / 10f, j / 10f, mode)
                    assertTrue("$mode went out of range at ${i / 10f}/${j / 10f}: $out",
                        out >= -eps && out <= 1f + eps)
                    j++
                }
                i++
            }
        }
    }

    // ── Composite ───────────────────────────────────────────────────────

    @Test
    fun `alpha zero leaves the album backdrop exactly as it was`() {
        // The guarantee that a silenced overlay is invisible rather than
        // nearly invisible: turning it down must not tint the artwork.
        VisualizerBlendMode.entries.forEach { mode ->
            assertEquals(0.42f, compositeChannel(0.42f, 1f, 0f, mode), eps)
        }
    }

    @Test
    fun `alpha one is the blend outright`() {
        VisualizerBlendMode.entries.forEach { mode ->
            assertEquals(
                blendChannel(0.3f, 0.7f, mode),
                compositeChannel(0.3f, 0.7f, 1f, mode),
                eps,
            )
        }
    }

    @Test
    fun `black MilkDrop over the cover is the cover, on the default settings`() {
        // End to end: the default configuration, a black preset pixel, and
        // the artwork coming through untouched.
        val settings = AmbientVisualizerSettings(enabled = true)
        val a = ambientAlpha(0f, 0f, 0f, settings.blackPoint, settings.opacity)
        assertEquals(0.35f, compositeChannel(0.35f, 0f, a, settings.blend), eps)
    }

    // ── Settings ────────────────────────────────────────────────────────

    @Test
    fun `screen is the default blend`() {
        assertEquals(VisualizerBlendMode.SCREEN, AmbientVisualizerSettings().blend)
        assertEquals(VisualizerBlendMode.SCREEN, VisualizerBlendMode.fromId(null))
        assertEquals(VisualizerBlendMode.SCREEN, VisualizerBlendMode.fromId("nonsense"))
    }

    @Test
    fun `the overlay is off until it is asked for`() {
        // It changes what the player looks like, so it cannot arrive by update.
        assertEquals(false, AmbientVisualizerSettings().enabled)
    }

    @Test
    fun `blend ids round-trip and are unique`() {
        val ids = VisualizerBlendMode.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        VisualizerBlendMode.entries.forEach {
            assertEquals(it, VisualizerBlendMode.fromId(it.id))
        }
    }

    @Test
    fun `percentages are clamped into the range the shader expects`() {
        assertEquals(1f, AmbientVisualizerSettings(opacityPercent = 500).opacity, eps)
        assertEquals(0f, AmbientVisualizerSettings(opacityPercent = -20).opacity, eps)
        assertEquals(
            AmbientVisualizerSettings.MAX_BLACK_POINT / 100f,
            AmbientVisualizerSettings(blackPointPercent = 90).blackPoint,
            eps,
        )
        assertEquals(0f, AmbientVisualizerSettings(blackPointPercent = -5).blackPoint, eps)
    }

    @Test
    fun `the shaders declare the uniforms the renderer sets`() {
        // A misspelled uniform is a silent no-op at runtime — the location
        // comes back -1 and the value is dropped. Nothing else in this build
        // can catch that, so the names are pinned here.
        listOf(
            "uFx", "uAlbum", "uScrimTone", "uOpacity", "uBlackPoint", "uKnee", "uBlend",
        ).forEach {
            assertTrue("$it is missing from the fragment shader",
                AMBIENT_FRAGMENT_SHADER.contains("uniform") &&
                    AMBIENT_FRAGMENT_SHADER.contains(it))
        }
        assertTrue(AMBIENT_VERTEX_SHADER.contains("aPos"))
        // Both stages must agree on the version, or linking fails.
        assertTrue(AMBIENT_VERTEX_SHADER.startsWith("#version 300 es"))
        assertTrue(AMBIENT_FRAGMENT_SHADER.startsWith("#version 300 es"))
    }
}
