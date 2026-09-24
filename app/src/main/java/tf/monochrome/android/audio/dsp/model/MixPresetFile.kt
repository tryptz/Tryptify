package tf.monochrome.android.audio.dsp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * On-disk envelope for an exported DSP Mixer preset. This is what gets written
 * to / read from a `.json` file when a user shares a preset.
 *
 * [version] says which mixer the state was saved from:
 *  - [VERSION_FIXED_BUSES] — four mix buses and the master, the only shape
 *    there was before buses could be added. Every build can load it.
 *  - [VERSION_ADDED_BUSES] — carries buses 5 and up. A build from before the +
 *    tile still loads it, but keeps only buses 1–4 and the master.
 * Import accepts either; the number is there so a reader can tell what it has.
 */
@Serializable
data class MixPresetFile(
    val version: Int = VERSION_FIXED_BUSES,
    val name: String,
    val stateJson: String,
    val exportedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val VERSION_FIXED_BUSES = 1
        const val VERSION_ADDED_BUSES = 2

        /** An envelope for [stateJson], versioned by the buses it carries. */
        fun of(name: String, stateJson: String): MixPresetFile =
            MixPresetFile(version = versionFor(stateJson), name = name, stateJson = stateJson)

        fun versionFor(stateJson: String): Int {
            val entries = runCatching {
                Json.parseToJsonElement(stateJson).jsonObject["buses"]?.jsonArray?.size
            }.getOrNull() ?: 0
            return if (entries > BusConfig.MIN_MIX_BUSES + 1) VERSION_ADDED_BUSES
            else VERSION_FIXED_BUSES
        }
    }
}
