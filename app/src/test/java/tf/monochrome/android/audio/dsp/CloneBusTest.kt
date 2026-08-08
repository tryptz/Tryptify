package tf.monochrome.android.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusInputSource
import tf.monochrome.android.audio.dsp.model.PluginInstance

/**
 * Covers the pure half of bus cloning: what chain gets rebuilt on the target.
 *
 * The replay itself goes through the live engine (addPlugin / setParameter /
 * …), which needs JNI, but the part that can silently get a strip wrong —
 * losing a parameter, reordering slots, exceeding the per-bus plugin cap — is
 * all decided here.
 */
class CloneBusTest {

    private fun plugin(
        slot: Int,
        type: Int,
        bypassed: Boolean = false,
        dryWet: Float = 1f,
        os: Int = 1,
        params: Map<Int, Float> = emptyMap(),
    ) = PluginInstance(
        slotIndex = slot,
        typeOrdinal = type,
        bypassed = bypassed,
        dryWet = dryWet,
        parameters = params,
        oversampling = os,
    )

    private fun bus(plugins: List<PluginInstance>) = BusConfig(
        index = 0,
        name = "Bus 1",
        gainDb = -4.5f,
        pan = 0.25f,
        inputEnabled = true,
        inputSource = BusInputSource.LineIn,
        plugins = plugins,
    )

    @Test
    fun `an empty bus clones to an empty chain`() {
        assertTrue(DspEngineManager.planClone(bus(emptyList())).isEmpty())
    }

    @Test
    fun `every per-plugin setting carries over`() {
        val source = bus(
            listOf(
                plugin(0, type = 3, bypassed = true, dryWet = 0.42f, os = 4,
                    params = mapOf(0 to 1.5f, 2 to -3f)),
                plugin(1, type = 7, dryWet = 0.75f, os = 2, params = mapOf(1 to 0.25f)),
            )
        )
        val planned = DspEngineManager.planClone(source)

        assertEquals(2, planned.size)
        assertEquals(3, planned[0].typeOrdinal)
        assertTrue(planned[0].bypassed)
        assertEquals(0.42f, planned[0].dryWet, 1e-6f)
        assertEquals(4, planned[0].oversampling)
        assertEquals(mapOf(0 to 1.5f, 2 to -3f), planned[0].parameters)

        assertEquals(7, planned[1].typeOrdinal)
        assertEquals(0.75f, planned[1].dryWet, 1e-6f)
        assertEquals(2, planned[1].oversampling)
        assertEquals(mapOf(1 to 0.25f), planned[1].parameters)
    }

    @Test
    fun `chain order is preserved`() {
        val source = bus((0..5).map { plugin(it, type = 10 + it) })
        val planned = DspEngineManager.planClone(source)
        assertEquals(listOf(10, 11, 12, 13, 14, 15), planned.map { it.typeOrdinal })
    }

    @Test
    fun `slot indices are renumbered from zero and contiguous`() {
        // A source whose slotIndex values are stale (as they can be mid-edit)
        // must still produce a clean 0..n-1 run, since the replay inserts at
        // exactly these positions.
        val source = bus(listOf(plugin(4, type = 1), plugin(9, type = 2), plugin(11, type = 3)))
        val planned = DspEngineManager.planClone(source)
        assertEquals(listOf(0, 1, 2), planned.map { it.slotIndex })
    }

    @Test
    fun `the chain is capped at the engine's per-bus plugin limit`() {
        val overfull = (0 until DspEngineManager.MAX_PLUGINS_PER_BUS + 5)
            .map { plugin(it, type = 1) }
        val planned = DspEngineManager.planClone(bus(overfull))
        assertEquals(DspEngineManager.MAX_PLUGINS_PER_BUS, planned.size)
        assertEquals(
            DspEngineManager.MAX_PLUGINS_PER_BUS - 1,
            planned.last().slotIndex,
        )
    }

    @Test
    fun `planning does not mutate the source bus`() {
        val original = plugin(4, type = 1)
        val source = bus(listOf(original))
        val planned = DspEngineManager.planClone(source)
        assertEquals(4, source.plugins[0].slotIndex)
        assertNotEquals(source.plugins[0].slotIndex, planned[0].slotIndex)
    }
}
