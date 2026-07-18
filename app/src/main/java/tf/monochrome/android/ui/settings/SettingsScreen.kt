package tf.monochrome.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import tf.monochrome.android.BuildConfig
import java.util.Locale
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

// "Radio" is appended after "About" so existing hardcoded tab indices
// ("settings?tab=4" for Equalizer, "settings?tab=7" for Instances) stay valid.
private val settingsTabs = listOf("Appearance", "Interface", "Scrobbling", "Audio", "Equalizer", "Library", "Downloads", "Instances", "System", "About", "Radio")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    initialTab: Int = 0,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // rememberSaveable so the selected settings tab survives background process
    // death instead of snapping back to the first tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

    // Toast one-shot ViewModel messages (font import, backup import, …) from
    // one always-composed collector, regardless of which tab is showing.
    val messageContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(messageContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

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
                1 -> InterfaceTab(viewModel, navController)
                2 -> ScrobblingTab(viewModel)
                3 -> AudioTab(viewModel, navController)
                4 -> EqualizerTab(navController)
                5 -> LibrarySettingsTab(viewModel)
                6 -> DownloadsTab(viewModel)
                7 -> InstancesTab(viewModel)
                8 -> SystemTab(viewModel, navController)
                9 -> AboutTab()
                10 -> tf.monochrome.android.ui.settings.radio.RadioSettingsTab()
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
        )

        SettingItem(
            title = "Headphone",
            subtitle = selectedHeadphone?.name ?: "None selected",
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
    // Plain local state + commit-on-Done/focus-loss (see the speed field for
    // the same rationale): typing a precise scale no longer resets mid-entry
    // and the whole app's type no longer jumps at each intermediate keystroke.
    var fontScaleText by remember { mutableStateOf(String.format(Locale.US, "%.2f", fontScale)) }
    var fontScaleFocused by remember { mutableStateOf(false) }
    LaunchedEffect(fontScale) {
        if (!fontScaleFocused) fontScaleText = String.format(Locale.US, "%.2f", fontScale)
    }
    val commitFontScale = {
        val parsed = fontScaleText.toFloatOrNull()?.coerceIn(0.5f, 2.0f)
        if (parsed != null) {
            viewModel.setFontScale(parsed)
            fontScaleText = String.format(Locale.US, "%.2f", parsed)
        } else {
            fontScaleText = String.format(Locale.US, "%.2f", fontScale)
        }
    }

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
            subtitle = "Master switch: tint the app AND the player from album art. Off = everything uses the theme color",
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
                    onValueChange = { fontScaleText = it },
                    modifier = Modifier
                        .width(80.dp)
                        .onFocusChanged {
                            if (!it.isFocused && fontScaleFocused) commitFontScale()
                            fontScaleFocused = it.isFocused
                        },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitFontScale() })
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
                        onClick = { fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")) },
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
                    onClick = { fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Custom Font (.ttf / .otf)")
                }
            }
        }
    }
}

