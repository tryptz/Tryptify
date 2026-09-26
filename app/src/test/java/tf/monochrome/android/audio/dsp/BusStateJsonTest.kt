package tf.monochrome.android.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.preset.BuiltInMixPresets

/**
 * The Kotlin reading of saved mixer state must land every bus where native
 * loadStateJson lands it — otherwise the strips show one mix while the engine
 * plays another.
 */
class BusStateJsonTest {

    private fun bus(gain: Float, input: Boolean, sends: String? = null) =
        """{"gain":$gain,"pan":0,"muted":false,"soloed":false,"inputEnabled":$input""" +
            (sends?.let { ""","sends":[$it]""" } ?: "") + ""","plugins":[]}"""

    @Test
    fun `old four-strip save keeps its master on the master`() {
        val json = """{"buses":[${bus(1f, true)},${bus(2f, false)},${bus(3f, false)},${bus(4f, false)},${bus(-6f, false)}]}"""
        val buses = BusStateJson.parse(json)!!
        assertEquals(BusConfig.TOTAL_BUSES, buses.size)
        assertEquals(-6f, buses[BusConfig.MASTER_INDEX].gainDb)
        assertTrue(buses[BusConfig.MASTER_INDEX].isMaster)
        assertEquals(4f, buses[3].gainDb)
        // Strips the save never had come up as defaults, routed to master.
        assertEquals(0f, buses[4].gainDb)
        assertEquals(mapOf(BusConfig.MASTER_INDEX to 1f), buses[4].sends)
        assertEquals(mapOf(BusConfig.MASTER_INDEX to 1f), buses[0].sends)
        assertTrue(buses[BusConfig.MASTER_INDEX].sends.isEmpty())
        buses.forEachIndexed { i, b -> assertEquals(i, b.index) }
    }

    @Test
    fun `routes are read with master written as -1`() {
        val strips = (0 until BusConfig.NUM_MIX_STRIPS).joinToString(",") { i ->
            when (i) {
                0 -> bus(0f, true, "5,0.5,-1,1")
                5 -> bus(0f, false, "")
                else -> bus(0f, false, "-1,1")
            }
        }
        val buses = BusStateJson.parse("""{"buses":[$strips,${bus(0f, false)}]}""")!!
        assertEquals(mapOf(5 to 0.5f, BusConfig.MASTER_INDEX to 1f), buses[0].sends)
        assertTrue("explicitly unrouted strip stays unrouted", buses[5].sends.isEmpty())
    }

    @Test
    fun `every built-in preset parses to the full mixer`() {
        for (preset in BuiltInMixPresets.presets) {
            val buses = BusStateJson.parse(preset.stateJson)
            assertEquals(preset.name, BusConfig.TOTAL_BUSES, buses?.size)
        }
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(BusStateJson.parse("not json"))
    }
}
