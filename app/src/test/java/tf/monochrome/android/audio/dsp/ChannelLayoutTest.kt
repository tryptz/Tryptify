package tf.monochrome.android.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.ChannelLayout.Lane
import tf.monochrome.android.audio.dsp.ChannelLayout.Side

class ChannelLayoutTest {

    private val L = Side.LEFT
    private val R = Side.RIGHT
    private val C = Side.CENTER

    @Test
    fun `7_1_4 pairs every left with its right, centre and LFE alone`() {
        // FL FR FC LFE BL BR SL SR TFL TFR TBL TBR
        assertEquals(
            listOf(Lane(0, 1), Lane(2, -1), Lane(3, -1), Lane(4, 5), Lane(6, 7), Lane(8, 9), Lane(10, 11)),
            ChannelLayout.lanes(12),
        )
        assertEquals(listOf(L, R, C, C, L, R, L, R, L, R, L, R), ChannelLayout.sides(12).toList())
        assertTrue(ChannelLayout.isLfe(12, 3))
        assertFalse(ChannelLayout.isLfe(12, 2))
    }

    @Test
    fun `5_1 and quad and stereo`() {
        assertEquals(listOf(Lane(0, 1), Lane(2, -1), Lane(3, -1), Lane(4, 5)), ChannelLayout.lanes(6))
        assertEquals(listOf(Lane(0, 1), Lane(2, 3)), ChannelLayout.lanes(4))
        assertFalse(ChannelLayout.isLfe(4, 3))
        assertEquals(listOf(Lane(0, 1)), ChannelLayout.lanes(2))
        assertEquals(listOf(Lane(0, -1)), ChannelLayout.lanes(1))
    }

    @Test
    fun `counts Android has no layout for pair consecutively, odd one out in the centre`() {
        assertEquals(
            listOf(Lane(0, 1), Lane(2, 3), Lane(4, 5), Lane(6, 7), Lane(8, -1)),
            ChannelLayout.lanes(9),
        )
        assertEquals(8, ChannelLayout.lanes(16).size)
        assertTrue(ChannelLayout.lanes(16).none { it.isMono })
    }

    @Test
    fun `every count covers every channel exactly once`() {
        for (n in 1..ChannelLayout.MAX_CHANNELS) {
            val covered = ChannelLayout.lanes(n).flatMap { listOfNotNull(it.first, it.second.takeIf { s -> s >= 0 }) }
            assertEquals("count $n", (0 until n).toList(), covered)
        }
    }

    @Test
    fun `every channel group has a name, one per lane`() {
        assertEquals(listOf("Front", "Centre", "LFE", "Rear", "Side", "Top Front", "Top Rear"),
            ChannelLayout.laneLabels(12))
        assertEquals(listOf("Front", "Centre", "LFE", "Surround"), ChannelLayout.laneLabels(6))
        for (n in 2..ChannelLayout.MAX_CHANNELS) {
            assertEquals("count $n", ChannelLayout.lanes(n).size, ChannelLayout.laneLabels(n).size)
        }
        // A 7.1.4 bed fits the mixer's sixteen buses with room to spare.
        assertTrue((2..ChannelLayout.MAX_CHANNELS).all { ChannelLayout.lanes(it).size <= 16 })
    }
}
