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
    val plugins: List<PluginInstance> = emptyList()
) {
    val isMaster: Boolean get() = index == MASTER_INDEX

    /** Buses 1–4 and the master stay; bus 5 and up can be removed. */
    val isRemovable: Boolean get() = index > MASTER_INDEX

    /** The number on the strip: 1–16, whatever the index behind it. */
    val number: Int get() = numberFor(index)

    companion object {
        /**
         * The master's index, fixed since the first build: every saved mix and
         * preset has it fifth. Mix buses 1–4 are indices 0–3 and buses added
         * later take 5, 6, … — so the list stays in index order while the
         * screen shows the master last (see [displayOrder]).
         */
        const val MASTER_INDEX = 4
        const val MIN_MIX_BUSES = 4
        const val MAX_MIX_BUSES = 16

        /** Every bus a mix can hold, master included. */
        const val MAX_TOTAL_BUSES = MAX_MIX_BUSES + 1

        fun numberFor(index: Int): Int = if (index < MASTER_INDEX) index + 1 else index

        fun nameFor(index: Int): String =
            if (index == MASTER_INDEX) "Master" else "Bus ${numberFor(index)}"

        /** Mix buses in number order, then the master. */
        fun displayOrder(buses: List<BusConfig>): List<BusConfig> =
            buses.filter { !it.isMaster }.sortedBy { it.index } + buses.filter { it.isMaster }

        fun mixBusCount(buses: List<BusConfig>): Int = buses.count { !it.isMaster }

        fun defaultBuses(): List<BusConfig> = listOf(
            BusConfig(index = 0, name = nameFor(0), inputEnabled = true),
            BusConfig(index = 1, name = nameFor(1)),
            BusConfig(index = 2, name = nameFor(2)),
            BusConfig(index = 3, name = nameFor(3)),
            BusConfig(index = MASTER_INDEX, name = nameFor(MASTER_INDEX))
        )
    }
}
