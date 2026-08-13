package tf.monochrome.android.ui.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The globe's land fill, checked against the world it is drawing.
 *
 * This exists because of a bug that could not be caught by looking at one frame.
 * Far-side vertices used to be pushed radially onto the rim and emitted in the
 * ring's own order; a ring with a large hidden portion then dragged a rim trace
 * most of the way round the disc, and Afro-Eurasia seen from the far south
 * dragged it the whole way. One extra loop around the disc is one extra crossing
 * for every point inside it, so under an even-odd fill the globe inverted — sea
 * filled, continents punched out of it — and turning the Earth flicked in and
 * out of that state several times a second.
 *
 * A single camera proves nothing here, because most cameras were fine. So this
 * sweeps the camera over the whole sphere and asserts the only thing that must
 * never stop being true: deep ocean is not land, and the middle of a continent
 * is.
 */
class GlobeLandClipTest {

    companion object {
        private lateinit var land: List<List<Int>>
        private var scale: Int = 100
        private lateinit var orientations: FloatArray

        @BeforeClass
        @JvmStatic
        fun loadAsset() {
            val text = File("src/main/assets/world_radio.json").readText()
            val root = Json.parseToJsonElement(text).jsonObject
            scale = root["scale"]?.jsonPrimitive?.content?.toInt() ?: 100
            land = root.getValue("land").jsonArray.map { ring ->
                ring.jsonArray.map { it.jsonPrimitive.content.toInt() }
            }
            orientations = GlobeLandClip.orientations(land, scale)
        }

        /** Deep water, far enough from any coast that no rounding reaches it. */
        private val OCEAN = mapOf(
            "South Pacific gyre" to (-35.0 to -125.0),
            "North Atlantic" to (35.0 to -40.0),
            "Indian Ocean" to (-20.0 to 80.0),
            "North Pacific" to (30.0 to -170.0),
        )

        /** Well inside a continent, for the other half of the same question. */
        private val LAND_POINTS = mapOf(
            "Amazon" to (-5.0 to -60.0),
            "Sahara" to (22.0 to 10.0),
            "Siberia" to (62.0 to 100.0),
            "Antarctica" to (-80.0 to 0.0),
        )
    }

    /** Even-odd, the same rule the Path is filled with. */
    private fun filled(polys: List<FloatArray>, px: Float, py: Float): Boolean {
        var crossings = 0
        for (poly in polys) {
            val n = poly.size / 2
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val x1 = poly[i * 2]
                val y1 = poly[i * 2 + 1]
                val x2 = poly[j * 2]
                val y2 = poly[j * 2 + 1]
                if ((y1 > py) != (y2 > py)) {
                    val xInt = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
                    if (xInt > px) crossings++
                }
            }
        }
        return crossings % 2 == 1
    }

    private fun world(yaw: Float, pitch: Float): List<FloatArray> =
        land.indices.flatMap { i ->
            GlobeLandClip.clipRing(land[i], orientations[i], yaw, pitch, scale)
        }

    /**
     * The sweep. Every camera the user can reach by dragging, at a resolution
     * fine enough to land inside the bands the old code broke in — those were
     * tens of degrees wide, not fractions of one.
     */
    @Test
    fun `the sea is never filled and the continents always are`() {
        val failures = mutableListOf<String>()
        var probes = 0

        var yawDeg = 0
        while (yawDeg < 360) {
            var pitchDeg = -85
            while (pitchDeg <= 85) {
                val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
                val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
                val polys = world(yaw, pitch)

                for ((name, at) in OCEAN + LAND_POINTS) {
                    val (lat, lon) = at
                    val v = GlobeLandClip.project(
                        (lat * scale).toInt(),
                        (lon * scale).toInt(),
                        scale,
                        yaw,
                        pitch,
                    )
                    // Only meaningful well inside the silhouette. A probe
                    // grazing the limb sits *on* the clipped polygon's own
                    // boundary — the limb is that boundary — and which side of
                    // an edge a point exactly on it falls is not what this is
                    // testing. Half a percent of the radius is a wide enough
                    // berth to be unambiguous and far narrower than the tens of
                    // degrees the inversion covered.
                    if (v.z <= 0f) continue
                    if (hypot(v.x, v.y) > 0.995f) continue
                    probes++
                    val isLand = filled(polys, v.x, v.y)
                    val shouldBeLand = name in LAND_POINTS
                    if (isLand != shouldBeLand) {
                        failures += "$name at yaw=$yawDeg pitch=$pitchDeg " +
                            "(filled=$isLand, expected=$shouldBeLand)"
                    }
                }
                pitchDeg += 5
            }
            yawDeg += 15
        }

        assertTrue("no probes ran — the asset failed to load?", probes > 2000)
        assertTrue(
            "land fill inverted at ${failures.size} of $probes probes; " +
                "first few: ${failures.take(6)}",
            failures.isEmpty(),
        )
    }

    /**
     * The direction of the closing arc is the whole fix, and it is one sign.
     * Flipping it is a one-character edit that compiles, still draws continents,
     * and is wrong on exactly the cameras nobody screenshots — so this pins the
     * consequence rather than the sign itself.
     */
    @Test
    fun `walking the limb the wrong way would fill the sea`() {
        // Afro-Eurasia from the far south: the case that produced a rim trace
        // all the way round the disc.
        val yaw = Math.toRadians(15.0).toFloat()
        val pitch = Math.toRadians(-65.0).toFloat()

        val correct = world(yaw, pitch)
        val reversed = land.indices.flatMap { i ->
            GlobeLandClip.clipRing(land[i], -orientations[i], yaw, pitch, scale)
        }

        val v = GlobeLandClip.project(
            (-35.0 * scale).toInt(),
            (-125.0 * scale).toInt(),
            scale,
            yaw,
            pitch,
        )
        assertTrue("probe should be on the near side", v.z > 0f)
        assertTrue("the South Pacific is not land", !filled(correct, v.x, v.y))
        assertTrue(
            "reversing the arc should break it — if this passes, the test no " +
                "longer exercises the bug",
            filled(reversed, v.x, v.y),
        )
    }

    /** Nothing on the far side should contribute geometry at all. */
    @Test
    fun `a ring entirely round the back draws nothing`() {
        val yaw = 0f
        // Looking straight at the north pole; Antarctica is wholly behind.
        val pitch = Math.toRadians(90.0).toFloat()
        val antarctic = land.indices.filter { i ->
            val ring = land[i]
            (0 until ring.size / 2).all { ring[it * 2 + 1] / scale < -60 }
        }
        assertTrue("no antarctic ring found in the asset", antarctic.isNotEmpty())
        for (i in antarctic) {
            val polys = GlobeLandClip.clipRing(land[i], orientations[i], yaw, pitch, scale)
            assertTrue("a hidden ring produced geometry", polys.isEmpty())
        }
    }
}
