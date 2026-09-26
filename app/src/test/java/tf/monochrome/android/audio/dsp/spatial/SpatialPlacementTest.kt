package tf.monochrome.android.audio.dsp.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.ChannelLayout

class SpatialPlacementTest {

    @Test
    fun `every layout's standard speakers are where the reference puts them`() {
        val s51 = SpatialLayout.speakers(6)
        assertEquals(listOf("FL", "FR", "FC", "LFE", "BL", "BR"), s51.map { it.label })
        // 5.1 surrounds at 110; with sides as well, the backs are rears at 150.
        assertEquals(-110f, s51[4].azimuthDeg)
        val s71 = SpatialLayout.speakers(8)
        assertEquals(-150f, s71.first { it.label == "BL" }.azimuthDeg)
        assertEquals(-90f, s71.first { it.label == "SL" }.azimuthDeg)
        // 9.1.6: wides at 60, heights up 45, top sides last (FFmpeg's order).
        val s916 = SpatialLayout.speakers(16)
        assertEquals(ChannelDetectorProcessor.channelNames(16), s916.map { it.label })
        assertEquals(-60f, s916[6].azimuthDeg)
        assertEquals("TSL", s916[14].label)
        assertEquals(45f, s916[14].elevationDeg)
        assertEquals(6, s916.count { it.isHeight })
    }

    @Test
    fun `5_1_4 and 7_1_4 follow Android's order, as the mixer lanes do`() {
        for (count in listOf(10, 12)) {
            val speakers = SpatialLayout.speakers(count)
            val sides = ChannelLayout.sides(count)
            speakers.forEachIndexed { i, s ->
                if (s.isLfe || s.azimuthDeg == 0f) {
                    assertEquals("count $count ch $i", ChannelLayout.Side.CENTER, sides[i])
                } else {
                    // Left speakers sit on the left of the map, right on the right.
                    val expected = if (s.azimuthDeg < 0f) ChannelLayout.Side.LEFT else ChannelLayout.Side.RIGHT
                    assertEquals("count $count ch $i (${s.label})", expected, sides[i])
                }
            }
            assertEquals(4, speakers.count { it.isHeight })
            assertEquals(3, SpatialLayout.lfeIndex(count))
        }
    }

    @Test
    fun `nothing saved means the standard positions, at the ring`() {
        val p = SpatialPlacement.DEFAULT
        assertFalse(p.enabled)
        val placed = p.placementFor(12)
        assertEquals(SpatialLayout.speakers(12).map { it.azimuthDeg }, placed.map { it.azimuthDeg })
        assertTrue(placed.all { it.distance == 1f })
        assertFalse(p.isMoved(12))
    }

    @Test
    fun `moving one channel keeps the rest and remembers the layout`() {
        val p = SpatialPlacement.DEFAULT.withChannel(6, 4, ChannelPlacement(-170f, 1.5f))
        val placed = p.placementFor(6)
        assertEquals(-170f, placed[4].azimuthDeg)
        assertEquals(1.5f, placed[4].distance)
        assertEquals(-30f, placed[0].azimuthDeg)
        assertTrue(p.isMoved(6))
        // Other layouts untouched; resetting drops only this one.
        assertFalse(p.isMoved(8))
        assertFalse(p.resetLayout(6).isMoved(6))
    }

    @Test
    fun `placements stay on the map`() {
        val wrapped = ChannelPlacement(270f, 10f).clamped()
        assertEquals(-90f, wrapped.azimuthDeg)
        assertEquals(SpatialLayout.MAX_DISTANCE, wrapped.distance)
        val nonsense = ChannelPlacement(Float.NaN, Float.NEGATIVE_INFINITY).clamped()
        assertEquals(0f, nonsense.azimuthDeg)
        assertEquals(1f, nonsense.distance)
        assertEquals(SpatialLayout.MIN_DISTANCE, ChannelPlacement(0f, 0.01f).clamped().distance)
    }

    @Test
    fun `a saved layout that no longer fits is ignored`() {
        // Five entries for a six-channel bed (an older save, say): standard
        // positions rather than a channel left without one.
        val p = SpatialPlacement(layouts = mapOf("6" to List(5) { ChannelPlacement(10f) }))
        assertEquals(SpatialLayout.speakers(6).map { it.azimuthDeg }, p.placementFor(6).map { it.azimuthDeg })
    }

    @Test
    fun `distance is level, six dB each way`() {
        assertEquals(1f, SpatialLayout.gainFor(1f), 1e-6f)
        assertEquals(2f, SpatialLayout.gainFor(0.5f), 1e-6f)
        assertEquals(0.5f, SpatialLayout.gainFor(2f), 1e-6f)
        assertEquals(2f, SpatialLayout.gainFor(0.01f), 1e-6f)
    }
}

class HeadphoneTargetTest {
    private fun pts(vararg p: Pair<Float, Float>) =
        p.map { tf.monochrome.android.domain.model.FrequencyPoint(it.first, it.second) }

    private val grid = { i: Int -> 20.0 * Math.pow(1000.0, i / 63.0) }

    @Test
    fun `a target is taken as it is, levelled at 1 kHz`() {
        // Diffuse Field is a target like any other: its ear gain is kept.
        val df = pts(20f to 71f, 1000f to 75f, 3000f to 86.7f, 20000f to 70f)
        val c = HeadphoneTarget.curve(df, 64, grid)
        assertEquals(-4f, c[0], 1e-3f)
        val at1k = c.indices.minByOrNull { kotlin.math.abs(grid(it) - 1000.0) }!!
        // The grid point nearest 1 kHz is a few percent above it, on a steep rise.
        assertEquals(0f, c[at1k], 0.5f)
        val at3k = c.indices.minByOrNull { kotlin.math.abs(grid(it) - 3000.0) }!!
        assertEquals(11.7f, c[at3k], 0.6f)
    }

    @Test
    fun `a flat target is flat`() {
        val flat = pts(20f to 75f, 20000f to 75f)
        assertTrue(HeadphoneTarget.curve(flat, 64, grid).all { kotlin.math.abs(it) < 1e-4f })
    }

    @Test
    fun `a missing curve is flat, not silence`() {
        assertTrue(HeadphoneTarget.curve(emptyList(), 64, grid).all { it == 0f })
    }

    @Test
    fun `the map starts on AutoEQ's Diffuse Field target`() {
        assertEquals("diffuse_field", SpatialPlacement.DEFAULT.targetId)
    }
}
