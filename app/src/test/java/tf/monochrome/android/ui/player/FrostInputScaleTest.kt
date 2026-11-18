package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.PlayerGlassSettings

/**
 * A frosted pane blurs its backdrop small ([frostInputScale]): the audio tools
 * sheet and the song menu halved the frame rate blurring it at full size on
 * every frame. A wide blur loses nothing by it; a narrow one must stay sharp.
 */
class FrostInputScaleTest {
    @Test
    fun `a wide blur is done on a small backdrop, a narrow one at full size`() {
        assertEquals(1f, frostInputScale(0f), 0f)
        assertEquals(1f, frostInputScale(3f), 0f)
        assertEquals(1f, frostInputScale(FROST_SMALL_BLUR_DP), 0f)
        assertEquals(0.5f, frostInputScale(20f), 1e-6f)
        // The default blur and everything wider: a quarter each way, a sixteenth of the pixels.
        assertEquals(FROST_MIN_SCALE, frostInputScale(PlayerGlassSettings().hazeBlurDp), 0f)
        assertEquals(FROST_MIN_SCALE, frostInputScale(80f), 0f)
    }

    @Test
    fun `the blur is never under ten dp where it is done, and the scale never rises with the blur`() {
        var last = 1f
        var dp = 0f
        while (dp <= 80f) {
            val scale = frostInputScale(dp)
            assertTrue("$dp dp", scale in FROST_MIN_SCALE..1f)
            assertTrue("$dp dp", scale <= last)
            if (scale < 1f) assertTrue("$dp dp", dp * scale >= FROST_SMALL_BLUR_DP - 1e-3f)
            last = scale
            dp += 0.5f
        }
    }
}
