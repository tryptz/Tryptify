package tf.monochrome.android.audio.dsp.model

import kotlinx.serialization.Serializable

@Serializable
data class BusConfig(
    val index: Int,
    val name: String,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val inputEnabled: Boolean = false,
    val plugins: List<PluginInstance> = emptyList(),
    /**
     * Where this strip's post-fader signal goes: destination bus index
     * ([MASTER_INDEX] for the master) to linear send level 0..1. Mirrors the
     * native send matrix; the master itself sends nowhere.
     */
    val sends: Map<Int, Float> = if (index < NUM_MIX_STRIPS) mapOf(MASTER_INDEX to 1f) else emptyMap(),
) {
    val isMaster: Boolean get() = index == MASTER_INDEX

    companion object {
        /** Mirrors NUM_MIX_BUSES / MASTER_BUS / TOTAL_BUSES in dsp_engine.h. */
        const val NUM_MIX_STRIPS = 48
        const val MASTER_INDEX = NUM_MIX_STRIPS
        const val TOTAL_BUSES = NUM_MIX_STRIPS + 1

        fun defaultName(index: Int): String =
            if (index == MASTER_INDEX) "Master" else "Bus ${index + 1}"

        fun defaultBus(index: Int): BusConfig =
            BusConfig(index = index, name = defaultName(index), inputEnabled = index == 0)

        fun defaultBuses(): List<BusConfig> = List(TOTAL_BUSES) { defaultBus(it) }
    }
}
