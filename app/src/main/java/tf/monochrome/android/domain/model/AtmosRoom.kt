package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/** Which room the headphone render plays in. */
@Serializable
enum class RoomKind {
    /** The render's own reflections and tail. */
    BUILT_IN,

    /** A room measured by someone, from a SOFA file of room impulse responses. */
    MEASURED,

    /** A room the listener describes by its size. */
    CUSTOM,
}

/**
 * A room the listener describes: its size in metres, how much of the sound
 * its walls take up ([damping], 0..1: bare concrete is near 0.03, a furnished
 * living room about 0.25, a studio 0.5 and over), and how rounded its corners
 * are ([roundness], 0 a box to 1 as round as its floor plan allows).
 */
@Serializable
data class CustomRoom(
    val width: Float = 5f,
    val depth: Float = 6f,
    val height: Float = 2.7f,
    val damping: Float = 0.25f,
    val roundness: Float = 0f,
) {
    fun clamped(): CustomRoom {
        fun Float.c(min: Float, max: Float, fb: Float) = if (isFinite()) coerceIn(min, max) else fb
        return copy(
            width = width.c(MIN_SIDE, MAX_SIDE, 5f),
            depth = depth.c(MIN_SIDE, MAX_SIDE, 6f),
            height = height.c(MIN_HEIGHT, MAX_HEIGHT, 2.7f),
            damping = damping.c(MIN_DAMPING, MAX_DAMPING, 0.25f),
            roundness = roundness.c(0f, 1f, 0f),
        )
    }

    /**
     * The room's lowest mode along its width, its depth and its height, in
     * Hz: sound going to and fro between two walls a distance L apart comes
     * back in step at c / 2L, and the room rings there.
     */
    fun modesHz(): FloatArray = floatArrayOf(
        SPEED_OF_SOUND / (2f * width),
        SPEED_OF_SOUND / (2f * depth),
        SPEED_OF_SOUND / (2f * height),
    )

    companion object {
        const val MIN_SIDE = 2f
        const val MAX_SIDE = 30f
        const val MIN_HEIGHT = 2f
        const val MAX_HEIGHT = 12f
        const val MIN_DAMPING = 0.05f
        const val MAX_DAMPING = 0.7f
        const val SPEED_OF_SOUND = 343f
    }
}

/**
 * A room made ready for the render (a packed room, as
 * `AtmosNative.nativePrepareRoomSofa` and `nativeBuildBoxRoom` give one and
 * `render/measured_room.h` lays it out): its response, and what is known of
 * the room itself. Positions are in metres, x from the listener's left wall
 * to the right one, y from the wall ahead to the wall behind, z up from the
 * floor.
 */
class AtmosRoom private constructor(val packed: FloatArray) {
    /** How long the room takes to fall 60 dB, in seconds. */
    val decaySeconds: Float get() = packed[2]

    /** Whether the room's size is known. A measurement of a room that is not a box gives none. */
    val sized: Boolean get() = packed[3] == 1f
    val width: Float get() = packed[4]
    val depth: Float get() = packed[5]

    /** 0 when only the floor plan is known. */
    val height: Float get() = packed[6]
    val roundness: Float get() = packed[7]

    /** Where the listener sat: x, y, z. */
    fun listener(): FloatArray = floatArrayOf(packed[8], packed[9], packed[10])

    /** How many loudspeakers sounded the room. */
    val sources: Int get() = packed[11].toInt().coerceIn(0, MAX_SOURCES)

    /** Where loudspeaker [i] stood: x, y, z. */
    fun source(i: Int): FloatArray {
        val at = SOURCES_AT + 3 * i
        return floatArrayOf(packed[at], packed[at + 1], packed[at + 2])
    }

    companion object {
        const val MAX_SOURCES = 64
        private const val SOURCES_AT = 12
        const val HEADER = SOURCES_AT + 3 * MAX_SOURCES
        private const val MAGIC = 1297043282f

        /** [packed] as a room, or null when it is not one. */
        fun of(packed: FloatArray?): AtmosRoom? {
            if (packed == null || packed.size < HEADER || packed[0] != MAGIC) return null
            val samples = packed[1]
            if (!(samples >= 1f) || packed.size < HEADER + 2 * samples.toInt()) return null
            return AtmosRoom(packed)
        }
    }
}

/**
 * A room as the Atmos page draws it, in metres: its size, how rounded its
 * corners are, where the listener's ears are and where the loudspeakers that
 * sounded it stood (x from the left wall, y from the wall ahead, z from the
 * floor). [dimensioned] says the size is a real one to be written on it; the
 * built-in room has a nominal one.
 */
