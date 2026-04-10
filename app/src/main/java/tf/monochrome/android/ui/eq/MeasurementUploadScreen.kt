package tf.monochrome.android.ui.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.model.EqTarget

/**
 * MeasurementUploadScreen - Advanced calibration with headphone measurement upload
 *
 * Features:
 * - Paste raw frequency response CSV data
 * - Select target curve for calibration
 * - Set number of EQ bands
 * - Auto-calculate optimal EQ from measurement
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementUploadScreen(
    viewModel: EqViewModel,
    onDismiss: () -> Unit,
    onCalibrationComplete: () -> Unit
) {
    val isCalculating by viewModel.isCalculating.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedTarget by viewModel.selectedTarget.collectAsState()
    val availableTargets by viewModel.availableTargets.collectAsState()
    val currentBands by viewModel.currentBands.collectAsState()

    var measurementData by remember { mutableStateOf("") }
    var bandCount by remember { mutableFloatStateOf(10f) }
    var showTargetMenu by remember { mutableStateOf(false) }
    var headphoneName by remember { mutableStateOf("") }
    var calculationAttempted by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val rawData = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!rawData.isNullOrEmpty()) {
                measurementData = rawData
            }
        } catch (_: Exception) { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Advanced Calibration",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close")
            }
        }

        Divider(modifier = Modifier.padding(horizontal = 16.dp))

        // Instructions
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    "How it works:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "1. Measure your headphone's frequency response using an app like \"REW\" or \"GRAS\" (CSV format).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "2. Paste the frequency response data below (format: Frequency (Hz), SPL (dB))",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "3. Select your target curve (Harman, Diffuse Field, etc.)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "4. The algorithm calculates optimal EQ bands to match your target",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Headphone Name
        OutlinedTextField(
            value = headphoneName,
            onValueChange = { headphoneName = it },
            label = { Text("Headphone Name (Optional)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Measurement Data Input
        Text(
            "Frequency Response CSV Data",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = measurementData,
            onValueChange = { measurementData = it },
            label = { Text("Paste measurement data (frequency, gain)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 16.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(4.dp)
                ),
            minLines = 6,
            maxLines = 6
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Example: 20,80.5\\n25,79.2\\n31.5,78.1\\n...",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { filePicker.launch("text/*") }
            ) {
                Icon(
                    Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Import File", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Divider(modifier = Modifier.padding(horizontal = 16.dp))

        Spacer(modifier = Modifier.height(16.dp))

        // Target Curve Selector
        Text(
            "Target Curve",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(
                onClick = { showTargetMenu = !showTargetMenu },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedTarget.label)
            }

            // Target dropdown menu (simplified - could use DropdownMenu)
            if (showTargetMenu) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = 40.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        availableTargets.forEach { target ->
                            TextButton(
                                onClick = {
                                    viewModel.selectTarget(target.id)
                                    showTargetMenu = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(target.label)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Band Count Selector
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Number of Bands",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${bandCount.toInt()}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = bandCount,
                onValueChange = { bandCount = it },
                valueRange = 3f..31f,
                steps = 27,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "3-31 bands (more bands = more precise but harder to adjust)",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Divider(modifier = Modifier.padding(horizontal = 16.dp))

        // Error message
        if (!error.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp
                )
            }
        }

        // Success message
        if (calculationAttempted && error.isNullOrEmpty() && currentBands.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    "✓ Calibration complete! ${currentBands.size} optimal bands calculated",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = {
                    // Clear and reset
                    measurementData = ""
                    bandCount = 10f
                    calculationAttempted = false
                    viewModel.clearError()
                },
                modifier = Modifier.weight(1f),
                enabled = !isCalculating
            ) {
                Text("Clear")
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = {
                    calculationAttempted = true
                    viewModel.setBandCount(bandCount.toInt())
                    viewModel.calculateAutoEq(measurementData)
                },
                modifier = Modifier.weight(1.5f),
                enabled = measurementData.isNotEmpty() && !isCalculating
            ) {
                if (isCalculating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(if (isCalculating) "Calculating..." else "Calculate EQ")
            }
        }

        // Close button when complete
        if (calculationAttempted && error.isNullOrEmpty() && currentBands.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    onCalibrationComplete()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text("Apply EQ & Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
