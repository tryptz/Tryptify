package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.GalaxyVisualSettings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The GPU work the galaxy skips is work that changed nothing on screen: the
 * god rays' samples out of a light's reach, a light pass no pixel can see, and
 * the black hole's light bounded from every camera.
 */
class GalaxyGpuPassesTest {

    // ── The god rays' sample window ─────────────────────────────────────

    @Test
    fun `a pixel leaves out exactly the samples beyond the light's reach`() {
        val rnd = Random(7)
        repeat(20_000) {
            val dist = rnd.nextFloat() * 3000f
            val reach = 1f + rnd.nextFloat() * 800f
            val jitter = rnd.nextFloat()
            val first = GalaxyLight.firstSample(dist, reach, jitter)
            val step = (dist * GalaxyLight.DENSITY / GalaxyLight.SAMPLES).coerceAtLeast(0.0001f)
            for (i in 0 until GalaxyLight.SAMPLES) {
                // How far sample i lies from the light, as the shader places it.
                val fromLight = dist - (i + 1 - jitter) * step
                if (i < first) {
                    assertTrue("sample $i at $fromLight left out within $reach", fromLight > reach - 1e-2f)
                } else {
                    assertTrue("sample $i at $fromLight taken beyond $reach", fromLight <= reach + 1e-2f)
                }
            }
        }
    }

    @Test
    fun `the rays with the window are the full march to under half a colour step`() {
        // The strongest the listener can make them: ray strength and shade at
        // the top of their ranges, the bass at its loudest.
        val exposure = GalaxyVisualSettings.RAY_RANGE.endInclusive * (1f + 0.45f * 2f)
        val shade = GalaxyVisualSettings.SHADE_RANGE.endInclusive
        val w = 540
        val h = 1200
        val rnd = Random(11)
        var worst = 0f
        for ((lx, ly, glowR) in listOf(
            Triple(270f, 600f, 82f),
            Triple(60f, 300f, 82f),
            Triple(270f, 600f, 340f),
            Triple(-200f, 1300f, 120f),
        )) {
            val pass = LightPass(lx, ly, glowR, emitterR = glowR * 0.2f, rnd = rnd, width = w, height = h)
            val reach = GalaxyLight.SHADE_REACH * glowR + 2f
            var y = 3
            while (y < h) {
                var x = 3
                while (x < w) {
                    val jitter = rnd.nextFloat()
                    val full = pass.march(x + 0.5f, y + 0.5f, Float.POSITIVE_INFINITY, jitter, exposure, shade)
                    val windowed = pass.march(x + 0.5f, y + 0.5f, reach, jitter, exposure, shade)
                    for (c in 0..3) worst = max(worst, abs(full[c] - windowed[c]))
                    x += 9
                }
                y += 9
            }
        }
        assertTrue("worst difference $worst of 1, over half of 1/255", worst < 0.5f / 255f)
    }

    @Test
    fun `shade past its reach is under half a step even at the strongest setting`() {
        // Everything left out is weighted by at most exp(-2 g²) at g = SHADE_REACH,
        // over at most all the weights, which the shade's uniform divides out.
        val leftOver = GalaxyLight.SHADE * GalaxyVisualSettings.SHADE_RANGE.endInclusive *
            exp(-2f * GalaxyLight.SHADE_REACH * GalaxyLight.SHADE_REACH)
        assertTrue("left over $leftOver", leftOver < 0.5f / 255f)
    }

    @Test
    fun `a light pass is skipped only when no pixel of the view can see it`() {
        val rnd = Random(3)
        val w = 540f
        val h = 1200f
        var skipped = 0
        repeat(4000) {
            val spot = LightSpot().apply {
                x = -4000f + rnd.nextFloat() * 8540f
                y = -4000f + rnd.nextFloat() * 9200f
                reach = 5f + rnd.nextFloat() * 300f
            }
            if (GalaxyLight.reachesView(spot, w, h)) return@repeat
            skipped++
            var y = 0f
            while (y <= h) {
                var x = 0f
                while (x <= w) {
                    val dist = hypot(x - spot.x, y - spot.y)
                    for (j in listOf(0f, 0.5f, 0.999f)) {
                        assertTrue(GalaxyLight.firstSample(dist, spot.reach, j) >= GalaxyLight.SAMPLES)
                    }
                    x += 30f
                }
                y += 30f
            }
        }
        assertTrue("some passes should have been skippable ($skipped)", skipped > 100)
        // A light on screen is always seen, and one with no known reach always runs.
        assertTrue(GalaxyLight.reachesView(LightSpot().apply { x = 270f; y = 600f; reach = 1f }, w, h))
        assertTrue(GalaxyLight.reachesView(LightSpot().apply { x = -1e6f; y = 0f }, w, h))
    }