data class RoomShape(
    val width: Float,
    val depth: Float,
    val height: Float,
    val roundness: Float = 0f,
    /**
     * How far the walls lean in: the share of the floor's width and depth
     * that each wall has come in by at the ceiling. 0 is upright walls; at
     * 0.25 the ceiling is half as wide and half as deep as the floor, and
     * the room is a trapezoid seen from any side.
     */
    val taper: Float = 0f,
    val listenerX: Float = width / 2f,
    val listenerY: Float = depth / 2f,
    val earHeight: Float = minOf(EAR_HEIGHT, height / 2f),
    val sources: List<FloatArray> = emptyList(),
    val dimensioned: Boolean = false,
    /** False for a measured room whose file gives the floor plan alone: [height] is then a guess. */
    val heightKnown: Boolean = true,
) {
    /** The share of the floor's size each wall has leaned in by at [z] metres up. */
    fun lean(z: Float): Float = taper.coerceIn(0f, MAX_TAPER) * (z / height).coerceIn(0f, 1f)

    /** How far a corner is rounded, in metres: up to half the shorter side. */
    val cornerRadius: Float get() = roundness.coerceIn(0f, 1f) * minOf(width, depth) / 2f

    /**
     * Where a point of the render's own room stands in this one. The render
     * mixes in a room of its own (x and y 0..1 round a listener at its
     * middle, z 0 at ear level to 1 at the ceiling): its walls are this
     * room's walls, and a point outside a rounded corner or a leaning wall is
     * brought in to it.
     */
    fun place(x: Float, y: Float, z: Float): FloatArray {
        var px = x.coerceIn(0f, 1f) * width
        var py = y.coerceIn(0f, 1f) * depth
        val r = cornerRadius
        if (r > 0f) {
            // The centre of the corner's arc this point is beyond, if it is beyond one.
            val cx = if (px < r) r else if (px > width - r) width - r else px
            val cy = if (py < r) r else if (py > depth - r) depth - r else py
            val dx = px - cx
            val dy = py - cy
            val far = kotlin.math.sqrt(dx * dx + dy * dy)
            if (cx != px && cy != py && far > r) {
                px = cx + dx * r / far
                py = cy + dy * r / far
            }
        }
        val pz = earHeight + z.coerceIn(0f, 1f) * (height - earHeight)
        val l = lean(pz)
        return floatArrayOf(
            px.coerceIn(l * width, width - l * width),
            py.coerceIn(l * depth, depth - l * depth),
            pz,
        )
    }

    /** The ceiling's outline: the floor's, drawn in by the walls' lean. */
    fun ceiling(): List<FloatArray> {
        val keep = 1f - 2f * lean(height)
        return outline().map {
            floatArrayOf(width / 2f + (it[0] - width / 2f) * keep, depth / 2f + (it[1] - depth / 2f) * keep)
        }
    }

    /** The floor's outline, clockwise from the front left: four corners, or their arcs. */
    fun outline(): List<FloatArray> {
        val r = cornerRadius
        if (r <= 0.01f) {
            return listOf(
                floatArrayOf(0f, 0f), floatArrayOf(width, 0f), floatArrayOf(width, depth), floatArrayOf(0f, depth),
            )
        }
        val points = ArrayList<FloatArray>(4 * (ARC_STEPS + 1))
        // Each corner's arc: its centre, and the angle it starts at.
        val corners = arrayOf(
            floatArrayOf(r, r, 180f), floatArrayOf(width - r, r, 270f),
            floatArrayOf(width - r, depth - r, 0f), floatArrayOf(r, depth - r, 90f),
        )
        for (c in corners) {
            for (i in 0..ARC_STEPS) {
                val a = Math.toRadians((c[2] + 90f * i / ARC_STEPS).toDouble())
                points += floatArrayOf(c[0] + r * kotlin.math.cos(a).toFloat(), c[1] + r * kotlin.math.sin(a).toFloat())
            }
        }
        return points
    }

    /** One reflection: where on a surface the sound turns, and how far it travels in all. */
    class Reflection(val x: Float, val y: Float, val z: Float, val path: Float)

    /**
     * The first reflections from a source at ([sx], [sy], [sz]) to the
     * listener, one off each wall, the ceiling and the floor: the mirror
     * image of the source in the surface, and where the line from it to the
     * listener goes through the surface. A source that stands on a surface
     * has none off it, and a turn that would fall outside the surface is not
     * one. The walls are flat (leaning, if the room tapers); rounded corners
     * move their points very little.
     */
    fun firstReflections(sx: Float, sy: Float, sz: Float): List<Reflection> {
        val source = floatArrayOf(sx, sy, sz)
        val listener = floatArrayOf(listenerX, listenerY, earHeight)
        val leanX = taper.coerceIn(0f, MAX_TAPER) * width   // how far a side wall is in at the ceiling
        val leanY = taper.coerceIn(0f, MAX_TAPER) * depth
        // Each surface: a point on it, and its normal into the room.
        val surfaces = arrayOf(
            floatArrayOf(0f, 0f, 0f, height, 0f, -leanX),        // the left wall
            floatArrayOf(width, 0f, 0f, -height, 0f, -leanX),    // the right wall
            floatArrayOf(0f, 0f, 0f, 0f, height, -leanY),        // the wall ahead
            floatArrayOf(0f, depth, 0f, 0f, -height, -leanY),    // the wall behind
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f),                // the floor
            floatArrayOf(0f, 0f, height, 0f, 0f, -1f),           // the ceiling
        )
        val out = ArrayList<Reflection>(surfaces.size)
        for (s in surfaces) {
            val norm = kotlin.math.sqrt(s[3] * s[3] + s[4] * s[4] + s[5] * s[5])
            val n = floatArrayOf(s[3] / norm, s[4] / norm, s[5] / norm)
            var fromSource = 0f
            var fromListener = 0f
            for (c in 0..2) {
                fromSource += (source[c] - s[c]) * n[c]
                fromListener += (listener[c] - s[c]) * n[c]
            }
            if (fromSource < ON_SURFACE || fromListener <= 0f) continue
            val image = FloatArray(3) { source[it] - 2f * fromSource * n[it] }
            val t = fromSource / (fromSource + fromListener)
            val p = FloatArray(3) { image[it] + t * (listener[it] - image[it]) }
            val l = lean(p[2])
            val inside = p[2] in -EDGE..height + EDGE &&
                p[0] in l * width - EDGE..width - l * width + EDGE &&
                p[1] in l * depth - EDGE..depth - l * depth + EDGE
            if (!inside) continue
            var path = 0f
            for (c in 0..2) path += (listener[c] - image[c]) * (listener[c] - image[c])
            out += Reflection(p[0], p[1], p[2], kotlin.math.sqrt(path))
        }
        return out
    }

    companion object {
        const val EAR_HEIGHT = 1.2f
        private const val ARC_STEPS = 6
        private const val ON_SURFACE = 0.15f
        private const val EDGE = 0.02f
        const val MAX_TAPER = 0.45f

        /**
         * The built-in room has no measurements: this is how it is drawn. Its
         * walls lean in to where the 15.1.12 layout's top ring of speakers
         * stands, a quarter in at the ceiling: no sound comes from wider up
         * there, so the room is not drawn wider.
         */
        val BUILT_IN = RoomShape(width = 5f, depth = 5f, height = 2.75f, taper = 0.25f)

        /** A measured or worked-out room, or null when its size is not known. */
        fun of(room: AtmosRoom): RoomShape? {
            if (!room.sized || room.width <= 0f || room.depth <= 0f) return null
            val listener = room.listener()
            val heightKnown = room.height > 0f
            val height = if (heightKnown) room.height else 0.5f * maxOf(room.width, room.depth)
            return RoomShape(
                width = room.width,
                depth = room.depth,
                height = height,
                roundness = room.roundness,
                listenerX = listener[0].coerceIn(0f, room.width),
                listenerY = listener[1].coerceIn(0f, room.depth),
                earHeight = if (listener[2] > 0.2f && listener[2] < height) listener[2] else minOf(EAR_HEIGHT, height / 2f),
                sources = List(room.sources) { room.source(it) },
                dimensioned = true,
                heightKnown = heightKnown,
            )
        }

        /**
         * The room the listener describes, at once (the render's own copy
         * takes a moment to work out). The listener and the two loudspeakers
         * stand where `render/box_room.h` puts them.
         */
        fun of(room: CustomRoom): RoomShape {
            val r = room.clamped()
            val ear = minOf(EAR_HEIGHT, r.height / 2f)
            return RoomShape(
                width = r.width,
                depth = r.depth,
                height = r.height,
                roundness = r.roundness,
                listenerX = r.width * 0.44f,
                listenerY = r.depth * 0.6f,
                earHeight = ear,
                sources = listOf(
                    floatArrayOf(r.width * 0.25f, r.depth / 6f, ear),
                    floatArrayOf(r.width * 0.75f, r.depth / 6f, ear),
                ),
                dimensioned = true,
            )
        }
    }
}
