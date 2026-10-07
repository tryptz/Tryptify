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
        listOf(16, 24, 32, 50).forEach { n ->
            val d = GodRayGeometry.perSampleDecay(0.95f, n)
            val w = GodRayGeometry.sampleWeight(2.5f, d, n)
            // Solid light under every tap, the centre one included, gives
            // exactly the gain.
            var sum = GodRayGeometry.CENTER_TAP
            var k = 1f
            repeat(n) { sum += k; k *= d }
            assertEquals("$n samples", 2.5f, sum * w, 1e-4f)
        }
    }

    @Test
    fun `at the Shadertoy's settings every tap weighs what it does there`() {
        // crepuscular_rays(): color = tex * 0.4, then 50 samples of
        // tex * 0.4 * 0.58767 * 0.92^i. The app scales a weighted average, so
        // it matches when the gain is the original's whole tap weight.
        val n = 50
        var decaySum = 0.0
        var k = 1.0
        repeat(n) { decaySum += k; k *= 0.92 }
        val total = (0.4 * (1 + 0.58767 * decaySum)).toFloat()

        val d = GodRayGeometry.perSampleDecay(0.92f, n)
        assertEquals(0.92f, d, 1e-6f)
        val w = GodRayGeometry.sampleWeight(total, d, n)
        assertEquals("a march sample", 0.4f * 0.58767f, w, 1e-5f)
        assertEquals("the centre tap", 0.4f, w * GodRayGeometry.CENTER_TAP, 1e-5f)

        // The Crepuscular preset is those numbers, to within 1% of brightness.
        val crepuscular = LyricsFxSettings.PRESETS.toMap().getValue("Crepuscular")
        assertEquals(0.92f, crepuscular.godRayDecay, 0f)
        assertEquals(1f, crepuscular.godRayDensity, 0f)
        assertEquals(total, GodRayGeometry.LETTERS_GAIN * crepuscular.godRayExposure, total * 0.01f)
        assertEquals(50, GodRayGeometry.samplesFor(4))
    }

    @Test
    fun `quality maps to the shader's sample counts`() {
        assertEquals(16, GodRayGeometry.samplesFor(1))
        assertEquals(24, GodRayGeometry.samplesFor(2))
        assertEquals(32, GodRayGeometry.samplesFor(3))
        assertEquals(50, GodRayGeometry.samplesFor(4))
        // The shader's loop is 50 long; nothing may ask for more.
        assertEquals(50, GodRayGeometry.samplesFor(9))
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
    fun `the orbit turns the azimuth and wraps it`() {
        val still = LyricsFxSettings(godRayAzimuthDeg = 90f, godRayElevationDeg = 60f)
        assertEquals(90f to 60f, GodRayGeometry.animatedAngles(still, 123f))

        val orbit = still.copy(godRaySpinDps = -45f)
        for (i in 0..400) {
            val (a, e) = GodRayGeometry.animatedAngles(orbit, i * 0.37f)
            assertTrue("azimuth $a", a in 0f..360f)
            assertEquals(60f, e, 0f)
        }
        assertEquals(0f, GodRayGeometry.animatedAngles(orbit, 10f).first, 1e-3f)
    }

    @Test
    fun `the sway is the Shadertoy's wandering light`() {
        // pos = (sin(t), sin(t * 0.913)) * 0.5, in units of the screen's height,
        // with the Shadertoy's y pointing up.
        val h = 2000f
        listOf(0f, 0.7f, 2.1f, 5.3f).forEach { t ->
            val o = GodRayGeometry.swayOffset(1f, t, h)
            assertEquals(kotlin.math.sin(t) * 0.5f * h, o.x, 1e-2f)
            assertEquals(-kotlin.math.sin(t * 0.913f) * 0.5f * h, o.y, 1e-2f)
        }
        assertEquals(Offset.Zero, GodRayGeometry.swayOffset(0f, 3f, h))
        assertEquals(GodRayGeometry.swayOffset(1f, 1.3f, h) * 0.25f, GodRayGeometry.swayOffset(0.25f, 1.3f, h))
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
