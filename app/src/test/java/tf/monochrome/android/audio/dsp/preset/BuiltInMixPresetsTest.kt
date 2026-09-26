package tf.monochrome.android.audio.dsp.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.model.BusConfig

/**
 * The shipped mixer presets.
 *
 * "Wide Stage" was captured out of the mixer and shipped as the engine's own
 * JSON until [MixPresetBuilder] could express it. It is built now, and the
 * captured string lives on here as the reference: the built preset has to say
 * exactly what the saved patch said, or it no longer sounds like it.
 */
class BuiltInMixPresetsTest {

    private fun buses(stateJson: String): JsonArray =
        Json.parseToJsonElement(stateJson).jsonObject["buses"]!!.jsonArray

    private fun JsonObject.f(key: String) = this[key]!!.jsonPrimitive.float
    private fun JsonObject.b(key: String) = this[key]!!.jsonPrimitive.boolean

    @Test
    fun `every preset has a unique negative id and a name`() {
        val presets = BuiltInMixPresets.presets
        assertTrue("there should be presets to ship", presets.isNotEmpty())
        // Negative so they never collide with Room's positive autoincrement,
        // which is what keeps a user's own preset from shadowing one of these.
        assertTrue("ids must be negative", presets.all { it.id < 0 })
        assertEquals("ids must be unique", presets.size, presets.map { it.id }.toSet().size)
        assertEquals("names must be unique", presets.size, presets.map { it.name }.toSet().size)
        assertTrue("names must not be blank", presets.none { it.name.isBlank() })
        // Read-only in the UI: loadable and exportable, not deletable.
        assertTrue("built-ins are not custom", presets.none { it.isCustom })
    }

    @Test
    fun `every preset is state the engine can load`() {
        for (preset in BuiltInMixPresets.presets) {
            val buses = buses(preset.stateJson)
            // Buses 1-4 and the master at least, and never more than the
            // engine's mix buses plus the master.
            assertTrue("${preset.name} bus count ${buses.size}", buses.size in 5..BusConfig.MAX_TOTAL_BUSES)
            for (bus in buses) {
                val o = bus.jsonObject
                listOf("gain", "pan", "muted", "soloed", "inputEnabled", "plugins")
                    .forEach { assertTrue("${preset.name} missing $it", o.containsKey(it)) }
                for (plugin in o["plugins"]!!.jsonArray) {
                    val p = plugin.jsonObject
                    assertTrue("${preset.name} plugin type", p.containsKey("type"))
                    assertTrue("${preset.name} plugin params", p["params"]!!.jsonArray.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `Wide Stage is the patch that was saved`() {
        val preset = BuiltInMixPresets.presets.single { it.name == "Wide Stage" }
        assertEquals(-8L, preset.id)
        // Same JSON tree, compared as numbers so 0 and 0.0 are the same value.
        assertEquals(normalize(Json.parseToJsonElement(WIDE_STAGE_AS_SAVED)),
                     normalize(Json.parseToJsonElement(preset.stateJson)))

        // And the two things that make it this patch, spelled out: two buses
        // take input (a dry and a wet path in parallel), and the dry side's
        // processors are present but switched off.
        val buses = buses(preset.stateJson)
        assertTrue("dry bus takes input", buses[0].jsonObject.b("inputEnabled"))
        assertTrue("wet bus takes input", buses[1].jsonObject.b("inputEnabled"))
        assertFalse(buses[2].jsonObject.b("inputEnabled"))
        assertFalse(buses[3].jsonObject.b("inputEnabled"))
        val dry = buses[0].jsonObject["plugins"]!!.jsonArray
        assertTrue("dry processors stay bypassed", dry.all { it.jsonObject.b("bypassed") })
        val wet = buses[1].jsonObject["plugins"]!!.jsonArray
        assertEquals(listOf(17, 1, 0), wet.map { it.jsonObject["type"]!!.jsonPrimitive.int })
        assertEquals("master trim", 4.61484f, buses[4].jsonObject.f("gain"), 1e-5f)
    }

    private fun normalize(e: JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValues { normalize(it.value) }
        is JsonArray -> e.map { normalize(it) }
        is JsonPrimitive -> if (e.isString) e.content
            else e.booleanOrNull ?: e.content.toFloat()
        else -> null
    }

    private companion object {
        /** The patch exactly as the mixer saved it, before it moved to the builder. */
        const val WIDE_STAGE_AS_SAVED =
            """{"buses":[""" +
                """{"gain":-0.0919491,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,"plugins":[{"type":23,"bypassed":true,"dryWet":1,"os":1,"params":[1,10.3143]},{"type":1,"bypassed":true,"dryWet":1,"os":1,"params":[-2.1135,4.39726,0]}]},""" +
                """{"gain":8.15997,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,"plugins":[{"type":17,"bypassed":false,"dryWet":1,"os":1,"params":[0,2.81718,34.0753,94.4716,34.6712,0.05,68.7378,10863,386.575,0,100,100]},{"type":1,"bypassed":false,"dryWet":1,"os":1,"params":[-13.8023,0,0]},{"type":0,"bypassed":false,"dryWet":1,"os":1,"params":[5.59187]}]},""" +
                """{"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},""" +
                """{"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},""" +
                """{"gain":4.61484,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]}""" +
                """]}"""
    }
}
