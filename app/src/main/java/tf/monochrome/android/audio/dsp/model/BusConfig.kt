package tf.monochrome.android.audio.dsp.model

import kotlinx.serialization.Serializable

/**
 * Which of the engine's two stereo inputs a mix bus reads.
 *
 * Ordinals are the wire format — they are written into `dsp_state_json` and
 * passed straight to `nativeSetBusInputSource`, where they must line up with
 * the `BusInputSource` enum in `cpp/dsp/dsp_engine.h`. Append only.
 */
@Serializable
enum class BusInputSource(val label: String, val blurb: String) {
    /** The decoded track — what every bus did before line-in existed. */
    Playback("Play", "Reads the track being played"),

    /** The captured hardware input. */
    LineIn("Line", "Reads the stereo line input"),

    /** Both, summed. */
    Both("Both", "Reads the track and the line input together"),
    ;

    companion object {
        fun fromOrdinal(value: Int): BusInputSource =
            entries.getOrElse(value) { Playback }
    }
}

@Serializable
data class BusConfig(
    val index: Int,
    val name: String,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val inputEnabled: Boolean = false,
    val inputSource: BusInputSource = BusInputSource.Playback,
    val plugins: List<PluginInstance> = emptyList()
) {
    val isMaster: Boolean get() = index == 4

    companion object {
        fun defaultBuses(): List<BusConfig> = listOf(
            BusConfig(index = 0, name = "Bus 1", inputEnabled = true),
            BusConfig(index = 1, name = "Bus 2"),
            BusConfig(index = 2, name = "Bus 3"),
            BusConfig(index = 3, name = "Bus 4"),
            BusConfig(index = 4, name = "Master")
        )
    }
}
