package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp

/**
 * How small the blurred background's cover is decoded. Small enough to stop it
 * filling Coil's cache with screen-sized bitmaps, never so small that the blur
 * stops hiding the difference.
 */
class BlurredCoverDecodeTest {

    /** A phone's long side and density, and a tablet's. */
    private val screens = listOf(2400 to 2.625f, 3200 to 3.5f, 2560 to 2f, 1600 to 1.5f)

    /** The player's two blurs: the background and the expanded lyrics' stain. */
    private val playerBlursDp = listOf(64f, 40f)

    private fun sigma(radiusPx: Float) = 0.57735f * radiusPx + 0.5f

    @Test
    fun `a phone decodes the player's backdrop at the floor`() {
        assertEquals(MIN_BLURRED_COVER_PX, blurredCoverDecodePx(64f * 2.625f, 2400))
        assertEquals(MIN_BLURRED_COVER_PX, blurredCoverDecodePx(40f * 2.625f, 2400))
    }

    @Test
    fun `every decode keeps four texels under one sigma of its blur`() {
        for ((longSide, density) in screens) {
            for (dp in playerBlursDp + listOf(4f, 8f, 16f)) {
                val radius = dp * density
                val side = blurredCoverDecodePx(radius, longSide) ?: continue
                val sigmaInTexels = sigma(radius) * side / longSide
                assertTrue("$dp dp on $longSide px: $sigmaInTexels", sigmaInTexels >= BLUR_TEXELS_PER_SIGMA - 1e-3f)
                assertTrue(side >= MIN_BLURRED_COVER_PX)
                assertTrue(side < longSide)
            }
        }
    }

    @Test
    fun `what the smaller decode drops comes through the blur far under a step of colour`() {
        // A gaussian passes a frequency f (cycles per pixel) at
        // exp(-2 pi^2 sigma^2 f^2). Half the decoded image's finest detail is a
        // quarter of a cycle per texel; past the player's blurs it must not
        // reach half of one 8-bit step.
        for ((longSide, density) in screens) {
            for (dp in playerBlursDp) {
                val radius = dp * density
                val side = blurredCoverDecodePx(radius, longSide) ?: continue
                val s = sigma(radius) * side / longSide
                val passed = exp(-2.0 * PI * PI * s * s * 0.25 * 0.25)
                assertTrue("$dp dp on $longSide px passes $passed", passed < 0.5 / 255.0)
            }
        }
    }

    @Test
    fun `a lighter blur keeps more of the cover`() {
        val heavy = blurredCoverDecodePx(168f, 2400)!!
        val light = blurredCoverDecodePx(8f, 2400)!!
        assertTrue(light > heavy)
    }

    @Test
    fun `no blur, or one too light to save anything, keeps the size it is drawn`() {
        assertNull(blurredCoverDecodePx(0f, 2400))
        assertNull(blurredCoverDecodePx(1f, 2400))
        assertNull(blurredCoverDecodePx(168f, 0))
    }
}
