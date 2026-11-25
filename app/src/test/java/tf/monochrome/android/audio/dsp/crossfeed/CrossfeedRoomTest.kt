package tf.monochrome.android.audio.dsp.crossfeed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Crossfeed page's three ways (Off, Crossfeed, Room) as the player hears
 * them, and what is kept on the phone. The native room is stood in for by
 * [FakeRoom]; the room itself is held by `dsp/tests/headphone_room_test.cpp`.
 */
class CrossfeedRoomTest {

    private class FakeRoom : RoomEngine {
        var preparedAt = 0
        val params = mutableMapOf<Int, Float>()
        var paramCalls = 0
        var processed = 0
        var resets = 0
        var sofa: FloatArray? = null
        var refuse = false
        var released = false
        override fun prepare(sampleRate: Int) { preparedAt = sampleRate }
        override fun setParameter(index: Int, value: Float) { params[index] = value; paramCalls++ }
        override fun setSofa(packed: FloatArray?): Boolean {
            if (packed != null && refuse) return false
            sofa = packed
            return true
        }
        override fun reset() { resets++ }
        override fun process(left: FloatArray, right: FloatArray, frames: Int) {
            processed += frames
            for (i in 0 until frames) { left[i] *= 2f; right[i] *= 2f }
        }
        override fun release() { released = true }
    }

    private fun effect(room: FakeRoom?) = CrossfeedEffect { room }.also { it.prepare(48000.0) }

    private fun block(v: Float = 0.25f) = FloatArray(256) { v } to FloatArray(256) { -v }

    @Test
    fun `off leaves the song as it is and makes no room`() {
        val room = FakeRoom()
        val fx = effect(room)
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        assertTrue(l.all { it == 0.25f } && r.all { it == -0.25f })
        assertEquals(0, room.processed)
    }

    @Test
    fun `room plays the room, with every setting, at the stream's rate`() {
        val room = FakeRoom()
        val fx = effect(room)
        fx.setMode(CrossfeedMode.ROOM)
        fx.updateRoom { it.copy(roomPercent = 40f, lfe = true, lfeHz = 120f, lfeDb = -3f) }
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        assertEquals(48000, room.preparedAt)
        assertEquals(256, room.processed)
        assertTrue(l.all { it == 0.5f })
        assertEquals(RoomSettings.PARAM_COUNT, room.params.size)
        assertEquals(40f, room.params[0])
        assertEquals(1f, room.params[9])
        assertEquals(120f, room.params[10])
        assertEquals(-3f, room.params[11])
    }

    @Test
    fun `a setting is sent once, and again only when it moves`() {
        val room = FakeRoom()
        val fx = effect(room)
        fx.setMode(CrossfeedMode.ROOM)
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        val first = room.paramCalls
        fx.processArrays(l, r, l.size)
        assertEquals(first, room.paramCalls)
        fx.updateRoom { it.copy(angleDeg = 45f) }
        fx.processArrays(l, r, l.size)
        assertEquals(first + 1, room.paramCalls)
        assertEquals(45f, room.params[2])
    }

    @Test
    fun `crossfeed and room never play at once`() {
        val room = FakeRoom()
        val fx = effect(room)
        fx.setMode(CrossfeedMode.ROOM)
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        fx.setMode(CrossfeedMode.CROSSFEED)
        val before = room.processed
        val (l2, r2) = block()
        fx.processArrays(l2, r2, l2.size)
        assertEquals(before, room.processed)
        // Crossfeed glides in from nothing: the first sample is the song's.
        assertEquals(0.25f, l2[0], 1e-3f)
        assertTrue(fx.state.value.crossfeedOn)
        assertFalse(fx.state.value.roomOn)
    }

    @Test
    fun `coming back to the room it starts from silence`() {
        val room = FakeRoom()
        val fx = effect(room)
        fx.setMode(CrossfeedMode.ROOM)
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        fx.setMode(null)
        fx.processArrays(l, r, l.size)
        fx.setMode(CrossfeedMode.ROOM)
        fx.processArrays(l, r, l.size)
        assertEquals(2, room.resets)
    }

    @Test
    fun `the room keeps the mode it was left in when switched off and on`() {
        val fx = effect(FakeRoom())
        fx.setMode(CrossfeedMode.ROOM)
        fx.setEnabled(false)
        assertFalse(fx.state.value.enabled)
        fx.setEnabled(true)
        assertTrue(fx.state.value.roomOn)
    }

    @Test
    fun `the HRTF set goes to the room, and a refused one leaves the built-in`() {
        val room = FakeRoom()
        val fx = effect(room)
        val set = FloatArray(8) { it.toFloat() }
        assertTrue(fx.setRoomHrtf(set))
        assertSame(set, room.sofa)
        assertSame(set, fx.roomHrtf)
        room.refuse = true
        assertFalse(fx.setRoomHrtf(FloatArray(4)))
        assertNull(fx.roomHrtf)
        assertNull(room.sofa)
    }

