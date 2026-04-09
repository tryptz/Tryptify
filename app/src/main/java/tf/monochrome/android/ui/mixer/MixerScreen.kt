package tf.monochrome.android.ui.mixer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(
    navController: NavController,
    viewModel: MixerViewModel
) {
    val enabled by viewModel.enabled.collectAsState()
    val buses by viewModel.buses.collectAsState()
    val busLevels by viewModel.busLevels.collectAsState()
    val selectedBusIndex by viewModel.selectedBusIndex.collectAsState()
    val showPluginPicker by viewModel.showPluginPicker.collectAsState()
    val editingPlugin by viewModel.editingPlugin.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val currentPresetName by viewModel.currentPresetName.collectAsState()
    val canvasState by viewModel.canvasState.collectAsState()

    val selectedBus = buses.getOrNull(selectedBusIndex)
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var showInsertRack by remember { mutableStateOf(false) }
    var showCanvas by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FLColors.background)
    ) {
        if (showCanvas) {
            // ── DSP Canvas View ─────────────────────────────────────────
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FLColors.headerBg)
                        .padding(top = statusBarPadding)
                        .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showCanvas = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FLColors.textWhite, modifier = Modifier.size(20.dp))
                        }
                        Text("DSP Canvas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FLColors.textWhite)
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Isolate audioAmplitude collection here so meter updates don't recompose entire screen
                    val audioAmplitude by viewModel.audioAmplitude.collectAsState()

                    tf.monochrome.android.ui.mixer.canvas.DspCanvas(
                        state = canvasState,
                        dspEnabled = enabled,
                        audioAmplitude = audioAmplitude,
                        onViewportPan = { viewModel.onViewportPan(it) },
                        onViewportZoom = { zoom, centroid -> viewModel.onViewportZoom(zoom, centroid) },
                        onNodeSelected = { viewModel.onNodeSelected(it) },
                        onNodeDragStart = { viewModel.onNodeDragStart(it) },
                        onNodeDrag = { id, delta -> viewModel.onNodeDrag(id, delta) },
                        onNodeDragEnd = { viewModel.onNodeDragEnd(it) },
                        onNodeDoubleTap = { viewModel.onNodeDoubleTap(it) },
                        onNodeLongPress = { viewModel.onNodeLongPress(it) },
                        onDeleteConfirmed = { viewModel.onDeleteConfirmed(it) },
                        onDeleteCancelled = { viewModel.onDeleteCancelled() },
                        onCanvasTap = { }
                    )
                }
            }
        } else {
            // ── FL Studio Console View ──────────────────────────────────
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ──────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FLColors.headerBg)
                        .padding(top = statusBarPadding)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)
                    ) {
                        // Back button
                        IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FLColors.textWhite, modifier = Modifier.size(20.dp))
                        }

                        // Title
                        Text(
                            text = "Mixer",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = FLColors.textBright
                        )

                        Box(modifier = Modifier.weight(1f))

                        // Insert-rack toggle
                        IconButton(onClick = { showInsertRack = !showInsertRack }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Tune, "Insert Rack",
                                tint = if (showInsertRack) FLColors.accent else FLColors.textDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Canvas toggle
                        IconButton(onClick = { showCanvas = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.AccountTree, "DSP Canvas",
                                tint = FLColors.textDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // DSP on/off
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (enabled) "ON" else "OFF",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (enabled) FLColors.accent else FLColors.textDim
                            )
                            Switch(
                                checked = enabled,
                                onCheckedChange = { viewModel.setEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = FLColors.accent,
                                    checkedThumbColor = FLColors.background
                                )
                            )
                        }
                    }

                    // Preset bar
                    PresetBar(
                        currentPresetName = currentPresetName,
                        presets = presets,
                        onSave = { viewModel.savePreset(it) },
                        onLoad = { viewModel.loadPreset(it) },
                        onDelete = { viewModel.deletePreset(it) }
                    )

                    HorizontalDivider(color = FLColors.stripBorder, modifier = Modifier.padding(horizontal = MonoDimens.spacingSm))
                }

                // ── Channel strips + insert rack ────────────────────────
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    // Horizontal-scrolling channel strips
                    LazyRow(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = MonoDimens.spacingXs),
                        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs)
                    ) {
                        itemsIndexed(buses) { index, bus ->
                            FLChannelStrip(
                                bus = bus,
                                isSelected = index == selectedBusIndex,
                                levels = busLevels.getOrNull(index) ?: tf.monochrome.android.audio.dsp.model.BusLevels(),
                                onSelect = {
                                    viewModel.selectBus(index)
                                    showInsertRack = true
                                },
                                onGainChange = { viewModel.setBusGain(index, it) },
                                onPanChange = { viewModel.setBusPan(index, it) },
                                onToggleMute = { viewModel.toggleMute(index) },
                                onToggleSolo = { viewModel.toggleSolo(index) }
                            )
                        }
                    }

                    // Insert rack — right panel
                    AnimatedVisibility(
                        visible = showInsertRack,
                        enter = slideInHorizontally(initialOffsetX = { it }),
                        exit = slideOutHorizontally(targetOffsetX = { it })
                    ) {
                        InsertRack(
                            bus = selectedBus,
                            busIndex = selectedBusIndex,
                            editingPlugin = editingPlugin,
                            allBuses = buses,
                            onSlotTap = { slotIdx -> viewModel.editPlugin(selectedBusIndex, slotIdx) },
                            onAddPlugin = { viewModel.showAddPlugin() },
                            onPluginBypass = { busIdx, slotIdx -> viewModel.togglePluginBypass(busIdx, slotIdx) },
                            onPluginRemove = { busIdx, slotIdx -> viewModel.removePlugin(busIdx, slotIdx) },
                            onParameterChange = { busIdx, slotIdx, paramIdx, value ->
                                viewModel.setParameter(busIdx, slotIdx, paramIdx, value)
                            },
                            onPluginDryWet = { busIdx, slotIdx, dw ->
                                viewModel.setPluginDryWet(busIdx, slotIdx, dw)
                            },
                            onBusInputToggle = { busIdx, enabled ->
                                viewModel.setBusInputEnabled(busIdx, enabled)
                            },
                            onDismissEditor = { viewModel.dismissPluginEditor() },
                            onClose = { showInsertRack = false }
                        )
                    }
                }
            }
        }
    }

    // ── Plugin picker dialog ────────────────────────────────────────────
    if (showPluginPicker) {
        PluginPickerDialog(
            onDismiss = { viewModel.dismissPluginPicker() },
            onSelect = { viewModel.addPlugin(it) }
        )
    }

    // ── Plugin editor sheet (canvas view) ───────────────────────────────
    if (showCanvas) {
        editingPlugin?.let { (busIdx, slotIdx) ->
            val bus = buses.getOrNull(busIdx)
            val plugin = bus?.plugins?.getOrNull(slotIdx)
            if (plugin != null) {
                PluginEditorSheet(
                    plugin = plugin,
                    busIndex = busIdx,
                    slotIndex = slotIdx,
                    onParameterChange = { b, s, p, v -> viewModel.setParameter(b, s, p, v) },
                    onDismiss = { viewModel.dismissPluginEditor() }
                )
            }
        }
    }
}
