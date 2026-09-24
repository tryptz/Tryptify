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
import tf.monochrome.android.audio.dsp.model.FxTapFrame
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

    // Meter levels — polled from the UI once per display frame
    // [peakL, peakR, holdL, holdR] per bus = 4 floats each, indexed by bus
    // index; sized for the most buses a mix can hold.
    private val levelsBuffer = FloatArray(BusConfig.MAX_TOTAL_BUSES * 4)
    private val _busLevels = MutableStateFlow(List(BusConfig.defaultBuses().size) { BusLevels() })
    val busLevels: StateFlow<List<BusLevels>> = _busLevels.asStateFlow()

    private val _clipped = MutableStateFlow(false)
    val clipped: StateFlow<Boolean> = _clipped.asStateFlow()

    // Live audio tap for the FX-chain visualizations. Buffers are reused every
    // poll (see FxTapFrame docs); only the frame wrapper is allocated at 60 Hz.
    private val fxMetersRaw = FloatArray(MAX_PLUGINS_PER_BUS * 2)
    private val fxMetersSmoothed = FloatArray(MAX_PLUGINS_PER_BUS * 2) { -60f }
    private var fxMetersBus = -1
    private val fxWaveBuffer = FloatArray(FX_WAVE_SAMPLES)
    private var fxTapSeq = 0L
    private var fxLastPollNanos = 0L
    private val _fxTap = MutableStateFlow<FxTapFrame?>(null)
    val fxTap: StateFlow<FxTapFrame?> = _fxTap.asStateFlow()

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
        // Push the user-selected DSP block size into the processor whenever
        // it changes. The processor honours it on the next queueInput call.
        scope.launch {
            preferences.dspBlockSize.collect { processor.setBlockSize(it) }
        }
        // A wide stream spreads its channel groups one per bus, adding buses
        // as it needs them; a narrower one hands back the ones nobody touched.
        // Either way the engine changed the mixer, so the mirror re-reads it.
        scope.launch {
            preferences.dspSpreadChannels.collect { processor.setSpreadChannels(it) }
        }
        scope.launch {
            processor.channelGroups.collect {
                if (processor.getEnginePtr() != 0L) syncFromEngine()
                else _buses.value = labelled(_buses.value)
            }
        }
        // Master "DSP mixer off" toggle becomes a true bypass on the audio
        // thread — no deinterleave, no nativeProcess, no Oxford, no
        // interleave — so flipping it off should leave audio identical to
        // a build with no DSP wired in at all.
        scope.launch {
            preferences.dspEnabled.collect { enabled ->
                processor.setBypassed(!enabled)
            }
        }
    }

    /**
     * The live state, captured the instant it changes.
     *
     * The DataStore write below is debounced by half a second, which is right
     * for flash but wrong as a source of truth: the native engine is destroyed
     * and rebuilt whenever the audio format changes — a track at a different
     * sample rate is enough — and whatever reapplies state to the new engine
     * has to reapply what the user *has*, not what was last written. Reading
     * the preference there meant a knob moved less than 500 ms before a track
     * change was reverted to its previous value, and then saved in that
     * reverted state, which made it permanent.
     *
     * Serialising here costs one native call per edit. The write is what is
     * expensive and the write is still debounced.
     */
    @Volatile private var liveStateJson: String? = null

    private fun requestSave() {
        getStateJson().takeIf { it != "{}" }?.let { liveStateJson = it }
        saveSignal.tryEmit(Unit)
    }

    /**
     * Push the state back into a freshly rebuilt engine.
     *
     * Prefers the in-memory copy over the persisted one for the reason above,
     * and falls back to the preference only when there is no in-memory state
     * yet — which is startup, and startup goes through [restoreState] anyway.
     */
    suspend fun reapplyAfterEngineRecreated() {
        val json = liveStateJson ?: preferences.dspStateJson.first()
        if (!json.isNullOrEmpty() && json != "{}") loadStateJson(json)
        processor.setMixBypassed(!_enabled.value)
    }

    /**
     * Back to a bare mixer: no plugins anywhere, every bus at unity and centre,
     * nothing muted or soloed, and input on bus 1 alone.
     *
     * Driven through the ordinary setters rather than by loading a hand-written
     * default JSON, so native, the Kotlin mirror and the save all move together
     * through paths that are already exercised — a reset that half-applied
     * would be worse than no reset button.
     */
    fun resetToDefaults() {
        // Back to four buses first, highest first so no index moves under us.
        for (bus in _buses.value.filter { it.isRemovable }.sortedByDescending { it.index }) {
            removeBus(bus.index)
        }
        for (bus in _buses.value) {
            for (slot in bus.plugins.indices.reversed()) removePlugin(bus.index, slot)
        }
        for (default in BusConfig.defaultBuses()) {
            setBusGain(default.index, default.gainDb)
            setBusPan(default.index, default.pan)
            setBusMute(default.index, default.muted)
            setBusSolo(default.index, default.soloed)
            setBusInputEnabled(default.index, default.inputEnabled)
        }
    }

    fun pollLevels() {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        processor.nativeGetBusLevels(ptr, levelsBuffer)
        val count = _buses.value.size.coerceAtMost(BusConfig.MAX_TOTAL_BUSES)
        _busLevels.value = List(count) { b ->
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

    /**
     * Poll the per-plugin tap meters and post-fader waveform for [busIndex]
     * into [fxTap]. Called once per display frame (60 or 120 Hz); meters get
     * instant attack and a wall-clock ~45 dB/s release, so the fall speed is
     * the same at any poll rate and short transients stay visible.
     */
    fun pollFxTap(busIndex: Int) {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        processor.nativeGetPluginMeters(ptr, busIndex, fxMetersRaw)
        if (fxMetersBus != busIndex) {
            fxMetersRaw.copyInto(fxMetersSmoothed)
            fxMetersBus = busIndex
        }
        val now = System.nanoTime()
        val dt = if (fxLastPollNanos == 0L) 0.016f
                 else ((now - fxLastPollNanos) / 1e9f).coerceIn(0f, 0.1f)
        fxLastPollNanos = now
        val release = FX_METER_RELEASE_DB_PER_SEC * dt
        for (i in fxMetersSmoothed.indices) {
            val raw = fxMetersRaw[i]
            fxMetersSmoothed[i] =
                if (raw >= fxMetersSmoothed[i]) raw
                else (fxMetersSmoothed[i] - release).coerceAtLeast(-60f)
        }
        val waveLen = processor.nativeGetBusWaveform(ptr, busIndex, fxWaveBuffer)
        _fxTap.value = FxTapFrame(
            seq = ++fxTapSeq,
            busIndex = busIndex,
            meters = fxMetersSmoothed,
            wave = fxWaveBuffer,
            waveLen = waveLen
        )
    }

    companion object {
        // Mirrors MAX_PLUGINS_PER_BUS in dsp_engine.h — native refuses inserts past this.
        const val MAX_PLUGINS_PER_BUS = 16

        // FX-chain scope tap: samples fetched per poll (~21 ms at 48 kHz).
        // Must be <= WAVE_TAP_SIZE in dsp_engine.h.
        const val FX_WAVE_SAMPLES = 1024

        // Tap meter release in dB/s of wall-clock time — poll-rate independent.
        private const val FX_METER_RELEASE_DB_PER_SEC = 45f

        // Parameter bounds — mirror the native clamps in dsp_engine.cpp / snapin_processor.h.
        // Clamping in Kotlin keeps the StateFlow value in sync with what native actually stores.
        const val MIN_BUS_GAIN_DB = -60f
        const val MAX_BUS_GAIN_DB = 12f
        const val MIN_PAN = -1f
        const val MAX_PAN = 1f
        const val MIN_DRY_WET = 0f
        const val MAX_DRY_WET = 1f
    }

    private fun sanitizeParam(value: Float): Float {
        // Plugin parameter ranges vary per processor; the native side clamps to its own range.
        // Here we only reject NaN/Inf so they never reach the native atomics or StateFlow.
        return if (value.isFinite()) value else 0f
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
            liveStateJson = stateJson
        }

        _enabled.value = enabled
        processor.setMixBypassed(!enabled)
    }

    // ── Adding and removing buses ───────────────────────────────────────

    /**
     * Adds a bus after the last one: unity, centre, input off, no plugins.
     * Returns its index, or null at [BusConfig.MAX_MIX_BUSES] or with no
     * engine.
     */
    fun addBus(): Int? {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return null
        val index = processor.nativeAddBus(ptr)
        if (index < 0) return null
        _buses.value = labelled(
            (_buses.value + BusConfig(index = index, name = BusConfig.nameFor(index)))
                .sortedBy { it.index }
        )
        requestSave()
        return index
    }

    /**
     * Removes bus [busIndex] (bus 5 and up). The buses above it move down one
     * index in the engine, so the Kotlin mirror is re-read from the engine's
     * own state rather than patched by hand.
     */
    fun removeBus(busIndex: Int): Boolean {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return false
        if (!processor.nativeRemoveBus(ptr, busIndex)) return false
        syncFromEngine()
        requestSave()
        return true
    }

    // ── Bus controls ────────────────────────────────────────────────────

    fun setBusGain(busIndex: Int, gainDb: Float) {
        val clamped = (if (gainDb.isFinite()) gainDb else 0f)
            .coerceIn(MIN_BUS_GAIN_DB, MAX_BUS_GAIN_DB)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusGain(ptr, busIndex, clamped)
        updateBus(busIndex) { it.copy(gainDb = clamped) }
        requestSave()
    }

    fun setBusPan(busIndex: Int, pan: Float) {
        val clamped = (if (pan.isFinite()) pan else 0f).coerceIn(MIN_PAN, MAX_PAN)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusPan(ptr, busIndex, clamped)
        updateBus(busIndex) { it.copy(pan = clamped) }
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
        val sanitized = sanitizeParam(value)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetParameter(ptr, busIndex, slotIndex, paramIndex, sanitized)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                val plugin = plugins[slotIndex]
                plugins[slotIndex] = plugin.copy(
                    parameters = plugin.parameters + (paramIndex to sanitized)
                )
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    /**
     * A whole preset at once: every parameter and the dry/wet, one update of
     * the mirror and one save. (Through [setParameter] a 12-parameter preset
     * would serialise the whole mixer twelve times.)
     */
    fun setParameters(busIndex: Int, slotIndex: Int, values: FloatArray, dryWet: Float) {
        val sanitized = FloatArray(values.size) { sanitizeParam(values[it]) }
        val dw = (if (dryWet.isFinite()) dryWet else MAX_DRY_WET).coerceIn(MIN_DRY_WET, MAX_DRY_WET)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) {
            sanitized.forEachIndexed { i, v -> processor.nativeSetParameter(ptr, busIndex, slotIndex, i, v) }
            processor.nativeSetPluginDryWet(ptr, busIndex, slotIndex, dw)
        }
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                val plugin = plugins[slotIndex]
                plugins[slotIndex] = plugin.copy(
                    parameters = plugin.parameters + sanitized.withIndex().associate { it.index to it.value },
                    dryWet = dw,
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
        val clamped = (if (dryWet.isFinite()) dryWet else MAX_DRY_WET)
            .coerceIn(MIN_DRY_WET, MAX_DRY_WET)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginDryWet(ptr, busIndex, slotIndex, clamped)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(dryWet = clamped)
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    /** Per-plugin oversampling: 1 (off), 2, or 4. Snaps other values down. */
    fun setPluginOversampling(busIndex: Int, slotIndex: Int, factor: Int) {
        val snapped = if (factor >= 4) 4 else if (factor >= 2) 2 else 1
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginOversampling(ptr, busIndex, slotIndex, snapped)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(oversampling = snapped)
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
        // Sync Kotlin state from what the engine made of it — which, while a
        // wide stream plays, can be more buses than the JSON had.
        if (ptr != 0L) syncFromEngine() else _buses.value = labelled(parseBusConfigsFromJson(json))
    }

    val spreadChannels = preferences.dspSpreadChannels

    fun setSpreadChannels(spread: Boolean) {
        processor.setSpreadChannels(spread)  // at once, not after the write
        scope.launch { preferences.setDspSpreadChannels(spread) }
    }

    /** The channel groups of the stream playing, in bus order; empty for stereo. */
    val channelGroups: StateFlow<List<String>> get() = processor.channelGroups

    /** Re-reads the whole mixer from the engine, grown buses included. */
    private fun syncFromEngine() {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        _buses.value = labelled(parseBusConfigsFromJson(processor.nativeGetLiveStateJson(ptr)))
    }

    /**
     * Names the buses a multichannel stream is spread onto after the channels
     * they carry — "Front", "Centre", "LFE"… — and clears the names off the
     * rest. Only the mirror is renamed; nothing here is saved.
     */
    private fun labelled(buses: List<BusConfig>): List<BusConfig> {
        val groups = processor.channelGroups.value
        return buses.map { bus ->
            val group = if (bus.isMaster) null else groups.getOrNull(bus.number - 1)
            bus.copy(
                name = group ?: BusConfig.nameFor(bus.index),
                channelGroup = group,
            )
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun updateBus(busIndex: Int, transform: (BusConfig) -> BusConfig) {
        _buses.value = _buses.value.map { bus ->
            if (bus.index == busIndex) transform(bus) else bus
        }
    }

    private fun parseBusConfigsFromJson(json: String): List<BusConfig> {
        return try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val root = jsonParser.parseToJsonElement(json).jsonObject
            val busesArray = root["buses"]?.jsonArray ?: return BusConfig.defaultBuses()

            // The engine takes at most 16 mix buses and the master; a longer
            // (hand-edited) file is cut to what it will actually run.
            val parsed = busesArray.take(BusConfig.MAX_TOTAL_BUSES).mapIndexed { index, element ->
                val obj = element.jsonObject
                val plugins = obj["plugins"]?.jsonArray?.mapIndexed { slotIdx, plugEl ->
                    val plugObj = plugEl.jsonObject
                    val typeOrd = plugObj["type"]?.jsonPrimitive?.int ?: 0
                    val bypassed = plugObj["bypassed"]?.jsonPrimitive?.boolean ?: false
                    val dryWet = (plugObj["dryWet"]?.jsonPrimitive?.float ?: 1f)
                        .let { if (it.isFinite()) it else 1f }
                        .coerceIn(MIN_DRY_WET, MAX_DRY_WET)
                    val params = plugObj["params"]?.jsonArray
                        ?.mapIndexed { pi, pv -> pi to sanitizeParam(pv.jsonPrimitive.float) }
                        ?.toMap() ?: emptyMap()
                    val os = when (plugObj["os"]?.jsonPrimitive?.int ?: 1) {
                        4 -> 4; 2 -> 2; else -> 1
                    }
                    PluginInstance(slotIdx, typeOrd, bypassed, dryWet, params, os)
                } ?: emptyList()

                val rawGain = obj["gain"]?.jsonPrimitive?.float ?: 0f
                val rawPan = obj["pan"]?.jsonPrimitive?.float ?: 0f
                BusConfig(
                    index = index,
                    name = BusConfig.nameFor(index),
                    gainDb = (if (rawGain.isFinite()) rawGain else 0f)
                        .coerceIn(MIN_BUS_GAIN_DB, MAX_BUS_GAIN_DB),
                    pan = (if (rawPan.isFinite()) rawPan else 0f).coerceIn(MIN_PAN, MAX_PAN),
                    muted = obj["muted"]?.jsonPrimitive?.boolean ?: false,
                    soloed = obj["soloed"]?.jsonPrimitive?.boolean ?: false,
                    inputEnabled = obj["inputEnabled"]?.jsonPrimitive?.boolean ?: (index == 0),
                    plugins = plugins
                )
            }
            // A short list still loads as buses 1–4 plus the master on the
            // native side (the entries it lacks stay empty), so the mirror
            // fills in the same buses rather than showing a mixer with no master.
            parsed + BusConfig.defaultBuses().drop(parsed.size)
        } catch (e: Exception) {
            Log.w("DspEngineManager", "Failed to parse DSP state JSON, using defaults", e)
            BusConfig.defaultBuses()
        }
    }
}
