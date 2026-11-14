package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.atmos.AtmosRoomStore

/** The rooms the headphone render can play in: what is read of one, and how it is drawn. */
class AtmosRoomTest {

    /** A packed room as `render/measured_room.h` lays one out, with [samples] of response an ear. */
    private fun packed(samples: Int = 4, sized: Boolean = true, height: Float = 2.4f): FloatArray {
        val p = FloatArray(AtmosRoom.HEADER + 2 * samples)
        p[0] = 1297043282f
        p[1] = samples.toFloat()
        p[2] = 0.42f
        p[3] = if (sized) 1f else 0f
        p[4] = 6f; p[5] = 5f; p[6] = height; p[7] = 0f
        p[8] = 3.9f; p[9] = 4.8f; p[10] = 1f
        p[11] = 2f
        p[12] = 3f; p[13] = 4.8f; p[14] = 1.4f
        p[15] = 1f; p[16] = 1f; p[17] = 1.2f
        return p
    }

    @Test
    fun `a packed room is read as the native side lays it out`() {
        val room = AtmosRoom.of(packed())!!
        assertEquals(0.42f, room.decaySeconds, 0f)
        assertTrue(room.sized)
        assertEquals(6f, room.width, 0f)
        assertEquals(5f, room.depth, 0f)
        assertEquals(2.4f, room.height, 0f)
        assertEquals(3.9f, room.listener()[0], 0f)
        assertEquals(2, room.sources)
        assertEquals(1.4f, room.source(0)[2], 0f)
        assertEquals(1f, room.source(1)[0], 0f)
        // The header is 12 floats and 64 loudspeakers of three.
        assertEquals(204, AtmosRoom.HEADER)
    }

    @Test
    fun `what is not a room is refused`() {
        assertNull(AtmosRoom.of(null))
        assertNull(AtmosRoom.of(FloatArray(10)))
        assertNull(AtmosRoom.of(packed().also { it[0] = 1f }))            // not the mark
        assertNull(AtmosRoom.of(packed().also { it[1] = 0f }))            // no samples
        assertNull(AtmosRoom.of(packed(samples = 4).also { it[1] = 9f })) // more than it holds
        assertNull(AtmosRoom.of(packed().also { it[1] = Float.NaN }))
        assertNotNull(AtmosRoom.of(packed()))
    }

    @Test
    fun `a measured room is drawn at its own size, a room with none is not`() {
        val shape = RoomShape.of(AtmosRoom.of(packed())!!)!!
        assertEquals(6f, shape.width, 0f)
        assertEquals(2.4f, shape.height, 0f)
        assertEquals(1f, shape.earHeight, 0f)          // where the listener's ears were
        assertEquals(2, shape.sources.size)
        assertTrue(shape.dimensioned && shape.heightKnown)
        assertNull(RoomShape.of(AtmosRoom.of(packed(sized = false))!!))
        // A floor plan alone: a height is guessed for the drawing, and said to be.
        val plan = RoomShape.of(AtmosRoom.of(packed(height = 0f))!!)!!
        assertFalse(plan.heightKnown)
        assertEquals(3f, plan.height, 0f)
    }

    @Test
    fun `the listener's room is clamped, and its modes are where c over 2L puts them`() {
        val wild = CustomRoom(width = 500f, depth = -1f, height = Float.NaN, damping = 9f, roundness = 4f).clamped()
        assertEquals(CustomRoom.MAX_SIDE, wild.width, 0f)
        assertEquals(CustomRoom.MIN_SIDE, wild.depth, 0f)
        assertEquals(2.7f, wild.height, 0f)
        assertEquals(CustomRoom.MAX_DAMPING, wild.damping, 0f)
        assertEquals(1f, wild.roundness, 0f)
        val modes = CustomRoom(width = 5f, depth = 6f, height = 2.7f).modesHz()
        assertEquals(34.3f, modes[0], 0.01f)
        assertEquals(28.58f, modes[1], 0.01f)
        assertEquals(63.52f, modes[2], 0.01f)
        // A stored profile from before rooms could be chosen gets the built-in one.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<RendererProfile>("""{"hrtfEnabled":true}""")
        assertEquals(RoomKind.BUILT_IN, old.roomKind)
        assertNull(old.roomFile)
        assertEquals(CustomRoom(), old.customRoom)
        assertEquals(CustomRoom.MAX_SIDE, old.copy(customRoom = CustomRoom(width = 99f)).clamped().customRoom.width, 0f)
    }

