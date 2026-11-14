package tf.monochrome.android.ui.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.RoomShape

/** The 3D room on the Atmos page: where things land, and what lights a speaker. */
class AtmosRoomViewTest {

    private val box = RoomShape(width = 4f, depth = 4f, height = 2f)

    @Test
    fun `seen from behind, left is left, the front is further and the ceiling is higher`() {
        val view = AtmosRoomView(yaw = 0f, shape = box)
        val frontLeft = view.project(0f, 0f, 0f)
        val frontRight = view.project(4f, 0f, 0f)
        val backLeft = view.project(0f, 4f, 0f)
        val topFrontLeft = view.project(0f, 0f, 2f)
        assertTrue(frontLeft.x < 0f && frontRight.x > 0f)
        assertEquals(-frontLeft.x, frontRight.x, 1e-5f)
        // The front wall is further from the eye: drawn smaller, and higher up the page.
        assertTrue(frontLeft.depth > backLeft.depth)
        assertTrue(frontLeft.scale < backLeft.scale)
        assertTrue(frontLeft.y > backLeft.y)
        assertTrue(topFrontLeft.y > frontLeft.y)
        // The middle of the room is the middle of the view.
        val centre = view.project(2f, 2f, 1f)
        assertEquals(0f, centre.x, 1e-5f)
        assertEquals(0f, centre.y, 1e-5f)
        assertEquals(1f, centre.scale, 1e-5f)
    }

    @Test
    fun `half a turn puts the front at the back and swaps the sides`() {
        val ahead = AtmosRoomView(yaw = 0f, shape = box)
        val behind = AtmosRoomView(yaw = Math.PI.toFloat(), shape = box)
        val a = ahead.project(0f, 0f, 0f)
        val b = behind.project(0f, 0f, 0f)
        assertEquals(-a.x * b.scale / a.scale, b.x, 1e-4f)
        assertTrue(b.depth < 0f && a.depth > 0f)
    }

    @Test
    fun `a room is drawn to its own proportions, as wide as its longer side allows`() {
        // Twice as deep as it is wide: the side walls are half as far out as the end walls.
        val long = AtmosRoomView(yaw = 0f, shape = RoomShape(width = 3f, depth = 6f, height = 3f))
        val square = AtmosRoomView(yaw = 0f, shape = RoomShape(width = 6f, depth = 6f, height = 3f))
        val mid = long.project(3f, 3f, 1.5f)
        val wide = square.project(6f, 3f, 1.5f)
        assertEquals(wide.x / 2f, mid.x, 1e-5f)
        // The same room at any size is the same picture.
        val small = AtmosRoomView(yaw = 0.4f, shape = RoomShape(width = 2f, depth = 3f, height = 1f)).project(2f, 0f, 1f)
        val large = AtmosRoomView(yaw = 0.4f, shape = RoomShape(width = 20f, depth = 30f, height = 10f)).project(20f, 0f, 10f)
        assertEquals(small.x, large.x, 1e-5f)
        assertEquals(small.y, large.y, 1e-5f)
    }

    @Test
    fun `a speaker is as bright as it plays, and half lit for an object that is only watched`() {
        assertEquals(0f, atmosSpeakerGlow(0f), 0f)
        assertEquals(1f, atmosSpeakerGlow(1f), 1e-5f)
        assertEquals(0.5f, atmosSpeakerGlow(0.0316f), 0.01f)   // -30 dB of the meter's 60
        // Under zero: a share of an object with no level. Never dark, never full.
        assertEquals(0.70f, atmosSpeakerGlow(-1f), 1e-5f)
        assertEquals(0.25f + 0.45f * 0.5f, atmosSpeakerGlow(-0.5f), 1e-5f)
        assertEquals(0.70f, atmosSpeakerGlow(-3f), 1e-5f)
    }

    // x, y, z, LFE: the corners, the centre, the LFE, a 5.1 surround and a side.
    private val layout = floatArrayOf(
        0.5f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f,
        1f, 0f, 0f, 0f,
        0f, 0.5f, 0f, 0f,
        0f, 0.75f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0.5f, 0.5f, 0f, 1f,
        0f, 0f, 0.5f, 0f,
    )

    @Test
    fun `a plain stream's channels light the speakers a bed plays from`() {
        val names = listOf("FL", "FR", "FC", "LFE", "BL", "BR")
        val db = floatArrayOf(0f, -20f, -120f, -6f, -20f, -20f)
        val got = roomLevelsFromChannels(layout, names, db)!!
        assertEquals(1f, got[1], 1e-5f)          // FL
        assertEquals(0.1f, got[2], 1e-5f)        // FR
        assertEquals(0f, got[0], 1e-5f)          // FC, silent
        assertEquals(0.501f, got[6], 1e-3f)      // the LFE, not the speaker at the same place
        // A 5.1 bed's surround is the speaker at 110 degrees, not the back corner.
        assertEquals(0.1f, got[4], 1e-5f)
        assertEquals(0f, got[5], 0f)
        assertEquals(0f, got[7], 0f)             // a height speaker over FL stays dark
    }

    @Test
    fun `a 7_1 bed's rears are at the back, and unknown channels light nothing`() {
        val names = listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
        val db = FloatArray(8) { -20f }
        val got = roomLevelsFromChannels(layout, names, db)!!
        assertEquals(0.1f, got[5], 1e-5f)        // BL at the back corner
        assertEquals(0.1f, got[3], 1e-5f)        // SL at the side
        assertEquals(0f, got[4], 0f)
        assertNull(roomLevelsFromChannels(layout, listOf("Ch 1", "Ch 2"), floatArrayOf(0f, 0f)))
        assertNull(roomLevelsFromChannels(FloatArray(0), names, db))
        assertArrayEquals(FloatArray(8), roomLevelsFromChannels(layout, listOf("FL"), floatArrayOf(-200f))!!, 1e-6f)
    }
}
