package tf.monochrome.android.ui.eq

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    navController: NavController,
    viewModel: EqViewModel = hiltViewModel()
) {
    val eqEnabled by viewModel.eqEnabled.collectAsState()
    val currentBands by viewModel.currentBands.collectAsState()
    val currentPreamp by viewModel.currentPreamp.collectAsState()
    val availableTargets by viewModel.availableTargets.collectAsState()
    val selectedTarget by viewModel.selectedTarget.collectAsState()
    val activePreset by viewModel.activePreset.collectAsState()
    val allPresets by viewModel.allPresets.collectAsState()
    val error by viewModel.error.collectAsState()
    val isCalculating by viewModel.isCalculating.collectAsState()
    val originalMeasurement by viewModel.originalMeasurement.collectAsState()
    val selectedHeadphone by viewModel.selectedHeadphone.collectAsState()
    val bandCount by viewModel.bandCount.collectAsState()
    val maxFrequency by viewModel.maxFrequency.collectAsState()
    val sampleRate by viewModel.sampleRate.collectAsState()
    val headphoneTypeFilter by viewModel.headphoneTypeFilter.collectAsState()
    val availableHeadphones by viewModel.availableHeadphones.collectAsState()
    val showTutorial by viewModel.showTutorial.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var showTargetMenu by remember { mutableStateOf(false) }
    var showHeadphoneSelect by remember { mutableStateOf(false) }
    var showMeasurementUpload by remember { mutableStateOf(false) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var showBandsExpanded by remember { mutableStateOf(true) }
    var showProfilesExpanded by remember { mutableStateOf(true) }
    var saveName by remember { mutableStateOf("") }
    var saveDescription by remember { mutableStateOf("") }
    var showTargetNameDialog by remember { mutableStateOf(false) }
    var pendingTargetData by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }
    var presetToDelete by remember { mutableStateOf<tf.monochrome.android.domain.model.EqPreset?>(null) }

    val context = LocalContext.current

    // File picker for measurement import
    val measurementFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val rawData = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!rawData.isNullOrEmpty()) {
                viewModel.importMeasurementData(rawData)
            }
        } catch (e: Exception) {
            viewModel.clearError()
        }
    }

    // File picker for custom target import
    val targetFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val rawData = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!rawData.isNullOrEmpty()) {
                pendingTargetData = rawData
                targetName = ""
                showTargetNameDialog = true
            }
        } catch (_: Exception) { }
    }

    // AutoEQ tutorial dialog (first visit)
    if (showTutorial) {
        AutoEqTutorialDialog(onDismiss = { viewModel.dismissTutorial() })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ─── Title Section ───
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "PRECISION AUTOEQ",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Headphone correction filters generator.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = eqEnabled,
                            onCheckedChange = { viewModel.toggleEq() }
                        )
                    }
                }
            }

            // ─── Interactive Frequency Graph ───
            item {
                FrequencyResponseGraph(
                    originalCurve = originalMeasurement,
                    targetCurve = selectedTarget.data,
                    eqBands = currentBands,
                    preamp = currentPreamp,
                    sampleRate = sampleRate,
                    onBandDragged = { bandId, freq, gain ->
                        viewModel.updateBandByDrag(bandId, freq, gain)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            // ─── Category Tabs (ALL / OVER-EAR / IN-EAR) ───
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TabChip(
                        label = "ALL",
                        isSelected = headphoneTypeFilter == null,
                        onClick = { viewModel.setHeadphoneTypeFilter(null) },
                        modifier = Modifier.weight(1f)
                    )
                    TabChip(
                        label = "OVER-EAR",
                        isSelected = headphoneTypeFilter == "over-ear",
                        onClick = { viewModel.setHeadphoneTypeFilter("over-ear") },
                        modifier = Modifier.weight(1f)
                    )
                    TabChip(
                        label = "IN-EAR",
                        isSelected = headphoneTypeFilter == "in-ear",
                        onClick = { viewModel.setHeadphoneTypeFilter("in-ear") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ─── Headphone Model Selector ───
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SectionLabel("HEADPHONE MODEL")
                    SelectorRow(
                        value = selectedHeadphone?.name ?: "Select headphone...",
                        onClick = { showHeadphoneSelect = true },
                        trailingIcon = {
                            IconButton(
                                onClick = { measurementFilePicker.launch("text/*") },
                                modifier = Modifier
                                    .size(44.dp)
                                    .liquidGlass(
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Icon(
                                    Icons.Default.UploadFile,
                                    contentDescription = "Import measurement file",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }

            // ─── Target Curve Selector ───
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SectionLabel("TARGET")
                    Box {
                        SelectorRow(
                            value = selectedTarget.label,
                            onClick = { showTargetMenu = true },
                            trailingIcon = {
                                IconButton(
                                    onClick = { targetFilePicker.launch("text/*") },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .liquidGlass(
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.UploadFile,
                                        contentDescription = "Import custom target",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showTargetMenu,
                            onDismissRequest = { showTargetMenu = false }
                        ) {
                            availableTargets.forEach { target ->
                                val isCustom = target.id.startsWith("custom_")
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(target.label, modifier = Modifier.weight(1f))
                                            if (isCustom) {
                                                IconButton(
                                                    onClick = {
                                                        viewModel.deleteCustomTarget(target.id)
                                                        showTargetMenu = false
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.selectTarget(target.id)
                                        showTargetMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ─── Parameters Row (Filter Bands / Max Hz / Sample Rate) ───
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ParameterDropdown(
                        label = "FILTER BANDS",
                        value = bandCount.toString(),
                        options = listOf("5", "10", "15", "20", "31"),
                        onValueChanged = { viewModel.setBandCount(it.toInt()) },
                        modifier = Modifier.weight(1f)
                    )
                    ParameterDropdown(
                        label = "MAX HZ",
                        value = formatFreqLabel(maxFrequency),
                        options = listOf("8k", "12k", "16k", "20k"),
                        onValueChanged = {
                            viewModel.setMaxFrequency(parseFreqLabel(it))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ParameterDropdown(
                        label = "SAMPLE RATE",
                        value = formatSampleRate(sampleRate),
                        options = listOf("44.1k", "48k", "96k", "192k"),
                        onValueChanged = {
                            viewModel.setSampleRate(parseSampleRate(it))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ─── Action Row (Download + AutoEQ Button) ───
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { /* export EQ preset */ },
                        modifier = Modifier
                            .size(52.dp)
                            .liquidGlass(
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Export EQ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GradientAutoEqButton(
                        isCalculating = isCalculating,
                        onClick = { viewModel.runAutoEq() },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val hp = selectedHeadphone?.name
                            saveName = if (hp.isNullOrBlank()) selectedTarget.label
                                       else "$hp — ${selectedTarget.label}"
                            saveDescription = ""
                            showSaveDialog = true
                        },
                        enabled = currentBands.isNotEmpty(),
                        modifier = Modifier
                            .size(52.dp)
                            .liquidGlass(shape = RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save as preset",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ─── Saved Profiles Section ───
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProfilesExpanded = !showProfilesExpanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "SAVED PROFILES",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (allPresets.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    allPresets.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = if (showProfilesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showProfilesExpanded) {
                if (allPresets.isEmpty()) {
                    item {
                        Text(
                            "No saved profiles yet. Run AutoEq, then tap the save icon next to it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(allPresets) { preset ->
                        val isActive = activePreset?.id == preset.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .liquidGlass(shape = RoundedCornerShape(10.dp))
                                .bounceClick(onClick = { viewModel.loadPreset(preset.id) })
                        ) {
                            // Mini graph
                            EqProfileMiniGraph(
                                bands = preset.bands,
                                preamp = preset.preamp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                            )
                            // Info row
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
                                    if (preset.description.isNotBlank()) {
                                        Text(
                                            preset.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
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

            // ─── Database Section ───
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Database",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "AutoEq Repo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${availableHeadphones.size} models",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .liquidGlass(shape = RoundedCornerShape(8.dp))
                            .clickable { showHeadphoneSelect = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Search model (e.g. HD 600)...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ─── Error Display ───
            if (!error.isNullOrEmpty()) {
                item {
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    )
                }
            }

            // ─── Collapsible EQ Bands Section ───
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBandsExpanded = !showBandsExpanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "PARAMETRIC EQ FILTERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Icon(
                        imageVector = if (showBandsExpanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showBandsExpanded) {
                // Preamp
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Preamp",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${currentPreamp.roundToInt()} dB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = if (currentPreamp.isNaN()) 0f else currentPreamp.coerceIn(-12f, 12f),
                            onValueChange = { viewModel.setPreamp(it) },
                            valueRange = -12f..12f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Band sliders
                items(currentBands) { band ->
                    EqBandSlider(
                        band = band,
                        onBandChanged = { viewModel.updateBand(band.id, it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Action buttons
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .liquidGlass(shape = RoundedCornerShape(8.dp))
                                .bounceClick(onClick = { viewModel.resetToFlat() }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelLarge)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .liquidGlass(shape = RoundedCornerShape(8.dp))
                                .bounceClick(onClick = { showSaveDialog = true }),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                                Text("Save", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Dialogs ───

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save EQ Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Preset Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = saveDescription,
                        onValueChange = { saveDescription = it },
                        label = { Text("Description") },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (saveName.isNotEmpty()) {
                            viewModel.saveAsPreset(saveName, saveDescription)
                            showSaveDialog = false
                            saveName = ""
                            saveDescription = ""
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showHeadphoneSelect) {
        AlertDialog(
            onDismissRequest = { showHeadphoneSelect = false },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            content = {
                HeadphoneSelectScreen(
                    viewModel = viewModel,
                    onHeadphoneSelected = { showHeadphoneSelect = false },
                    onDismiss = { showHeadphoneSelect = false }
                )
            }
        )
    }

    if (showTargetNameDialog) {
        AlertDialog(
            onDismissRequest = { showTargetNameDialog = false },
            title = { Text("Name Custom Target") },
            text = {
                OutlinedTextField(
                    value = targetName,
                    onValueChange = { targetName = it },
                    label = { Text("Target Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (targetName.isNotBlank()) {
                            viewModel.importCustomTarget(pendingTargetData, targetName.trim())
                            showTargetNameDialog = false
                            pendingTargetData = ""
                        }
                    }
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTargetNameDialog = false
                    pendingTargetData = ""
                }) { Text("Cancel") }
            }
        )
    }

    if (showMeasurementUpload) {
        AlertDialog(
            onDismissRequest = { showMeasurementUpload = false },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            ),
            content = {
                MeasurementUploadScreen(
                    viewModel = viewModel,
                    onDismiss = { showMeasurementUpload = false },
                    onCalibrationComplete = { showMeasurementUpload = false }
                )
            }
        )
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Delete \"${preset.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePreset(preset.id)
                    presetToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun EqBandSlider(
    band: EqBand,
    onBandChanged: (EqBand) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(shape = RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // --- Frequency Slider ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Freq (${band.type})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${band.freq.toInt()} Hz", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        val minLogFreq = kotlin.math.log10(20f)
        val maxLogFreq = kotlin.math.log10(20000f)
        val currentLogFreq = kotlin.math.log10(band.freq.coerceIn(20f, 20000f))
        val freqRatio = (currentLogFreq - minLogFreq) / (maxLogFreq - minLogFreq)
        Slider(
            value = if (freqRatio.isNaN()) 0.5f else freqRatio.coerceIn(0f, 1f),
            onValueChange = {
                val newLog = minLogFreq + it * (maxLogFreq - minLogFreq)
                val newFreq = java.lang.Math.pow(10.0, newLog.toDouble()).toFloat()
                onBandChanged(band.copy(freq = newFreq))
            },
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // --- Gain Slider ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Gain", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${band.gain.roundToInt()} dB",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    band.gain > 0 -> MaterialTheme.colorScheme.primary
                    band.gain < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
        Slider(
            value = if (band.gain.isNaN()) 0f else band.gain.coerceIn(-12f, 12f),
            onValueChange = { onBandChanged(band.copy(gain = it)) },
            valueRange = -12f..12f,
            steps = 23,
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
        
        // --- Q-Factor Slider ---
        if (band.q > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Q-Factor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%.2f".format(band.q), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = if (band.q.isNaN()) 1f else band.q.coerceIn(0.1f, 10f),
                onValueChange = { onBandChanged(band.copy(q = it)) },
                valueRange = 0.1f..10f,
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )
        }
    }
}

// ─── Utility functions ───

private fun formatFreqLabel(freq: Float): String = "${(freq / 1000).toInt()}k"

private fun parseFreqLabel(label: String): Float =
    label.removeSuffix("k").toFloat() * 1000f

private fun formatSampleRate(rate: Float): String = when (rate.toInt()) {
    44100 -> "44.1k"
    48000 -> "48k"
    96000 -> "96k"
    192000 -> "192k"
    else -> "${(rate / 1000).toInt()}k"
}

private fun parseSampleRate(label: String): Float = when (label) {
    "44.1k" -> 44100f
    "48k" -> 48000f
    "96k" -> 96000f
    "192k" -> 192000f
    else -> 48000f
}
