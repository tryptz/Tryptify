package tf.monochrome.android.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import tf.monochrome.android.R
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import android.content.Context
import androidx.compose.ui.text.input.PasswordVisualTransformation
import tf.monochrome.android.domain.model.NowPlayingViewMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
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

private val settingsTabs = listOf("Appearance", "Interface", "Scrobbling", "Audio", "Equalizer", "Library", "Downloads", "Instances", "Radio", "System", "About")

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

        tf.monochrome.android.devedit.DevEditScreen("settings/${devSlug(settingsTabs[selectedTab])}") {
            when (selectedTab) {
                0 -> AppearanceTab(viewModel)
                1 -> InterfaceTab(viewModel)
                2 -> ScrobblingTab(viewModel)
                3 -> AudioTab(viewModel, navController)
                4 -> EqualizerTab(navController)
                5 -> LibrarySettingsTab(viewModel)
                6 -> DownloadsTab(viewModel)
                7 -> InstancesTab(viewModel)
                8 -> RadioSettingsTab(viewModel)
                9 -> SystemTab(viewModel, navController)
                10 -> AboutTab()
            }
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
    val autoplaySimilar by viewModel.autoplaySimilar.collectAsState()
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
        SettingSwitchItem(
            title = "Autoplay similar music",
            subtitle = "Keep playing related tracks when your queue ends",
            checked = autoplaySimilar,
            onCheckedChange = { viewModel.setAutoplaySimilar(it) }
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
    val diagnostics by viewModel.usbBypassDiagnostics.collectAsState()
    val failure by viewModel.usbBypassFailure.collectAsState()
    val supportedRates by viewModel.usbBypassSupportedRates.collectAsState()
    SettingSwitchItem(
        title = "Exclusive USB DAC (bypass Android audio)",
        subtitle = exclusiveSubtitle(
            exclusiveEnabled, exclusiveStatus, failure
        ),
        checked = exclusiveEnabled,
        onCheckedChange = { viewModel.setUsbExclusiveBitPerfectEnabled(it) },
    )
    // Only render the diagnostic card when the toggle is on AND we
    // have something honest to say — either an active stream, a
    // categorised failure, or a known rate inventory. Hidden the rest
    // of the time so the toggle row stays clean.
    if (exclusiveEnabled &&
        (diagnostics != null || failure != null || supportedRates.isNotEmpty())) {
        BypassDiagnosticsCard(
            diagnostics = diagnostics,
            failure = failure,
            supportedRates = supportedRates,
        )
    }
}

/**
 * Renders a compact info card beneath the exclusive-USB toggle:
 *   - Active stream specs (when streaming): rate / bits / channels /
 *     UAC version / device speed / async-feedback presence / clock id
 *   - Failure detail (when not streaming and a start attempt failed)
 *   - Supported-rates list from the device's GET_RANGE table
 *
 * Card styling matches the existing Settings cards. Stays terse: a
 * Bathys at 192/24 should fit the active-stream block in two lines so
 * the toggle below it isn't pushed off-screen.
 */
@Composable
private fun BypassDiagnosticsCard(
    diagnostics: tf.monochrome.android.audio.usb.BypassDiagnostics?,
    failure: tf.monochrome.android.audio.usb.StartFailure?,
    supportedRates: List<tf.monochrome.android.audio.usb.ClockRateRange>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (diagnostics != null) {
                Text(
                    text = "Active stream",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(diagnostics.rateLabel())
                        append(" · ")
                        append(diagnostics.bitsPerSample)
                        append("-bit · ")
                        append(diagnostics.channels)
                        append("ch · ")
                        append(diagnostics.uacLabel())
                        append(" ")
                        append(diagnostics.speedLabel())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    // Second line: less critical metadata. Async
                    // feedback presence is the big one — it's the
                    // difference between rate-locked playback and the
                    // host drifting against the DAC's clock.
                    text = buildString {
                        append("alt ")
                        append(diagnostics.altSetting)
                        if (diagnostics.clockSourceId != 0) {
                            append(" · clock #")
                            append(diagnostics.clockSourceId)
                        }
                        append(" · ")
                        append(
                            if (diagnostics.hasFeedbackEndpoint)
                                "async feedback ✓"
                            else "no feedback EP (open-loop)"
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (failure != null && failure.code !=
                tf.monochrome.android.audio.usb.StartError.Ok) {
                Text(
                    text = "Bypass failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = failure.actionableMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (failure.detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        // The native detail is technical (libusb error
                        // codes, control-transfer info). Useful for
                        // anyone debugging via logcat — render in mono
                        // to make it visually distinct from the
                        // user-facing actionable message above.
                        text = failure.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (supportedRates.isNotEmpty()) {
                if (diagnostics != null || failure != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Supported rates",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    // Most DACs expose discrete rates with min==max,
                    // so deduplicating on the label produces the clean
                    // "44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz" line
                    // that Hi-Fi people care about. Continuous-range
                    // PLLs render as "44.1–768 kHz" untouched.
                    text = supportedRates
                        .map { it.label() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
    failure: tf.monochrome.android.audio.usb.StartFailure?,
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
        // The Error subtitle used to hardcode the kernel-claim story.
        // Now we defer to whichever StartFailure category the native
        // side reported — claim failures, rate-negotiation failures,
        // alloc failures all surface as their own actionable line. If
        // somehow we're in Error with no failure recorded (shouldn't
        // happen but: defensive), fall back to the old text.
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Error ->
            failure?.actionableMessage()?.takeIf { it.isNotBlank() }
                ?: ("Bypass couldn't engage — see logcat tagged " +
                    "'LibusbUacDriver' for details.")
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
    val customEndpoint by viewModel.customEndpoint.collectAsState()
    val qobuzEndpoint by viewModel.qobuzEndpoint.collectAsState()
    val sourceMode by viewModel.sourceMode.collectAsState()
    var customInput by remember(customEndpoint) { mutableStateOf(customEndpoint ?: "") }
    var qobuzInput by remember(qobuzEndpoint) { mutableStateOf(qobuzEndpoint ?: "") }

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
                    text = "Your own Tidal HiFi server — used for search, browse, and streaming.",
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

    }
}

// ─── Tab 8: Radio ──────────────────────────────────────────────────────
@Composable
private fun RadioSettingsTab(viewModel: SettingsViewModel) {
    val weights by viewModel.radioPlannerWeights.collectAsState()
    var showTutorial by rememberSaveable { mutableStateOf(false) }

    if (showTutorial) {
        RadioWeightsTutorialSheet(onDismiss = { showTutorial = false })
    }

    SettingsTabContent {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Radio planner weights",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Tune how strongly the optional planner favors each recommendation signal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { showTutorial = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = "Radio weights tutorial",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "0.00x ignores a signal, 1.00x is neutral, and 3.00x is the strongest allowed preference. Android still validates playable local and Qobuz tracks before anything enters the queue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))
        PlannerLlmTester(viewModel)

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Source preference")
        RadioWeightSlider(
            title = "Local library",
            description = "Ranks owned tracks, downloaded files, and encrypted collection matches ahead of remote-only candidates when the planner builds search hints.",
            valueCopy = RadioWeightValueCopy(
                off = "The planner will not give local or downloaded matches any extra priority.",
                reduced = "Local matches act as a light tie-breaker, but remote catalogs can lead.",
                neutral = "Local availability gets the normal station-building priority.",
                elevated = "Local matches are preferred before broad remote catalog exploration.",
                strong = "Stations become strongly local-first and stay close to your owned library.",
            ),
            value = weights.localLibrary,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(localLibrary = it)) },
        )
        RadioWeightSlider(
            title = "Qobuz",
            description = "Raises Qobuz catalog searches and high-resolution alternatives when local identity matches are missing, stale, or too narrow.",
            valueCopy = RadioWeightValueCopy(
                off = "Qobuz hints are suppressed unless Android already has another reason to use them.",
                reduced = "Qobuz is available as a backup source, not a leading recommendation source.",
                neutral = "Qobuz gets the default share of planner search and resolution hints.",
                elevated = "Qobuz candidates are favored when the local library has weak coverage.",
                strong = "The planner aggressively looks for Qobuz-quality versions and adjacent releases.",
            ),
            value = weights.qobuz,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(qobuz = it)) },
        )
        RadioWeightSlider(
            title = "Spotify discovery",
            description = "Uses Spotify taste metadata to widen artist, genre, and adjacent-track queries without bypassing Android-side playback validation.",
            valueCopy = RadioWeightValueCopy(
                off = "Spotify-derived discovery hints are ignored for this station shape.",
                reduced = "Spotify taste data only nudges searches when stronger signals agree.",
                neutral = "Spotify discovery has its normal influence on widening the station.",
                elevated = "Spotify metadata actively broadens artist and style exploration.",
                strong = "Stations lean hard into Spotify-style discovery and adjacent catalog jumps.",
            ),
            value = weights.spotifyDiscovery,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(spotifyDiscovery = it)) },
        )
        RadioWeightSlider(
            title = "MetaBrainz metadata",
            description = "Uses MusicBrainz and AcousticBrainz-style identity, alias, tag, release, and recording context when the planner service has an index.",
            valueCopy = RadioWeightValueCopy(
                off = "MetaBrainz identity and tag hints are not used for candidate shaping.",
                reduced = "MetaBrainz can clean up obvious identities but will not steer strongly.",
                neutral = "MetaBrainz metadata gets the default role in matching and deduping.",
                elevated = "Recording IDs, aliases, tags, and release context strongly guide candidates.",
                strong = "Stations heavily trust MetaBrainz identity context for precise related tracks.",
            ),
            value = weights.metabrainzMetadata,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(metabrainzMetadata = it)) },
        )
        RadioWeightSlider(
            title = "ListenBrainz graph",
            description = "Uses co-listening edges and nearby-listener behavior from the planner index to find tracks people tend to play together.",
            valueCopy = RadioWeightValueCopy(
                off = "Crowd listening graph edges are ignored.",
                reduced = "Co-listening data can help ties but personal and identity signals lead.",
                neutral = "ListenBrainz graph hints receive their normal discovery weight.",
                elevated = "Co-listening relationships become a major source of adjacent tracks.",
                strong = "Stations strongly follow crowd listening paths around the seed.",
            ),
            value = weights.listenbrainzGraph,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(listenbrainzGraph = it)) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Identity matching")
        RadioWeightSlider(
            title = "Canonical version bias",
            description = "Prefers the canonical studio recording when metadata sees remasters, live cuts, radio edits, compilations, or duplicate release entries.",
            valueCopy = RadioWeightValueCopy(
                off = "Alternate releases and duplicate versions are not pushed down by identity bias.",
                reduced = "Canonical versions get a small preference, but variants can still surface.",
                neutral = "The planner applies normal canonical-recording cleanup.",
                elevated = "Canonical studio versions are preferred over most duplicate variants.",
                strong = "The station aggressively avoids remasters, live cuts, edits, and duplicates.",
            ),
            value = weights.canonicalVersionBias,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(canonicalVersionBias = it)) },
        )
        RadioWeightSlider(
            title = "Artist similarity",
            description = "Keeps the station near related artists, collaborations, shared scenes, member projects, and artist-neighborhood matches.",
            valueCopy = RadioWeightValueCopy(
                off = "Artist relationships do not influence the station shape.",
                reduced = "Artist similarity is a soft nudge behind track and tag evidence.",
                neutral = "Related artists receive the default matching weight.",
                elevated = "Artist-neighborhood matches become a strong ranking signal.",
                strong = "Stations stay very close to the seed artist's musical neighborhood.",
            ),
            value = weights.artistSimilarity,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(artistSimilarity = it)) },
        )
        RadioWeightSlider(
            title = "Genre and tag similarity",
            description = "Favors shared genre, style, mood, scene, and descriptive tags pulled from local metadata and planner-side indexes.",
            valueCopy = RadioWeightValueCopy(
                off = "Shared genres and tags are ignored.",
                reduced = "Tags help only when stronger identity or source signals agree.",
                neutral = "Genre and tag overlap gets the default coherence weight.",
                elevated = "Shared tags strongly hold the station inside a recognizable style lane.",
                strong = "Stations become highly tag-coherent and avoid broad stylistic jumps.",
            ),
            value = weights.genreTagSimilarity,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(genreTagSimilarity = it)) },
        )
        RadioWeightSlider(
            title = "Era consistency",
            description = "Keeps recommendations near the seed's release period or scene era when release years are known.",
            valueCopy = RadioWeightValueCopy(
                off = "Release year does not constrain the station.",
                reduced = "Era is a light hint, so decade jumps are common.",
                neutral = "The station keeps the default amount of release-period coherence.",
                elevated = "Recommendations stay noticeably closer to the seed's era.",
                strong = "Stations strongly prefer the same period and avoid large decade shifts.",
            ),
            value = weights.eraConsistency,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(eraConsistency = it)) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Discovery behavior")
        RadioWeightSlider(
            title = "Novelty",
            description = "Raises unheard, less-played, or less-obvious candidates when the planner has enough context to branch away from the seed.",
            valueCopy = RadioWeightValueCopy(
                off = "The planner will not intentionally push unfamiliar tracks upward.",
                reduced = "Novelty is gentle, so familiar candidates usually win close calls.",
                neutral = "Discovery gets the default balance against familiarity.",
                elevated = "Unheard and less-obvious candidates are promoted more often.",
                strong = "Stations become deliberately exploratory and less predictable.",
            ),
            value = weights.novelty,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(novelty = it)) },
        )
        RadioWeightSlider(
            title = "Familiarity",
            description = "Pulls the station back toward known artists, proven local matches, familiar styles, and tracks that resemble recent listening.",
            valueCopy = RadioWeightValueCopy(
                off = "Known artists and familiar tracks receive no extra comfort bias.",
                reduced = "Familiarity is present but leaves plenty of room for discovery.",
                neutral = "The station keeps the normal familiar-to-new balance.",
                elevated = "Known artists, familiar styles, and proven matches are preferred.",
                strong = "Stations become comfort-focused and stay close to known listening habits.",
            ),
            value = weights.familiarity,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(familiarity = it)) },
        )
        RadioWeightSlider(
            title = "Mood continuity",
            description = "Keeps energy, pacing, atmosphere, and listening context consistent across queue extensions.",
            valueCopy = RadioWeightValueCopy(
                off = "Mood and energy continuity do not constrain the next tracks.",
                reduced = "The queue can change energy quickly when other signals point elsewhere.",
                neutral = "Mood continuity receives its normal station-smoothing weight.",
                elevated = "Energy and atmosphere stay noticeably smoother between tracks.",
                strong = "Stations strongly preserve the current mood and avoid abrupt turns.",
            ),
            value = weights.moodContinuity,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(moodContinuity = it)) },
        )
        RadioWeightSlider(
            title = "Avoid recently played",
            description = "Penalizes tracks, recordings, artists, albums, and queue identities that appeared recently in playback history.",
            valueCopy = RadioWeightValueCopy(
                off = "Recent playback does not reduce candidate priority.",
                reduced = "Recent repeats are discouraged lightly but can still pass validation.",
                neutral = "The planner applies its default repeat-avoidance pressure.",
                elevated = "Recent tracks and identities are strongly pushed down.",
                strong = "Stations aggressively avoid repeats from the recent queue and history.",
            ),
            value = weights.avoidRecentlyPlayed,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(avoidRecentlyPlayed = it)) },
        )
        RadioWeightSlider(
            title = "Discovery distance",
            description = "Controls how many similarity, identity, and listening-graph hops the planner is encouraged to take from the seed.",
            valueCopy = RadioWeightValueCopy(
                off = "The planner stays close to direct seed matches and avoids graph hops.",
                reduced = "Discovery can move one small step away, but stays conservative.",
                neutral = "The station uses the normal amount of graph and similarity distance.",
                elevated = "The planner can make broader but still related jumps from the seed.",
                strong = "Stations are allowed to roam far through metadata and listening graphs.",
            ),
            value = weights.discoveryDistance,
            onValueChange = { viewModel.setRadioPlannerWeights(weights.copy(discoveryDistance = it)) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = { viewModel.resetRadioPlannerWeights() }) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset defaults")
        }
    }
}