// ─── Tab 2: Interface ──────────────────────────────────────────────────
@Composable
private fun InterfaceTab(viewModel: SettingsViewModel, navController: NavController) {
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
    val playerDynamicColor by viewModel.playerDynamicColor.collectAsState()
    val playerBlurredBackground by viewModel.playerBlurredBackground.collectAsState()
    val appFps by viewModel.appTargetFps.collectAsState()
    val appResolution by viewModel.appRenderResolution.collectAsState()
    val selectedPresetName = presets.firstOrNull { it.id == presetId }?.displayName ?: "Auto-select bundled preset"
    var showTextureDropdown by remember { mutableStateOf(false) }
    var showPresetDropdown by remember { mutableStateOf(false) }
    var showFftDropdown by remember { mutableStateOf(false) }

    // Presets install lazily; make sure the preset dropdown has data.
    LaunchedEffect(Unit) {
        viewModel.prepareVisualizerEngine()
    }

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

        // Word-level lyrics provider — which karaoke-timing source(s) run when
        // TIDAL has no synced lyrics. "Both" tries NetEase first, then Kugou.
        val lyricsProvider by viewModel.lyricsWordProvider.collectAsState()
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = "Word-level lyrics provider",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Karaoke-timing source when your instance has no synced lyrics. Both = each falls back to the other.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val providerOptions = listOf(
                tf.monochrome.android.data.preferences.LyricsWordProvider.NETEASE_ONLY,
                tf.monochrome.android.data.preferences.LyricsWordProvider.KUGOU_ONLY,
                tf.monochrome.android.data.preferences.LyricsWordProvider.BOTH,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                providerOptions.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = lyricsProvider == mode,
                        onClick = { viewModel.setLyricsWordProvider(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, providerOptions.size),
                    ) {
                        Text(mode.displayName)
                    }
                }
            }
        }

        // App-wide frame rate and panel resolution, applied by selecting a
        // matching display mode in MainActivity. Resolution options only list
        // classes the panel can reach; a request maps to the nearest mode.
        var showFpsDropdown by remember { mutableStateOf(false) }
        SettingItem(
            title = "Frame Rate",
            subtitle = "Whole app: " + if (appFps == 0) "Unlocked (display max)" else "${appFps}fps",
            onClick = { showFpsDropdown = true }
        )
        DropdownMenu(expanded = showFpsDropdown, onDismissRequest = { showFpsDropdown = false }) {
            listOf(0, 60, 120).forEach { fps ->
                DropdownMenuItem(
                    text = { Text(if (fps == 0) "Unlocked" else "${fps}fps") },
                    onClick = { viewModel.setAppTargetFps(fps); showFpsDropdown = false }
                )
            }
        }

        val context = LocalContext.current
        var showResDropdown by remember { mutableStateOf(false) }
        val nativeShortSide = remember {
            val modes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display?.supportedModes
            } else null
            modes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) }
                ?: minOf(
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels,
                )
        }
        val resolutionOptions = remember(nativeShortSide) {
            listOf(0) + listOf(720, 1080, 1440, 2160).filter { it <= nativeShortSide }
        }
        fun resolutionLabel(shortSide: Int) = when {
            shortSide == 0 -> "Native"
            shortSide >= 2160 -> "4K (2160p)"
            shortSide >= 1440 -> "2K (1440p)"
            shortSide >= 1080 -> "1080p"
            else -> "720p"
        }
        SettingItem(
            title = "Resolution",
            subtitle = "Whole app: ${resolutionLabel(appResolution)} — nearest panel mode is used",
            onClick = { showResDropdown = true }
        )
        DropdownMenu(expanded = showResDropdown, onDismissRequest = { showResDropdown = false }) {
            resolutionOptions.forEach { shortSide ->
                DropdownMenuItem(
                    text = { Text(resolutionLabel(shortSide)) },
                    onClick = { viewModel.setAppRenderResolution(shortSide); showResDropdown = false }
                )
            }
        }

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
        SettingSwitchItem(
            title = "Dynamic Player Color",
            subtitle = "Tint the player from album art (needs Dynamic Colors on); off keeps the player on the theme color",
            checked = playerDynamicColor,
            onCheckedChange = { viewModel.setPlayerDynamicColor(it) }
        )
        SettingSwitchItem(
            title = "Blurred Album Background",
            subtitle = "Behind the lyrics (collapsed and fullscreen): the album art stretched and heavily blurred",
            checked = playerBlurredBackground,
            onCheckedChange = { viewModel.setPlayerBlurredBackground(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Now Playing Appearance")
        SettingItem(
            title = "Player Visuals Studio",
            subtitle = "Live editor for the lyric type / 3D wave / beat FX, plus the player and mini-player glass",
            onClick = { navController.navigate(Screen.LyricsFxStudio.route) },
        )

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
        
        IntSettingSlider(
            value = meshX,
            valueRange = 8f..128f,
            onCommit = { viewModel.setVisualizerMeshX(it) },
            label = { "Mesh X: $it" },
        )

        IntSettingSlider(
            value = meshY,
            valueRange = 8f..128f,
            onCommit = { viewModel.setVisualizerMeshY(it) },
            label = { "Mesh Y: $it" },
        )

        IntSettingSlider(
            value = targetFps,
            valueRange = 30f..144f,
            onCommit = { viewModel.setVisualizerTargetFps(it) },
            label = { if (vsyncEnabled) "Target FPS: $it" else "Target FPS: $it (vsync off)" },
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

        IntSettingSlider(
            value = rotationSeconds,
            valueRange = 5f..120f,
            onCommit = { viewModel.setVisualizerRotationSeconds(it) },
            label = { "Preset Rotation: ${it}s" },
        )

        IntSettingSlider(
            value = sensitivity,
            valueRange = 0f..100f,
            onCommit = { viewModel.setVisualizerSensitivity(it) },
            label = { "Sensitivity: $it%" },
            subtitle = "Controls intensity (High = Epilepsy Warning)",
        )

        IntSettingSlider(
            value = brightness,
            valueRange = 0f..100f,
            onCommit = { viewModel.setVisualizerBrightness(it) },
            label = { "Brightness: $it%" },
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

/**
 * Integer settings slider that writes its value once, on release, instead of on
 * every drag frame. A local float drives the label and thumb live; [onCommit]
 * (a DataStore write) fires only from onValueChangeFinished. The local state is
 * re-seeded whenever [value] changes externally so it stays in sync.
 */
@Composable
private fun IntSettingSlider(
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        label(local.toInt()),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local.toInt()) },
        valueRange = valueRange,
        modifier = modifier.fillMaxWidth(),
    )
}

// ─── Tab 3: Scrobbling ─────────────────────────────────────────────────
@Composable
private fun ScrobblingTab(viewModel: SettingsViewModel) {
    val lastFmEnabled by viewModel.lastFmEnabled.collectAsState()
    val lastFmUsername by viewModel.lastFmUsername.collectAsState()
    val lbEnabled by viewModel.listenBrainzEnabled.collectAsState()
    val lbToken by viewModel.listenBrainzToken.collectAsState()

    var showLastFmDialog by rememberSaveable { mutableStateOf(false) }
    var showLbDialog by rememberSaveable { mutableStateOf(false) }

    if (showLastFmDialog) {
        var sessionInput by rememberSaveable { mutableStateOf("") }
        var usernameInput by rememberSaveable { mutableStateOf("") }
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
                TextButton(
                    onClick = {
                        viewModel.setLastFmSession(sessionInput, usernameInput)
                        showLastFmDialog = false
                    },
                    // Disabled until both fields are filled, so Connect can't
                    // silently close without connecting.
                    enabled = sessionInput.isNotBlank() && usernameInput.isNotBlank()
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showLastFmDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLbDialog) {
        var tokenInput by rememberSaveable { mutableStateOf(lbToken ?: "") }
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
    val systemWideAutoEq by viewModel.systemWideAutoEqEnabled.collectAsState()
    var showWifiDropdown by remember { mutableStateOf(false) }
    var showCellularDropdown by remember { mutableStateOf(false) }
    // Plain local state (NOT keyed on playbackSpeed) so typing isn't reset by
    // the value round-tripping back from the ViewModel; sync from external
    // changes (slider/reset) only while the field is unfocused, and commit on
    // Done / focus-loss instead of on every keystroke (which dropped playback
    // to 0.01x mid-entry).
    var speedText by remember { mutableStateOf(String.format(Locale.US, "%.2f", playbackSpeed)) }
    var speedFocused by remember { mutableStateOf(false) }
    LaunchedEffect(playbackSpeed) {
        if (!speedFocused) speedText = String.format(Locale.US, "%.2f", playbackSpeed)
    }
    val commitSpeed = {
        val parsed = speedText.toFloatOrNull()?.coerceIn(0.01f, 100f)
        if (parsed != null) {
            viewModel.setPlaybackSpeed(parsed)
            speedText = String.format(Locale.US, "%.2f", parsed)
        } else {
            speedText = String.format(Locale.US, "%.2f", playbackSpeed)
        }
    }

    SettingsTabContent {
        SettingsGroupHeader("System-wide EQ")
        SettingSwitchItem(
            title = "System-wide AutoEQ",
            subtitle = "Apply your AutoEQ + tone to all device audio (device-permitting)",
            checked = systemWideAutoEq,
            onCheckedChange = viewModel::setSystemWideAutoEq,
        )

        Spacer(modifier = Modifier.height(16.dp))
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
        MultichannelDownmixToggle(viewModel)

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
                onValueChange = { speedText = it },
                modifier = Modifier
                    .width(80.dp)
                    .onFocusChanged {
                        if (!it.isFocused && speedFocused) commitSpeed()
                        speedFocused = it.isFocused
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitSpeed() }),
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
 * Multichannel (5.1/7.1) handling: fold down to stereo through the DSP/EQ
 * chain (default), or pass multichannel PCM through to the device with
 * DSP/EQ bypassed for those tracks. Applies from the next track / seek.
 */
@Composable
private fun MultichannelDownmixToggle(viewModel: SettingsViewModel) {
    val enabled by viewModel.multichannelDownmixEnabled.collectAsState()
    SettingSwitchItem(
        title = "Downmix multichannel to stereo",
        subtitle = if (enabled) {
            "5.1/7.1 tracks fold into stereo (ITU-R BS.775) and run through DSP/EQ."
        } else {
            "Off — multichannel passes to the device untouched; DSP/EQ bypassed for those tracks."
        },
        checked = enabled,
        onCheckedChange = { viewModel.setMultichannelDownmixEnabled(it) },
    )
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
                    viewModel.clearAllDownloads()
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

        // remember so the DocumentFile ContentResolver lookup runs only when the
        // folder actually changes, not on every recomposition of this screen.
        val folderDisplay = remember(downloadFolder, context) {
            downloadFolder?.let { folder ->
                try {
                    val uri = folder.toUri()
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    docFile?.name ?: "Custom folder"
                } catch (_: Exception) { "Custom folder" }
            } ?: "Internal app storage (default)"
        }

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
                            // Success/failure is reported by viewModel.messages
                            // once the import actually finishes — not here,
                            // where it fired even for a corrupt backup.
                            viewModel.importLibrary(content)
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

    // Export writes to a SAF-chosen file, not the clipboard: a large library's
    // backup JSON exceeds the binder transaction limit and setPrimaryClip()
    // hard-crashed exactly the users with the most data to protect.
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportSaveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val ok = try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    true
                } catch (e: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Backup saved" else "Failed to save backup",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Reset Settings & Cache") },
            // Reworded to match what clearAllData() actually does: it resets
            // preferences and wipes the cache but leaves the Room library
            // untouched, and the app does not restart. The old copy promised
            // to clear "all local data" and restart, neither of which happened.
            text = { Text("This resets all app settings and clears cached data. Your saved library, playlists, favorites, and downloads are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData()
                    showClearAllDialog = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsTabContent {
        SettingsGroupHeader("Storage")
        SettingItem(title = "Cache Size", subtitle = cacheSize)
        OutlinedButton(onClick = { viewModel.clearCache() }) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear Cache")
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Data")
        SettingItem(
            title = "Restart onboarding",
            subtitle = "Run the first-time setup again. Your library and settings are kept.",
            onClick = { viewModel.restartOnboarding() }
        )
        OutlinedButton(
            onClick = { showClearAllDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset Settings & Cache")
        }

        Spacer(modifier = Modifier.height(20.dp))
        LinkItem("Website", "https://monochrome.tf", context)

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Account & Sync")
        val isLoggedIn by viewModel.isLoggedIn.collectAsState()
        val userEmail by viewModel.userEmail.collectAsState()

        var showSignOutDialog by remember { mutableStateOf(false) }
        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("Sign out?") },
                text = { Text("You'll stop syncing favorites and playlists across devices until you sign back in.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSignOutDialog = false
                        viewModel.logout()
                    }) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
                }
            )
        }
        if (isLoggedIn) {
            SettingItem(
                title = "Signed in as",
                subtitle = userEmail ?: "Unknown",
            )
            OutlinedButton(onClick = { showSignOutDialog = true }) {
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
        val spotifyViewModel: SpotifyImportViewModel = hiltViewModel()
        val spotifyConnected by spotifyViewModel.isConnected.collectAsState()
        val spotifyUserName by spotifyViewModel.userName.collectAsState()
        val spotifyConnecting by spotifyViewModel.isConnecting.collectAsState()
        val spotifyAuthError by spotifyViewModel.authError.collectAsState()
        val importProgress by spotifyViewModel.importProgress.collectAsState()
        val isImporting by spotifyViewModel.isImporting.collectAsState()
        var showSpotifyPicker by remember { mutableStateOf(false) }
        var importUrl by remember { mutableStateOf("") }

        val onImportResult: (Boolean, String) -> Unit = { success, msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            if (success) importUrl = ""
        }

        if (spotifyConnected) {
            SettingItem(
                title = "Spotify",
                subtitle = "Connected as ${spotifyUserName ?: "…"}",
                onClick = {}
            )
            OutlinedButton(onClick = { spotifyViewModel.disconnect() }) {
                Text("Disconnect Spotify")
            }
        } else {
            Text(
                "Connect your Spotify account to import playlists directly. " +
                    "Note: only Spotify accounts allowlisted for this app can connect while it is in Development mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = { spotifyViewModel.connect(context) },
                enabled = !spotifyConnecting
            ) {
                Text(if (spotifyConnecting) "Connecting…" else "Connect Spotify")
            }
        }
        spotifyAuthError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = importUrl,
            onValueChange = { importUrl = it },
            label = { Text("Spotify playlist URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = spotifyConnected,
            supportingText = if (!spotifyConnected) {
                { Text("Connect Spotify above to enable URL import") }
            } else null
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { spotifyViewModel.importByUrl(context, importUrl, onResult = onImportResult) },
                enabled = spotifyConnected && importUrl.isNotBlank() && !isImporting
            ) {
                Text("Import Playlist")
            }
            OutlinedButton(
                onClick = {
                    spotifyViewModel.loadMyPlaylists()
                    showSpotifyPicker = true
                },
                enabled = spotifyConnected && !isImporting
            ) {
                Text("Browse My Playlists")
            }
        }

        when (val progress = importProgress) {
            is tf.monochrome.android.data.import_.ImportProgress.Fetching -> {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Fetching playlist from ${progress.source}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is tf.monochrome.android.data.import_.ImportProgress.Matching -> {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Matching ${progress.current}/${progress.total} — ${progress.matched} found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is tf.monochrome.android.data.import_.ImportProgress.Done -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Imported ${progress.matched} of ${progress.total} tracks into '${progress.playlistName}'",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (progress.unmatched.isNotEmpty()) {
                    var showUnmatched by remember { mutableStateOf(false) }
                    TextButton(onClick = { showUnmatched = !showUnmatched }) {
                        Text(if (showUnmatched) "Hide unmatched tracks" else "Show ${progress.unmatched.size} unmatched tracks")
                    }
                    if (showUnmatched) {
                        progress.unmatched.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            is tf.monochrome.android.data.import_.ImportProgress.Failed -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            tf.monochrome.android.data.import_.ImportProgress.Idle -> {}
        }

        if (showSpotifyPicker) {
            val spotifyPlaylists by spotifyViewModel.myPlaylists.collectAsState()
            val playlistsLoading by spotifyViewModel.playlistsLoading.collectAsState()
            val playlistsError by spotifyViewModel.playlistsError.collectAsState()
            tf.monochrome.android.ui.components.SpotifyPlaylistPickerDialog(
                playlists = spotifyPlaylists,
                isLoading = playlistsLoading,
                error = playlistsError,
                onPick = { playlistId, name, strict ->
                    showSpotifyPicker = false
                    spotifyViewModel.importPlaylist(context, playlistId, name, strict, onImportResult)
                },
                onPickLikedSongs = { strict ->
                    showSpotifyPicker = false
                    spotifyViewModel.importLikedSongs(context, strict, onImportResult)
                },
                onDismiss = { showSpotifyPicker = false }
            )
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
                    // Stash the JSON, then let the user pick a destination file
                    // (safe for any size, unlike the old clipboard copy).
                    pendingExportJson = json
                    exportSaveLauncher.launch("monochrome-backup.json")
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
                    + "earned a place in your day, a tip keeps the lights on "
                    + "and the next features shipping.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Recurring donations via Stripe's in-app PaymentSheet (card / Google Pay,
            // the donor never leaves the app), with Ko-fi kept as a one-tap fallback.
            tf.monochrome.android.ui.donate.DonateSupportCard(
                onOpenKofi = { openDonationUrl(context, "https://ko-fi.com/trypt") }
            )

            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Tryptify version ${BuildConfig.VERSION_NAME} · 2026",
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

// Opens an external donation/support URL in the browser (or a Custom Tab, if the
// user's default browser supports it). Wrapped so a device with no browser can't
// crash the app — it surfaces a Toast instead.
private fun openDonationUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open the link", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LinkItem(label: String, url: String, context: android.content.Context) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            // Route through the guarded helper so a device with no browser
            // (or a locked-down work profile) shows a toast instead of crashing
            // with ActivityNotFoundException.
            .clickable { openDonationUrl(context, url) }
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
fun SettingItem(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    tf.monochrome.android.devedit.DevEditable("item_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Only clickable (with ripple) when there's an action — an
                // empty onClick used to ripple like a picker but do nothing.
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
            val isScanning by viewModel.isScanning.collectAsState()
            val scanContext = LocalContext.current
            OutlinedButton(
                onClick = {
                    viewModel.rescanLibrary()
                    android.widget.Toast.makeText(scanContext, "Scanning library…", android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isScanning) "Scanning…" else "Rescan Library Now")
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
