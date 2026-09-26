package tf.monochrome.android.ui.mixer.spatial

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.spatial.ChannelPlacement
import tf.monochrome.android.audio.dsp.spatial.SpatialLayout
import tf.monochrome.android.domain.model.SpeakerChannel

/** The map's drag maths: finger to placement and back. */
class SpatialMapGeometryTest {

    private val c = Offset(500f, 500f)
    private val half = 500f * RING_FILL
    private val free = SpeakerChannel("X", 77f) // far from every test angle: no snap

    @Test
    fun `a placement drawn and picked up again is the same placement`() {
        for (az in listOf(-170f, -120f, -45f, 0f, 30f, 95f, 150f)) {
            for (d in listOf(0.5f, 0.8f, 1.4f, 2f)) {
                val at = pointFor(az, d, c, half)
                val back = placementAt(at, c.x, c.y, free)
                assertEquals("az $az d $d", az, back.azimuthDeg, 0.05f)
                assertEquals("az $az d $d", d, back.distance, 1e-3f)
            }
        }
    }

    @Test
    fun `ahead is up, left is left`() {
        val ahead = pointFor(0f, 1f, c, half)
        assertTrue(ahead.y < c.y && kotlin.math.abs(ahead.x - c.x) < 1e-3f)
        val left = pointFor(-90f, 1f, c, half)
        assertTrue(left.x < c.x && kotlin.math.abs(left.y - c.y) < 1e-3f)
        val behind = pointFor(180f, 1f, c, half)
        assertTrue(behind.y > c.y)
    }

    @Test
    fun `the ring and the channel's own angle snap`() {
        val bl = SpeakerChannel("BL", -150f)
        val near = placementAt(pointFor(-148f, 1.04f, c, half), c.x, c.y, bl)
        assertEquals(-150f, near.azimuthDeg)
        assertEquals(1f, near.distance)
    }

    @Test
    fun `a finger past the edge or on the head stays on the map`() {
        assertEquals(SpatialLayout.MAX_DISTANCE, placementAt(Offset(c.x + 2000f, c.y), c.x, c.y, free).distance)
        assertEquals(SpatialLayout.MIN_DISTANCE, placementAt(Offset(c.x + 1f, c.y), c.x, c.y, free).distance)
    }

    @Test
    fun `the dot nearest the finger is picked, the LFE never`() {
        val speakers = SpatialLayout.speakers(6)
        val placed = speakers.map { ChannelPlacement(it.azimuthDeg, 1f) }
        val onBl = pointFor(-110f, 1f, c, half)
        assertEquals(4, nearestDot(onBl + Offset(5f, 5f), c.x, c.y, speakers, placed, 60f))
        // Nothing within reach.
        assertEquals(-1, nearestDot(Offset(0f, 0f), c.x, c.y, speakers, placed, 60f))
        // FC and the LFE share an angle; the LFE is not draggable.
        assertEquals(2, nearestDot(pointFor(0f, 1f, c, half), c.x, c.y, speakers, placed, 60f))
    }
}
