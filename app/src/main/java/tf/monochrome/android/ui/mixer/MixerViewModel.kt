package tf.monochrome.android.ui.mixer

import android.media.AudioDeviceInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusInputSource
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.audio.dsp.model.MixPreset
import tf.monochrome.android.audio.dsp.model.MixPresetFile
import tf.monochrome.android.audio.input.StereoInputController
import tf.monochrome.android.audio.input.StereoInputEngine
import tf.monochrome.android.audio.input.StereoInputFormat
import tf.monochrome.android.audio.input.StereoInputMode
import tf.monochrome.android.audio.input.StereoInputStatus
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MixPresetRepository
import javax.inject.Inject

@HiltViewModel
class MixerViewModel @Inject constructor(
    private val dspManager: DspEngineManager,
    private val presetRepository: MixPresetRepository,
    private val preferencesManager: PreferencesManager,
    private val stereoInput: StereoInputController,
    private val stereoInputEngine: StereoInputEngine,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = dspManager.enabled
    val buses: StateFlow<List<BusConfig>> = dspManager.buses
    val busLevels: StateFlow<List<BusLevels>> = dspManager.busLevels

    // ── Stereo input ────────────────────────────────────────────────────

    val inputEnabled: StateFlow<Boolean> = stereoInput.enabled
    val inputActive: StateFlow<Boolean> = stereoInput.active
    val inputMode: StateFlow<StereoInputMode> = stereoInput.mode
    val inputStatus: StateFlow<StereoInputStatus> = stereoInput.status
    val inputFormat: StateFlow<StereoInputFormat?> = stereoInput.activeFormat
    val inputDevices: StateFlow<List<AudioDeviceInfo>> = stereoInputEngine.devices
    val inputSelectedDeviceId: StateFlow<Int?> = stereoInputEngine.selectedDeviceId
    val inputPeakL: StateFlow<Float> = stereoInputEngine.peakL
    val inputPeakR: StateFlow<Float> = stereoInputEngine.peakR

    val inputTrimDb: StateFlow<Float> = preferencesManager.stereoInputTrimDb
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    fun describeInputDevice(device: AudioDeviceInfo): String = stereoInputEngine.describe(device)
    fun describeInputCapabilities(device: AudioDeviceInfo): String =
        stereoInputEngine.describeCapabilities(device)
    fun isInputFeedbackRisk(device: AudioDeviceInfo): Boolean =
        stereoInputEngine.isFeedbackRisk(device)
    fun hasRecordPermission(): Boolean = stereoInputEngine.hasPermission()

    fun toggleStereoInput() = stereoInput.toggle()
    fun setStereoInputEnabled(enabled: Boolean) = stereoInput.setEnabled(enabled)
    fun setStereoInputMode(mode: StereoInputMode) = stereoInput.setMode(mode)
    fun selectInputDevice(deviceId: Int?) = stereoInput.selectDevice(deviceId)
    fun setInputTrimDb(db: Float) = stereoInput.setTrimDb(db)

    // Live audio tap (per-plugin meters + scope waveform) for the FX chain.
    val fxTap = dspManager.fxTap

    /** Channel coloring mode: false = curated palette, true = album/theme-derived. */
    val channelDynamicColor: StateFlow<Boolean> = preferencesManager.mixerChannelDynamic
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setChannelDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setMixerChannelDynamic(enabled) }
    }

    val presets: StateFlow<List<MixPreset>> = presetRepository.getAllPresets()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _currentPresetName = MutableStateFlow<String?>(null)
    val currentPresetName: StateFlow<String?> = _currentPresetName.asStateFlow()

    private val _selectedBusIndex = MutableStateFlow(0)
    val selectedBusIndex: StateFlow<Int> = _selectedBusIndex.asStateFlow()

    /**
     * One meter + FX-tap poll (cheap native reads). Called by the mixer UI
     * once per display frame, so meters and visuals run at the panel's native
     * refresh rate — 120 Hz where the device permits — and polling stops
     * entirely while the mixer is off screen.
     */
    fun pollTick() {
        dspManager.pollLevels()
        dspManager.pollFxTap(_selectedBusIndex.value)
    }

    private val _showPluginPicker = MutableStateFlow(false)
    val showPluginPicker: StateFlow<Boolean> = _showPluginPicker.asStateFlow()

    private val _editingPlugin = MutableStateFlow<Pair<Int, Int>?>(null) // busIndex, slotIndex
    val editingPlugin: StateFlow<Pair<Int, Int>?> = _editingPlugin.asStateFlow()

    // When set, the next picker selection replaces the plugin at this
    // (busIndex, slotIndex) instead of appending. Nothing is removed until the
    // user actually picks a replacement — cancelling the picker leaves the
    // original plugin (and its settings) intact.
    private val _pendingReplaceSlot = MutableStateFlow<Pair<Int, Int>?>(null)

    fun setEnabled(enabled: Boolean) = dspManager.setEnabled(enabled)

    fun selectBus(index: Int) { _selectedBusIndex.value = index }

    // ── Bus controls ────────────────────────────────────────────────────

    fun setBusGain(busIndex: Int, gainDb: Float) = dspManager.setBusGain(busIndex, gainDb)
    fun setBusPan(busIndex: Int, pan: Float) = dspManager.setBusPan(busIndex, pan)
    fun setBusInputSource(busIndex: Int, source: BusInputSource) =
        dspManager.setBusInputSource(busIndex, source)

    /**
     * Advance a bus through OFF → PLAY → LINE → BOTH → OFF.
     *
     * The strip's input pad is a single tap target, so routing rides on the
     * same control that used to be a plain on/off: "off" stays the disabled
     * state and the three live positions are the input sources.
     */
    fun cycleBusInput(busIndex: Int) {
        val bus = buses.value.getOrNull(busIndex) ?: return
        when {
            !bus.inputEnabled -> {
                dspManager.setBusInputSource(busIndex, BusInputSource.Playback)
                dspManager.setBusInputEnabled(busIndex, true)
            }
            bus.inputSource == BusInputSource.Playback ->
                dspManager.setBusInputSource(busIndex, BusInputSource.LineIn)
            bus.inputSource == BusInputSource.LineIn ->
                dspManager.setBusInputSource(busIndex, BusInputSource.Both)
            else -> dspManager.setBusInputEnabled(busIndex, false)
        }
    }

    /** Copy a bus's whole chain, gain, pan and routing onto another bus. */
    fun cloneBus(sourceIndex: Int, targetIndex: Int) =
        dspManager.cloneBus(sourceIndex, targetIndex)

    fun toggleMute(busIndex: Int) {
        val bus = buses.value.getOrNull(busIndex) ?: return
        dspManager.setBusMute(busIndex, !bus.muted)
    }
    fun toggleSolo(busIndex: Int) {
        val bus = buses.value.getOrNull(busIndex) ?: return
        dspManager.setBusSolo(busIndex, !bus.soloed)
    }

    // ── Plugin chain ────────────────────────────────────────────────────

    fun showAddPlugin() { _showPluginPicker.value = true }

    /** Open the picker in "replace this slot" mode (no removal happens yet). */
    fun showReplacePlugin(busIndex: Int, slotIndex: Int) {
        _pendingReplaceSlot.value = busIndex to slotIndex
        _showPluginPicker.value = true
    }

    fun dismissPluginPicker() {
        _showPluginPicker.value = false
        _pendingReplaceSlot.value = null
    }

    fun addPlugin(type: SnapinType) {
        val pending = _pendingReplaceSlot.value
        if (pending != null) {
            val (busIndex, slotIndex) = pending
            _pendingReplaceSlot.value = null
            _showPluginPicker.value = false
            val bus = buses.value.getOrNull(busIndex) ?: return
            // Remove the old plugin and insert the replacement at the same slot
            // so the chain's processing order is preserved.
            if (slotIndex in bus.plugins.indices) dspManager.removePlugin(busIndex, slotIndex)
            dspManager.addPlugin(busIndex, slotIndex, type)
            return
        }
        val busIndex = _selectedBusIndex.value
        val bus = buses.value.getOrNull(busIndex) ?: return
        if (bus.plugins.size >= DspEngineManager.MAX_PLUGINS_PER_BUS) {
            _showPluginPicker.value = false
            return
        }
        dspManager.addPlugin(busIndex, bus.plugins.size, type)
        _showPluginPicker.value = false
    }

    fun movePlugin(busIndex: Int, fromSlot: Int, toSlot: Int) {
        val bus = buses.value.getOrNull(busIndex) ?: return
        if (fromSlot == toSlot) return
        if (fromSlot !in bus.plugins.indices || toSlot !in bus.plugins.indices) return
        dspManager.movePlugin(busIndex, fromSlot, toSlot)
        _editingPlugin.value = _editingPlugin.value?.let { editing ->
            val (b, s) = editing
            when {
                b != busIndex -> editing
                s == fromSlot -> b to toSlot
                s in minOf(fromSlot, toSlot)..maxOf(fromSlot, toSlot) ->
                    b to (if (fromSlot < toSlot) s - 1 else s + 1)
                else -> editing
            }
        }
    }

    fun removePlugin(busIndex: Int, slotIndex: Int) {
        dspManager.removePlugin(busIndex, slotIndex)
        if (_editingPlugin.value == Pair(busIndex, slotIndex)) {
            _editingPlugin.value = null
        }
    }

    fun togglePluginBypass(busIndex: Int, slotIndex: Int) {
        val bus = buses.value.getOrNull(busIndex) ?: return
        val plugin = bus.plugins.getOrNull(slotIndex) ?: return
        dspManager.setPluginBypassed(busIndex, slotIndex, !plugin.bypassed)
    }

    fun editPlugin(busIndex: Int, slotIndex: Int) {
        _editingPlugin.value = Pair(busIndex, slotIndex)
    }

    fun dismissPluginEditor() { _editingPlugin.value = null }

    fun setParameter(busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float) {
        dspManager.setParameter(busIndex, slotIndex, paramIndex, value)
    }

    fun setPluginDryWet(busIndex: Int, slotIndex: Int, dryWet: Float) {
        dspManager.setPluginDryWet(busIndex, slotIndex, dryWet)
    }

    fun setPluginOversampling(busIndex: Int, slotIndex: Int, factor: Int) {
        dspManager.setPluginOversampling(busIndex, slotIndex, factor)
    }

    // ── Presets ──────────────────────────────────────────────────────────

    fun savePreset(name: String) {
        viewModelScope.launch {
            val stateJson = dspManager.getStateJson()
            presetRepository.savePreset(MixPreset(name = name, stateJson = stateJson))
            _currentPresetName.value = name
        }
    }

    fun loadPreset(preset: MixPreset) {
        dspManager.loadStateJson(preset.stateJson)
        _currentPresetName.value = preset.name
    }

    fun deletePreset(id: Long) {
        if (id < 0) return // built-in presets are read-only
        viewModelScope.launch {
            presetRepository.deletePreset(id)
            if (presets.value.find { it.id == id }?.name == _currentPresetName.value) {
                _currentPresetName.value = null
            }
        }
    }

    // ── Preset sharing (export to / import from a .json file) ─────────────

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Serialize a preset to the shareable on-disk file format. */
    fun exportPayload(preset: MixPreset): String =
        json.encodeToString(
            MixPresetFile.serializer(),
            MixPresetFile(name = preset.name, stateJson = preset.stateJson)
        )

    /**
     * Import preset file [text]: parse the [MixPresetFile] envelope (falling
     * back to treating the text as raw engine state JSON), persist it as a
     * custom preset, and apply it live.
     */
    fun importPreset(text: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val trimmed = text.trim()
            val file = runCatching {
                json.decodeFromString(MixPresetFile.serializer(), trimmed)
            }.getOrNull()

            // Only accept a valid MixPresetFile envelope or a bare engine-state
            // JSON *object*. Rejecting anything else stops the old behavior of
            // saving arbitrary files as a junk preset and resetting the mixer,
            // then falsely toasting success.
            val stateJson = when {
                file?.stateJson?.takeIf { it.isNotBlank() } != null -> file.stateJson
                runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
                    is kotlinx.serialization.json.JsonObject -> trimmed
                else -> null
            }
            if (stateJson == null) {
                onResult(false)
                return@launch
            }

            val name = file?.name?.takeIf { it.isNotBlank() } ?: "Imported Preset"
            presetRepository.savePreset(
                MixPreset(name = name, stateJson = stateJson, isCustom = true)
            )
            dspManager.loadStateJson(stateJson)
            _currentPresetName.value = name
            onResult(true)
        }
    }
}