    @Test
    fun `the report's estimate counts the reads the window leaves`() {
        val all = raySamplesPerPixel(50f, 100f, Float.POSITIVE_INFINITY, 100, 200)
        assertEquals(GalaxyLight.SAMPLES.toFloat(), all, 1e-3f)
        val none = raySamplesPerPixel(-5000f, 100f, 10f, 100, 200)
        assertEquals(0f, none, 1e-3f)
        val some = raySamplesPerPixel(50f, 100f, 15f, 100, 200)
        assertTrue("$some", some > 0f && some < GalaxyLight.SAMPLES)
    }

    @Test
    fun `the star rays' march, from the first sample with a break, is the windowed march exactly`() {
        // GalaxyStarRays loops from each light's first counted sample and
        // stops at the last; GalaxyLight loops over all and skips. Same sum.
        val rnd = Random(21)
        val w = 300
        val h = 500
        val pass = LightPass(150f, 250f, 40f, emitterR = 8f, rnd = rnd, width = w, height = h)
        val reach = GalaxyLight.SHADE_REACH * 40f + 2f
        var y = 2
        while (y < h) {
            var x = 2
            while (x < w) {
                val j = rnd.nextFloat()
                val a = pass.march(x + 0.5f, y + 0.5f, reach, j, 1f, 1f)
                val b = pass.marchFromFirst(x + 0.5f, y + 0.5f, reach, j, 1f, 1f)
                for (c in 0..3) assertEquals(a[c], b[c], 1e-6f)
                x += 7
            }
            y += 7
        }
    }

    @Test
    fun `the usual ray length is the usual falloff, and a longer one fades slower`() {
        assertEquals(GalaxyLight.DECAY, GalaxyLight.decayFor(1f), 1e-7f)
        assertTrue(GalaxyLight.decayFor(2f) > GalaxyLight.DECAY && GalaxyLight.decayFor(2f) < 1f)
        assertTrue(GalaxyLight.decayFor(0.5f) < GalaxyLight.DECAY)
        val shortest = GalaxyLight.decayFor(GalaxyVisualSettings.RAY_LENGTH_RANGE.start)
        assertTrue("a falloff of $shortest still has weights to share", shortest > 0f)
    }

    // ── The black hole's light ──────────────────────────────────────────

    @Test
    fun `the hole's reach holds its whole disk, from any camera`() {
        val rnd = Random(5)
        val p = FloatArray(3)
        val q = FloatArray(3)
        val a = FloatArray(3)
        val b = FloatArray(3)
        var bounded = 0
        repeat(5000) {
            val cam = GalaxyCamera().apply {
                yaw = rnd.nextFloat() * 6.283f
                pitch = GalaxyCamera.MIN_PITCH + rnd.nextFloat() * (GalaxyCamera.MAX_PITCH - GalaxyCamera.MIN_PITCH)
                distance = exp(rnd.nextFloat() * kotlin.math.ln(GalaxyCamera.MAX_DISTANCE))
                targetX = (rnd.nextFloat() - 0.5f) * 1200f
                targetZ = (rnd.nextFloat() - 0.5f) * 1200f
            }
            val f = cam.frame(1080f, 2400f, spin = rnd.nextFloat() * 6.283f)
            val extent = holeLightExtent(f, p, q)
            if (!extent.isFinite()) return@repeat
            bounded++
            assertTrue(f.project(0f, 0f, 0f, p, 0))
            val cx = p[0]; val cy = p[1]
            val r = GalaxyScene.DISK_OUTER
            // The disk as BlackHoleArt lays it: on the images of two
            // perpendicular radii, at whatever angle it has turned to.
            repeat(24) {
                val t = rnd.nextFloat() * 6.283f
                if (!f.project(cos(t) * r, 0f, sin(t) * r, a, 0)) return@repeat
                if (!f.project(-sin(t) * r, 0f, cos(t) * r, b, 0)) return@repeat
                val ax = a[0] - cx; val ay = a[1] - cy
                val bx = b[0] - cx; val by = b[1] - cy
                // The farthest the unit disk's image reaches: the larger singular value.
                val s = ax * ax + ay * ay + bx * bx + by * by
                val d = ax * bx + ay * by
                val diff = ax * ax + ay * ay - (bx * bx + by * by)
                val reachOf = sqrt((s + sqrt(diff * diff + 4f * d * d)) / 2f)
                assertTrue("disk reaches $reachOf past $extent", reachOf <= extent * 1.0001f)
            }
        }
        assertTrue("most cameras should bound it ($bounded)", bounded > 2500)
    }

