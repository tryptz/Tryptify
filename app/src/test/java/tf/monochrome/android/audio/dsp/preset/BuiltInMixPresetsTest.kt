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
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.StereoUpmixer
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.MixUpmix

/**
 * The shipped mixer presets.
 *
 * "Wide Stage" was captured out of the mixer and shipped as the engine's own
 * JSON until [MixPresetBuilder] could express it. The captured string lives on
 * here as the reference: the preset has since been rewired onto the console
 * every preset shares (a send instead of a second input, the master at 0 dB),
 * and the test pins both what it kept and what it changed.
 *
 * The rest hold the catalog to how it is built: master faders at 0 dB, a
 * limiter at the ceiling last on every master, routes the engine will take,
 * nothing processed and then dropped, and returns that are fully wet.
 */
class BuiltInMixPresetsTest {

    private fun buses(stateJson: String): JsonArray =
        Json.parseToJsonElement(stateJson).jsonObject["buses"]!!.jsonArray

    private fun JsonObject.f(key: String) = this[key]!!.jsonPrimitive.float
    private fun JsonObject.b(key: String) = this[key]!!.jsonPrimitive.boolean

    private val MASTER = BusConfig.MASTER_INDEX
    private val WET = 2

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
    fun `Wide Stage is the saved patch, rewired onto a send with the master at 0 dB`() {
        val preset = BuiltInMixPresets.presets.single { it.name == "Wide Stage" }
        assertEquals(-8L, preset.id)
        val saved = buses(WIDE_STAGE_AS_SAVED)
        val built = buses(preset.stateJson)

        // What makes it this patch, unchanged: the same processors with the
        // same values on the dry and the wet side, the dry ones still parked
        // (bypassed), and the wet fader where it was. The wet side moved from
        // bus 2 to bus 3, where every preset keeps its returns.
        for ((was, now) in listOf(0 to 0, 1 to WET)) {
            assertEquals("bus ${was + 1} processors",
                normalize(saved[was].jsonObject["plugins"]!!), normalize(built[now].jsonObject["plugins"]!!))
            assertEquals("bus ${was + 1} fader", saved[was].jsonObject.f("gain"), built[now].jsonObject.f("gain"), 1e-5f)
        }
        val dry = built[0].jsonObject["plugins"]!!.jsonArray
        assertTrue("dry processors stay bypassed", dry.all { it.jsonObject.b("bypassed") })

        // What changed, on purpose: the wet bus is fed by a send from the dry
        // one instead of taking the track itself, and the +4.6 dB master trim
        // is gone — the master sits at 0 dB with the ceiling on it.
        assertTrue("dry bus takes input", built[0].jsonObject.b("inputEnabled"))
        assertFalse("wet bus is fed by a send", built[WET].jsonObject.b("inputEnabled"))
        assertEquals(mapOf(MASTER to 1f, WET to 1f), sends(0, built[0].jsonObject))
        assertEquals("bus 2 is empty now", 0, built[1].jsonObject["plugins"]!!.jsonArray.size)
        assertEquals(0f, built[MASTER].jsonObject.f("gain"), 0f)
        assertEquals(4.61484f, saved[MASTER].jsonObject.f("gain"), 1e-5f)
    }

    @Test
    fun `every master fader is at 0 dB`() {
        for (preset in BuiltInMixPresets.presets) {
            assertEquals("${preset.name} master", 0f, buses(preset.stateJson)[MASTER].jsonObject.f("gain"), 0f)
        }
    }

    @Test
    fun `every master ends on a limiter at the ceiling`() {
        for (preset in BuiltInMixPresets.presets) {
            val chain = buses(preset.stateJson)[MASTER].jsonObject["plugins"]!!.jsonArray
            assertTrue("${preset.name} has a master chain", chain.isNotEmpty())
            val last = chain.last().jsonObject
            assertEquals("${preset.name} ends on a limiter", SnapinType.LIMITER.ordinal, last["type"]!!.jsonPrimitive.int)
            assertFalse(last.b("bypassed"))
            val params = last["params"]!!.jsonArray
            assertEquals("${preset.name} ceiling", BuiltInMixPresets.CEILING_DB,
                params[LimiterP.THRESHOLD].jsonPrimitive.float, 0f)
            assertEquals("${preset.name} output trim", 0f, params[LimiterP.OUTPUT_GAIN].jsonPrimitive.float, 0f)
        }
    }

