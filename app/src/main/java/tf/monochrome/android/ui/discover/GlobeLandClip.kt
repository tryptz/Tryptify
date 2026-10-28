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
     * The camera's rotation, as the four numbers every point needs: worked out
     * once a frame rather than once a point.
     *
     * The projection used to take the sine and cosine of each point's own
     * longitude plus the yaw, and of its latitude — six trig calls a point, for
     * every coastline, border, land and city vertex, about ninety-five thousand
     * a frame of a drag. A point's place on the unspun sphere never changes
     * ([SpherePoints]), so turning it is a handful of multiplications.
     */
    class Turn(yaw: Float, pitch: Float) {
        internal val cosYaw = cos(yaw.toDouble())
        internal val sinYaw = sin(yaw.toDouble())
        internal val cosPitch = cos(pitch.toDouble())
        internal val sinPitch = sin(pitch.toDouble())
    }

    /**
     * A dataset's points on the unit sphere before any camera turns it, in
     * doubles, as runs: one per coastline, border or land ring, or one run of
     * single points for the cities. Built once per dataset, which is where all
     * of the trig is.
     */
    class SpherePoints internal constructor(
        // Where each run starts, in points; one more entry than there are runs.
        private val starts: IntArray,
        // x, y, z for every point, back to back.
        internal val xyz: DoubleArray,
    ) {
        val runCount: Int get() = starts.size - 1
        val pointCount: Int get() = starts[starts.size - 1]
        fun start(run: Int): Int = starts[run]
        fun size(run: Int): Int = starts[run + 1] - starts[run]
    }

    /** The asset's flat `[lon, lat, lon, lat, …]` runs (coastline, borders, land) on the sphere. */
    fun spherePoints(runs: List<List<Int>>, scale: Int): SpherePoints {
        val starts = IntArray(runs.size + 1)
        for (r in runs.indices) starts[r + 1] = starts[r] + runs[r].size / 2
        val xyz = DoubleArray(starts[runs.size] * 3)
        for (r in runs.indices) {
            val run = runs[r]
            var at = starts[r] * 3
            for (i in 0 until run.size / 2) {
                unit(run[i * 2 + 1], run[i * 2], scale) { x, y, z ->
                    xyz[at] = x
                    xyz[at + 1] = y
                    xyz[at + 2] = z
                }
                at += 3
            }
        }
        return SpherePoints(starts, xyz)
    }

    /** [count] single points, each read through [latOf] and [lonOf] (the cities), as one run. */
    fun spherePoints(count: Int, scale: Int, latOf: (Int) -> Int, lonOf: (Int) -> Int): SpherePoints {
        val xyz = DoubleArray(count * 3)
        for (i in 0 until count) {
            unit(latOf(i), lonOf(i), scale) { x, y, z ->
                xyz[i * 3] = x
                xyz[i * 3 + 1] = y
                xyz[i * 3 + 2] = z
            }
        }
        return SpherePoints(intArrayOf(0, count), xyz)
    }

    /** View-space coordinates, reused from frame to frame so a turn allocates nothing. */
    class ViewPoints {
        var x = FloatArray(0)
            private set
        var y = FloatArray(0)
            private set
        var z = FloatArray(0)
            private set

        internal fun ensure(count: Int) {
            if (x.size >= count) return
            x = FloatArray(count)
            y = FloatArray(count)
            z = FloatArray(count)
        }
    }

    /** Every point of [points] turned by [turn], into [out] at the same indices. */
    fun turn(points: SpherePoints, turn: Turn, out: ViewPoints) {
        val n = points.pointCount
        out.ensure(n)
        val p = points.xyz
        val xs = out.x
        val ys = out.y
        val zs = out.z
        for (i in 0 until n) {
            turned(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], turn) { x, y, z ->
                xs[i] = x
                ys[i] = y
                zs[i] = z
            }
        }
    }

    /**
     * Latitude and longitude in hundredths of a degree to the unit view sphere.
     *
     * Orthographic: rotate the unit sphere by the camera and keep the depth for
     * culling. No perspective divide, because from orbit there effectively isn't
     * one and adding it would make the centre of the globe swell.
     *
     * The same arithmetic as [spherePoints] followed by [turn], to the bit, so a
     * single point projected here lands exactly where the batch puts it.
     */
    fun project(latRaw: Int, lonRaw: Int, scale: Int, yaw: Float, pitch: Float): View =
        project(latRaw, lonRaw, scale, Turn(yaw, pitch))

    fun project(latRaw: Int, lonRaw: Int, scale: Int, turn: Turn): View =
        unit(latRaw, lonRaw, scale) { ux, uy, uz ->
            turned(ux, uy, uz, turn) { x, y, z -> View(x, y, z) }
        }

    /** A point on the unspun sphere: x right, y up, z toward the viewer at yaw 0. */
    private inline fun <R> unit(latRaw: Int, lonRaw: Int, scale: Int, emit: (Double, Double, Double) -> R): R {
        val lat = Math.toRadians(latRaw.toDouble() / scale)
        val lon = Math.toRadians(lonRaw.toDouble() / scale)
        val cosLat = cos(lat)
        return emit(cosLat * sin(lon), sin(lat), cosLat * cos(lon))
    }

    /**
     * The camera's rotation of one point: yaw about the vertical axis — the
     * same as adding it to the longitude, which is what the projection used to
     * do — then a tilt about the horizontal axis, so dragging up and down walks
     * the poles toward the viewer instead of rolling the image.
     */
    private inline fun <R> turned(x: Double, y: Double, z: Double, t: Turn, emit: (Float, Float, Float) -> R): R {
        val xr = x * t.cosYaw + z * t.sinYaw
        val zr = z * t.cosYaw - x * t.sinYaw
        return emit(
            xr.toFloat(),
            (y * t.cosPitch - zr * t.sinPitch).toFloat(),
            (y * t.sinPitch + zr * t.cosPitch).toFloat(),
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
        val points = spherePoints(listOf(ring), scale)
        val view = ViewPoints().also { turn(points, Turn(yaw, pitch), it) }
        val out = ArrayList<FloatArray>()
        clipTurned(view, 0, count, orientation, ClipScratch(), arcSteps) { xy, size -> out.add(xy.copyOf(size)) }
        return out
    }

    /** Takes each clipped polygon as a flat `[x, y, …]` run: the first [size] floats of `xy`, valid only during the call. */
    fun interface PolygonSink {
        fun polygon(xy: FloatArray, size: Int)
    }

    /**
     * Working space for [clipTurned], kept between rings and frames so that
     * clipping the whole world allocates nothing once it has grown to fit. The
     * runs used to be boxed `ArrayList<Float>`s, rebuilt for every ring that
     * crossed the limb on every frame of a drag.
     */
    class ClipScratch {
        // Every visible run's points, back to back.
        internal var pts = FloatArray(256)
        internal var ptsSize = 0
        // Run k is pts[runStart[k] until runEnd[k]]; run 0 may be preceded by
        // the stretch the ring's last vertex opened ([headStart] until [headEnd]).
        internal var runStart = IntArray(16)
        internal var runEnd = IntArray(16)
        internal var entries = FloatArray(16)
        internal var exits = FloatArray(16)
        internal var used = BooleanArray(16)
        internal var runCount = 0
        internal var headStart = -1
        internal var headEnd = -1
        // The polygon being assembled.
        internal var poly = FloatArray(256)
        internal var polySize = 0

        internal fun addPoint(x: Float, y: Float) {
            if (ptsSize + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
            pts[ptsSize++] = x
            pts[ptsSize++] = y
        }

        internal fun addRun(start: Int, end: Int, entry: Float, exit: Float) {
            if (runCount == runStart.size) {
                val grown = runStart.size * 2
                runStart = runStart.copyOf(grown)
                runEnd = runEnd.copyOf(grown)
                entries = entries.copyOf(grown)
                exits = exits.copyOf(grown)
                used = used.copyOf(grown)
            }
            runStart[runCount] = start
            runEnd[runCount] = end
            entries[runCount] = entry
            exits[runCount] = exit
            used[runCount] = false
            runCount++
        }

        internal fun polyAdd(x: Float, y: Float) {
            if (polySize + 2 > poly.size) poly = poly.copyOf(poly.size * 2)
            poly[polySize++] = x
            poly[polySize++] = y
        }

        internal fun polyAddRange(from: Int, to: Int) {
            val n = to - from
            if (polySize + n > poly.size) poly = poly.copyOf(maxOf(poly.size * 2, polySize + n))
            pts.copyInto(poly, polySize, from, to)
            polySize += n
        }
    }

    /**
     * [clipRing] on points already turned into view space: the ring is
     * [count] points of [view] starting at [from]. What the screen calls every
     * frame, and what [clipRing] runs underneath, so the test of the one is a
     * test of the other.
     */
    fun clipTurned(
        view: ViewPoints,
        from: Int,
        count: Int,
        orientation: Float,
        scratch: ClipScratch,
        arcSteps: Int = ARC_STEPS,
        sink: PolygonSink,
    ) {
        if (count < 3) return
        val ux = view.x
        val uy = view.y
        val uz = view.z
        val last = from + count - 1

        var anyVisible = false
        var anyHidden = false
        for (i in from..last) {
            if (uz[i] >= 0f) anyVisible = true else anyHidden = true
        }
        if (!anyVisible) return

        // Wholly on this side: no limb to reason about.
        if (!anyHidden) {
            scratch.polySize = 0
            for (i in from..last) scratch.polyAdd(ux[i], uy[i])
            sink.polygon(scratch.poly, scratch.polySize)
            return
        }

        // Cut into visible runs, each remembering the rim angle it entered at
        // and the one it left at — all the rejoin needs.
        scratch.ptsSize = 0
        scratch.runCount = 0
        scratch.headStart = -1
        scratch.headEnd = -1
        var open = -1 // where the run being built starts in scratch.pts; -1 for none
        var currentEntry = 0f

        for (i in from..last) {
            val j = if (i == last) from else i + 1
            val visI = uz[i] >= 0f
            val visJ = uz[j] >= 0f
            if (visI) {
                if (open < 0) open = scratch.ptsSize
                scratch.addPoint(ux[i], uy[i])
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
                if (open < 0) open = scratch.ptsSize
                scratch.addPoint(cos(angle), sin(angle))
                scratch.addRun(open, scratch.ptsSize, currentEntry, angle)
                open = -1
            } else {
                // A run left open here (its exit dropped above) is abandoned,
                // as it always was: the new one starts at this entry.
                open = scratch.ptsSize
                scratch.addPoint(cos(angle), sin(angle))
                currentEntry = angle
            }
        }

        // A run still open at the end began before the ring's first vertex and
        // continues into the run that starts it — one run, split by wherever the
        // vertex list happens to begin.
        if (open >= 0 && scratch.runCount > 0) {
            scratch.headStart = open
            scratch.headEnd = scratch.ptsSize
            scratch.entries[0] = currentEntry
        }
        val runs = scratch.runCount
        if (runs == 0) return

        val direction = -orientation
        val used = scratch.used
        val entries = scratch.entries
        val exits = scratch.exits

        for (seed in 0 until runs) {
            if (used[seed]) continue
            scratch.polySize = 0
            var k = seed
            while (!used[k]) {
                used[k] = true
                if (k == 0 && scratch.headStart >= 0) scratch.polyAddRange(scratch.headStart, scratch.headEnd)
                scratch.polyAddRange(scratch.runStart[k], scratch.runEnd[k])
                val exit = exits[k]
                var next = -1
                var shortest = Float.MAX_VALUE
                for (m in 0 until runs) {
                    if (used[m] && m != seed) continue
                    val gap = positiveAngle((entries[m] - exit) * direction)
                    if (gap < shortest) {
                        shortest = gap
                        next = m
                    }
                }
                if (next < 0) break
                // The rim from this run's exit to the next one's entry, stepped
                // round by a fixed rotation instead of a sine and a cosine for
                // every point: the same points to well under a pixel at the
                // closest zoom, for four trig calls an arc instead of 192.
                val step = direction.toDouble() * shortest / arcSteps
                val cosStep = cos(step)
                val sinStep = sin(step)
                var rimX = cos(exit.toDouble())
                var rimY = sin(exit.toDouble())
                repeat(arcSteps) {
                    val x = rimX * cosStep - rimY * sinStep
                    rimY = rimY * cosStep + rimX * sinStep
                    rimX = x
                    scratch.polyAdd(rimX.toFloat(), rimY.toFloat())
                }
                if (next == seed) break
                k = next
            }
            if (scratch.polySize >= 6) sink.polygon(scratch.poly, scratch.polySize)
        }
    }

    /** An angle brought into `[0, 2π)`, so "how far round" is never negative. */
    fun positiveAngle(radians: Float): Float {
        val tau = (2 * Math.PI).toFloat()
        var a = radians % tau
        if (a < 0f) a += tau
        return a
    }
}
