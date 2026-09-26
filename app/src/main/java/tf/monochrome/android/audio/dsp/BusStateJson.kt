package tf.monochrome.android.audio.dsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.PluginInstance

/**
 * The Kotlin mirror of the native engine's saved state (getStateJson /
 * loadStateJson in dsp_engine.cpp), read the same way native reads it so the
 * UI shows what the engine actually loaded. Pure, so it is unit-tested.
 */
internal object BusStateJson {

    private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

    /** Always [BusConfig.TOTAL_BUSES] buses, or null if [json] isn't state. */
    fun parse(json: String): List<BusConfig>? {
        return try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val root = jsonParser.parseToJsonElement(json).jsonObject
            val busesArray = root["buses"]?.jsonArray ?: return BusConfig.defaultBuses()

            // Same mapping as native loadStateJson: the LAST listed bus is the
            // master and the rest fill the strips from 0, so a save or preset
            // with fewer buses (the old 4 strips + master) keeps its master.
            val parsed = busesArray.take(BusConfig.TOTAL_BUSES * 2)
            val byIndex = parsed.mapIndexed { position, element ->
                val index = if (position == parsed.lastIndex) BusConfig.MASTER_INDEX else position
                val obj = element.jsonObject
                val plugins = obj["plugins"]?.jsonArray?.mapIndexed { slotIdx, plugEl ->
                    val plugObj = plugEl.jsonObject
                    val typeOrd = plugObj["type"]?.jsonPrimitive?.int ?: 0
                    val bypassed = plugObj["bypassed"]?.jsonPrimitive?.boolean ?: false
                    val dryWet = (plugObj["dryWet"]?.jsonPrimitive?.float ?: 1f)
                        .let { if (it.isFinite()) it else 1f }
                        .coerceIn(DspEngineManager.MIN_DRY_WET, DspEngineManager.MAX_DRY_WET)
                    val params = plugObj["params"]?.jsonArray
                        ?.mapIndexed { pi, pv -> pi to finiteOrZero(pv.jsonPrimitive.float) }
                        ?.toMap() ?: emptyMap()
                    val os = when (plugObj["os"]?.jsonPrimitive?.int ?: 1) {
                        4 -> 4; 2 -> 2; else -> 1
                    }
                    PluginInstance(slotIdx, typeOrd, bypassed, dryWet, params, os)
                } ?: emptyList()

                val rawGain = obj["gain"]?.jsonPrimitive?.float ?: 0f
                val rawPan = obj["pan"]?.jsonPrimitive?.float ?: 0f
                // [dst, level, ...], master written as -1; absent = old save,
                // routed to master alone.
                val sends = obj["sends"]?.jsonArray?.let { flat ->
                    flat.chunked(2).mapNotNull { pair ->
                        if (pair.size < 2) return@mapNotNull null
                        val d = pair[0].jsonPrimitive.float.toInt()
                        val lv = pair[1].jsonPrimitive.float
                        val dst = if (d < 0) BusConfig.MASTER_INDEX else d
                        if (!lv.isFinite() || lv <= 0f || dst == index || dst !in 0 until BusConfig.TOTAL_BUSES) null
                        else dst to lv.coerceAtMost(1f)
                    }.toMap()
                }
                index to BusConfig(
                    index = index,
                    name = BusConfig.defaultName(index),
                    gainDb = (if (rawGain.isFinite()) rawGain else 0f)
                        .coerceIn(DspEngineManager.MIN_BUS_GAIN_DB, DspEngineManager.MAX_BUS_GAIN_DB),
                    pan = (if (rawPan.isFinite()) rawPan else 0f).coerceIn(DspEngineManager.MIN_PAN, DspEngineManager.MAX_PAN),
                    muted = obj["muted"]?.jsonPrimitive?.boolean ?: false,
                    soloed = obj["soloed"]?.jsonPrimitive?.boolean ?: false,
                    inputEnabled = obj["inputEnabled"]?.jsonPrimitive?.boolean ?: (index == 0),
                    plugins = plugins,
                    sends = when {
                        index == BusConfig.MASTER_INDEX -> emptyMap()
                        sends != null -> sends
                        else -> mapOf(BusConfig.MASTER_INDEX to 1f)
                    }
                )
            }.filter { (index, _) -> index < BusConfig.TOTAL_BUSES }.toMap()
            List(BusConfig.TOTAL_BUSES) { byIndex[it] ?: BusConfig.defaultBus(it) }
        } catch (e: Exception) {
            null
        }
    }
}
