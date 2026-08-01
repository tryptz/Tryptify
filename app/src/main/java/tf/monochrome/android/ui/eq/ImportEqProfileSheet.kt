package tf.monochrome.android.ui.eq

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.import_.ApoProfileParser
import tf.monochrome.android.data.import_.ParsedEqProfile

/**
 * Import an EqualizerAPO-style parametric profile (`ParametricEQ.txt` or a
 * band CSV) by paste or file.
 *
 * With [perEar] the sheet is two panes — LEFT and RIGHT — each with its own
 * paste window and Open-file button, and one Upload button at the end:
 *  - only L filled  → mono import (drives both ears)
 *  - L and R filled → a stereo preset; each ear gets its own filter stack
 *  - only R filled  → imported as mono (it's the only curve there is)
 *
 * The preview shows both stacks at once (right ear as the amber overlay) plus
 * every warning the parser produced — an EQ profile is invisible until it
 * plays, so nothing is fixed up or applied silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportEqProfileSheet(
    maxBandGainDb: Float,
    onDismiss: () -> Unit,
    onImport: (left: ParsedEqProfile?, right: ParsedEqProfile?, name: String) -> Unit,
    perEar: Boolean = false,
    onMeasurementDetected: ((raw: String, channel: EqChannel?) -> Unit)? = null,
) {
    var rawL by rememberSaveable { mutableStateOf("") }
    var rawR by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    val parsedL = remember(rawL, maxBandGainDb) {
        if (rawL.isBlank()) null else ApoProfileParser.parse(rawL, maxBandGainDb)
    }
    val parsedR = remember(rawR, maxBandGainDb) {
        if (rawR.isBlank()) null else ApoProfileParser.parse(rawR, maxBandGainDb)
    }
    val validL = parsedL?.takeIf { !it.isEmpty && !it.looksLikeMeasurement }
    val validR = parsedR?.takeIf { !it.isEmpty && !it.looksLikeMeasurement }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Import EQ profile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (perEar) {
                    "EqualizerAPO / AutoEq ParametricEQ.txt or a type,fc,gain,q CSV. " +
                        "Fill LEFT only for a mono profile, or both panes for a " +
                        "per-ear stereo profile."
                } else {
                    "EqualizerAPO / AutoEq ParametricEQ.txt, or a type,fc,gain,q CSV. " +
                        "Paste below or open a file."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ProfilePane(
                title = if (perEar) "LEFT EAR" else null,
                raw = rawL,
                onRaw = { rawL = it },
                parsed = parsedL,
                onFileName = { if (name.isBlank()) name = it },
                onMeasurementImport = onMeasurementDetected?.let { cb ->
                    { text -> cb(text, EqChannel.LEFT); onDismiss() }
                },
            )

            if (perEar) {
                ProfilePane(
                    title = "RIGHT EAR",
                    raw = rawR,
                    onRaw = { rawR = it },
                    parsed = parsedR,
                    onFileName = { if (name.isBlank()) name = it },
                    onMeasurementImport = onMeasurementDetected?.let { cb ->
                        { text -> cb(text, EqChannel.RIGHT); onDismiss() }
                    },
                )
            }

            if (validL != null || validR != null) {
                // ── Live preview: L primary, R as the amber overlay ──
                val primaryProfile = validL ?: validR!!
                FrequencyResponseGraph(
                    originalCurve = emptyList(),
                    targetCurve = emptyList(),
                    eqBands = primaryProfile.bands,
                    preamp = primaryProfile.preamp,
                    centerOnZero = true,
                    showLegend = false,
                    maxAbsDragGain = maxBandGainDb,
                    secondaryBands = if (validL != null && validR != null) validR.bands else null,
                )
                val summary = buildString {
                    append(primaryProfile.bands.size)
                    append(" filters")
                    if (validL != null && validR != null) {
                        append(" L / ")
                        append(validR.bands.size)
                        append(" filters R")
                    }
                    append(" · preamp ")
                    append("%.1f dB".format(minOf(
                        validL?.preamp ?: Float.MAX_VALUE,
                        validR?.preamp ?: Float.MAX_VALUE,
                    )))
                }
                Text(summary, style = MaterialTheme.typography.labelMedium)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile name") },
                    singleLine = true,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = validL != null || validR != null,
                    onClick = {
                        onImport(validL, validR, name.ifBlank { "Imported profile" })
                        onDismiss()
                    },
                ) { Text("Upload") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One channel's paste window + Open-file button + live parse feedback. */
@Composable
private fun ProfilePane(
    title: String?,
    raw: String,
    onRaw: (String) -> Unit,
    parsed: ParsedEqProfile?,
    onFileName: (String) -> Unit,
    onMeasurementImport: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (!text.isNullOrBlank()) {
                onRaw(text)
                uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringAfterLast(':')
                    ?.removeSuffix(".txt")
                    ?.removeSuffix(".csv")
                    ?.let(onFileName)
            }
        } catch (_: Exception) {
            // Unreadable file: leave the pane as-is.
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title ?: "PROFILE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.height(28.dp),
            ) {
                Icon(
                    Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Open file", style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedTextField(
            value = raw,
            onValueChange = onRaw,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 130.dp),
            placeholder = {
                Text(
                    "Preamp: -6.8 dB\nFilter 1: ON PK Fc 105 Hz Gain -2.9 dB Q 0.70",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        when {
            parsed == null -> Unit
            parsed.looksLikeMeasurement -> {
                Text(
                    "This looks like a frequency-response measurement, not a filter profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (onMeasurementImport != null) {
                    TextButton(onClick = { onMeasurementImport(raw) }) {
                        Text("Import as measurement instead")
                    }
                }
            }
            parsed.isEmpty -> parsed.warnings.forEach { w ->
                Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            else -> {
                Text(
                    "${parsed.bands.size} filters · preamp ${"%.1f".format(parsed.preamp)} dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                parsed.warnings.forEach { w ->
                    Text(
                        "⚠ $w",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
