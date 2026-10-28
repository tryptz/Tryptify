package tf.monochrome.android.ui.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The globe's per-frame projection, held to the one it replaced.
 *
 * The screen used to project every coastline, border, land and city vertex on
 * every frame of a drag with its own sines and cosines. It now places the
 * dataset on the sphere once ([GlobeLandClip.spherePoints]) and turns those
 * points by the camera ([GlobeLandClip.turn]); the land clipper runs on the
 * turned points in place ([GlobeLandClip.clipTurned]). [GlobeLandClipTest]
 * checks that the land stays land through [GlobeLandClip.clipRing] and
 * [GlobeLandClip.project]; this checks that what the screen draws is the same
 * thing those two compute, and that both still agree with the classic formula.
 */
class GlobeTurnTest {

    companion object {
        private lateinit var land: List<List<Int>>
        private lateinit var lines: List<List<Int>>
        private var scale: Int = 100
        private lateinit var orientations: FloatArray

        @BeforeClass
        @JvmStatic
        fun loadAsset() {
            val text = File("src/main/assets/world_radio.json").readText()
            val root = Json.parseToJsonElement(text).jsonObject
            scale = root["scale"]?.jsonPrimitive?.content?.toInt() ?: 100
            fun runs(key: String) = root.getValue(key).jsonArray.map { ring ->
                ring.jsonArray.map { it.jsonPrimitive.content.toInt() }
            }
            land = runs("land")
            lines = runs("coastline") + runs("borders") + land
            orientations = GlobeLandClip.orientations(land, scale)
        }

        /** The cameras GlobeLandClipTest sweeps: every 15° of yaw, every 5° of pitch to ±85°. */
        private val cameras: List<Pair<Float, Float>> = buildList {
            for (yawDeg in 0 until 360 step 15) {
                for (pitchDeg in -85..85 step 5) {
                    add(Math.toRadians(yawDeg.toDouble()).toFloat() to Math.toRadians(pitchDeg.toDouble()).toFloat())
                }
            }
        }
    }

    /** The projection as it was written before the turn was split out: the longitude plus the yaw. */
    private fun classic(latRaw: Int, lonRaw: Int, yaw: Float, pitch: Float): GlobeLandClip.View {
        val lat = Math.toRadians(latRaw.toDouble() / scale)
        val lon = Math.toRadians(lonRaw.toDouble() / scale) + yaw
        val cosLat = cos(lat)
        val x = cosLat * sin(lon)
        val y = sin(lat)
        val z = cosLat * cos(lon)
        val cosP = cos(pitch.toDouble())
        val sinP = sin(pitch.toDouble())
        return GlobeLandClip.View(x.toFloat(), (y * cosP - z * sinP).toFloat(), (y * sinP + z * cosP).toFloat())
    }

    @Test
    fun `a whole dataset turned at once lands exactly where project puts each point`() {
        val sphere = GlobeLandClip.spherePoints(lines, scale)
        val view = GlobeLandClip.ViewPoints()
        for ((yaw, pitch) in cameras.filterIndexed { i, _ -> i % 7 == 0 }) {
            GlobeLandClip.turn(sphere, GlobeLandClip.Turn(yaw, pitch), view)
            for (r in lines.indices) {
                val line = lines[r]
                for (k in 0 until line.size / 2) {
                    val one = GlobeLandClip.project(line[k * 2 + 1], line[k * 2], scale, yaw, pitch)
                    val i = sphere.start(r) + k
                    // Exact, not close: the dots, the outlines and the land
                    // clipper must agree on where the horizon is to the bit.
                    assertEquals(one.x, view.x[i], 0f)
                    assertEquals(one.y, view.y[i], 0f)
                    assertEquals(one.z, view.z[i], 0f)
                }
            }
        }
    }

    @Test
    fun `the turn is the same projection as adding the yaw to the longitude`() {
        var worst = 0f
        for ((yaw, pitch) in cameras) {
            val turn = GlobeLandClip.Turn(yaw, pitch)
            for (line in lines) {
                var k = 0
                while (k + 1 < line.size) {
                    val now = GlobeLandClip.project(line[k + 1], line[k], scale, turn)
                    val was = classic(line[k + 1], line[k], yaw, pitch)
                    worst = maxOf(worst, abs(now.x - was.x), abs(now.y - was.y), abs(now.z - was.z))
                    k += 2
                }
            }
        }
        // Rounding in the last place of a double at most, which a float
        // almost never even sees: a millionth of the radius is a fiftieth of a
        // pixel at the closest zoom.
        assertTrue("the turned projection drifted by $worst", worst < 1e-6f)
    }

    @Test
    fun `clipping turned points in place gives what clipRing gives`() {
        val sphere = GlobeLandClip.spherePoints(land, scale)
        val view = GlobeLandClip.ViewPoints()
        // One scratch for every ring and every camera, as the screen keeps it:
        // nothing a ring leaves behind may reach the next one.
        val scratch = GlobeLandClip.ClipScratch()
        var crossing = 0
        for ((yaw, pitch) in cameras.filterIndexed { i, _ -> i % 3 == 0 }) {
            GlobeLandClip.turn(sphere, GlobeLandClip.Turn(yaw, pitch), view)
            for (r in land.indices) {
                val expected = GlobeLandClip.clipRing(land[r], orientations[r], yaw, pitch, scale)
                val got = ArrayList<FloatArray>()
                GlobeLandClip.clipTurned(view, sphere.start(r), sphere.size(r), orientations[r], scratch) { xy, size ->
                    got.add(xy.copyOf(size))
                }
                assertEquals("ring $r at yaw=$yaw pitch=$pitch", expected.size, got.size)
                for (p in expected.indices) {
                    assertTrue("ring $r polygon $p at yaw=$yaw pitch=$pitch", expected[p].contentEquals(got[p]))
                }
                if (got.size > 1 || (got.size == 1 && got[0].size != land[r].size)) crossing++
            }
        }
        assertTrue("no ring crossed the limb: the sweep is not exercising the clip", crossing > 100)
    }

    @Test
    fun `the rim walked between runs stays on the rim`() {
        // The closing arcs are stepped round by a fixed rotation rather than a
        // sine and cosine per point, so check the rotation never wanders off
        // the circle it is meant to trace.
        val yaw = Math.toRadians(15.0).toFloat()
        val pitch = Math.toRadians(-65.0).toFloat()
        var rimPoints = 0
        for (r in land.indices) {
            val ring = land[r]
            val visible = (0 until ring.size / 2).map {
                GlobeLandClip.project(ring[it * 2 + 1], ring[it * 2], scale, yaw, pitch)
            }.filter { it.z >= 0f }.map { it.x to it.y }.toSet()
            for (poly in GlobeLandClip.clipRing(ring, orientations[r], yaw, pitch, scale)) {
                for (i in 0 until poly.size / 2) {
                    val x = poly[i * 2]
                    val y = poly[i * 2 + 1]
                    if ((x to y) in visible) continue
                    rimPoints++
                    assertEquals("ring $r point $i is off the rim", 1f, hypot(x, y), 1e-5f)
                }
            }
        }
        assertTrue("no rim was walked at this camera", rimPoints > GlobeLandClip.ARC_STEPS)
    }
}