    @Test
    fun `the render's speakers stand on the room's walls, from ear height to the ceiling`() {
        val room = RoomShape(width = 6f, depth = 4f, height = 3f, earHeight = 1.2f)
        assertEquals(listOf(0f, 0f, 1.2f), room.place(0f, 0f, 0f).toList())          // front left, ear level
        assertEquals(listOf(6f, 4f, 3f), room.place(1f, 1f, 1f).toList())            // back right, ceiling
        assertEquals(listOf(3f, 2f, 2.1f), room.place(0.5f, 0.5f, 0.5f).toList())    // the middle, half way up
        assertEquals(listOf(6f, 0f, 1.2f), room.place(7f, -2f, -1f).toList())        // never outside it
        // A rounded room: a corner speaker is brought in to the arc, a wall's middle is not moved.
        val round = room.copy(roundness = 1f)                                       // corners of 2 m
        val corner = round.place(0f, 0f, 0f)
        val fromCentre = kotlin.math.hypot(corner[0] - 2f, corner[1] - 2f)
        assertEquals(2f, fromCentre, 1e-4f)
        assertEquals(corner[0], corner[1], 1e-5f)
        assertEquals(listOf(3f, 0f, 1.2f), round.place(0.5f, 0f, 0f).toList())
        // Its floor is drawn with those arcs: every point inside the box, the corners cut.
        assertEquals(4, room.outline().size)
        val outline = round.outline()
        assertTrue(outline.size > 12)
        assertTrue(outline.all { it[0] in 0f..6f && it[1] in 0f..4f })
        assertTrue(outline.none { it[0] < 0.5f && it[1] < 0.5f })
    }

    @Test
    fun `a first reflection turns where the mirror image says`() {
        val room = RoomShape(width = 6f, depth = 8f, height = 3f, listenerX = 3f, listenerY = 4f, earHeight = 1f)
        val hits = room.firstReflections(1f, 2f, 1f)
        // Off the left wall (x = 0): the image is at x = -1, and the line to the
        // listener at x = 3 crosses the wall a quarter of the way along.
        val left = hits.first { kotlin.math.abs(it.x) < 1e-4f }
        assertEquals(2.5f, left.y, 1e-4f)
        assertEquals(1f, left.z, 1e-4f)
        assertEquals(kotlin.math.sqrt(16f + 4f), left.path, 1e-4f)
        // Six surfaces, and each turn is on its surface.
        assertEquals(6, hits.size)
        assertTrue(hits.any { it.z == 3f } && hits.any { it.z == 0f } && hits.any { kotlin.math.abs(it.y - 8f) < 1e-4f })
        // Every way round is longer than the straight one.
        val direct = kotlin.math.sqrt(4f + 4f)
        assertTrue(hits.all { it.path > direct })
        // A speaker on a wall has no reflection off that wall.
        assertTrue(room.firstReflections(0f, 2f, 1f).none { kotlin.math.abs(it.x) < 1e-4f })
        assertEquals(5, room.firstReflections(0f, 2f, 1f).size)
    }

