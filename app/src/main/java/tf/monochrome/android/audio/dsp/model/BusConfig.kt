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
     * The channel group of a multichannel stream this bus carries right now
     * ("Centre", "Top Front"…), or null. Set only while such a stream plays;
     * never saved.
     */
    val channelGroup: String? = null,
    /**
     * Where this bus's post-fader signal goes: destination bus index
     * ([MASTER_INDEX] for the master) to linear send level 0..1, mirroring the
     * engine's routing. A mix bus goes to the master alone until routed
     * elsewhere; the master sends nowhere.
     */
    val sends: Map<Int, Float> = if (index == MASTER_INDEX) emptyMap() else DEFAULT_SENDS,
) {
    /** Routed anywhere other than the master alone. */
    val hasCustomSends: Boolean get() = !isMaster && sends != DEFAULT_SENDS

    val isMaster: Boolean get() = index == MASTER_INDEX

    /**
     * Buses 1–4 and the master stay; bus 5 and up can be removed, except
     * while a channel group is routed to it.
     */
    val isRemovable: Boolean get() = index > MASTER_INDEX && channelGroup == null

    /** The number on the strip: 1–48, whatever the index behind it. */
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
        const val MAX_MIX_BUSES = 48

        /** A mix bus's routing until it is changed: the master, at unity. */
        val DEFAULT_SENDS: Map<Int, Float> = mapOf(MASTER_INDEX to 1f)

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
