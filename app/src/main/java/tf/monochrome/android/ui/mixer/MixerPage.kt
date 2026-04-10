package tf.monochrome.android.ui.mixer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.mixer.canvas.DspCanvas
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * FL Studio-inspired mixer console page with liquid-glass aesthetic.
 *
 * Layout:
 *   - Header:  title · preset controls · insert-rack toggle · canvas toggle · DSP on/off
 *   - Body:    horizontally-scrollable channel strips  |  insert-rack side panel
 *
 * The DSP node canvas is still accessible via a toggle button in the
 * header — it replaces the console view when active.
 *
 * This replaces the previous Macro / Micro pager layout while keeping
 * every ViewModel API and the glass morphism styling intact.
 */
@Composable
fun MixerPage(
    modifier: Modifier = Modifier,
    viewModel: MixerViewModel = hiltViewModel()
) {
    val enabled          by viewModel.enabled.collectAsState()
    val buses            by viewModel.buses.collectAsState()
    val busLevels        by viewModel.busLevels.collectAsState()
    val selectedBusIndex by viewModel.selectedBusIndex.collectAsState()
    val presets           by viewModel.presets.collectAsState()
    val currentPresetName by viewModel.currentPresetName.collectAsState()
    val showPluginPicker by viewModel.showPluginPicker.collectAsState()
    val editingPlugin    by viewModel.editingPlugin.collectAsState()
    val canvasState      by viewModel.canvasState.collectAsState()
    val audioAmplitude   by viewModel.audioAmplitude.collectAsState()

    val selectedBus = buses.getOrNull(selectedBusIndex)

    var showCanvas     by remember { mutableStateOf(false) }
    var showInsertRack by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {

        if (showCanvas) {
            // ── DSP Canvas View ─────────────────────────────────────────
            CanvasView(
                viewModel      = viewModel,
                canvasState    = canvasState,
                enabled        = enabled,
                audioAmplitude = audioAmplitude,
                onBack         = { showCanvas = false }
            )
        } else {
            // ── FL Studio Console View ──────────────────────────────────
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header bar ──────────────────────────────────────────
                MixerHeader(
                    enabled          = enabled,
                    currentPresetName = currentPresetName,
                    presets          = presets,
                    showInsertRack   = showInsertRack,
                    onEnabledChange  = { viewModel.setEnabled(it) },
                    onToggleInsertRack = { showInsertRack = !showInsertRack },
                    onToggleCanvas   = { showCanvas = true },
                    onPresetSave     = { viewModel.savePreset(it) },
                    onPresetLoad     = { viewModel.loadPreset(it) },
                    onPresetDelete   = { viewModel.deletePreset(it) }
                )

                // ── Main content: strips  +  insert rack ───────────────
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    // Channel strips (horizontal scroll)
                    LazyRow(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding        = PaddingValues(horizontal = MonoDimens.spacingXs),
                        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs)
                    ) {
                        itemsIndexed(buses) { index, bus ->
                            ConsoleBusStrip(
                                bus          = bus,
                                isSelected   = index == selectedBusIndex,
                                levels       = busLevels.getOrNull(index) ?: tf.monochrome.android.audio.dsp.model.BusLevels(),
                                onSelect     = {
                                    viewModel.selectBus(index)
                                    showInsertRack = true
                                },
                                onGainChange = { viewModel.setBusGain(index, it) },
                                onPanChange  = { viewModel.setBusPan(index, it) },
                                onToggleMute = { viewModel.toggleMute(index) },
                                onToggleSolo = { viewModel.toggleSolo(index) }
                            )
                        }
                    }

                    // Insert rack — right-side panel (animated)
                    AnimatedVisibility(
                        visible = showInsertRack,
                        enter   = slideInHorizontally(initialOffsetX = { it }),
                        exit    = slideOutHorizontally(targetOffsetX = { it })
                    ) {
                        InsertRack(
                            bus              = selectedBus,
                            busIndex         = selectedBusIndex,
                            editingPlugin    = editingPlugin,
                            allBuses         = buses,
                            onSlotTap        = { slotIdx ->
                                viewModel.editPlugin(selectedBusIndex, slotIdx)
                            },
                            onAddPlugin      = { viewModel.showAddPlugin() },
                            onPluginBypass   = { busIdx, slotIdx ->
                                viewModel.togglePluginBypass(busIdx, slotIdx)
                            },
                            onPluginRemove   = { busIdx, slotIdx ->
                                viewModel.removePlugin(busIdx, slotIdx)
                            },
                            onParameterChange = { busIdx, slotIdx, paramIdx, value ->
                                viewModel.setParameter(busIdx, slotIdx, paramIdx, value)
                            },
                            onPluginDryWet   = { busIdx, slotIdx, dw ->
                                viewModel.setPluginDryWet(busIdx, slotIdx, dw)
                            },
                            onBusInputToggle = { busIdx, enabled ->
                                viewModel.setBusInputEnabled(busIdx, enabled)
                            },
                            onDismissEditor  = { viewModel.dismissPluginEditor() },
                            onClose          = { showInsertRack = false }
                        )
                    }
                }
            }
        }
    }

    // ── Plugin picker dialog ─────────────────────────────────────────────
    if (showPluginPicker) {
        PluginPickerDialog(
            onDismiss = { viewModel.dismissPluginPicker() },
            onSelect  = { viewModel.addPlugin(it) }
        )
    }

    // ── Plugin editor sheet (canvas view only) ───────────────────────────
    if (showCanvas) {
        editingPlugin?.let { (busIdx, slotIdx) ->
            val bus    = buses.getOrNull(busIdx)
            val plugin = bus?.plugins?.getOrNull(slotIdx)
            if (plugin != null) {
                PluginEditorSheet(
                    plugin           = plugin,
                    busIndex         = busIdx,
                    slotIndex        = slotIdx,
                    onParameterChange = { b, s, p, v -> viewModel.setParameter(b, s, p, v) },
                    onDismiss        = { viewModel.dismissPluginEditor() }
                )
            }
        }
    }
}

