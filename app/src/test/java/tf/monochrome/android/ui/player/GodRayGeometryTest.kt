package tf.monochrome.android.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricsFxSettings
import kotlin.math.pow

class GodRayGeometryTest {

    private val center = Offset(500f, 300f)
    private val focal = 400f

    @Test
    fun `a light straight behind the line lands on it, whatever its azimuth`() {
        listOf(0f, 90f, 217f, 360f).forEach { az ->
            val p = GodRayGeometry.lightPoint(center, az, 90f, focal)
            assertEquals("az $az", center.x, p.x, 1e-3f)
            assertEquals("az $az", center.y, p.y, 1e-3f)
        }
    }

    @Test
    fun `at 45 degrees the light sits one focal length out along its azimuth`() {
        // cot(45°) = 1. Azimuth runs counter-clockwise from the right with 90°
        // straight up, and screen y points down.
        val above = GodRayGeometry.lightPoint(center, 90f, 45f, focal)
        assertEquals(center.x, above.x, 1e-2f)
        assertEquals(center.y - focal, above.y, 1e-2f)

        val right = GodRayGeometry.lightPoint(center, 0f, 45f, focal)
        assertEquals(center.x + focal, right.x, 1e-2f)
        assertEquals(center.y, right.y, 1e-2f)

        val below = GodRayGeometry.lightPoint(center, 270f, 45f, focal)
        assertEquals(center.y + focal, below.y, 1e-2f)
    }

    @Test
    fun `lowering the light pushes it outward, and a flat light stays finite`() {
        var last = 0f
        listOf(80f, 60f, 40f, 20f).forEach { el ->
            val d = (GodRayGeometry.lightPoint(center, 0f, el, focal) - center).getDistance()
            assertTrue("el $el should sit further out than the one above it", d > last)
            last = d
        }
        val flat = GodRayGeometry.lightPoint(center, 0f, 0f, focal)
        assertEquals(center.x + focal * GodRayGeometry.COT_MAX, flat.x, 1e-2f)
        assertTrue(flat.y.isFinite())
    }

    @Test
    fun `the article's decay is quoted at 50 samples and holds over the whole march`() {
        assertEquals(0.92f, GodRayGeometry.perSampleDecay(0.92f, 50), 1e-6f)
        // The fade over a full march is the same at every quality setting, so
        // the quality slider changes how clean the shafts are, not how long.
        val whole = 0.92f.pow(50)
        listOf(16, 24, 32).forEach { n ->
            val d = GodRayGeometry.perSampleDecay(0.92f, n)
            assertEquals("$n samples", whole, d.pow(n), 1e-4f)
        }
        assertEquals(1f, GodRayGeometry.perSampleDecay(1f, 24), 0f)
    }

    @Test
    fun `the sample weight turns the decayed sum into an average times the gain`() {
        listOf(16, 24, 32).forEach { n ->
            val d = GodRayGeometry.perSampleDecay(0.95f, n)
            val w = GodRayGeometry.sampleWeight(2.5f, d, n)
            // A march through solid light (every sample 1) gives exactly the gain.
            var sum = 0f
            var k = 1f
            repeat(n) { sum += k; k *= d }
            assertEquals("$n samples", 2.5f, sum * w, 1e-4f)
        }
    }

    @Test
    fun `quality maps to the shader's sample counts`() {
        assertEquals(16, GodRayGeometry.samplesFor(1))
        assertEquals(24, GodRayGeometry.samplesFor(2))
        assertEquals(32, GodRayGeometry.samplesFor(3))
        // The shader's loop is 32 long; nothing may ask for more.
        assertEquals(32, GodRayGeometry.samplesFor(9))
        assertEquals(16, GodRayGeometry.samplesFor(-1))
    }

    @Test
    fun `the dust closes on itself round the light`() {
        val cells = GodRayGeometry.stripeCells(radius = 300f, stripePx = 21f)
        assertEquals(90, cells)
        assertEquals(8, GodRayGeometry.stripeCells(radius = 1f, stripePx = 21f))
    }

    @Test
    fun `the light centres on the sung word, or across the line`() {
        assertEquals(Offset(540f, 1000f), GodRayGeometry.lightCenter(null, 1080f, 2000f))
        val line = Rect(-GodRayGeometry.UNBOUNDED, 400f, GodRayGeometry.UNBOUNDED, 480f)
        assertEquals(Offset(540f, 440f), GodRayGeometry.lightCenter(line, 1080f, 2000f))
        val word = Rect(100f, 400f, 300f, 480f)
        assertEquals(Offset(200f, 440f), GodRayGeometry.lightCenter(word, 1080f, 2000f))
    }

    @Test
    fun `orbit and sway move the light without leaving the dome`() {
        val still = LyricsFxSettings(godRayAzimuthDeg = 90f, godRayElevationDeg = 60f)
        assertEquals(90f to 60f, GodRayGeometry.animatedAngles(still, 123f))

        val orbit = still.copy(godRaySpinDps = -45f)
        val (az, _) = GodRayGeometry.animatedAngles(orbit, 10f)
        assertEquals(((90f - 450f) % 360f + 360f) % 360f, az, 1e-3f)

        val sway = still.copy(godRayElevationDeg = 85f, godRaySway = 1f)
        for (i in 0..400) {
            val (a, e) = GodRayGeometry.animatedAngles(sway, i * 0.37f)
            assertTrue(a in 0f..360f)
            assertTrue("elevation $e left the dome", e in 0f..90f)
        }
    }

    @Test
    fun `the jitter frame cycles through 64 and never goes negative`() {
        assertEquals(0, GodRayGeometry.jitterFrame(0f))
        assertEquals(1, GodRayGeometry.jitterFrame(1f / 60f + 1e-4f))
        for (i in 0..1000) {
            assertTrue(GodRayGeometry.jitterFrame(i * 0.0173f) in 0..63)
        }
    }

    @Test
    fun `nothing moves the rays unless something asks to`() {
        val still = LyricsFxSettings(godRayShimmer = 0f, godRaySpinDps = 0f, godRaySway = 0f)
        assertEquals(false, GodRayGeometry.isMoving(still))
        assertEquals(true, GodRayGeometry.isMoving(still.copy(godRaySpinDps = -3f)))
        assertEquals(true, GodRayGeometry.isMoving(still.copy(godRayShimmer = 0.1f)))
        assertEquals(true, GodRayGeometry.isMoving(still.copy(godRaySway = 0.1f)))
    }
}
