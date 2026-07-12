package tf.monochrome.android.ui.mixer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.audio.dsp.model.MixPreset
import tf.monochrome.android.audio.dsp.model.MixPresetFile
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MixPresetRepository
import javax.inject.Inject

@HiltViewModel
class MixerViewModel @Inject constructor(
    private val dspManager: DspEngineManager,
    private val presetRepository: MixPresetRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val enabled: StateFlow<Boolean> = dspManager.enabled
    val buses: StateFlow<List<BusConfig>> = dspManager.buses
    val busLevels: StateFlow<List<BusLevels>> = dspManager.busLevels

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

    init {
        // 60 Hz meter poll — fluid VU bars under fast transients. pollLevels
        // is a native hot-path read; cheap enough to call at frame rate.
        viewModelScope.launch {
            while (isActive) {
                dspManager.pollLevels()
                delay(16L)
            }
        }
    }

    private val _selectedBusIndex = MutableStateFlow(0)
    val selectedBusIndex: StateFlow<Int> = _selectedBusIndex.asStateFlow()

    private val _showPluginPicker = MutableStateFlow(false)
    val showPluginPicker: StateFlow<Boolean> = _showPluginPicker.asStateFlow()

    private val _editingPlugin = MutableStateFlow<Pair<Int, Int>?>(null) // busIndex, slotIndex
    val editingPlugin: StateFlow<Pair<Int, Int>?> = _editingPlugin.asStateFlow()

    fun setEnabled(enabled: Boolean) = dspManager.setEnabled(enabled)

    fun selectBus(index: Int) { _selectedBusIndex.value = index }

    // ── Bus controls ────────────────────────────────────────────────────

    fun setBusGain(busIndex: Int, gainDb: Float) = dspManager.setBusGain(busIndex, gainDb)
    fun setBusPan(busIndex: Int, pan: Float) = dspManager.setBusPan(busIndex, pan)
    fun setBusInputEnabled(busIndex: Int, enabled: Boolean) = dspManager.setBusInputEnabled(busIndex, enabled)

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
    fun dismissPluginPicker() { _showPluginPicker.value = false }

    fun addPlugin(type: SnapinType) {
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
    fun importPreset(text: String) {
        viewModelScope.launch {
            val trimmed = text.trim()
            val file = runCatching {
                json.decodeFromString(MixPresetFile.serializer(), trimmed)
            }.getOrNull()

            val name = file?.name?.takeIf { it.isNotBlank() } ?: "Imported Preset"
            val stateJson = file?.stateJson?.takeIf { it.isNotBlank() } ?: trimmed

            presetRepository.savePreset(
                MixPreset(name = name, stateJson = stateJson, isCustom = true)
            )
            dspManager.loadStateJson(stateJson)
            _currentPresetName.value = name
        }
    }
}
