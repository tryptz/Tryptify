package tf.monochrome.android.audio.dsp.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.SnapinType

/**
 * The engine reads a preset positionally: entry 4 is the master and the
 * number of entries sets how many mix buses there are. These hold the builder
 * to that, now that a preset can reach past bus 4.
 */
class MixPresetBuilderTest {

    private fun buses(json: String): JsonArray =
        Json.parseToJsonElement(json).jsonObject["buses"]!!.jsonArray

    private fun JsonArray.bus(i: Int): JsonObject = this[i].jsonObject

    @Test
    fun `a preset on buses 1-4 stays the classic five entries`() {
        val json = MixPresetBuilder.build {
            bus(2) { plugin(SnapinType.GAIN) }
            master(gainDb = -1f)
        }
        val buses = buses(json)
        assertEquals(5, buses.size)
        assertEquals(-1f, buses.bus(4)["gain"]!!.jsonPrimitive.float, 0f)
        assertEquals(1, buses.bus(2)["plugins"]!!.jsonArray.size)
    }

    @Test
    fun `an added bus extends the list, with the gap filled and the master still fifth`() {
        val json = MixPresetBuilder.build {
            bus(7, gainDb = 3f) { plugin(SnapinType.REVERB, ReverbP.MIX to 40f) }
        }
        val buses = buses(json)
        // Entries 0..7: buses 1-4, master, then buses 5, 6 (empty) and 7.
        assertEquals(8, buses.size)
        assertEquals(3f, buses.bus(7)["gain"]!!.jsonPrimitive.float, 0f)
        assertTrue(buses.bus(5)["plugins"]!!.jsonArray.isEmpty())
        assertTrue(buses.bus(6)["plugins"]!!.jsonArray.isEmpty())
        assertTrue(buses.bus(4)["plugins"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `bus 16 is the last one there is`() {
        assertEquals(17, buses(MixPresetBuilder.build { bus(16) {} }).size)
        assertThrows(IllegalArgumentException::class.java) {
            MixPresetBuilder.build { bus(17) {} }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MixPresetBuilder.build { bus(-1) {} }
        }
    }

    @Test
    fun `input, mute, solo, bypass and oversampling are written as set`() {
        val json = MixPresetBuilder.build {
            bus(5) {
                inputEnabled = true
                muted = true
                soloed = true
                plugin(SnapinType.COMPRESSOR, bypassed = true, oversample = 4)
            }
        }
        val bus5 = buses(json).bus(5)
        assertTrue(bus5["inputEnabled"]!!.jsonPrimitive.boolean)
        assertTrue(bus5["muted"]!!.jsonPrimitive.boolean)
        assertTrue(bus5["soloed"]!!.jsonPrimitive.boolean)
        val plugin = bus5["plugins"]!!.jsonArray[0].jsonObject
        assertTrue(plugin["bypassed"]!!.jsonPrimitive.boolean)
        assertEquals(4, plugin["os"]!!.jsonPrimitive.int)
        assertThrows(IllegalArgumentException::class.java) {
            MixPresetBuilder.build { bus(0) { plugin(SnapinType.GAIN, oversample = 3) } }
        }
    }

    @Test
    fun `only bus 1 takes input unless told otherwise, and never the master`() {
        val buses = buses(MixPresetBuilder.build {
            bus(6) {}
            master { inputEnabled = true }
        })
        assertTrue(buses.bus(0)["inputEnabled"]!!.jsonPrimitive.boolean)
        for (i in 1 until buses.size) {
            assertFalse("entry $i", buses.bus(i)["inputEnabled"]!!.jsonPrimitive.boolean)
        }
    }
}