@Composable
private fun PlannerLlmTester(viewModel: SettingsViewModel) {
    val state by viewModel.plannerTesterState.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val canSend = query.trim().isNotBlank() && !state.loading
    val submit = {
        if (canSend) viewModel.testPlannerQuery(query)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Tryptify-Playlist LLM tester",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Send the full radio context to Tryptify-Playlist and render its direct song-list response.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("dark trip hop with clean bass") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = submit,
                enabled = canSend,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Test planner",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.hasResponse,
            enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(180)) +
                fadeOut(animationSpec = tween(120)),
        ) {
            PlannerChatBubble(
                state = state,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PlannerChatBubble(
    state: PlannerTesterUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            if (state.submittedPrompt.isNotBlank()) {
                Text(
                    text = state.submittedPrompt,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = state.responseTitle.ifBlank { "tryptz planner" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.responseDetail.isNotBlank()) {
                Text(
                    text = state.responseDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.songs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                PlannerSongRevealList(
                    songs = state.songs,
                    requestId = state.requestId,
                )
            }
        }
    }
}

@Composable
private fun PlannerSongRevealList(
    songs: List<tf.monochrome.android.radio.planner.PlannerSong>,
    requestId: Long,
) {
    var revealCount by remember(requestId, songs.size) { mutableIntStateOf(0) }

    LaunchedEffect(requestId, songs) {
        revealCount = 0
        songs.indices.forEach { index ->
            delay(58L)
            revealCount = index + 1
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        songs.forEachIndexed { index, song ->
            AnimatedVisibility(
                visible = index < revealCount,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 220),
                    initialOffsetY = { fullHeight -> -fullHeight },
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = 160),
                    targetOffsetY = { fullHeight -> -fullHeight },
                ) + fadeOut(animationSpec = tween(durationMillis = 120)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = songDetail(song),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun songDetail(song: tf.monochrome.android.radio.planner.PlannerSong): String =
    buildList {
        song.album?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        song.reason.trim().takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" / ").ifBlank { "Tryptify-Playlist LLM" }

@Composable
private fun RadioWeightSlider(
    title: String,
    description: String,
    valueCopy: RadioWeightValueCopy,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val safeValue = value.takeIf { !it.isNaN() && !it.isInfinite() }?.coerceIn(0f, 3f) ?: 1f
    val currentDescription = radioWeightValueDescription(safeValue, valueCopy)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = String.format(Locale.US, "%.2fx", safeValue),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = currentDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = safeValue,
            onValueChange = { raw ->
                val rounded = (Math.round(raw.coerceIn(0f, 3f) * 4f) / 4f)
                onValueChange(rounded)
            },
            valueRange = 0f..3f,
            steps = 11,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Ignore", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Neutral", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Strong", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class RadioWeightValueCopy(
    val off: String,
    val reduced: String,
    val neutral: String,
    val elevated: String,
    val strong: String,
)

private fun radioWeightValueDescription(
    value: Float,
    copy: RadioWeightValueCopy,
): String {
    val safeValue = value.takeIf { !it.isNaN() && !it.isInfinite() }?.coerceIn(0f, 3f) ?: 1f
    val (label, body) = when {
        safeValue == 0f -> "Off" to copy.off
        safeValue < 0.85f -> "Reduced" to copy.reduced
        safeValue < 1.15f -> "Neutral" to copy.neutral
        safeValue < 2.25f -> "Raised" to copy.elevated
        else -> "Strong" to copy.strong
    }
    return "$label: $body"
}

private data class RadioTutorialSection(val heading: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioWeightsTutorialSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Radio weights",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A quick guide to shaping planner hints without giving up Android-side queue validation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(18.dp))
            val sections = radioWeightTutorialSections()
            sections.forEachIndexed { index, section ->
                Text(
                    text = section.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (index != sections.lastIndex) {
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

private fun radioWeightTutorialSections(): List<RadioTutorialSection> = listOf(
    RadioTutorialSection(
        "What the sliders do",
        "These values are sent to the optional Tryptify-Playlist planner. They influence search terms, source boosts, identity matching, and candidate ranking hints. They do not directly enqueue tracks.",
    ),
    RadioTutorialSection(
        "The scale",
        "1.00x is neutral. Values below 1.00x reduce a signal, 0.00x asks the planner to ignore it, and values above 1.00x strengthen it. Each slider now explains the current setting in plain language.",
    ),
    RadioTutorialSection(
        "Source preference",
        "Local library, Qobuz, Spotify discovery, MetaBrainz, and ListenBrainz decide where the planner should look first. Raise local library for owned-music stations; raise discovery sources when the queue feels narrow.",
    ),
    RadioTutorialSection(
        "Identity matching",
        "Canonical version, artist similarity, genre/tag similarity, and era consistency reduce bad duplicates and steer the station toward the seed's musical neighborhood.",
    ),
    RadioTutorialSection(
        "Discovery behavior",
        "Novelty, familiarity, mood continuity, recently played avoidance, and discovery distance control how adventurous the station feels after the first few tracks.",
    ),
    RadioTutorialSection(
        "Good starting points",
        "For local-first radio, raise Local library and Avoid recently played. For exploration, raise Novelty and Discovery distance while lowering Familiarity. For album-era stations, raise Era consistency and Canonical version bias.",
    ),
)

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
        SettingsGroupHeader("Integrations")
        val spotifyAuth by viewModel.spotifyAuthState.collectAsState()
        val spotifySyncCurrentPlaying by viewModel.spotifySyncCurrentPlaying.collectAsState()
        val llmPlaylistRadioRecommendationsEnabled by
            viewModel.llmPlaylistRadioRecommendationsEnabled.collectAsState()
        if (spotifyAuth.isAuthenticated) {
            SettingItem(
                title = "Spotify",
                subtitle = spotifyAuth.accountEmail ?: "Connected",
                onClick = {}
            )
            OutlinedButton(onClick = { viewModel.disconnectSpotify() }) {
                Text("Disconnect Spotify")
            }
        } else {
            SettingItem(
                title = "Spotify",
                subtitle = "Connect to use Spotify radio recommendations",
                onClick = { viewModel.startSpotifyAuth() }
            )
        }
        SettingSwitchItem(
            title = "Sync Spotify current listening",
            subtitle = "Allow session radio to include Spotify currently playing metadata",
            checked = spotifySyncCurrentPlaying,
            onCheckedChange = { viewModel.setSpotifySyncCurrentPlaying(it) }
        )
        SettingSwitchItem(
            title = "LLM playlist & radio recommendations",
            subtitle = "Use the Railway planner for playlist and radio recommendation hints",
            checked = llmPlaylistRadioRecommendationsEnabled,
            onCheckedChange = { viewModel.setLlmPlaylistRadioRecommendationsEnabled(it) }
        )

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

        val devEdit = tf.monochrome.android.devedit.LocalDevEditController.current
        if (devEdit != null) {
            val devEnabled = devEdit.masterEnabled.collectAsState().value
            SettingSwitchItem(
                title = "DevEdit layout mode",
                subtitle = "Unlocks a per-screen Edit button. Drag, hide & add UI elements; the toolbar's Save writes the layout to internal storage",
                checked = devEnabled,
                onCheckedChange = { devEdit.setMasterEnabled(it) },
            )
        }
    }
}

// ─── Tab 9: About ──────────────────────────────────────────────────────
@Composable
private fun AboutTab() {
    val context = LocalContext.current
    SettingsTabContent {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Image(
                painter = painterResource(id = R.drawable.trypt_pfp),
                contentDescription = "trypt avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Support the app",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tryptify is built and maintained by trypt. If it's "
                    + "earned a place in your day, a tip on Ko-fi keeps the "
                    + "lights on and the next features shipping.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://ko-fi.com/trypt".toUri())
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Support on Ko-fi")
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Tryptify version 1.6.2 · 2026",
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

// Slugify a label into a stable DevEdit element id (e.g. "Gapless Playback" →
// "gapless_playback"). Used so wrapping the shared setting rows yields stable,
// human-readable ids that persist across launches.
internal fun devSlug(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }

@Composable
private fun SettingsGroupHeader(title: String) {
    tf.monochrome.android.devedit.DevEditable("hdr_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
        )
    }
}

@Composable
fun SettingItem(title: String, subtitle: String, onClick: () -> Unit) {
    tf.monochrome.android.devedit.DevEditable("item_${devSlug(title)}", Modifier.fillMaxWidth()) {
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
}

@Composable
fun SettingSwitchItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    tf.monochrome.android.devedit.DevEditable("sw_${devSlug(title)}", Modifier.fillMaxWidth()) {
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
}

// ─── Tab 5: Library Settings ──────────────────────────────────────────
@Composable
private fun LibrarySettingsTab(viewModel: SettingsViewModel) {
    val scanOnAppOpen by viewModel.scanOnAppOpen.collectAsState()
    val minTrackDuration by viewModel.minTrackDuration.collectAsState()
    val backgroundScanInterval by viewModel.backgroundScanInterval.collectAsState()
    val libraryTabOrder by viewModel.libraryTabOrder.collectAsState()
    val analyzeAudioFeatures by viewModel.analyzeAudioFeatures.collectAsState()
    val audioFeaturesAnalyzed by viewModel.audioFeaturesAnalyzed.collectAsState()
    val audioFeaturesTarget by viewModel.audioFeaturesTarget.collectAsState()

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
            Text(
                "Audio Analysis",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingSwitchItem(
                title = "Analyze audio features",
                subtitle = "Measure tempo, energy, key, loudness & brightness for your library",
                checked = analyzeAudioFeatures,
                onCheckedChange = { viewModel.setAnalyzeAudioFeatures(it) }
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.analyzeAudioNow() }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    "Analyze now",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (audioFeaturesTarget > 0)
                        "$audioFeaturesAnalyzed / $audioFeaturesTarget tracks analyzed"
                    else
                        "$audioFeaturesAnalyzed tracks analyzed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