// ── Header bar ──────────────────────────────────────────────────────────

@Composable
private fun MixerHeader(
    enabled: Boolean,
    currentPresetName: String?,
    presets: List<tf.monochrome.android.audio.dsp.model.MixPreset>,
    showInsertRack: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onToggleInsertRack: () -> Unit,
    onToggleCanvas: () -> Unit,
    onPresetSave: (String) -> Unit,
    onPresetLoad: (tf.monochrome.android.audio.dsp.model.MixPreset) -> Unit,
    onPresetDelete: (Long) -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                shape     = MonoDimens.shapeSm,
                tintAlpha = 0.30f
            )
            .padding(top = statusBarPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)
        ) {
            // Title
            Text(
                text       = "Mix",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Spacer pushes controls right
            Box(modifier = Modifier.weight(1f))

            // Insert-rack toggle
            IconButton(
                onClick  = onToggleInsertRack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Toggle Insert Rack",
                    tint     = if (showInsertRack) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Canvas toggle
            IconButton(
                onClick  = onToggleCanvas,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = "DSP Canvas",
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // DSP on/off
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text       = if (enabled) "ON" else "OFF",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked         = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        // Preset bar
        PresetBar(
            currentPresetName = currentPresetName,
            presets           = presets,
            onSave            = onPresetSave,
            onLoad            = onPresetLoad,
            onDelete          = onPresetDelete
        )

        HorizontalDivider(
            color    = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            modifier = Modifier.padding(horizontal = MonoDimens.spacingMd)
        )
    }
}

// ── Canvas sub-view ─────────────────────────────────────────────────────

@Composable
private fun CanvasView(
    viewModel: MixerViewModel,
    canvasState: tf.monochrome.android.ui.mixer.canvas.model.CanvasState,
    enabled: Boolean,
    audioAmplitude: Float,
    onBack: () -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = Modifier.fillMaxSize()) {
        // Canvas header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(
                    shape     = MonoDimens.shapeSm,
                    tintAlpha = 0.30f
                )
                .padding(top = statusBarPadding)
                .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text       = "DSP Canvas",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick  = onBack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Console View",
                    tint     = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DspCanvas(
                state             = canvasState,
                dspEnabled        = enabled,
                audioAmplitude    = audioAmplitude,
                onViewportPan     = { viewModel.onViewportPan(it) },
                onViewportZoom    = { zoom, centroid -> viewModel.onViewportZoom(zoom, centroid) },
                onNodeSelected    = { viewModel.onNodeSelected(it) },
                onNodeDragStart   = { viewModel.onNodeDragStart(it) },
                onNodeDrag        = { id, delta -> viewModel.onNodeDrag(id, delta) },
                onNodeDragEnd     = { viewModel.onNodeDragEnd(it) },
                onNodeDoubleTap   = { viewModel.onNodeDoubleTap(it) },
                onNodeLongPress   = { viewModel.onNodeLongPress(it) },
                onDeleteConfirmed = { viewModel.onDeleteConfirmed(it) },
                onDeleteCancelled = { viewModel.onDeleteCancelled() },
                onCanvasTap       = { }
            )

            // FAB to add plugin on canvas view
            FloatingActionButton(
                onClick        = { viewModel.showAddPlugin() },
                shape          = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                elevation      = FloatingActionButtonDefaults.elevation(4.dp),
                modifier       = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = MonoDimens.spacingLg, bottom = MonoDimens.spacingLg)
                    .size(48.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Plugin",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