    @Test
    fun `a room whose walls lean in is a trapezoid, and everything in it keeps inside`() {
        // 8 x 8 x 5 m, ears at 1 m, the ceiling half the floor each way.
        val room = RoomShape(width = 8f, depth = 8f, height = 5f, earHeight = 1f, ceilingWidth = 0.5f, ceilingDepth = 0.5f)
        // The walls stand upright to ear height and lean from there: the
        // ceiling is half the floor, about the same middle.
        assertEquals(0f, room.leanX(0.5f), 0f)
        assertEquals(0f, room.leanX(1f), 0f)
        assertEquals(0.125f, room.leanX(3f), 1e-6f)
        assertEquals(0.25f, room.leanX(5f), 1e-6f)
        assertEquals(listOf(0f, 0f), room.ring(1f)[0].toList())
        assertEquals(listOf(2f, 2f), room.ring(5f)[0].toList())
        assertEquals(listOf(6f, 6f), room.ring(5f)[2].toList())
        assertEquals(listOf(0f, 1f, 5f), room.wallHeights().toList())
        assertFalse(room.upright)
        // An ear-level speaker is on the wall; one half way up is on the
        // leaning wall there; the layout's top ring, a quarter in, is at the
        // ceiling's corners as it stands.
        assertEquals(0f, room.place(0f, 0.5f, 0f)[0], 0f)
        assertEquals(1f, room.place(0f, 0.5f, 0.5f)[0], 1e-5f)
        assertEquals(7f, room.place(1f, 0.5f, 0.5f)[0], 1e-5f)
        assertEquals(listOf(2f, 2f, 5f), room.place(0.25f, 0.25f, 1f).toList())
        assertEquals(listOf(2f, 2f, 5f), room.place(0f, 0f, 1f).toList())
        // What is inside the walls is not moved.
        assertEquals(listOf(4f, 4f, 3f), room.place(0.5f, 0.5f, 0.5f).toList())
        // Its width and depth lean apart, and a curve bends the walls: drawn in steps.
        val odd = room.copy(ceilingWidth = 0.2f, ceilingDepth = 1f, curve = 2f)
        assertEquals(0.4f, odd.leanX(5f), 1e-6f)
        assertEquals(0f, odd.leanY(5f), 0f)
        assertEquals(0.4f * 0.25f, odd.leanX(3f), 1e-6f)   // half way up, a quarter of the way in
        assertTrue(odd.wallHeights().size > 3)
        // The built-in room is such a trapezoid; a measured or described one stands upright.
        assertEquals(0.5f, RoomShape.BUILT_IN.ceilingWidth, 0f)
        assertTrue(RoomShape.of(CustomRoom()).upright)
        assertEquals(listOf(0f, 2.7f), RoomShape.of(CustomRoom()).wallHeights().toList())
        // A reflection off a leaning wall turns on that wall, the angle in the angle out.
        val listening = room.copy(listenerX = 4f, listenerY = 4f, earHeight = 1f)
        val hit = listening.firstReflections(4f, 2f, 3f).first { it.x < 2.5f && it.z > 1f && it.z < 5f && kotlin.math.abs(it.x - listening.leanX(it.z) * 8f) < 1e-2f }
        val toSource = kotlin.math.sqrt((4f - hit.x) * (4f - hit.x) + (2f - hit.y) * (2f - hit.y) + (3f - hit.z) * (3f - hit.z))
        val toListener = kotlin.math.sqrt((4f - hit.x) * (4f - hit.x) + (4f - hit.y) * (4f - hit.y) + (1f - hit.z) * (1f - hit.z))
        assertEquals(hit.path, toSource + toListener, 1e-3f)
    }

    @Test
    fun `the listener's own room is drawn with the head in the middle`() {
        // Where the objects are panned around, not where the reflections are worked out from.
        val shape = RoomShape.of(CustomRoom(width = 10f, depth = 16f, height = 4f))
        assertEquals(5f, shape.listenerX, 0f)
        assertEquals(8f, shape.listenerY, 0f)
    }

    @Test
    fun `the panning envelope shapes the room that is drawn, and is clamped`() {
        val box = RoomShape.of(CustomRoom(width = 6f, depth = 4f, height = 3f))
        assertTrue(box.within(PanEnvelope()).upright)                      // off: the room as it is
        val shaped = box.within(PanEnvelope(enabled = true, ceilingWidth = 0.4f, ceilingDepth = 0.8f, curve = 2f))
        assertEquals(0.4f, shaped.ceilingWidth, 0f)
        assertEquals(0.8f, shaped.ceilingDepth, 0f)
        assertEquals(2f, shaped.curve, 0f)
        assertEquals(6f, shaped.width, 0f)
        // The built-in room takes the envelope's shape in place of its own.
        assertEquals(0.9f, RoomShape.BUILT_IN.within(PanEnvelope(enabled = true, ceilingWidth = 0.9f)).ceilingWidth, 0f)
        val wild = PanEnvelope(enabled = true, ceilingWidth = -2f, ceilingDepth = 9f, curve = Float.NaN).clamped()
        assertEquals(PanEnvelope.MIN_CEILING, wild.ceilingWidth, 0f)
        assertEquals(1f, wild.ceilingDepth, 0f)
        assertEquals(1f, wild.curve, 0f)
        assertEquals(PanEnvelope.MAX_CURVE, PanEnvelope(curve = 99f).clamped().curve, 0f)
        // A stored profile from before the envelope plays every object where the mix puts it.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<RendererProfile>("""{"hrtfEnabled":true}""").panEnvelope.enabled)
    }

