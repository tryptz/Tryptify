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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped mixer presets.
 *
 * Most of these are built by [MixPresetBuilder], which cannot produce malformed
 * state. "Wide Stage" is not: it is a patch captured out of the mixer and kept
 * as the engine's own JSON, so nothing between here and the native parser would
 * notice if a character of it were lost to an edit or a bad merge. The engine
 * answers a broken preset by silently doing nothing, so it is pinned here.
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
            // The engine's state is a fixed four mix buses plus a master.
            assertEquals("${preset.name} bus count", 5, buses.size)
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
        val buses = buses(preset.stateJson)

        // Two buses take input. That parallel dry/wet split is the preset, and
        // it is the exact thing MixPresetBuilder cannot express -- it derives
        // inputEnabled from the bus index, so a "port this to the DSL" change
        // would quietly collapse it to the dry bus alone.
        assertTrue("dry bus takes input", buses[0].jsonObject.b("inputEnabled"))
        assertTrue("wet bus takes input", buses[1].jsonObject.b("inputEnabled"))
        assertFalse(buses[2].jsonObject.b("inputEnabled"))
        assertFalse(buses[3].jsonObject.b("inputEnabled"))

        // Dry side: two processors present but switched off. The builder writes
        // bypassed = false unconditionally, so this is the other thing it would
        // lose -- and turning these on changes the sound.
        val dry = buses[0].jsonObject["plugins"]!!.jsonArray
        assertEquals(2, dry.size)
        assertTrue("dry processors stay bypassed", dry.all { it.jsonObject.b("bypassed") })
        assertEquals(-0.0919491f, buses[0].jsonObject.f("gain"), 1e-6f)

        // Wet side: Reverb (17) -> Stereo (1) -> Gain (0), in that order.
        val wet = buses[1].jsonObject["plugins"]!!.jsonArray
        assertEquals(listOf(17, 1, 0), wet.map { it.jsonObject["type"]!!.jsonPrimitive.int })
        assertTrue("wet processors are active", wet.none { it.jsonObject.b("bypassed") })
        assertEquals(8.15997f, buses[1].jsonObject.f("gain"), 1e-5f)
        assertEquals(
            listOf(0f, 2.81718f, 34.0753f, 94.4716f, 34.6712f, 0.05f, 68.7378f, 10863f, 386.575f, 0f, 100f, 100f),
            wet[0].jsonObject["params"]!!.jsonArray.map { it.jsonPrimitive.float },
        )

        assertEquals("master trim", 4.61484f, buses[4].jsonObject.f("gain"), 1e-5f)
    }
}
