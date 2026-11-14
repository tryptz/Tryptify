package tf.monochrome.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.RoomShape

/** The GPU room's camera and scene: what the shader is told, and where the labels go over it. */
class RoomCameraTest {
    private val room = RoomShape(width = 6f, depth = 4f, height = 3f)
    private val w = 1000f
    private val h = 800f

    @Test
    fun `seen from behind, left is left, up is up and the wall ahead is the far one`() {
        val camera = RoomCamera(yaw = 0f, shape = room, width = w, height = h)
        val frontLeft = camera.project(0f, 0f, 0f)!!
        val frontRight = camera.project(6f, 0f, 0f)!!
        val backLeft = camera.project(0f, 4f, 0f)!!
        val topFrontLeft = camera.project(0f, 0f, 3f)!!
        assertTrue(frontLeft.x < w / 2f && frontRight.x > w / 2f)
        assertEquals(w - frontLeft.x, frontRight.x, 0.01f)
        assertTrue(topFrontLeft.y < frontLeft.y)                       // the page's y runs down
        // The wall behind is nearer the camera: lower on the page, and further out.
        assertTrue(backLeft.y > frontLeft.y)
        assertTrue(backLeft.x < frontLeft.x)
        assertTrue(camera.distanceTo(0f, 4f, 0f) < camera.distanceTo(0f, 0f, 0f))
        // What it looks at is the middle of the page.
        val middle = camera.project(3f, 2f, 3f * RoomCamera.LOOK_AT)!!
        assertEquals(w / 2f, middle.x, 0.01f)
        assertEquals(h / 2f, middle.y, 0.01f)
    }

    @Test
    fun `its basis is square, and the whole room is in the picture at any turn`() {
        for (yaw in listOf(-2.5f, -0.5f, 0f, 0.9f, 3.1f)) {
            val camera = RoomCamera(yaw, room, w, h)
            fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            assertEquals(1f, dot(camera.right, camera.right), 1e-5f)
            assertEquals(1f, dot(camera.up, camera.up), 1e-5f)
            assertEquals(1f, dot(camera.forward, camera.forward), 1e-5f)
            assertEquals(0f, dot(camera.right, camera.up), 1e-5f)
            assertEquals(0f, dot(camera.right, camera.forward), 1e-5f)
            assertEquals(0f, dot(camera.up, camera.forward), 1e-5f)
            assertTrue(camera.up[2] > 0f)                               // the ceiling is up
            for (x in listOf(0f, 6f)) for (y in listOf(0f, 4f)) for (z in listOf(0f, 3f)) {
                val p = camera.project(x, y, z)
                assertNotNull(p)
                assertTrue("corner ($x, $y, $z) at yaw $yaw is at $p", p!!.x in 0f..w && p.y in 0f..h)
            }
        }
        // A tall, narrow page still holds it.
        val tall = RoomCamera(0.4f, room, 400f, 900f)
        for (x in listOf(0f, 6f)) for (y in listOf(0f, 4f)) {
            assertTrue(tall.project(x, y, 0f)!!.x in 0f..400f)
        }
        // Behind the camera there is nothing to place.
        val camera = RoomCamera(0f, room, w, h)
        assertNull(camera.project(camera.eye[0], camera.eye[1] + 5f, camera.eye[2]))
    }

    @Test
    fun `the scene keeps what the shader has room for`() {
        val scene = RoomScene()
        repeat(40) { scene.addSpeaker(it.toFloat(), 0f, 0f, level = 0.5f, air = 0.25f, kind = RoomScene.KIND_EAR) }
        assertEquals(RoomScene.MAX_SPEAKERS, scene.speakerCount)
        assertEquals(0.25f, scene.speakerAir[RoomScene.MAX_SPEAKERS - 1], 0f)
        // An object's level is never nothing, and a height object's is under zero.
        scene.addObject(1f, 2f, 3f, 0f, high = false)
        scene.addObject(1f, 2f, 3f, 0.6f, high = true)
        assertEquals(2, scene.objectCount)
        assertTrue(scene.objects[3] > 0f)
        assertEquals(-0.6f, scene.objects[7], 0f)
        // Where sound turns on a surface: the strongest twelve are kept, whatever order they come in.
        for (i in 0 until 20) scene.addSource(i.toFloat(), 0f, 0f, 0.05f * (i + 1))
        scene.addSource(99f, 0f, 0f, 0f)                                 // nothing: not a source
        assertEquals(RoomScene.MAX_SOURCES, scene.sourceCount)
        val kept = (0 until scene.sourceCount).map { scene.sources[it * 4 + 3] }.sorted()
        assertEquals(0.45f, kept.first(), 1e-5f)                         // the ninth of twenty and up
        assertEquals(1f, kept.last(), 1e-5f)
        scene.setRay(0, 1f, 1f, 1f, 0.7f)
        scene.setRay(7, 1f, 1f, 1f, 0.7f)                                // out of range: ignored
        assertEquals(0.7f, scene.rays[3], 0f)
        repeat(12) { scene.addMark(0f, 0f, 0f) }
        assertEquals(RoomScene.MAX_MARKS, scene.markCount)
        scene.clear()
        assertEquals(0, scene.speakerCount + scene.objectCount + scene.sourceCount + scene.markCount)
        assertEquals(0f, scene.rays[3], 0f)
    }

    @Test
    fun `the grain and the marks follow the room's size`() {
        val hall = RoomShape(width = 23f, depth = 23f, height = 6.7f)
        // The marks are their own size in the built-in room and no smaller in a little one.
        assertEquals(1f, RoomGrain.markScale(RoomShape.BUILT_IN), 0f)
        assertEquals(1f, RoomGrain.markScale(RoomShape(width = 2f, depth = 2.4f, height = 2.3f)), 0f)
        assertEquals(23f / 5.5f, RoomGrain.markScale(hall), 1e-4f)
        // A cloud reaches as far across a hall as across a booth, as a share of it.
        assertEquals(RoomGrain.reach(RoomShape.BUILT_IN) / 5f, RoomGrain.reach(hall) / 23f, 1e-5f)
        // However a room is proportioned, a ray crosses no more cells than the
        // shader walks (its CELLS, 168): the cells across, along and up it.
        for (room in listOf(hall, RoomShape.BUILT_IN, RoomShape(30f, 30f, 12f), RoomShape(2f, 30f, 12f), RoomShape(30f, 2f, 2f))) {
            val cell = RoomGrain.cell(room)
            assertTrue((room.width + room.depth + room.height) / cell <= 2.5f * RoomGrain.ACROSS + 1e-3f)
        }
        assertTrue(2.5f * RoomGrain.ACROSS <= 168f)
    }
}
