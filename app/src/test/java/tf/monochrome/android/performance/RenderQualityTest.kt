package tf.monochrome.android.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.player.GodRayGeometry
import tf.monochrome.android.ui.player.SHAFTS_SCALE
import tf.monochrome.android.ui.player.lensDivisorFor

/**
 * Settings › System › Performance › Rendering quality ([RenderQuality]): High
 * is the app as it was, and each step down asks the GPU for fewer pixels and
 * fewer reads a pixel, never more.
 */
class RenderQualityTest {
    @Test
    fun `High is the app as it was before the setting`() {
        val high = RenderQuality.HIGH
        // Every pixel of the lens, every ring and sample the Studio allows,
        // the shafts at the half size they were fixed at, the dither on.
        assertEquals(1, high.lensDivisor)
        assertEquals(100, high.lensPixelPercent)
        assertEquals(SHAFTS_SCALE, high.shaftsScale, 0f)
        assertTrue(high.dither)
        for (rings in 1..3) assertEquals(rings, high.glassRings(rings))
        for (step in 1..4) assertEquals(step, high.rayQuality(step))
        // The defaults the two Studios ship are untouched by it.
        assertEquals(PlayerGlassSettings.INITIAL.sampleRings, high.glassRings(PlayerGlassSettings.INITIAL.sampleRings))
        assertEquals(LyricsFxSettings.DEFAULT.glassSampleRings, high.glassRings(LyricsFxSettings.DEFAULT.glassSampleRings))
        assertEquals(LyricsFxSettings.DEFAULT.godRayQuality, high.rayQuality(LyricsFxSettings.DEFAULT.godRayQuality))
    }

    @Test
    fun `it is the default, and what a build does not know is High`() {
        assertEquals(RenderQuality.HIGH, LowPerformanceSettings().renderQuality)
        assertEquals(RenderQuality.HIGH, RenderQuality.of(null))
        assertEquals(RenderQuality.HIGH, RenderQuality.of(""))
        assertEquals(RenderQuality.HIGH, RenderQuality.of("ultra"))
        RenderQuality.entries.forEach { assertEquals(it, RenderQuality.of(it.id)) }
        assertEquals(RenderQuality.entries.size, RenderQuality.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `each step down is fewer pixels and fewer reads, never more`() {
        val steps = listOf(RenderQuality.HIGH, RenderQuality.BALANCED, RenderQuality.FAST)
        steps.zipWithNext { finer, coarser ->
            assertTrue(coarser.lensPixelPercent < finer.lensPixelPercent)
            assertTrue(coarser.shaftsPixelPercent < finer.shaftsPixelPercent)
            assertTrue(coarser.maxGlassReads < finer.maxGlassReads)
            assertTrue(
                GodRayGeometry.samplesFor(coarser.maxRayQuality) < GodRayGeometry.samplesFor(finer.maxRayQuality),
            )
        }
        // The megapixels: a lens at half size is a quarter of its pixels, at a
        // third a ninth; the shafts are a quarter of the screen's at High.
        assertEquals(25, RenderQuality.BALANCED.lensPixelPercent)
        assertEquals(11, RenderQuality.FAST.lensPixelPercent)
        assertEquals(25, RenderQuality.HIGH.shaftsPixelPercent)
        assertEquals(16, RenderQuality.BALANCED.shaftsPixelPercent)
        assertEquals(11, RenderQuality.FAST.shaftsPixelPercent)
        // And the reads a slab pixel takes: 13, 9, 5.
        assertEquals(listOf(13, 9, 5), steps.map { it.maxGlassReads })
        assertFalse(RenderQuality.FAST.dither)
    }

    @Test
    fun `a cap never raises what the listener set`() {
        RenderQuality.entries.forEach { quality ->
            for (rings in 1..3) {
                assertTrue(quality.glassRings(rings) <= rings)
                assertTrue(quality.glassRings(rings) >= 1)
            }
            for (step in 1..4) {
                assertTrue(quality.rayQuality(step) <= step)
                assertTrue(quality.rayQuality(step) >= 1)
            }
        }
        // A listener who already chose the lightest keeps it at every quality.
        RenderQuality.entries.forEach { assertEquals(1, it.glassRings(1)) }
    }

    @Test
    fun `a lens is only drawn as small as its blur can hide`() {
        // Nothing smaller asked for: whole, whatever the blur.
        assertEquals(1, lensDivisorFor(1, 0f))
        assertEquals(1, lensDivisorFor(1, 200f))
        // Half size from a 12 px blur, as the galaxy's lighter glass always was.
        assertEquals(1, lensDivisorFor(2, 11.9f))
        assertEquals(2, lensDivisorFor(2, 12f))
        // A third needs 18 px; a narrower blur falls back to half, then whole.
        assertEquals(3, lensDivisorFor(3, 18f))
        assertEquals(2, lensDivisorFor(3, 17.9f))
        assertEquals(1, lensDivisorFor(3, 11f))
        // At every divisor the blur is at least six of the small layer's px.
        for (asked in 1..4) {
            var blur = 0f
            while (blur <= 120f) {
                val k = lensDivisorFor(asked, blur)
                assertTrue(k in 1..asked)
                if (k > 1) assertTrue("blur $blur at 1/$k", blur / k >= 6f)
                blur += 0.5f
            }
        }
        assertEquals(1, lensDivisorFor(0, 50f))
    }

    @Test
    fun `it is not one of the low-performance switches`() {
        // The master switch is the three switches and nothing else: choosing
        // Fast does not turn it on, and High does not turn it off.
        val all = LowPerformanceSettings(disableAnimations = true, legacyPlayer = true, disableLiquidGlass = true)
        assertTrue(all.copy(renderQuality = RenderQuality.HIGH).allEnabled)
        assertFalse(LowPerformanceSettings(renderQuality = RenderQuality.FAST).allEnabled)
    }
}
