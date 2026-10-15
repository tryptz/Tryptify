package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinch zooms more than the fingers move, and the same both ways. */
class GalaxyPinchTest {

    @Test
    fun `fingers still is no zoom`() {
        assertEquals(1f, GalaxyCamera.pinch(1f), 0f)
    }

    @Test
    fun `a pinch zooms further than one to one, in and out`() {
        assertTrue(GalaxyCamera.pinch(1.1f) > 1.1f)
        assertTrue(GalaxyCamera.pinch(0.9f) < 0.9f)
    }

    @Test
    fun `out and back in again lands where it started`() {
        val camera = GalaxyCamera().apply { distance = 500f }
        camera.zoom(GalaxyCamera.pinch(1.3f))
        camera.zoom(GalaxyCamera.pinch(1f / 1.3f))
        assertEquals(500f, camera.distance, 1e-2f)
    }
}
