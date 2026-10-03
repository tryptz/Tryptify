package tf.monochrome.android.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.PluginInstance

/**
 * The mixer as written before the engine exists must be the engine's own
 * format, field for field. This pins the Kotlin side to
 * mixer_state_fixture.json; the host test state_fixture_test loads the same
 * file into the engine and checks it reads back every value.
 */
class DspStateJsonTest {

    private fun bus(index: Int, block: BusConfig.() -> BusConfig = { this }) =
        BusConfig(index = index, name = BusConfig.nameFor(index)).block()

    private fun plugin(type: SnapinType, vararg params: Pair<Int, Float>, dryWet: Float = 1f,
                       bypassed: Boolean = false, os: Int = 1) =
        PluginInstance(slotIndex = 0, typeOrdinal = type.ordinal, bypassed = bypassed,
            dryWet = dryWet, parameters = mapOf(*params), oversampling = os)

    /**
     * Seven buses, the master fifth, with sparse parameters, every plugin flag
     * and two routes: bus 1 to the master and bus 7, bus 2 to bus 6 alone.
     */
    private val fixture = listOf(
        bus(0) { copy(gainDb = -3.5f, pan = 0.25f, inputEnabled = true, sends = mapOf(6 to 0.5f, 4 to 1f), plugins = listOf(
            plugin(SnapinType.REVERB, 1 to 2.8f, 11 to 25f, dryWet = 0.8f, os = 2),
            plugin(SnapinType.GAIN, 0 to -1f),
        )) },
        bus(1) { copy(muted = true, sends = mapOf(5 to 0.25f)) },
        bus(2) { copy(soloed = true) },
        bus(3),
        bus(4) { copy(gainDb = 1.5f, plugins = listOf(plugin(SnapinType.LIMITER, 1 to -1f))) },
        bus(5) { copy(gainDb = -6f, plugins = listOf(plugin(SnapinType.EQ_10BAND, 2 to 3f, bypassed = true))) },
        bus(6),
    )

    @Test
    fun `the mirror is written exactly as the engine writes it`() {
        val expected = javaClass.classLoader!!.getResourceAsStream("mixer_state_fixture.json")!!
            .bufferedReader().readText().trim()
        assertEquals(expected, DspStateJson.encode(fixture))
    }

    @Test
    fun `a bus routed to the master alone carries no sends`() {
        val json = DspStateJson.encode(BusConfig.defaultBuses())
        assertEquals(false, json.contains("sends"))
    }
}