    @Test
    fun `without a room there is nothing to play and nothing breaks`() {
        val fx = effect(null)
        fx.setMode(CrossfeedMode.ROOM)
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        assertTrue(l.all { it == 0.25f })
        assertFalse(fx.setRoomHrtf(FloatArray(4)))
        assertTrue(fx.setRoomHrtf(null))
    }

    @Test
    fun `release frees the room`() {
        val room = FakeRoom()
        val fx = effect(room)
        fx.setMode(CrossfeedMode.ROOM)
        val (l, r) = block()
        fx.processArrays(l, r, l.size)
        fx.release()
        assertTrue(room.released)
    }

    @Test
    fun `what is kept reads back as it was`() {
        val state = CrossfeedState(
            enabled = true,
            mode = CrossfeedMode.ROOM,
            algorithm = CrossfeedAlgorithm.JMEIER,
            speakerAngleDeg = 90f,
            room = RoomSettings(roomPercent = 60f, myRoom = true, widthM = 8f, lfe = true, lfeHz = 100f, hrtfPath = "/x/a.sofa"),
        )
        assertEquals(state, CrossfeedState.decode(CrossfeedState.encode(state)))
    }

    @Test
    fun `nothing kept, or something unreadable, is nothing`() {
        assertNull(CrossfeedState.decode(null))
        assertNull(CrossfeedState.decode(""))
        assertNull(CrossfeedState.decode("{not json"))
    }

    @Test
    fun `what is kept is held to the knobs' limits`() {
        val text = """{"enabled":true,"mode":"ROOM","speakerAngleDeg":500,"room":{"roomPercent":400,"angleDeg":-5,"lfeHz":5000,"lfeDb":-90,"widthM":1,"hrtfPath":""}}"""
        val s = CrossfeedState.decode(text)!!
        assertEquals(CrossfeedState.MAX_ANGLE_DEG, s.speakerAngleDeg)
        assertEquals(100f, s.room.roomPercent)
        assertEquals(0f, s.room.angleDeg)
        assertEquals(RoomSettings.MAX_LFE_HZ, s.room.lfeHz)
        assertEquals(RoomSettings.MIN_LFE_DB, s.room.lfeDb)
        assertEquals(RoomSettings.MIN_SIDE_M, s.room.widthM)
        assertNull(s.room.hrtfPath)
    }

    /**
     * The page's Room takes the mixer effect's presets as they are: the same
     * nine settings in the same order and within the same limits, so a
     * preset reads back as itself and lights its chip, and the page's own
     * LFE and HRTF are left as the listener set them.
     */
    @Test
    fun `every Binaural Room preset fits the page's room and leaves the LFE and HRTF alone`() {
        val defs = tf.monochrome.android.ui.mixer.getParamDefs(tf.monochrome.android.audio.dsp.SnapinType.BINAURAL_ROOM)
        val presets = tf.monochrome.android.ui.mixer.fxchain.FxPresets.forType(tf.monochrome.android.audio.dsp.SnapinType.BINAURAL_ROOM)
        assertEquals(RoomSettings.ROOM_PARAM_COUNT, defs.size)
        assertArrayEquals("the page's room starts as the effect does", FloatArray(defs.size) { defs[it].default }, RoomSettings().roomParams(), 0f)
        val mine = RoomSettings(lfe = true, lfeHz = 120f, lfeDb = -3f, hrtfPath = "/x/a.sofa")
        for (preset in presets) {
            val values = preset.resolved(defs)
            val room = mine.withRoom(values)
            assertArrayEquals(preset.name, values, room.roomParams(), 0f)
            val current = room.roomParams().withIndex().associate { it.index to it.value }
            assertTrue("${preset.name} lights its own chip", preset.matches(defs, current, 1f))
            assertEquals("${preset.name}: one chip lit", 1, presets.count { it.matches(defs, current, 1f) })
            assertTrue(preset.name, room.lfe && room.lfeHz == 120f && room.lfeDb == -3f && room.hrtfPath == "/x/a.sofa")
        }
        // A slider moved: no chip is lit.
        val moved = mine.withRoom(presets[0].resolved(defs)).copy(roomPercent = 13f)
        val current = moved.roomParams().withIndex().associate { it.index to it.value }
        assertTrue(presets.none { it.matches(defs, current, 1f) })
    }

    @Test
    fun `the room's parameters are in the native room's order`() {
        val p = RoomSettings(
            roomPercent = 1f, binauralPercent = 2f, angleDeg = 3f, outputDb = 4f, myRoom = true,
            widthM = 6f, depthM = 7f, heightM = 8f, dampingPercent = 9f, lfe = true, lfeHz = 81f, lfeDb = -2f,
        ).params()
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 1f, 6f, 7f, 8f, 9f, 1f, 81f, -2f), p, 0f)
    }
}
