package tf.monochrome.android.audio.dsp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DspEngineManager @Inject constructor(
    private val processor: MixBusProcessor,
    private val preferences: PreferencesManager
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _buses = MutableStateFlow(BusConfig.defaultBuses())
    val buses: StateFlow<List<BusConfig>> = _buses.asStateFlow()

    // Meter levels — polled from UI at ~50ms intervals
    // [peakL, peakR, holdL, holdR] per bus = 4 floats each
    private val levelsBuffer = FloatArray(TOTAL_BUSES * 4)
    private val _busLevels = MutableStateFlow(List(TOTAL_BUSES) { BusLevels() })
    val busLevels: StateFlow<List<BusLevels>> = _busLevels.asStateFlow()

    private val _clipped = MutableStateFlow(false)
    val clipped: StateFlow<Boolean> = _clipped.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            saveSignal.debounce(500L).collect {
                val json = getStateJson()
                if (json != "{}") preferences.setDspStateJson(json)
            }
        }
    }

    private fun requestSave() { saveSignal.tryEmit(Unit) }

    fun pollLevels() {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        processor.nativeGetBusLevels(ptr, levelsBuffer)
        _busLevels.value = List(TOTAL_BUSES) { b ->
            BusLevels(
                peakDbL = levelsBuffer[b * 4],
                peakDbR = levelsBuffer[b * 4 + 1],
                holdDbL = levelsBuffer[b * 4 + 2],
                holdDbR = levelsBuffer[b * 4 + 3]
            )
        }
        // Check clipping
        if (processor.nativeGetAndResetClipped(ptr)) {
            _clipped.value = true
        }
    }

    fun resetClipIndicator() {
        _clipped.value = false
    }

    companion object {
        private const val TOTAL_BUSES = 5
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        // Bypass mix bus plugins when mixer DSP is off; master bus (AutoEQ) keeps running
        processor.setMixBypassed(!enabled)
        scope.launch { preferences.setDspEnabled(enabled) }
    }

    suspend fun restoreState() {
        val enabled = preferences.dspEnabled.first()
        val stateJson = preferences.dspStateJson.first()

        if (!stateJson.isNullOrEmpty() && stateJson != "{}") {
            loadStateJson(stateJson)
        }

        _enabled.value = enabled
        processor.setMixBypassed(!enabled)
    }

    // ── Bus controls ────────────────────────────────────────────────────

    fun setBusGain(busIndex: Int, gainDb: Float) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusGain(ptr, busIndex, gainDb)
        updateBus(busIndex) { it.copy(gainDb = gainDb) }
        requestSave()
    }

    fun setBusPan(busIndex: Int, pan: Float) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusPan(ptr, busIndex, pan)
        updateBus(busIndex) { it.copy(pan = pan) }
        requestSave()
    }

    fun setBusMute(busIndex: Int, muted: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusMute(ptr, busIndex, muted)
        updateBus(busIndex) { it.copy(muted = muted) }
        requestSave()
    }

    fun setBusInputEnabled(busIndex: Int, enabled: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusInputEnabled(ptr, busIndex, enabled)
        updateBus(busIndex) { it.copy(inputEnabled = enabled) }
        requestSave()
    }

    fun setBusSolo(busIndex: Int, soloed: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusSolo(ptr, busIndex, soloed)
        updateBus(busIndex) { it.copy(soloed = soloed) }
        requestSave()
    }

    // ── Plugin chain ────────────────────────────────────────────────────

    fun addPlugin(busIndex: Int, slotIndex: Int, type: SnapinType): Int {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return -1
        val resultSlot = processor.nativeAddPlugin(ptr, busIndex, slotIndex, type.ordinal)
        if (resultSlot >= 0) {
            updateBus(busIndex) { bus ->
                val plugins = bus.plugins.toMutableList()
                plugins.add(resultSlot, PluginInstance(
                    slotIndex = resultSlot,
                    typeOrdinal = type.ordinal
                ))
                // Re-index
                bus.copy(plugins = plugins.mapIndexed { i, p -> p.copy(slotIndex = i) })
            }
            requestSave()
        }
        return resultSlot
    }

    fun removePlugin(busIndex: Int, slotIndex: Int) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeRemovePlugin(ptr, busIndex, slotIndex)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins.removeAt(slotIndex)
            }
            bus.copy(plugins = plugins.mapIndexed { i, p -> p.copy(slotIndex = i) })
        }
        requestSave()
    }

    fun movePlugin(busIndex: Int, fromSlot: Int, toSlot: Int) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeMovePlugin(ptr, busIndex, fromSlot, toSlot)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (fromSlot in plugins.indices && toSlot in plugins.indices) {
                val plugin = plugins.removeAt(fromSlot)
                plugins.add(toSlot, plugin)
            }
            bus.copy(plugins = plugins.mapIndexed { i, p -> p.copy(slotIndex = i) })
        }
        requestSave()
    }

    fun setParameter(busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetParameter(ptr, busIndex, slotIndex, paramIndex, value)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                val plugin = plugins[slotIndex]
                plugins[slotIndex] = plugin.copy(
                    parameters = plugin.parameters + (paramIndex to value)
                )
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    fun setPluginBypassed(busIndex: Int, slotIndex: Int, bypassed: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginBypassed(ptr, busIndex, slotIndex, bypassed)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(bypassed = bypassed)
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    fun setPluginDryWet(busIndex: Int, slotIndex: Int, dryWet: Float) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginDryWet(ptr, busIndex, slotIndex, dryWet)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(dryWet = dryWet)
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    // ── Plugin state reset (for gapless track transitions) ───────────────

    fun resetPluginState() {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeResetPluginState(ptr)
    }

    // ── State serialization ─────────────────────────────────────────────

    fun getStateJson(): String {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return "{}"
        return processor.nativeGetStateJson(ptr)
    }

    fun loadStateJson(json: String) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeLoadStateJson(ptr, json)
        // Sync Kotlin state from the loaded JSON
        _buses.value = parseBusConfigsFromJson(json)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun updateBus(busIndex: Int, transform: (BusConfig) -> BusConfig) {
        _buses.value = _buses.value.map { bus ->
            if (bus.index == busIndex) transform(bus) else bus
        }
    }

    private val defaultBusNames = listOf("Bus 1", "Bus 2", "Bus 3", "Bus 4", "Master")

    private fun parseBusConfigsFromJson(json: String): List<BusConfig> {
        return try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val root = jsonParser.parseToJsonElement(json).jsonObject
            val busesArray = root["buses"]?.jsonArray ?: return BusConfig.defaultBuses()

            busesArray.mapIndexed { index, element ->
                val obj = element.jsonObject
                val plugins = obj["plugins"]?.jsonArray?.mapIndexed { slotIdx, plugEl ->
                    val plugObj = plugEl.jsonObject
                    val typeOrd = plugObj["type"]?.jsonPrimitive?.int ?: 0
                    val bypassed = plugObj["bypassed"]?.jsonPrimitive?.boolean ?: false
                    val dryWet = plugObj["dryWet"]?.jsonPrimitive?.float ?: 1f
                    val params = plugObj["params"]?.jsonArray
                        ?.mapIndexed { pi, pv -> pi to pv.jsonPrimitive.float }
                        ?.toMap() ?: emptyMap()
                    PluginInstance(slotIdx, typeOrd, bypassed, dryWet, params)
                } ?: emptyList()

                BusConfig(
                    index = index,
                    name = defaultBusNames.getOrElse(index) { "Bus ${index + 1}" },
                    gainDb = obj["gain"]?.jsonPrimitive?.float ?: 0f,
                    pan = obj["pan"]?.jsonPrimitive?.float ?: 0f,
                    muted = obj["muted"]?.jsonPrimitive?.boolean ?: false,
                    soloed = obj["soloed"]?.jsonPrimitive?.boolean ?: false,
                    inputEnabled = obj["inputEnabled"]?.jsonPrimitive?.boolean ?: (index == 0),
                    plugins = plugins
                )
            }
        } catch (e: Exception) {
            Log.w("DspEngineManager", "Failed to parse DSP state JSON, using defaults", e)
            BusConfig.defaultBuses()
        }
    }
}
