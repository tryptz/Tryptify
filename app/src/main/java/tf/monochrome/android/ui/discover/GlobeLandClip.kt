package tf.monochrome.android.ui.discover

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The geometry behind the globe's filled continents, with no Compose in it.
 *
 * Split out from the screen because the interesting part is a clipping problem
 * that is easy to get subtly, intermittently wrong and impossible to eyeball:
 * the failure it exists to prevent showed up as the whole globe inverting —
 * ocean filled, continents punched out of it — for a few frames at a time while
 * the Earth was being turned. Everything here is pure maths over plain arrays so
 * a unit test can sweep the camera over the real asset and check that the sea
 * stays sea.
 *
 * Coordinates are the unit view sphere: x right, y up, z toward the viewer, so
 * z >= 0 is the hemisphere facing us. The caller scales to pixels.
 */
internal object GlobeLandClip {

    /** How many segments one closing arc along the limb is drawn with. */
    const val ARC_STEPS = 96

    /** A point on the unit view sphere. */
    data class View(val x: Float, val y: Float, val z: Float)

    /**
     * Latitude and longitude in hundredths of a degree to the unit view sphere.
     *
     * Orthographic: rotate the unit sphere by the camera and keep the depth for
     * culling. No perspective divide, because from orbit there effectively isn't
     * one and adding it would make the centre of the globe swell.
     */
    fun project(latRaw: Int, lonRaw: Int, scale: Int, yaw: Float, pitch: Float): View {
        val lat = Math.toRadians(latRaw.toDouble() / scale)
        val lon = Math.toRadians(lonRaw.toDouble() / scale) + yaw

        val cosLat = cos(lat)
        val x = cosLat * sin(lon)
        val y = sin(lat)
        val z = cosLat * cos(lon)

        // Tilt about the horizontal axis, so dragging up and down walks the
        // poles toward the viewer instead of rolling the image.
        val cosP = cos(pitch.toDouble())
        val sinP = sin(pitch.toDouble())
        return View(
            x = x.toFloat(),
            y = (y * cosP - z * sinP).toFloat(),
            z = (y * sinP + z * cosP).toFloat(),
        )
    }

    /**
     * Which way each ring is wound, seen from outside the sphere.
     *
     * A property of the rings and not of the camera, so it is computed once per
     * dataset and kept. [clipRing] needs it to know which side of a ring the
     * land is on when closing a shape along the limb, and that is the one
     * question the projection cannot answer for itself.
     *
     * Chamberlain–Duquette: the sign of the spherical signed area. Longitude
     * steps are wrapped into ±180° so a ring crossing the antimeridian doesn't
     * count a lap it never took.
     */
    fun orientations(rings: List<List<Int>>, scale: Int): FloatArray =
        FloatArray(rings.size) { r ->
            val ring = rings[r]
            val count = ring.size / 2
            var sum = 0.0
            for (i in 0 until count) {
                val j = if (i + 1 == count) 0 else i + 1
                val lon1 = Math.toRadians(ring[i * 2].toDouble() / scale)
                val lat1 = Math.toRadians(ring[i * 2 + 1].toDouble() / scale)
                val lon2 = Math.toRadians(ring[j * 2].toDouble() / scale)
                val lat2 = Math.toRadians(ring[j * 2 + 1].toDouble() / scale)
                var d = lon2 - lon1
                while (d > Math.PI) d -= 2 * Math.PI
                while (d < -Math.PI) d += 2 * Math.PI
                sum += d * (2 + sin(lat1) + sin(lat2))
            }
            if (sum > 0) 1f else -1f
        }