    @Test
    fun `every route is one the engine will take`() {
        for (preset in BuiltInMixPresets.presets) {
            val buses = buses(preset.stateJson)
            val graph = buses.indices.associateWith { sends(it, buses[it].jsonObject) }
            for ((src, routes) in graph) {
                for ((dst, level) in routes) {
                    assertTrue("${preset.name}: bus $src → $dst is outside the mix", dst in buses.indices)
                    assertTrue("${preset.name}: bus $src routes to itself", dst != src)
                    assertTrue("${preset.name}: bus $src → $dst at $level", level > 0f && level <= 1f)
                }
            }
            // No loops: the engine refuses a route that closes one, and would
            // drop it from the preset as it loads.
            val visiting = mutableSetOf<Int>()
            val done = mutableSetOf<Int>()
            fun visit(b: Int) {
                assertFalse("${preset.name}: a loop through bus $b", b in visiting)
                if (b in done) return
                visiting += b
                graph[b].orEmpty().keys.forEach(::visit)
                visiting -= b
                done += b
            }
            graph.keys.forEach(::visit)
        }
    }

    @Test
    fun `nothing in a stereo preset is processed and then lost`() {
        for (preset in BuiltInMixPresets.presets.filter { !MixUpmix.isOn(it.stateJson) }) {
            val buses = buses(preset.stateJson)
            val graph = buses.indices.associateWith { sends(it, buses[it].jsonObject) }
            // Live: takes the track, or something live sends to it.
            val live = buses.indices.filter { buses[it].jsonObject.b("inputEnabled") }.toMutableSet()
            var grew = true
            while (grew) {
                grew = false
                for ((src, routes) in graph) if (src in live) for (dst in routes.keys) if (live.add(dst)) grew = true
            }
            assertTrue("${preset.name}: the master is reached", MASTER in live)
            for (i in buses.indices) {
                if (i == MASTER) continue
                val plugins = buses[i].jsonObject["plugins"]!!.jsonArray
                if (plugins.isEmpty()) continue
                assertTrue("${preset.name}: bus $i has processors but nothing feeds it", i in live)
                assertTrue("${preset.name}: bus $i is processed but goes nowhere", graph[i].orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun `effects on a return are fully wet and reverbs leave the low end alone`() {
        for (preset in BuiltInMixPresets.presets) {
            for (bus in buses(preset.stateJson)) {
                val o = bus.jsonObject
                if (o.b("inputEnabled")) continue // a return is fed by sends
                for (plugin in o["plugins"]!!.jsonArray) {
                    val p = plugin.jsonObject
                    val params = p["params"]!!.jsonArray.map { it.jsonPrimitive.float }
                    when (p["type"]!!.jsonPrimitive.int) {
                        SnapinType.REVERB.ordinal -> {
                            assertEquals("${preset.name} reverb mix", 100f, params[ReverbP.MIX], 0f)
                            assertTrue("${preset.name} reverb low cut ${params[ReverbP.LOW_CUT]}",
                                params[ReverbP.LOW_CUT] >= 200f)
                        }
                        SnapinType.DELAY.ordinal ->
                            assertEquals("${preset.name} delay mix", 100f, params[DelayP.MIX], 0f)
                        SnapinType.CHORUS.ordinal ->
                            assertEquals("${preset.name} chorus mix", 100f, params[ChorusP.MIX], 0f)
                        SnapinType.DISTORTION.ordinal ->
                            assertEquals("${preset.name} distortion mix", 100f, params[DistortionP.MIX], 0f)
                    }
                }
            }
        }
    }

    @Test
    fun `only the Atmos preset turns the upmix on, and it fills nine strips`() {
        for (preset in BuiltInMixPresets.presets) {
            assertEquals(preset.name, preset.id == BuiltInMixPresets.ATMOS_UPMIX_ID, MixUpmix.isOn(preset.stateJson))
        }
        val atmos = BuiltInMixPresets.presets.single { it.id == BuiltInMixPresets.ATMOS_UPMIX_ID }
        val buses = buses(atmos.stateJson)
        // Nine channel groups on buses 1–9: engine indices 0–3 and 5–9.
        assertEquals(10, buses.size)
        // The LFE strip (bus 3) keeps the sub to bass: a low-pass at or under
        // the crossover.
        val lfe = buses[2].jsonObject["plugins"]!!.jsonArray.single().jsonObject
        assertEquals(SnapinType.FILTER.ordinal, lfe["type"]!!.jsonPrimitive.int)
        val params = lfe["params"]!!.jsonArray.map { it.jsonPrimitive.float }
        assertEquals(FilterP.LOW_PASS, params[FilterP.TYPE], 0f)
        assertTrue(params[FilterP.CUTOFF] <= StereoUpmixer.CROSSOVER_HZ)
        // The key is for Kotlin alone: stripped, it is plain engine state.
        assertFalse(MixUpmix.strip(atmos.stateJson).contains("upmix"))
    }

    /**
     * Bus [index]'s routes as the engine reads them: absent means the master
     * alone, except on the master, which sends nowhere.
     */
    private fun sends(index: Int, bus: JsonObject): Map<Int, Float> {
        if (index == MASTER) return emptyMap()
        val flat = bus["sends"]?.jsonArray ?: return mapOf(MASTER to 1f)
        return flat.chunked(2).associate { (d, l) -> d.jsonPrimitive.int to l.jsonPrimitive.float }
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
