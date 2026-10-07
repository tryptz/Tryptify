package tf.monochrome.android.ui.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricsFxSettings

class LyricShadowGeometryTest {

    private fun frame(light: Offset, center: Offset, el: Float) = RayFrame(
        light = light, center = center, line = null, exposure = 0.6f, density = 0.85f,
        elevationDeg = el, scale = 1080f, time = 0f,
    )

    @Test
    fun `with no rays the shadow falls away from the glass key light`() {
        // The lyric glass ships lit from 135°, the upper left: shadow down-right.
        val cast = LyricShadowGeometry.cast(LyricsFxSettings(), ray = null)
        assertTrue("falls right", cast.direction.x > 0.6f)
        assertTrue("falls down", cast.direction.y > 0.6f)
        assertEquals(1f, cast.direction.getDistance(), 1e-4f)

        // Lit from straight above (90°): straight down.
        val top = LyricShadowGeometry.cast(LyricsFxSettings(glassLightAngleDeg = 90f), ray = null)
        assertEquals(0f, top.direction.x, 1e-4f)
        assertEquals(1f, top.direction.y, 1e-4f)
    }

    @Test
    fun `with rays in front it falls away from the rays' own light`() {
        val fx = LyricsFxSettings(godRays = true)
        val center = Offset(500f, 800f)
        // Light up and to the left: shadow down and to the right.
        val cast = LyricShadowGeometry.cast(fx, frame(Offset(200f, 400f), center, el = 40f))
        assertTrue(cast.direction.x > 0f && cast.direction.y > 0f)
        assertEquals(0.6f, cast.direction.x, 1e-4f)
        assertEquals(0.8f, cast.direction.y, 1e-4f)
    }

    @Test
    fun `a lower light throws a longer shadow, and one straight behind drops short and down`() {
        val fx = LyricsFxSettings(godRays = true)
        val center = Offset(500f, 800f)
        val low = LyricShadowGeometry.cast(fx, frame(Offset(500f, 100f), center, el = 15f))
        val high = LyricShadowGeometry.cast(fx, frame(Offset(500f, 600f), center, el = 70f))
        assertTrue(low.lengthDp > high.lengthDp)

        val behind = LyricShadowGeometry.cast(fx, frame(center, center, el = 90f))
        assertEquals(Offset(0f, 1f), behind.direction)
        assertTrue(behind.lengthDp < high.lengthDp)
    }

    @Test
    fun `a backlight leaves the shadow to the glass key light`() {
        // Lit from behind, the letters' shadow would fall toward the viewer,
        // where there is no background for it to land on.
        val fx = LyricsFxSettings(godRays = true, godRaySource = LyricsFxSettings.GOD_RAYS_BACKLIGHT)
        val withRays = LyricShadowGeometry.cast(fx, frame(Offset(500f, 100f), Offset(500f, 800f), el = 30f))
        val without = LyricShadowGeometry.cast(fx, ray = null)
        assertEquals(without, withRays)
    }

    @Test
    fun `soft at any depth, and never black`() {
        assertTrue(LyricShadowGeometry.blurDp(0.05f) >= 2f)
        assertTrue(LyricShadowGeometry.blurDp(1f) > LyricShadowGeometry.blurDp(0.3f))
        assertTrue(LyricShadowGeometry.alpha(1f) <= 0.65f)
        assertTrue(LyricShadowGeometry.alpha(0.3f) < LyricShadowGeometry.alpha(0.9f))
        // Deeper reaches further.
        assertTrue(
            LyricShadowGeometry.cast(LyricsFxSettings(shadowDepth = 1f), null).lengthDp >
                LyricShadowGeometry.cast(LyricsFxSettings(shadowDepth = 0.2f), null).lengthDp,
        )
    }
}
