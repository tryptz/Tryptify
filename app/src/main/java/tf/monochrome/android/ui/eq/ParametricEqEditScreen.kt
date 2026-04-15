package tf.monochrome.android.ui.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.bounceCombinedClick

@Composable
fun ParametricEqEditScreen(
    navController: NavController,
    viewModel: ParametricEqViewModel = hiltViewModel()
) {
    val currentBands by viewModel.currentBands.collectAsState()
    val currentPreamp by viewModel.currentPreamp.collectAsState()
    val selectedBandId by viewModel.selectedBandId.collectAsState()
    val spectrumBins by viewModel.spectrumAnalyzer.spectrumBins.collectAsState()
    val fftSize by viewModel.fftSize.collectAsState()

    val analyzer = viewModel.spectrumAnalyzer
    DisposableEffect(Unit) {
        analyzer.setAnalysisActive(true)
        onDispose { analyzer.setAnalysisActive(false) }
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var saveDescription by remember { mutableStateOf("") }

    val selectedBand = currentBands.find { it.id == selectedBandId }
    val spectrumColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "EDIT PARAMETRIC EQ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.resetToFlat() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reset to flat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                saveName = ""
                saveDescription = ""
                showSaveDialog = true
            }) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Save as profile",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // FFT size toggle (4K / 8K / 16K)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "FFT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            TabChip(
                label = "4K",
                isSelected = fftSize == SpectrumAnalyzerTap.FFT_SIZE_4K,
                onClick = { viewModel.setFftSize(SpectrumAnalyzerTap.FFT_SIZE_4K) }
            )
            TabChip(
                label = "8K",
                isSelected = fftSize == SpectrumAnalyzerTap.FFT_SIZE_8K,
                onClick = { viewModel.setFftSize(SpectrumAnalyzerTap.FFT_SIZE_8K) }
            )
            TabChip(
                label = "16K",
                isSelected = fftSize == SpectrumAnalyzerTap.FFT_SIZE_16K,
                onClick = { viewModel.setFftSize(SpectrumAnalyzerTap.FFT_SIZE_16K) }
            )
        }

        // Interactive graph with live spectrum overlay
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            FrequencyResponseGraph(
                originalCurve = emptyList(),
                targetCurve = emptyList(),
                eqBands = currentBands,
                preamp = currentPreamp,
                centerOnZero = true,
                showLegend = false,
                spectrumBins = spectrumBins,
                spectrumColor = spectrumColor,
                onBandDragged = { id, f, g -> viewModel.updateBandByDrag(id, f, g) }
            )
        }

        // Band strip with add button
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("BANDS")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                currentBands.sortedBy { it.freq }.forEach { band ->
                    BandChip(
                        label = formatFreq(band.freq),
                        gainDb = band.gain,
                        isSelected = band.id == selectedBandId,
                        onClick = { viewModel.selectBand(band.id) },
                        onLongPress = { viewModel.removeBand(band.id) }
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        .bounceClick(onClick = { viewModel.addBand() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add band",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Selected band detail
        if (selectedBand != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionLabel("BAND ${selectedBand.id + 1}")
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { viewModel.removeBand(selectedBand.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove band",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Filter type segmented row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterType.values().forEach { type ->
                        val isSel = selectedBand.type == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .then(
                                    if (isSel) Modifier.background(MaterialTheme.colorScheme.primary)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                                )
                                .bounceClick(onClick = { viewModel.updateBand(selectedBand.copy(type = type)) })
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (type) {
                                    FilterType.PEAKING -> "PEAK"
                                    FilterType.LOWSHELF -> "LOW-S"
                                    FilterType.HIGHSHELF -> "HIGH-S"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 1.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Frequency slider (log)
                ValueSlider(
                    label = "Frequency",
                    value = logFreqToSlider(selectedBand.freq),
                    valueRange = 0f..1f,
                    display = formatFreq(selectedBand.freq),
                    onChange = { t ->
                        val newFreq = sliderToLogFreq(t)
                        viewModel.updateBand(selectedBand.copy(freq = newFreq))
                    }
                )

                // Gain slider
                ValueSlider(
                    label = "Gain",
                    value = selectedBand.gain,
                    valueRange = -24f..24f,
                    display = "%+.1f dB".format(selectedBand.gain),
                    onChange = { viewModel.updateBand(selectedBand.copy(gain = it)) }
                )

                // Q slider
                ValueSlider(
                    label = "Q",
                    value = selectedBand.q,
                    valueRange = 0.1f..10f,
                    display = "%.2f".format(selectedBand.q),
                    onChange = { viewModel.updateBand(selectedBand.copy(q = it)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Preamp slider
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SectionLabel("PREAMP")
            ValueSlider(
                label = "",
                value = currentPreamp,
                valueRange = -24f..24f,
                display = "%+.1f dB".format(currentPreamp),
                onChange = { viewModel.setPreamp(it) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Profile") },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Profile name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveDescription,
                        onValueChange = { saveDescription = it },
                        label = { Text("Description (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveName.isNotBlank()) {
                        viewModel.saveAsPreset(saveName.trim(), saveDescription.trim())
                        showSaveDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BandChip(
    label: String,
    gainDb: Float,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            )
            .bounceCombinedClick(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            "%+.1f".format(gainDb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (label.isNotBlank()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
            Text(
                display,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun logFreqToSlider(freq: Float): Float {
    val logMin = Math.log10(20.0)
    val logMax = Math.log10(20000.0)
    val logF = Math.log10(freq.coerceIn(20f, 20000f).toDouble())
    return ((logF - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToLogFreq(t: Float): Float {
    val logMin = Math.log10(20.0)
    val logMax = Math.log10(20000.0)
    val logF = logMin + t.coerceIn(0f, 1f) * (logMax - logMin)
    return Math.pow(10.0, logF).toFloat().coerceIn(20f, 20000f)
}

private fun formatFreq(freq: Float): String = when {
    freq >= 1000f -> "%.1fk".format(freq / 1000f).replace(".0k", "k")
    else -> "${freq.toInt()}"
}