    /**
     * Clip one land ring to the hemisphere facing the viewer.
     *
     * Returns the closed polygons that remain, each a flat `[x, y, x, y, …]` run
     * in unit view coordinates. Empty when the ring is entirely round the back.
     *
     * The shortcut this replaced pushed every far-side vertex radially onto the
     * rim and emitted them in the ring's own order, on the theory that the
     * collapsed stretch traces the silhouette it was hiding behind. It does —
     * but a ring with a large hidden portion then drags a rim trace most of the
     * way round the disc, and Afro-Eurasia seen from the far south drags it the
     * *whole* way. An extra loop around the disc is an extra crossing for every
     * point inside it, so under an even-odd fill the entire globe inverted.
     *
     * Instead the ring is cut into the runs that are actually visible, each
     * bracketed by its exact limb crossings, and the runs are rejoined by
     * walking the rim from one run's exit to the next run's entry. Which way
     * round the rim is the whole question, and it is answered by the ring's own
     * winding rather than guessed from the projection: the interior lies to the
     * opposite hand from the way the boundary is traced, so the walk goes
     * against the winding. Walking it the other way fills the sea instead of the
     * land — which is the bug, reintroduced.
     */
    fun clipRing(
        ring: List<Int>,
        orientation: Float,
        yaw: Float,
        pitch: Float,
        scale: Int,
        arcSteps: Int = ARC_STEPS,
    ): List<FloatArray> {
        val count = ring.size / 2
        if (count < 3) return emptyList()

        val ux = FloatArray(count)
        val uy = FloatArray(count)
        val uz = FloatArray(count)
        var anyVisible = false
        var anyHidden = false
        for (i in 0 until count) {
            val p = project(ring[i * 2 + 1], ring[i * 2], scale, yaw, pitch)
            ux[i] = p.x
            uy[i] = p.y
            uz[i] = p.z
            if (p.z >= 0f) anyVisible = true else anyHidden = true
        }
        if (!anyVisible) return emptyList()

        // Wholly on this side: no limb to reason about.
        if (!anyHidden) {
            val whole = FloatArray(count * 2)
            for (i in 0 until count) {
                whole[i * 2] = ux[i]
                whole[i * 2 + 1] = uy[i]
            }
            return listOf(whole)
        }

        // Cut into visible runs, each remembering the rim angle it entered at
        // and the one it left at — all the rejoin needs.
        val entries = ArrayList<Float>()
        val exits = ArrayList<Float>()
        val runs = ArrayList<ArrayList<Float>>()
        var current: ArrayList<Float>? = null
        var currentEntry = 0f

        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            val visI = uz[i] >= 0f
            val visJ = uz[j] >= 0f
            if (visI) {
                val run = current ?: ArrayList<Float>().also { current = it }
                run.add(ux[i])
                run.add(uy[i])
            }
            if (visI == visJ) continue

            // Where the edge crosses the limb. Interpolating the view-space
            // vector puts z exactly at zero, so the point is on the rim circle
            // once its length is normalised away.
            val t = uz[i] / (uz[i] - uz[j])
            val hx = ux[i] + (ux[j] - ux[i]) * t
            val hy = uy[i] + (uy[j] - uy[i]) * t
            val len = hypot(hx, hy)
            // A crossing on the view axis has no direction to be pushed out
            // along — it is the dead centre of the far side, so it cannot be a
            // real limb crossing and dropping it loses nothing.
            if (len <= 1e-5f) continue
            val angle = atan2(hy / len, hx / len)

            if (visI) {
                val run = current ?: ArrayList<Float>().also { current = it }
                run.add(cos(angle))
                run.add(sin(angle))
                runs.add(run)
                entries.add(currentEntry)
                exits.add(angle)
                current = null
            } else {
                current = ArrayList<Float>().apply {
                    add(cos(angle))
                    add(sin(angle))
                }
                currentEntry = angle
            }
        }

        // A run still open at the end began before the ring's first vertex and
        // continues into the run that starts it — one run, split by wherever the
        // vertex list happens to begin.
        val trailing = current
        if (trailing != null && runs.isNotEmpty()) {
            trailing.addAll(runs[0])
            runs[0] = trailing
            entries[0] = currentEntry
        }
        if (runs.isEmpty()) return emptyList()

        val direction = -orientation
        val used = BooleanArray(runs.size)
        val out = ArrayList<FloatArray>()

        for (seed in runs.indices) {
            if (used[seed]) continue
            val poly = ArrayList<Float>()
            var k = seed
            while (!used[k]) {
                used[k] = true
                poly.addAll(runs[k])
                val exit = exits[k]
                var next = -1
                var shortest = Float.MAX_VALUE
                for (m in runs.indices) {
                    if (used[m] && m != seed) continue
                    val gap = positiveAngle((entries[m] - exit) * direction)
                    if (gap < shortest) {
                        shortest = gap
                        next = m
                    }
                }
                if (next < 0) break
                for (q in 1..arcSteps) {
                    val a = exit + direction * shortest * q / arcSteps
                    poly.add(cos(a))
                    poly.add(sin(a))
                }
                if (next == seed) break
                k = next
            }
            if (poly.size >= 6) out.add(poly.toFloatArray())
        }
        return out
    }

    /** An angle brought into `[0, 2π)`, so "how far round" is never negative. */
    fun positiveAngle(radians: Float): Float {
        val tau = (2 * Math.PI).toFloat()
        var a = radians % tau
        if (a < 0f) a += tau
        return a
    }
}