    // ── The clocks ──────────────────────────────────────────────────────

    @Test
    fun `a stepped clock ticks at its rate and never runs ahead`() {
        var t = 0f
        val seen = HashSet<Float>()
        while (t < 2f) {
            val s = stepped(t, 20f)
            assertTrue(s <= t + 1e-6f && t - s < 1f / 20f + 1e-5f)
            seen += s
            t += 1f / 120f
        }
        assertEquals(40, seen.size)
    }
}

/**
 * A light pass as the galaxy draws one, in premultiplied colour: the light's
 * glow and its white-hot middle, with black grains and bodies scattered
 * everywhere — in its glow, and far outside it, where the window leaves them.
 * [march] is the god-ray shader, line for line.
 */
private class LightPass(
    val lx: Float,
    val ly: Float,
    val glowR: Float,
    val emitterR: Float,
    rnd: Random,
    width: Int,
    height: Int,
) {
    private val tint = floatArrayOf(1f, 0.85f, 0.6f)
    private val grains = FloatArray(160 * 3).also { g ->
        for (k in 0 until 160) {
            // Half near the light, half anywhere on (and off) the view.
            val near = k % 2 == 0
            g[k * 3] = if (near) lx + (rnd.nextFloat() - 0.5f) * glowR * 3f else rnd.nextFloat() * width * 1.4f - width * 0.2f
            g[k * 3 + 1] = if (near) ly + (rnd.nextFloat() - 0.5f) * glowR * 3f else rnd.nextFloat() * height * 1.4f - height * 0.2f
            g[k * 3 + 2] = if (k % 7 == 0) 4f + rnd.nextFloat() * 10f else 1.5f
        }
    }

    fun content(x: Float, y: Float, out: FloatArray) {
        val d = hypot(x - lx, y - ly)
        var a = 0f
        var r = 0f; var g = 0f; var b = 0f
        if (d < glowR) {
            val u = d / glowR
            val edge = exp(-3f)
            a = 0.32f * ((exp(-3f * u * u) - edge) / (1f - edge)).coerceIn(0f, 1f)
            r = tint[0] * a; g = tint[1] * a; b = tint[2] * a
        }
        if (d < emitterR) {
            val e = 1f - d / emitterR
            r = (r + e).coerceAtMost(1f); g = (g + e).coerceAtMost(1f); b = (b + e).coerceAtMost(1f)
            a = (a + e).coerceAtMost(1f)
        }
        for (k in 0 until grains.size / 3) {
            if (hypot(x - grains[k * 3], y - grains[k * 3 + 1]) > grains[k * 3 + 2]) continue
            // Black at 0.85, over what is there.
            r *= 0.15f; g *= 0.15f; b *= 0.15f
            a = 0.85f + a * 0.15f
        }
        out[0] = r; out[1] = g; out[2] = b; out[3] = a
    }

    private val c = FloatArray(4)

    /** The god-ray shader at pixel ([px], [py]) with samples counted within [reach] (infinite: all of them). */
    fun march(px: Float, py: Float, reach: Float, jitter: Float, exposure: Float, shadeShare: Float): FloatArray {
        val samples = GalaxyLight.SAMPLES
        val decay = GalaxyLight.DECAY
        val weights = (1f - decay.pow(samples)) / (1f - decay)
        val uExposure = GalaxyLight.EXPOSURE * exposure / weights
        val uShade = GalaxyLight.SHADE * shadeShare / weights
        val tx = lx - px; val ty = ly - py
        val dist = hypot(tx, ty)
        val dx = tx / max(dist, 0.001f); val dy = ty / max(dist, 0.001f)
        val stepLen = dist * GalaxyLight.DENSITY / samples
        val first = if (reach.isFinite()) {
            max(ceil((dist - reach) / max(stepLen, 0.0001f) - 1f + jitter), 0f)
        } else {
            0f
        }
        if (first >= samples) return floatArrayOf(0f, 0f, 0f, 0f)
        var lr = 0f; var lg = 0f; var lb = 0f
        var shade = 0f
        var w = decay.pow(first)
        for (i in 0 until 40) {
            val fi = i.toFloat()
            if (fi < samples && fi >= first) {
                val sx = px + dx * ((fi + 1f - jitter) * stepLen)
                val sy = py + dy * ((fi + 1f - jitter) * stepLen)
                content(sx, sy, c)
                lr += c[0] * w; lg += c[1] * w; lb += c[2] * w
                val block = (c[3] - max(c[0], max(c[1], c[2]))).coerceIn(0f, 1f)
                val g = hypot(sx - lx, sy - ly) / glowR
                shade += block * exp(-2f * g * g) * w
                w *= decay
            }
        }
        val cr = 1f - exp(-lr * uExposure); val cg = 1f - exp(-lg * uExposure); val cb = 1f - exp(-lb * uExposure)
        val a = max(cr, max(cg, cb))
        val sa = (shade * uShade).coerceIn(0f, 0.6f)
        return floatArrayOf(cr, cg, cb, a + sa * (1f - a))
    }

    /** GalaxyStarRays' loop for one light at full power: from the first counted sample, breaking at the last. */
    fun marchFromFirst(px: Float, py: Float, reach: Float, jitter: Float, exposure: Float, shadeShare: Float): FloatArray {
        val samples = GalaxyLight.SAMPLES
        val decay = GalaxyLight.DECAY
        val weights = (1f - decay.pow(samples)) / (1f - decay)
        val uExposure = GalaxyLight.EXPOSURE * exposure / weights
        val uShade = GalaxyLight.SHADE * shadeShare / weights
        val tx = lx - px; val ty = ly - py
        val dist = hypot(tx, ty)
        val stepLen = dist * GalaxyLight.DENSITY / samples
        val first = max(ceil((dist - reach) / max(stepLen, 0.0001f) - 1f + jitter), 0f)
        var lr = 0f; var lg = 0f; var lb = 0f
        var shade = 0f
        if (first < samples) {
            val dx = tx / max(dist, 0.001f); val dy = ty / max(dist, 0.001f)
            var w = decay.pow(first) * 1f
            for (k in 0 until 32) {
                val fi = first + k
                if (fi >= samples) break
                val sx = px + dx * ((fi + 1f - jitter) * stepLen)
                val sy = py + dy * ((fi + 1f - jitter) * stepLen)
                content(sx, sy, c)
                lr += c[0] * w; lg += c[1] * w; lb += c[2] * w
                val block = (c[3] - max(c[0], max(c[1], c[2]))).coerceIn(0f, 1f)
                val g = hypot(sx - lx, sy - ly) / glowR
                shade += block * exp(-2f * g * g) * w
                w *= decay
            }
        }
        val cr = 1f - exp(-lr * uExposure); val cg = 1f - exp(-lg * uExposure); val cb = 1f - exp(-lb * uExposure)
        val a = max(cr, max(cg, cb))
        val sa = (shade * uShade).coerceIn(0f, 0.6f)
        return floatArrayOf(cr, cg, cb, a + sa * (1f - a))
    }
}