    @Test
    fun `a room of any shape sounds as the box that holds as much air`() {
        // A box is its own.
        val box = CustomRoom(width = 6f, depth = 8f, height = 3f)
        assertTrue(box.isBox)
        assertEquals(144f, box.volume(), 0.01f)
        assertEquals(box, box.equivalentBox())
        // Walls leaning to a ceiling half the size each way: 7/12 of the box
        // (a frustum), and its box keeps the height and the proportions.
        val leaning = box.copy(ceilingWidth = 0.5f, ceilingDepth = 0.5f)
        assertFalse(leaning.isBox)
        assertEquals(144f * 7f / 12f, leaning.volume(), 0.3f)
        val same = leaning.equivalentBox()
        assertTrue(same.isBox)
        assertEquals(3f, same.height, 0f)
        assertEquals(leaning.volume(), same.width * same.depth * same.height, 0.01f)
        assertEquals(6f / 8f, same.width / same.depth, 1e-4f)
        assertEquals(leaning.damping, same.damping, 0f)
        // A fully round room on a square plan is a cylinder: pi r squared h.
        val round = CustomRoom(width = 8f, depth = 8f, height = 4f, roundness = 1f)
        assertEquals((Math.PI * 16.0 * 4.0).toFloat(), round.volume(), 0.05f)
        assertTrue(round.equivalentBox().width < 8f)
        // It is drawn as it is, leaning from its floor, whatever it sounds as.
        val shape = RoomShape.of(leaning)
        assertEquals(0f, shape.leanStart, 0f)
        assertEquals(0.5f, shape.ceilingWidth, 0f)
        assertEquals(0.125f, shape.leanX(1.5f), 1e-6f)              // half way up, half way in
        assertEquals(listOf(0f, 3f), shape.wallHeights().toList())
        assertEquals(1f, RoomShape.of(round).roundness, 0f)
        // Its own lean is its walls: the panning envelope does not redraw them.
        val envelope = PanEnvelope(enabled = true, ceilingWidth = 0.9f)
        assertEquals(0.5f, shape.within(envelope).ceilingWidth, 0f)
        assertEquals(0.9f, RoomShape.of(box).within(envelope).ceilingWidth, 0f)
        assertEquals(RoomShape.EAR_HEIGHT, RoomShape.of(box).within(envelope).leanStart, 0f)
    }

    @Test
    fun `every preset is a room that can be made, and no two are the same`() {
        val rooms = RoomPreset.entries.map { it.room }
        assertEquals(rooms.size, rooms.toSet().size)
        for (preset in RoomPreset.entries) {
            assertEquals("${preset.name} is out of range", preset.room, preset.room.clamped())
            assertTrue(preset.room.volume() > 5f)
        }
        // Choosing one and changing a value leaves a room that is no preset's: it is the listener's.
        val edited = RoomPreset.CONTROL.room.copy(width = 6.1f)
        assertTrue(RoomPreset.entries.none { it.room == edited })
        // A stored room from before shapes stands upright with square corners.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString<CustomRoom>("""{"width":4.0,"depth":5.0,"height":2.5,"damping":0.3}""").isBox)
    }

    @Test
    fun `a room's file name is its name and nothing a path could use`() {
        assertEquals("OfficeII.room", AtmosRoomStore.fileName("OfficeII.sofa"))
        assertEquals("BRIR_CR1 KU-MICS.L.room", AtmosRoomStore.fileName("BRIR_CR1 KU-MICS.L"))
        assertEquals("_.._etc_passwd.room", AtmosRoomStore.fileName("/../etc/passwd"))
        assertEquals("room.room", AtmosRoomStore.fileName("..."))
        assertEquals("OfficeII", AtmosRoomStore.displayName(java.io.File("/x/OfficeII.room")))
    }
}
