package tf.monochrome.android.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import java.util.Locale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import android.content.Context
import androidx.compose.ui.text.input.PasswordVisualTransformation
import tf.monochrome.android.domain.model.NowPlayingViewMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.AudioQuality
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import tf.monochrome.android.ui.eq.EqViewModel
import tf.monochrome.android.ui.eq.EqProfileMiniGraph
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.theme.themeDisplayNames

private val settingsTabs = listOf("Appearance", "Interface", "Scrobbling", "Audio", "Equalizer", "Library", "Downloads", "Instances", "System")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    initialTab: Int = 0,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        // Tab row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            settingsTabs.forEachIndexed { index, tab ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    label = { Text(tab, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        when (selectedTab) {
            0 -> AppearanceTab(viewModel)
            1 -> InterfaceTab(viewModel)
            2 -> ScrobblingTab(viewModel)
            3 -> AudioTab(viewModel, navController)
            4 -> EqualizerTab(navController)
            5 -> LibrarySettingsTab(viewModel)
            6 -> DownloadsTab(viewModel)
            7 -> InstancesTab(viewModel)
            8 -> SystemTab(viewModel, navController)
        }
    }
}

// ─── Tab 4: Equalizer ──────────────────────────────────────────────────
@Composable
private fun EqualizerTab(navController: NavController, eqViewModel: EqViewModel = hiltViewModel()) {
    val eqEnabled by eqViewModel.eqEnabled.collectAsState()
    val selectedTarget by eqViewModel.selectedTarget.collectAsState()
    val selectedHeadphone by eqViewModel.selectedHeadphone.collectAsState()
    val allPresets by eqViewModel.allPresets.collectAsState()
    val activePreset by eqViewModel.activePreset.collectAsState()
    var presetToDelete by remember { mutableStateOf<EqPreset?>(null) }

    SettingsTabContent {
        SettingsGroupHeader("Equalizer")
        SettingSwitchItem(
            title = "Enable Equalizer",
            subtitle = "Apply EQ processing to playback",
            checked = eqEnabled,
            onCheckedChange = { eqViewModel.toggleEq() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingItem(
            title = "Target Curve",
            subtitle = selectedTarget.label,
            onClick = {}
        )

        SettingItem(
            title = "Headphone",
            subtitle = selectedHeadphone?.name ?: "None selected",
            onClick = {}
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = {
                navController.navigate("settings?tab=4") {
                    popUpTo("settings?tab={tab}") { inclusive = true }
                }
                navController.navigate("equalizer")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Precision AutoEQ")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                navController.navigate("settings?tab=4") {
                    popUpTo("settings?tab={tab}") { inclusive = true }
                }
                navController.navigate("parametric_eq")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Parametric EQ")
        }

        // ─── Saved Profiles ───
        if (allPresets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroupHeader("Saved Profiles")

            allPresets.forEach { preset ->
                val isActive = activePreset?.id == preset.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .liquidGlass(shape = RoundedCornerShape(10.dp))
                        .bounceClick(onClick = { eqViewModel.loadPreset(preset.id) })
                ) {
                    EqProfileMiniGraph(
                        bands = preset.bands,
                        preamp = preset.preamp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isActive) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Active",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${preset.bands.size} bands · ${preset.targetName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (preset.isCustom) {
                            IconButton(
                                onClick = { presetToDelete = preset },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete preset",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Delete \"${preset.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    eqViewModel.deletePreset(preset.id)
                    presetToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ─── Tab 1: Appearance ─────────────────────────────────────────────────
@Composable
private fun AppearanceTab(viewModel: SettingsViewModel) {
    val themeName by viewModel.theme.collectAsState()
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val fontScale by viewModel.fontScale.collectAsState()
    val customFontUri by viewModel.customFontUri.collectAsState()
    val availableFonts by viewModel.availableFonts.collectAsState()
    var showThemeDropdown by remember { mutableStateOf(false) }
    var fontScaleText by remember(fontScale) { mutableStateOf(String.format(Locale.US, "%.2f", fontScale)) }

    // File picker for .ttf font import
    val context = LocalContext.current
    val fontPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importFont(it) }
    }

    SettingsTabContent {
        SettingsGroupHeader("Theme")
        SettingItem(title = "Color Theme", subtitle = themeDisplayNames[themeName] ?: themeName, onClick = { showThemeDropdown = true })
        DropdownMenu(expanded = showThemeDropdown, onDismissRequest = { showThemeDropdown = false }) {
            themeDisplayNames.forEach { (key, displayName) ->
                DropdownMenuItem(text = { Text(displayName) }, onClick = { viewModel.setTheme(key); showThemeDropdown = false })
            }
        }
        SettingSwitchItem(
            title = "Dynamic Colors",
            subtitle = "Extract accent colors from album art",
            checked = dynamicColors,
            onCheckedChange = { viewModel.setDynamicColors(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Typography")

        // Font scale slider
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Font Scale",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = fontScaleText,
                    onValueChange = { newText ->
                        fontScaleText = newText
                        newText.toFloatOrNull()?.let { value ->
                            if (value in 0.5f..2.0f) {
                                viewModel.setFontScale(value)
                            }
                        }
                    },
                    modifier = Modifier.width(80.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true
                )
            }
            Text(
                "Preview: The quick brown fox jumps over the lazy dog",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = fontScale,
                onValueChange = { newScale ->
                    val rounded = (Math.round(newScale * 100f) / 100f)
                    viewModel.setFontScale(rounded)
                    fontScaleText = String.format(Locale.US, "%.2f", rounded)
                },
                valueRange = 0.5f..2.0f,
                steps = 29,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("1.00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("2.00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Custom font import matching Font Library requirements
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Font Library",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (availableFonts.isNotEmpty()) {
                availableFonts.forEach { file ->
                    val isSelected = file.absolutePath == customFontUri
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectFont(file) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            file.nameWithoutExtension,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.removeFont(file) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { fontPickerLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add Font")
                    }
                    if (customFontUri != null) {
                        OutlinedButton(
                            onClick = { viewModel.resetDefaultFont() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Reset")
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { fontPickerLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Custom Font (.ttf)")
                }
            }
        }
    }
}

// ─── Tab 2: Interface ──────────────────────────────────────────────────
@Composable
private fun InterfaceTab(viewModel: SettingsViewModel) {
    val gapless by viewModel.gaplessPlayback.collectAsState()
    val explicit by viewModel.showExplicitBadges.collectAsState()
    val confirmQueue by viewModel.confirmClearQueue.collectAsState()
    val sensitivity by viewModel.visualizerSensitivity.collectAsState()
    val brightness by viewModel.visualizerBrightness.collectAsState()
    val engineEnabled by viewModel.visualizerEngineEnabled.collectAsState()
    val autoShuffle by viewModel.visualizerAutoShuffle.collectAsState()
    val presetId by viewModel.visualizerPresetId.collectAsState()
    val rotationSeconds by viewModel.visualizerRotationSeconds.collectAsState()
    val textureSize by viewModel.visualizerTextureSize.collectAsState()
    val meshX by viewModel.visualizerMeshX.collectAsState()
    val meshY by viewModel.visualizerMeshY.collectAsState()
    val targetFps by viewModel.visualizerTargetFps.collectAsState()
    val vsyncEnabled by viewModel.visualizerVsyncEnabled.collectAsState()
    val showFps by viewModel.visualizerShowFps.collectAsState()
    val fullscreen by viewModel.visualizerFullscreen.collectAsState()
    val touchWaveform by viewModel.visualizerTouchWaveform.collectAsState()
    val engineStatus by viewModel.visualizerEngineStatus.collectAsState()
    val presets by viewModel.visualizerPresets.collectAsState()
    val spectrumEnabled by viewModel.spectrumAnalyzerEnabled.collectAsState()
    val spectrumShowOnNowPlaying by viewModel.spectrumShowOnNowPlaying.collectAsState()
    val spectrumFftSize by viewModel.spectrumFftSize.collectAsState()
    val spectrumBins by viewModel.spectrumBins.collectAsState()
    val selectedPresetName = presets.firstOrNull { it.id == presetId }?.displayName ?: "Auto-select bundled preset"
    var showTextureDropdown by remember { mutableStateOf(false) }
    var showPresetDropdown by remember { mutableStateOf(false) }
    var showFftDropdown by remember { mutableStateOf(false) }

    SettingsTabContent {
        SettingsGroupHeader("Playback")
        SettingSwitchItem(
            title = "Gapless Playback",
            subtitle = "Remove silence between tracks",
            checked = gapless,
            onCheckedChange = { viewModel.setGaplessPlayback(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Display")
        SettingSwitchItem(
            title = "Show Explicit Badges",
            subtitle = "Display 'E' badge on explicit tracks",
            checked = explicit,
            onCheckedChange = { viewModel.setShowExplicitBadges(it) }
        )
        val romaji by viewModel.romajiLyrics.collectAsState()
        SettingSwitchItem(
            title = "Romaji Lyrics",
            subtitle = "Transliterate Japanese lyrics to Latin characters",
            checked = romaji,
            onCheckedChange = { viewModel.setRomajiLyrics(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Now Playing")
        val viewMode by viewModel.nowPlayingViewMode.collectAsState()
        var showModeDropdown by remember { mutableStateOf(false) }
        SettingItem(
            title = "View Mode", 
            subtitle = "Action when clicking album art: ${viewMode.displayName}", 
            onClick = { showModeDropdown = true }
        )
        DropdownMenu(expanded = showModeDropdown, onDismissRequest = { showModeDropdown = false }) {
            NowPlayingViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) }, 
                    onClick = { viewModel.setNowPlayingViewMode(mode); showModeDropdown = false }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Spectrum Analyzer")
        SettingSwitchItem(
            title = "Show Spectrum Analyzer",
            subtitle = "Display live audio spectrum on the player and EQ screens",
            checked = spectrumEnabled,
            onCheckedChange = { viewModel.setSpectrumAnalyzerEnabled(it) }
        )
        SettingSwitchItem(
            title = "Show on Now Playing",
            subtitle = "Overlay the spectrum on the album-art hero",
            checked = spectrumShowOnNowPlaying,
            onCheckedChange = { viewModel.setSpectrumShowOnNowPlaying(it) }
        )
        val fftLabel = when (spectrumFftSize) {
            4096 -> "Low (4096)"
            16384 -> "High (16384)"
            else -> "Medium (8192)"
        }
        SettingItem(
            title = "FFT Size",
            subtitle = fftLabel,
            onClick = { showFftDropdown = true }
        )
        DropdownMenu(expanded = showFftDropdown, onDismissRequest = { showFftDropdown = false }) {
            listOf(
                4096 to "Low (4096)",
                8192 to "Medium (8192)",
                16384 to "High (16384)"
            ).forEach { (size, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        viewModel.setSpectrumFftSize(size)
                        showFftDropdown = false
                    }
                )
            }
        }
        if (spectrumEnabled) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                viewModel.acquireSpectrum()
                onDispose { viewModel.releaseSpectrum() }
            }
            Spacer(modifier = Modifier.height(8.dp))
            tf.monochrome.android.ui.player.SpectrumOverlay(
                bins = spectrumBins,
                color = MaterialTheme.colorScheme.primary,
                height = 96.dp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Audio Visualizer")
        SettingSwitchItem(
            title = "Use projectM Visualizer",
            subtitle = "Use the native OpenGL renderer when the bridge is available",
            checked = engineEnabled,
            onCheckedChange = { viewModel.setVisualizerEngineEnabled(it) }
        )
        SettingSwitchItem(
            title = "Auto-shuffle Presets",
            subtitle = "Rotate bundled presets automatically during playback",
            checked = autoShuffle,
            onCheckedChange = { viewModel.setVisualizerAutoShuffle(it) }
        )
        SettingItem(
            title = "Default Preset",
            subtitle = selectedPresetName,
            onClick = { showPresetDropdown = true }
        )
        DropdownMenu(expanded = showPresetDropdown, onDismissRequest = { showPresetDropdown = false }) {
            DropdownMenuItem(
                text = { Text("Auto-select bundled preset") },
                onClick = {
                    viewModel.setVisualizerPresetId(null)
                    showPresetDropdown = false
                }
            )
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.displayName) },
                    onClick = {
                        viewModel.setVisualizerPresetId(preset.id)
                        showPresetDropdown = false
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Graphics")
        
        SettingItem(
            title = "Texture Size",
            subtitle = "$textureSize",
            onClick = { showTextureDropdown = true }
        )
        DropdownMenu(expanded = showTextureDropdown, onDismissRequest = { showTextureDropdown = false }) {
            listOf(256, 512, 1024, 2048, 4096).forEach { size ->
                DropdownMenuItem(
                    text = { Text(size.toString()) },
                    onClick = {
                        viewModel.setVisualizerTextureSize(size)
                        showTextureDropdown = false
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("Mesh X: $meshX", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = meshX.toFloat(),
            onValueChange = { viewModel.setVisualizerMeshX(it.toInt()) },
            valueRange = 8f..128f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("Mesh Y: $meshY", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = meshY.toFloat(),
            onValueChange = { viewModel.setVisualizerMeshY(it.toInt()) },
            valueRange = 8f..128f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (vsyncEnabled) "Target FPS: $targetFps" else "Target FPS: $targetFps (vsync off)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = targetFps.toFloat(),
            onValueChange = { viewModel.setVisualizerTargetFps(it.toInt()) },
            valueRange = 30f..144f,
            modifier = Modifier.fillMaxWidth()
        )

        SettingSwitchItem(
            title = "Disable vsync",
            subtitle = "Let the visualizer exceed display refresh (capped by Target FPS). Increases battery and heat — Adreno honours this; some GPUs ignore it.",
            checked = !vsyncEnabled,
            onCheckedChange = { viewModel.setVisualizerVsyncEnabled(!it) }
        )

        SettingSwitchItem(
            title = "Show FPS",
            subtitle = "Display visualizer framerate counter",
            checked = showFps,
            onCheckedChange = { viewModel.setVisualizerShowFps(it) }
        )

        SettingSwitchItem(
            title = "Fullscreen",
            subtitle = "Fill screen in Now Playing visualizer view",
            checked = fullscreen,
            onCheckedChange = { viewModel.setVisualizerFullscreen(it) }
        )
        SettingSwitchItem(
            title = "Touch Waveform",
            subtitle = "Draw audio waveforms between touch points on the visualizer",
            checked = touchWaveform,
            onCheckedChange = { viewModel.setVisualizerTouchWaveform(it) }
        )
        SettingItem(
            title = "Engine Status",
            subtitle = "${engineStatus.badge} • assets ${engineStatus.assetVersion}",
            onClick = {}
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Preset Rotation: ${rotationSeconds}s",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = rotationSeconds.toFloat(),
            onValueChange = { viewModel.setVisualizerRotationSeconds(it.toInt()) },
            valueRange = 5f..120f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("Sensitivity: $sensitivity%", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("Controls intensity (High = Epilepsy Warning)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = sensitivity.toFloat(),
            onValueChange = { viewModel.setVisualizerSensitivity(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("Brightness: $brightness%", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = brightness.toFloat(),
            onValueChange = { viewModel.setVisualizerBrightness(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Queue")
        SettingSwitchItem(
            title = "Confirm Before Clearing Queue",
            subtitle = "Ask before replacing the current queue",
            checked = confirmQueue,
            onCheckedChange = { viewModel.setConfirmClearQueue(it) }
        )
    }
}

// ─── Tab 3: Scrobbling ─────────────────────────────────────────────────
@Composable
private fun ScrobblingTab(viewModel: SettingsViewModel) {
    val lastFmEnabled by viewModel.lastFmEnabled.collectAsState()
    val lastFmUsername by viewModel.lastFmUsername.collectAsState()
    val lbEnabled by viewModel.listenBrainzEnabled.collectAsState()
    val lbToken by viewModel.listenBrainzToken.collectAsState()

    var showLastFmDialog by remember { mutableStateOf(false) }
    var showLbDialog by remember { mutableStateOf(false) }

    if (showLastFmDialog) {
        var sessionInput by remember { mutableStateOf("") }
        var usernameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLastFmDialog = false },
            title = { Text("Last.fm") },
            text = {
                Column {
                    OutlinedTextField(
                        value = usernameInput, onValueChange = { usernameInput = it },
                        label = { Text("Username") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sessionInput, onValueChange = { sessionInput = it },
                        label = { Text("Session Key") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sessionInput.isNotBlank() && usernameInput.isNotBlank()) {
                        viewModel.setLastFmSession(sessionInput, usernameInput)
                    }
                    showLastFmDialog = false
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showLastFmDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLbDialog) {
        var tokenInput by remember { mutableStateOf(lbToken ?: "") }
        AlertDialog(
            onDismissRequest = { showLbDialog = false },
            title = { Text("ListenBrainz Token") },
            text = {
                OutlinedTextField(
                    value = tokenInput, onValueChange = { tokenInput = it },
                    label = { Text("User Token") }, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tokenInput.isNotBlank()) viewModel.setListenBrainzToken(tokenInput)
                    else viewModel.clearListenBrainzToken()
                    showLbDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showLbDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsTabContent {
        SettingsGroupHeader("Last.fm")
        SettingItem(
            title = "Last.fm",
            subtitle = if (lastFmEnabled) "Connected as ${lastFmUsername ?: "user"}" else "Not connected",
            onClick = { showLastFmDialog = true }
        )
        if (lastFmEnabled) {
            TextButton(onClick = { viewModel.clearLastFmSession() }) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("ListenBrainz")
        SettingItem(
            title = "ListenBrainz",
            subtitle = if (lbEnabled) "Connected" else "Not connected",
            onClick = { showLbDialog = true }
        )
        if (lbEnabled) {
            TextButton(onClick = { viewModel.clearListenBrainzToken() }) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─── Tab 4: Audio ──────────────────────────────────────────────────────
@Composable
private fun AudioTab(viewModel: SettingsViewModel, navController: NavController) {
    val wifiQuality by viewModel.wifiQuality.collectAsState()
    val cellularQuality by viewModel.cellularQuality.collectAsState()
    val crossfade by viewModel.crossfadeDuration.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val preservePitch by viewModel.preservePitch.collectAsState()
    var showWifiDropdown by remember { mutableStateOf(false) }
    var showCellularDropdown by remember { mutableStateOf(false) }
    var speedText by remember(playbackSpeed) { mutableStateOf(String.format(Locale.US, "%.2f", playbackSpeed)) }

    SettingsTabContent {
        SettingsGroupHeader("Streaming Quality")
        SettingItem(title = "Wi-Fi Streaming", subtitle = wifiQuality.displayName, onClick = { showWifiDropdown = true })
        DropdownMenu(expanded = showWifiDropdown, onDismissRequest = { showWifiDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setWifiQuality(q); showWifiDropdown = false })
            }
        }

        SettingItem(title = "Cellular Streaming", subtitle = cellularQuality.displayName, onClick = { showCellularDropdown = true })
        DropdownMenu(expanded = showCellularDropdown, onDismissRequest = { showCellularDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setCellularQuality(q); showCellularDropdown = false })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Audio Processing")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { navController.navigate("oxford?tab=0") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Seap Compressor")
            }
            OutlinedButton(
                onClick = { navController.navigate("oxford?tab=1") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Seap Inflator")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        DspBlockSizeSelector(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        UsbBitPerfectToggle(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        Text("Crossfade: ${crossfade}s", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("Blend between tracks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = crossfade.toFloat(),
            onValueChange = { viewModel.setCrossfadeDuration(it.toInt()) },
            valueRange = 0f..12f,
            steps = 11,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Playback Speed")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Slider(
                value = playbackSpeed,
                onValueChange = { newSpeed ->
                    val rounded = (Math.round(newSpeed * 100) / 100f)
                    speedText = String.format(Locale.US, "%.2f", rounded)
                    viewModel.setPlaybackSpeed(rounded)
                },
                valueRange = 0.25f..3.0f,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = speedText,
                onValueChange = { input ->
                    speedText = input
                    input.toFloatOrNull()?.let { parsed ->
                        val clamped = parsed.coerceIn(0.01f, 100f)
                        viewModel.setPlaybackSpeed(clamped)
                    }
                },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Text("x", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = {
                viewModel.setPlaybackSpeed(1.0f)
                speedText = "1.00"
            }) {
                Text("Reset")
            }
        }

        SettingSwitchItem(
            title = "Preserve Pitch",
            subtitle = "Keep original pitch when changing speed",
            checked = preservePitch,
            onCheckedChange = { viewModel.setPreservePitch(it) }
        )
    }
}



// User-facing DSP buffer (block) size selector. Smaller = lower latency
// + more JNI / native overhead per second; larger = lower CPU at the
// cost of slightly later parameter response. Mirrors the buffer-size
// dropdown most pro-audio apps expose.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DspBlockSizeSelector(viewModel: SettingsViewModel) {
    val current by viewModel.dspBlockSize.collectAsState()
    Text(
        text = "DSP Block Size",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = "Smaller = lower latency, more CPU. Default 1024.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        viewModel.dspBlockSizes.forEach { size ->
            FilterChip(
                selected = size == current,
                onClick = { viewModel.setDspBlockSize(size) },
                label = { Text(formatBlockSize(size)) },
            )
        }
    }
}

/** "8192" → "8K", "16384" → "16K" so the chip row stays readable. */
private fun formatBlockSize(size: Int): String = when {
    size >= 1024 && size % 1024 == 0 -> "${size / 1024}K"
    else -> size.toString()
}

/**
 * Settings switch that pins the player's output to an attached USB Audio
 * Class DAC via setPreferredAudioDevice. The device-name line below the
 * switch updates live as the DAC plugs / unplugs.
 */
@Composable
private fun UsbBitPerfectToggle(viewModel: SettingsViewModel) {
    val enabled by viewModel.usbBitPerfectEnabled.collectAsState()
    val deviceName by viewModel.usbOutputDeviceName.collectAsState()
    SettingSwitchItem(
        title = "USB DAC bit-perfect routing",
        subtitle = when {
            !enabled -> "Off — uses system audio output."
            deviceName != null -> "On → $deviceName"
            else -> "On — plug in a USB DAC to start routing."
        },
        checked = enabled,
        onCheckedChange = { viewModel.setUsbBitPerfectEnabled(it) },
    )

    val exclusiveEnabled by viewModel.usbExclusiveBitPerfectEnabled.collectAsState()
    val exclusiveStatus by viewModel.usbExclusiveStatus.collectAsState()
    SettingSwitchItem(
        title = "Exclusive USB DAC (bypass Android audio)",
        subtitle = exclusiveSubtitle(exclusiveEnabled, exclusiveStatus),
        checked = exclusiveEnabled,
        onCheckedChange = { viewModel.setUsbExclusiveBitPerfectEnabled(it) },
    )
}

/**
 * Renders the controller's real state instead of just echoing the
 * toggle. Status flows through:
 *  Disabled → NoDevice → AwaitingPermission → DeviceOpen
 *  (→ InterfaceClaimed → Streaming once Stage 2/3 land).
 *
 * Honest about the Stage-1 ceiling: even when everything works, the
 * iso pump isn't running yet so audio is still going through the
 * standard sink. The DeviceOpen string says so.
 */
private fun exclusiveSubtitle(
    enabled: Boolean,
    status: tf.monochrome.android.audio.usb.UsbExclusiveController.Status,
): String {
    if (!enabled) {
        return "Off — UAPP-style libusb output. Needs Developer Options " +
            "→ Disable USB audio routing → ON, otherwise Android's audio " +
            "HAL will keep grabbing the DAC and fight us for it."
    }
    return when (status) {
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Disabled ->
            "Starting up…"
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.NoDevice ->
            "On, waiting for a USB DAC to be plugged in."
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.AwaitingPermission ->
            "DAC detected — accept the system USB-permission prompt."
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.DeviceOpen ->
            "DAC handle acquired ✓ — bypass engages on the next " +
            "track (or skip the current track to engage now)."
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.InterfaceClaimed ->
            "Streaming interface claimed ✓"
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Streaming ->
            "Bit-perfect: bypassing Android audio ✓ (EQ / DSP still active)"
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Error ->
            "Couldn't claim the DAC's streaming interface — Android's " +
            "audio HAL still owns it. Turn ON Developer Options → " +
            "Disable USB audio routing, then re-toggle Exclusive USB DAC. " +
            "Audio is currently flowing through the standard sink."
    }
}

// ─── Tab 6: Downloads ──────────────────────────────────────────────────
@Composable
private fun DownloadsTab(viewModel: SettingsViewModel) {
    val downloadQuality by viewModel.downloadQuality.collectAsState()
    val downloadFolder by viewModel.downloadFolderUri.collectAsState()
    var showQualityDropdown by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist permission across reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadFolderUri(uri.toString())
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Downloads") },
            text = { Text("This will delete all downloaded tracks from your device. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val downloadDir = java.io.File(context.getExternalFilesDir(null), "downloads")
                    downloadDir.deleteRecursively()
                    showClearDialog = false
                }) { Text("Delete All", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsTabContent {
        SettingsGroupHeader("Download Quality")
        SettingItem(title = "Quality", subtitle = downloadQuality.displayName, onClick = { showQualityDropdown = true })
        DropdownMenu(expanded = showQualityDropdown, onDismissRequest = { showQualityDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setDownloadQuality(q); showQualityDropdown = false })
            }
        }

        val dlLyrics by viewModel.downloadLyrics.collectAsState()
        SettingSwitchItem(
            title = "Download Lyrics",
            subtitle = "Bundle .lrc files with downloaded tracks",
            checked = dlLyrics,
            onCheckedChange = { viewModel.setDownloadLyrics(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Download Folder")

        val folderDisplay = downloadFolder?.let { folder ->
            try {
                val uri = folder.toUri()
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                docFile?.name ?: "Custom folder"
            } catch (_: Exception) { "Custom folder" }
        } ?: "Internal app storage (default)"

        SettingItem(
            title = "Save location",
            subtitle = folderDisplay,
            onClick = { folderPickerLauncher.launch(null) }
        )

        if (downloadFolder != null) {
            TextButton(onClick = { viewModel.setDownloadFolderUri(null) }) {
                Text("Reset to default", color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Storage")
        OutlinedButton(
            onClick = { showClearDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All Downloads")
        }
    }
}

// ─── Tab 6: Instances ──────────────────────────────────────────────────
@Composable
private fun InstancesTab(viewModel: SettingsViewModel) {
    val apiInstances by viewModel.apiInstances.collectAsState()
    val streamingInstances by viewModel.streamingInstances.collectAsState()
    val customEndpoint by viewModel.customEndpoint.collectAsState()
    val qobuzEndpoint by viewModel.qobuzEndpoint.collectAsState()
    val qobuzCookie by viewModel.qobuzCookie.collectAsState()
    val devModeEnabled by viewModel.devModeEnabled.collectAsState()
    val sourceMode by viewModel.sourceMode.collectAsState()
    val refreshing by viewModel.instancesRefreshing.collectAsState()
    var customInput by remember(customEndpoint) { mutableStateOf(customEndpoint ?: "") }
    var qobuzInput by remember(qobuzEndpoint) { mutableStateOf(qobuzEndpoint ?: "") }
    var qobuzCookieInput by remember(qobuzCookie) { mutableStateOf(qobuzCookie ?: "") }

    SettingsTabContent {
        // Source mode picker — controls which catalogs feed search/discovery.
        // Plays/downloads still follow the per-track PlaybackSource so a
        // download you triggered earlier keeps working regardless of this
        // setting.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = "Catalog source",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Which catalogs power Search and Browse.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val sourceOptions = listOf(
                tf.monochrome.android.data.preferences.SourceMode.BOTH to "Both",
                tf.monochrome.android.data.preferences.SourceMode.TIDAL_ONLY to "TIDAL only",
                tf.monochrome.android.data.preferences.SourceMode.QOBUZ_ONLY to "Qobuz only",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sourceOptions.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = sourceMode == mode,
                        onClick = { viewModel.setSourceMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, sourceOptions.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        SettingSwitchItem(
            title = "Dev Mode",
            subtitle = "Route all Tidal API/streaming requests through your own server. Requires a compatible Tidal HiFi instance at the URL below.",
            checked = devModeEnabled,
            onCheckedChange = { viewModel.setDevModeEnabled(it) }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tidal HiFi URL",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Used for search, browse, and streaming when Dev Mode is on",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Snapshot the latest input/saved value into stable holders so the
            // onFocusChanged closure captured by the OutlinedTextField doesn't
            // need to re-allocate on every keystroke recomposition.
            val latestInput = rememberUpdatedState(customInput)
            val latestSaved = rememberUpdatedState(customEndpoint)
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it },
                placeholder = {
                    Text(
                        "http://127.0.0.1:8000",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                enabled = devModeEnabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.setCustomEndpoint(latestInput.value.trim().ifBlank { null })
                }),
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            val trimmed = latestInput.value.trim().ifBlank { null }
                            if (trimmed != latestSaved.value) {
                                viewModel.setCustomEndpoint(trimmed)
                            }
                        }
                    }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Qobuz URL",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Used for downloads. Honored whenever set, independent of Dev Mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val latestQobuzInput = rememberUpdatedState(qobuzInput)
            val latestQobuzSaved = rememberUpdatedState(qobuzEndpoint)
            OutlinedTextField(
                value = qobuzInput,
                onValueChange = { qobuzInput = it },
                placeholder = {
                    Text(
                        "https://your-qobuz-instance",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.setQobuzEndpoint(latestQobuzInput.value.trim().ifBlank { null })
                }),
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            val trimmed = latestQobuzInput.value.trim().ifBlank { null }
                            if (trimmed != latestQobuzSaved.value) {
                                viewModel.setQobuzEndpoint(trimmed)
                            }
                        }
                    }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Qobuz Auth Cookie",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Optional. Paste the GAESA=… session cookie from the trypt-hifi web app's DevTools → Application → Cookies if the API requires auth.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val latestCookieInput = rememberUpdatedState(qobuzCookieInput)
            val latestCookieSaved = rememberUpdatedState(qobuzCookie)
            OutlinedTextField(
                value = qobuzCookieInput,
                onValueChange = { qobuzCookieInput = it },
                placeholder = {
                    Text(
                        "GAESA=…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.setQobuzCookie(latestCookieInput.value.trim().ifBlank { null })
                }),
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            val trimmed = latestCookieInput.value.trim().ifBlank { null }
                            if (trimmed != latestCookieSaved.value) {
                                viewModel.setQobuzCookie(trimmed)
                            }
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsGroupHeader("API Instances")
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshInstances() }) {
                if (refreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (apiInstances.isEmpty()) {
            Text("No API instances loaded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            apiInstances.forEach { instance ->
                InstanceCard(url = instance.url, version = instance.version)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Streaming Instances")
        if (streamingInstances.isEmpty()) {
            Text("No streaming instances loaded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            streamingInstances.forEach { instance ->
                InstanceCard(url = instance.url, version = instance.version)
            }
        }
    }
}

@Composable
private fun InstanceCard(url: String, version: String?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .liquidGlass(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (version != null) {
                    Text("v$version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.Check, contentDescription = "Online", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

// ─── Tab 7: System ─────────────────────────────────────────────────────
@Composable
private fun SystemTab(viewModel: SettingsViewModel, navController: NavController) {
    val cacheSize by viewModel.cacheSize.collectAsState()
    var showClearAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }
                    if (!content.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            viewModel.importLibrary(content)
                            android.widget.Toast.makeText(context, "Library imported", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Failed to read file", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Error reading file", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Reset All Data") },
            text = { Text("This will clear all settings, cache, and local data. The app will restart in a clean state.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData()
                    showClearAllDialog = false
                }) { Text("Reset Everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsTabContent {
        SettingsGroupHeader("Storage")
        SettingItem(title = "Cache Size", subtitle = cacheSize, onClick = {})
        OutlinedButton(onClick = { viewModel.clearCache() }) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear Cache")
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Data")
        OutlinedButton(
            onClick = { showClearAllDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset All Data")
        }

        Spacer(modifier = Modifier.height(20.dp))
        LinkItem("Website", "https://monochrome.tf", context)

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Account & Sync")
        val isLoggedIn by viewModel.isLoggedIn.collectAsState()
        val userEmail by viewModel.userEmail.collectAsState()

        if (isLoggedIn) {
            SettingItem(
                title = "Signed in as",
                subtitle = userEmail ?: "Unknown",
                onClick = {}
            )
            OutlinedButton(onClick = { viewModel.logout() }) {
                Text("Sign Out")
            }
        } else {
            Text(
                "Sign in to sync favorites and playlists across devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "Use the Account page to sign in with Google or email.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Playlist Import")
        var importUrl by remember { mutableStateOf("") }
        androidx.compose.material3.OutlinedTextField(
            value = importUrl,
            onValueChange = { importUrl = it },
            label = { Text("Spotify / YT Music URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                viewModel.importPlaylist(importUrl) { success, msg ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    if (success) importUrl = ""
                }
            },
            enabled = importUrl.isNotBlank()
        ) {
            Text("Import Playlist")
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Backup & Restore")
        Text(
            "Export or import your library and history as a JSON file",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                viewModel.exportLibrary { json ->
                    // Copy to clipboard or share
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Monochrome Backup", json)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Backup copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Export JSON")
            }
            OutlinedButton(onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            }) {
                Text("Import JSON")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Diagnostics")
        SettingItem(
            title = "View debug log",
            subtitle = "Live logcat stream for this process — copy or export as a file for bug reports",
            onClick = { navController.navigate(Screen.DebugLog.route) },
        )

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("About")
        Text(
            text = "Monochrome for Android v1.0.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Open-source, ad-free music streaming",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LinkItem(label: String, url: String, context: android.content.Context) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .padding(vertical = 10.dp)
    )
}

// ─── Shared components ─────────────────────────────────────────────────
@Composable
private fun SettingsTabContent(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item { content() }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
    )
}

@Composable
fun SettingItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingSwitchItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ─── Tab 5: Library Settings ──────────────────────────────────────────
@Composable
private fun LibrarySettingsTab(viewModel: SettingsViewModel) {
    val scanOnAppOpen by viewModel.scanOnAppOpen.collectAsState()
    val minTrackDuration by viewModel.minTrackDuration.collectAsState()
    val backgroundScanInterval by viewModel.backgroundScanInterval.collectAsState()
    val libraryTabOrder by viewModel.libraryTabOrder.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                "Local Media Scanning",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingSwitchItem(
                title = "Scan on App Open",
                subtitle = "Automatically scan for new music when the app opens",
                checked = scanOnAppOpen,
                onCheckedChange = { viewModel.setScanOnAppOpen(it) }
            )
        }

        item {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Minimum Track Duration",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Skip files shorter than ${minTrackDuration / 1000} seconds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = minTrackDuration.toFloat(),
                    onValueChange = { viewModel.setMinTrackDuration(it.toLong()) },
                    valueRange = 0f..120_000f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    "Background Scan Interval",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    backgroundScanInterval.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("never", "hourly", "daily").forEach { interval ->
                        DropdownMenuItem(
                            text = { Text(interval.replaceFirstChar { it.titlecase(Locale.getDefault()) }) },
                            onClick = {
                                viewModel.setBackgroundScanInterval(interval)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            OutlinedButton(
                onClick = { viewModel.rescanLibrary() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rescan Library Now")
            }
        }

        if (libraryTabOrder.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Text(
                    "Library Tab Order",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                Text(
                    "Reorder the tabs shown in the Library screen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(libraryTabOrder.size) { index ->
                val sectionId = libraryTabOrder[index]
                val displayName = sectionId.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.moveLibraryTab(index, index - 1) },
                        enabled = index > 0
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = if (index > 0) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.moveLibraryTab(index, index + 1) },
                        enabled = index < libraryTabOrder.size - 1
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = if (index < libraryTabOrder.size - 1) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}
