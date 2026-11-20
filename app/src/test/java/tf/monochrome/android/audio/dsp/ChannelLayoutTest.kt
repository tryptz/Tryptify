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
    }

    @Test
    fun `sixteen channels are 9_1_6, centre and LFE on their own`() {
        assertEquals(
            listOf(
                Lane(0, 1), Lane(2, -1), Lane(3, -1), Lane(4, 5), Lane(6, 7),
                Lane(8, 9), Lane(10, 11), Lane(12, 13), Lane(14, 15),
            ),
            ChannelLayout.lanes(16),
        )
        assertEquals(
            listOf("Front", "Centre", "LFE", "Rear", "Front Wide", "Side", "Top Front", "Top Rear", "Top Side"),
            ChannelLayout.laneLabels(16),
        )
        assertTrue(ChannelLayout.isLfe(16, 3))
        assertFalse(ChannelLayout.isLfe(16, 2))
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
        // Even a 9.1.6 bed (nine groups) fits the mixer's sixteen buses.
        assertTrue((2..ChannelLayout.MAX_CHANNELS).all { ChannelLayout.lanes(it).size <= 16 })
    }

    @Test
    fun `every channel of a known layout names its speaker, a left with its right`() {
        val r = ChannelLayout.Role
        // FFmpeg's 9.1.6, which the upmix makes: the rears before the wides.
        assertEquals(
            listOf(r.L, r.R, r.C, r.LFE, r.LRS, r.RRS, r.LW, r.RW, r.LSS, r.RSS, r.LTF, r.RTF, r.LTR, r.RTR, r.LTS, r.RTS),
            ChannelLayout.roles(16).toList(),
        )
        // 5.1's surrounds are its own, at 110 degrees; 7.1 has rears and sides.
        assertEquals(listOf(r.L, r.R, r.C, r.LFE, r.LS, r.RS), ChannelLayout.roles(6).toList())
        assertEquals(listOf(r.L, r.R, r.C, r.LFE, r.LRS, r.RRS, r.LSS, r.RSS), ChannelLayout.roles(8).toList())
        for (n in listOf(2, 3, 4, 5, 6, 7, 8, 10, 12, 16)) {
            val roles = ChannelLayout.roles(n)
            assertEquals("count $n", n, roles.size)
            assertTrue("count $n: every channel has a speaker", roles.all { it >= 0 })
            assertEquals("count $n: no speaker twice", n, roles.toSet().size)
            assertEquals("count $n", r.LFE == roles.getOrNull(3), ChannelLayout.isLfe(n, 3))
            // A pair's two channels are one speaker's left and right: the
            // roles come in left/right twins, a right one after its left.
            val sides = ChannelLayout.sides(n)
            for (lane in ChannelLayout.lanes(n)) {
                if (lane.isMono) {
                    assertEquals("count $n ch ${lane.first}", Side.CENTER, sides[lane.first])
                    assertTrue(roles[lane.first] in listOf(r.C, r.LFE, r.BACK_CENTRE))
                } else {
                    assertEquals("count $n ch ${lane.first}", roles[lane.first] + 1, roles[lane.second])
                    assertEquals("count $n ch ${lane.first}: a left", 0, roles[lane.first] % 2)
                }
            }
            assertEquals("count $n: the front pair first", listOf(r.L, r.R), roles.take(2))
        }
        // The numbers cross JNI: RoomRole in snapin_processor.h has the same.
        assertEquals(
            (0..18).toList(),
            listOf(r.L, r.R, r.C, r.LFE, r.LS, r.RS, r.LSS, r.RSS, r.LRS, r.RRS, r.LTF, r.RTF, r.LTR, r.RTR, r.LTS, r.RTS, r.LW, r.RW, r.BACK_CENTRE),
        )
    }

    @Test
    fun `a count with no layout names no speaker, so nothing places it`() {
        for (n in listOf(1, 9, 11, 13, 14, 15)) {
            assertTrue("count $n", ChannelLayout.roles(n).all { it == ChannelLayout.Role.NONE })
        }
    }
}
