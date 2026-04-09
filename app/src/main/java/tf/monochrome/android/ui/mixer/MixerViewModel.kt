package tf.monochrome.android.ui.mixer

import androidx.compose.ui.geometry.Offset
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
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.audio.dsp.model.MixPreset
import tf.monochrome.android.data.repository.MixPresetRepository
import tf.monochrome.android.ui.mixer.canvas.model.CanvasOffset
import tf.monochrome.android.ui.mixer.canvas.model.CanvasState
import tf.monochrome.android.ui.mixer.canvas.model.DspNode
import tf.monochrome.android.ui.mixer.canvas.model.NodeConnection
import tf.monochrome.android.ui.mixer.canvas.model.NodeId
import javax.inject.Inject

@HiltViewModel
class MixerViewModel @Inject constructor(
    private val dspManager: DspEngineManager,
    private val presetRepository: MixPresetRepository
) : ViewModel() {

    val enabled: StateFlow<Boolean> = dspManager.enabled
    val buses: StateFlow<List<BusConfig>> = dspManager.buses
    val busLevels: StateFlow<List<BusLevels>> = dspManager.busLevels

    val presets: StateFlow<List<MixPreset>> = presetRepository.getAllPresets()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _currentPresetName = MutableStateFlow<String?>(null)
    val currentPresetName: StateFlow<String?> = _currentPresetName.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _canvasState = MutableStateFlow(CanvasState.empty())
    val canvasState: StateFlow<CanvasState> = _canvasState.asStateFlow()

    // Stable node IDs keyed by bus/slot index so positions survive topology updates
    private val busInputIds = mutableMapOf<Int, NodeId>()
    private val pluginIds = mutableMapOf<Pair<Int, Int>, NodeId>()
    private val masterBusId: NodeId = "master_bus"
    private val outputId: NodeId = "output_node"

    init {
        // Poll meter levels at ~250ms (4 fps) — imperceptible to user, 5x less CPU
        viewModelScope.launch {
            while (isActive) {
                dspManager.pollLevels()
                delay(250L)
                val levels = busLevels.value
                if (levels.isNotEmpty()) {
                    val peakDb = levels.maxOfOrNull { maxOf(it.peakDbL, it.peakDbR) } ?: -60f
                    // Normalize -60..0 dB → 0..1
                    _audioAmplitude.value = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
                }
            }
        }

        // Rebuild canvas nodes whenever bus topology changes
        viewModelScope.launch {
            buses.collect { busList -> rebuildCanvasNodes(busList) }
        }
    }

    private fun rebuildCanvasNodes(busList: List<BusConfig>) {
        val inputBuses = busList.filter { !it.isMaster }
        val current = _canvasState.value
        val nodes = mutableMapOf<NodeId, DspNode>()
        val connections = mutableListOf<NodeConnection>()

        val maxPlugins = inputBuses.maxOfOrNull { it.plugins.size } ?: 0
        val masterX = CanvasState.PLUGIN_START_X + (maxPlugins + 1) * CanvasState.NODE_H_SPACING
        val outputX = masterX + CanvasState.NODE_H_SPACING
        val midY = if (inputBuses.isEmpty()) 0f
                   else (inputBuses.size - 1) * CanvasState.NODE_V_SPACING / 2f

        val masterPos = current.nodes[masterBusId]?.position ?: CanvasOffset(masterX, midY)
        val outputPos = current.nodes[outputId]?.position ?: CanvasOffset(outputX, midY)

        nodes[masterBusId] = DspNode.BusMaster(id = masterBusId, position = masterPos)
        nodes[outputId] = DspNode.Output(id = outputId, position = outputPos)
        connections.add(NodeConnection(masterBusId, outputId, -1))

        inputBuses.forEachIndexed { rowIdx, bus ->
            val y = rowIdx * CanvasState.NODE_V_SPACING
            val inputId = busInputIds.getOrPut(bus.index) { "bus_input_${bus.index}" }
            val inputPos = current.nodes[inputId]?.position ?: CanvasOffset(CanvasState.LEFT_MARGIN, y)
            nodes[inputId] = DspNode.BusInput(
                id = inputId,
                position = inputPos,
                busIndex = bus.index,
                busName = bus.name
            )

            var prevId: NodeId = inputId
            bus.plugins.forEachIndexed { slotIdx, plugin ->
                val type = plugin.type ?: return@forEachIndexed
                val key = Pair(bus.index, slotIdx)
                val pluginId = pluginIds.getOrPut(key) { "plugin_${bus.index}_$slotIdx" }
                val pluginX = CanvasState.PLUGIN_START_X + slotIdx * CanvasState.NODE_H_SPACING
                val pluginPos = current.nodes[pluginId]?.position ?: CanvasOffset(pluginX, y)
                nodes[pluginId] = DspNode.Plugin(
                    id = pluginId,
                    position = pluginPos,
                    busIndex = bus.index,
                    slotIndex = slotIdx,
                    snapinType = type,
                    bypassed = plugin.bypassed,
                    parameters = plugin.parameters
                )
                connections.add(NodeConnection(prevId, pluginId, bus.index))
                prevId = pluginId
            }
            connections.add(NodeConnection(prevId, masterBusId, bus.index))
        }

        // Prune stale node IDs
        busInputIds.keys.retainAll { idx -> inputBuses.any { it.index == idx } }
        pluginIds.keys.retainAll { (busIdx, slotIdx) ->
            slotIdx < (inputBuses.find { it.index == busIdx }?.plugins?.size ?: 0)
        }

        _canvasState.value = current.copy(nodes = nodes, connections = connections)
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
        dspManager.addPlugin(busIndex, bus.plugins.size, type)
        _showPluginPicker.value = false
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
            val json = dspManager.getStateJson()
            presetRepository.savePreset(MixPreset(name = name, stateJson = json))
            _currentPresetName.value = name
        }
    }

    fun loadPreset(preset: MixPreset) {
        dspManager.loadStateJson(preset.stateJson)
        _currentPresetName.value = preset.name
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch {
            presetRepository.deletePreset(id)
            if (presets.value.find { it.id == id }?.name == _currentPresetName.value) {
                _currentPresetName.value = null
            }
        }
    }

    // ── Canvas interactions ──────────────────────────────────────────────

    fun onViewportPan(delta: Offset) {
        _canvasState.value = _canvasState.value.let { s ->
            s.copy(viewportOffset = CanvasOffset(
                s.viewportOffset.x + delta.x,
                s.viewportOffset.y + delta.y
            ))
        }
    }

    fun onViewportZoom(zoom: Float, centroid: Offset) {
        _canvasState.value = _canvasState.value.let { s ->
            val newScale = (s.viewportScale * zoom).coerceIn(CanvasState.MIN_SCALE, CanvasState.MAX_SCALE)
            val ratio = newScale / s.viewportScale
            val newOffsetX = centroid.x - ratio * (centroid.x - s.viewportOffset.x)
            val newOffsetY = centroid.y - ratio * (centroid.y - s.viewportOffset.y)
            s.copy(viewportScale = newScale, viewportOffset = CanvasOffset(newOffsetX, newOffsetY))
        }
    }

    fun onNodeSelected(id: NodeId?) {
        _canvasState.value = _canvasState.value.copy(selectedNodeId = id)
    }

    fun onNodeDragStart(id: NodeId) {
        _canvasState.value = _canvasState.value.copy(selectedNodeId = id)
    }

    fun onNodeDrag(id: NodeId, delta: CanvasOffset) {
        val s = _canvasState.value
        val node = s.nodes[id] ?: return
        _canvasState.value = s.copy(nodes = s.nodes + (id to node.withPosition(node.position + delta)))
    }

    fun onNodeDragEnd(id: NodeId) { /* position already committed */ }

    fun onNodeDoubleTap(id: NodeId) {
        val node = _canvasState.value.nodes[id]
        if (node is DspNode.Plugin) editPlugin(node.busIndex, node.slotIndex)
    }

    fun onNodeLongPress(id: NodeId) {
        _canvasState.value = _canvasState.value.copy(deleteTargetId = id)
    }

    fun onDeleteConfirmed(id: NodeId) {
        val node = _canvasState.value.nodes[id]
        if (node is DspNode.Plugin) removePlugin(node.busIndex, node.slotIndex)
        _canvasState.value = _canvasState.value.copy(deleteTargetId = null)
    }

    fun onDeleteCancelled() {
        _canvasState.value = _canvasState.value.copy(deleteTargetId = null)
    }
}
