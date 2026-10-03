package tf.monochrome.android.audio.dsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The master sits at index 4 in the middle of the bus list, so the index, the
 * number on the strip and the position on screen all differ past bus 4.
 */
class BusConfigTest {

    private fun bus(index: Int) = BusConfig(index = index, name = BusConfig.nameFor(index))

    @Test
    fun `numbers skip the master's index`() {
        assertEquals(listOf("Bus 1", "Bus 2", "Bus 3", "Bus 4", "Master", "Bus 5", "Bus 16"),
            listOf(0, 1, 2, 3, 4, 5, 16).map { BusConfig.nameFor(it) })
        assertEquals(5, bus(5).number)
        assertEquals(4, bus(3).number)
    }

    @Test
    fun `the master is shown last and the buses in number order`() {
        val buses = (0..7).map { bus(it) }
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 7, 4), BusConfig.displayOrder(buses).map { it.index })
        assertEquals(7, BusConfig.mixBusCount(buses))
    }

    @Test
    fun `only the added buses can be removed`() {
        assertTrue((0..4).none { bus(it).isRemovable })
        assertTrue((5..16).all { bus(it).isRemovable })
        assertFalse(bus(3).isMaster)
        assertTrue(bus(4).isMaster)
    }

    @Test
    fun `exports say when they carry added buses`() {
        val five = """{"buses":[{},{},{},{},{}]}"""
        val six = """{"buses":[{},{},{},{},{},{}]}"""
        assertEquals(MixPresetFile.VERSION_FIXED_BUSES, MixPresetFile.of("a", five).version)
        assertEquals(MixPresetFile.VERSION_ADDED_BUSES, MixPresetFile.of("b", six).version)
        assertEquals(MixPresetFile.VERSION_FIXED_BUSES, MixPresetFile.versionFor("not json"))
    }
}
