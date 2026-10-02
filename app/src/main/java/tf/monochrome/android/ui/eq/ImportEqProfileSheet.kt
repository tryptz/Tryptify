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
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import androidx.compose.ui.res.pluralStringResource

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
                stringResource(R.string.eq_import_eq_profile),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (perEar) {
                    stringResource(R.string.eq_import_hint_per_ear)
                } else {
                    stringResource(R.string.eq_import_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ProfilePane(
                title = if (perEar) stringResource(R.string.eq_left_ear_caps) else null,
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
                    title = stringResource(R.string.eq_right_ear_caps),
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
                val filtersL = pluralStringResource(
                    R.plurals.eq_filter_count, primaryProfile.bands.size, primaryProfile.bands.size,
                )
                val filters = if (validL != null && validR != null) {
                    stringResource(
                        R.string.eq_filters_per_ear, filtersL,
                        pluralStringResource(R.plurals.eq_filter_count, validR.bands.size, validR.bands.size),
                    )
                } else filtersL
                val summary = filters + stringResource(
                    R.string.eq_preamp_summary,
                    minOf(validL?.preamp ?: Float.MAX_VALUE, validR?.preamp ?: Float.MAX_VALUE),
                )
                Text(summary, style = MaterialTheme.typography.labelMedium)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.eq_profile_name)) },
                    singleLine = true,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                val importedProfileName = stringResource(R.string.eq_imported_profile)
                Button(
                    enabled = validL != null || validR != null,
                    onClick = {
                        onImport(validL, validR, name.ifBlank { importedProfileName })
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.eq_upload)) }
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
                title ?: stringResource(R.string.eq_profile_caps),
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
                Text(stringResource(R.string.eq_open_file), style = MaterialTheme.typography.labelMedium)
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
                    stringResource(R.string.eq_this_looks_like_a_frequency_response_measurement),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (onMeasurementImport != null) {
                    TextButton(onClick = { onMeasurementImport(raw) }) {
                        Text(stringResource(R.string.eq_import_as_measurement_instead))
                    }
                }
            }
            parsed.isEmpty -> parsed.warnings.forEach { w ->
                Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            else -> {
                Text(
                    pluralStringResource(R.plurals.eq_filter_count, parsed.bands.size, parsed.bands.size) +
                        stringResource(R.string.eq_preamp_summary, parsed.preamp),
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
