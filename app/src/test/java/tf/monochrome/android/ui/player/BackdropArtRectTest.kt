package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping that decides which pixels a pane of glass refracts.
 *
 * [backdropArtRect] inverts the `ContentScale.Crop` that BlurredCoverLayer
 * stretches the cover with, so the shader can ask "what is on the screen behind
 * me" in the artwork's own coordinates. Get it wrong and nothing crashes and
 * nothing looks obviously broken — the glass simply lenses the wrong part of
 * the cover, which reads as a texture rather than as a mistake. That is exactly
 * the kind of bug that survives review, so it is pinned here instead.
 */
class BackdropArtRectTest {

    /** A square 64px thumbnail behind a 1080x2400 portrait screen. */
    private fun portrait(
        paneLeft: Float,
        paneTop: Float,
        paneW: Float,
        paneH: Float,
    ) = backdropArtRect(
        artW = 64, artH = 64,
        rootW = 1080f, rootH = 2400f,
        paneLeft = paneLeft, paneTop = paneTop, paneW = paneW, paneH = paneH,
    )

    @Test
    fun `a full-screen pane takes the whole height and the middle of the width`() {
        val r = portrait(0f, 0f, 1080f, 2400f)
        // Crop on a portrait screen scales by the HEIGHT ratio (2400/64 = 37.5),
        // so the full height maps to the full 64px.
        assertEquals(64f, r[3], 0.01f)
        assertEquals(0f, r[1], 0.01f)
        // The width overflows the screen, which means the screen sees only the
        // MIDDLE band of the cover — so the rect starts inside the bitmap, not
        // before it. (Getting this sign backwards is the obvious mistake: the
        // cover overflows, but the rect describes the visible part of it.)
        assertEquals(1080f / 37.5f, r[2], 0.01f)
        assertTrue("should start inside the bitmap, was ${r[0]}", r[0] > 0f)
        assertEquals((64f - 1080f / 37.5f) / 2f, r[0], 0.01f)
        // ...and that band is centred on the cover.
        assertEquals(32f, r[0] + r[2] / 2f, 0.01f)
    }

    @Test
    fun `a pane low on the screen reads low on the cover`() {
        val top = portrait(0f, 0f, 1080f, 200f)
        val bottom = portrait(0f, 2200f, 1080f, 200f)
        assertTrue("bottom pane must sample below the top one", bottom[1] > top[1])
        // Same height, so the same slice of cover — only moved.
        assertEquals(top[3], bottom[3], 0.01f)
        assertEquals(2200f / 37.5f, bottom[1], 0.01f)
    }

    @Test
    fun `two panes side by side sample different columns`() {
        val left = portrait(0f, 1000f, 300f, 300f)
        val right = portrait(780f, 1000f, 300f, 300f)
        assertTrue(right[0] > left[0])
        assertEquals(780f / 37.5f, right[0] - left[0], 0.01f)
    }

    @Test
    fun `a landscape window crops the other axis`() {
        // 2400x1080: now the WIDTH ratio wins, so the cover's full width maps
        // across and it is the top and bottom that are cropped away — the
        // mirror image of the portrait case, and the reason the helper takes
        // the max of the two ratios rather than assuming a tall screen.
        val r = backdropArtRect(
            artW = 64, artH = 64,
            rootW = 2400f, rootH = 1080f,
            paneLeft = 0f, paneTop = 0f, paneW = 2400f, paneH = 1080f,
        )
        assertEquals(64f, r[2], 0.01f)
        assertEquals(0f, r[0], 0.01f)
        assertTrue("should start inside the bitmap, was ${r[1]}", r[1] > 0f)
        assertEquals(32f, r[1] + r[3] / 2f, 0.01f)
    }

    @Test
    fun `a non-square cover keeps its aspect ratio`() {
        // A 128x64 cover across a square window: the height ratio wins, the
        // width overflows, and the pane still reads a square slice.
        val r = backdropArtRect(
            artW = 128, artH = 64,
            rootW = 1000f, rootH = 1000f,
            paneLeft = 0f, paneTop = 0f, paneW = 500f, paneH = 500f,
        )
        assertEquals(r[2], r[3], 0.01f)
    }

    @Test
    fun `fitted to the bar, the cover sweeps along its whole length`() {
        // BackdropArtFit.PANE passes the pane in as its own root. A mini player
        // is about 1000x64: the honest mapping would hand it a five-pixel strip
        // of thumbnail, which refraction cannot move enough to see, so instead
        // the cover is fitted to the bar.
        val bar = backdropArtRect(
            artW = 64, artH = 64,
            rootW = 1000f, rootH = 64f,
            paneLeft = 0f, paneTop = 0f, paneW = 1000f, paneH = 64f,
        )
        // The full width of the cover is in play -- that is what makes its
        // colours sweep from one end of the bar to the other.
        assertEquals(0f, bar[0], 0.01f)
        assertEquals(64f, bar[2], 0.01f)
        // A thin band, taken from the middle of the cover rather than its edge.
        assertTrue("band should be short, was ${bar[3]}", bar[3] < 8f)
        assertEquals(32f, bar[1] + bar[3] / 2f, 0.01f)

        // And it is strictly more colour than the honest mapping would give:
        // that one reads a sliver a fraction of a thumbnail pixel wide.
        val honest = portrait(0f, 2300f, 1000f, 64f)
        assertTrue("fitted must span more of the cover", bar[2] > honest[2] * 2f)
    }

    @Test
    fun `a pane measured before layout falls back to the whole cover`() {
        // rootW/rootH of zero is the one frame between composition and layout.
        // Dividing by it would hand the shader a NaN rect, and a NaN sampler
        // coordinate takes the whole pane black rather than degrading.
        val r = backdropArtRect(64, 64, 0f, 0f, 0f, 0f, 100f, 100f)
        assertEquals(0f, r[0], 0f)
        assertEquals(0f, r[1], 0f)
        assertEquals(64f, r[2], 0f)
        assertEquals(64f, r[3], 0f)
    }

    @Test
    fun `an undecoded cover cannot produce a divide by zero`() {
        val r = backdropArtRect(0, 0, 1080f, 2400f, 0f, 0f, 100f, 100f)
        assertTrue(r.all { it.isFinite() })
    }
}
