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
import androidx.compose.material3.FilterChip
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
 * The centrepiece is the live preview: an EQ profile is invisible until it
 * plays, and a bad import is *audible* — so the parsed curve, the filter
 * count, and every warning the parser produced (skipped filter types, clamped
 * gains, derived preamp) are shown BEFORE anything is saved or applied.
 * Nothing is fixed up silently.
 *
 * [channelChoices] — non-null on the AutoEQ surface while 2-channel mode is
 * on: the profile can target both ears or just one. Null hides the selector
 * (Parametric EQ, or mono AutoEQ).
 *
 * [onMeasurementDetected] — AutoEq publishes a frequency-response CSV right
 * next to the PEQ file, and users will inevitably hand us one. The parser
 * recognises it; if this callback is set the sheet offers to import it down
 * the measurement path instead of failing with "no filters found".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportEqProfileSheet(
    maxBandGainDb: Float,
    onDismiss: () -> Unit,
    onImport: (profile: ParsedEqProfile, name: String, channel: EqChannel?, apply: Boolean) -> Unit,
    channelChoices: Boolean = false,
    onMeasurementDetected: ((raw: String, channel: EqChannel?) -> Unit)? = null,
) {
    val context = LocalContext.current
    var rawText by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // null = both ears (or mono); otherwise the single ear to import into.
    var targetChannel by rememberSaveable { mutableStateOf<String?>(null) }

    val parsed = remember(rawText, maxBandGainDb) {
        if (rawText.isBlank()) null else ApoProfileParser.parse(rawText, maxBandGainDb)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (!text.isNullOrBlank()) {
                rawText = text
                if (name.isBlank()) {
                    // Last path segment, minus extension and SAF's "primary:" /
                    // "raw:" prefixes — a sensible default the user can retype.
                    name = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringAfterLast(':')
                        ?.removeSuffix(".txt")
                        ?.removeSuffix(".csv")
                        ?: ""
                }
            }
        } catch (_: Exception) {
            // Unreadable file: leave the sheet as-is; the empty state explains.
        }
    }

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
                "EqualizerAPO / AutoEq ParametricEQ.txt, or a type,fc,gain,q CSV. " +
                    "Paste below or open a file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(
                        Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Open file")
                }
            }

            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 160.dp),
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
                        "This looks like a frequency-response measurement " +
                            "(AutoEq CSV), not a filter profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    if (onMeasurementDetected != null) {
                        Button(onClick = {
                            onMeasurementDetected(
                                rawText,
                                targetChannel?.let { EqChannel.valueOf(it) },
                            )
                            onDismiss()
                        }) { Text("Import as measurement instead") }
                    } else {
                        Text(
                            "Import it from the AutoEQ screen's measurement picker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                parsed.isEmpty -> {
                    parsed.warnings.forEach { w ->
                        Text(
                            w,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                else -> {
                    // ── Live preview ──
                    FrequencyResponseGraph(
                        originalCurve = emptyList(),
                        targetCurve = emptyList(),
                        eqBands = parsed.bands,
                        preamp = parsed.preamp,
                        centerOnZero = true,
                        showLegend = false,
                        maxAbsDragGain = maxBandGainDb,
                    )
                    Text(
                        "${parsed.bands.size} filters · preamp " +
                            "%.1f dB".format(parsed.preamp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    parsed.warnings.forEach { w ->
                        Text(
                            "⚠ $w",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Profile name") },
                        singleLine = true,
                    )

                    if (channelChoices) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = targetChannel == null,
                                onClick = { targetChannel = null },
                                label = { Text("Both ears") },
                            )
                            FilterChip(
                                selected = targetChannel == EqChannel.LEFT.name,
                                onClick = { targetChannel = EqChannel.LEFT.name },
                                label = { Text("Left") },
                            )
                            FilterChip(
                                selected = targetChannel == EqChannel.RIGHT.name,
                                onClick = { targetChannel = EqChannel.RIGHT.name },
                                label = { Text("Right") },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                onImport(
                                    parsed,
                                    name.ifBlank { "Imported profile" },
                                    targetChannel?.let { EqChannel.valueOf(it) },
                                    false,
                                )
                                onDismiss()
                            },
                        ) { Text("Save") }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                onImport(
                                    parsed,
                                    name.ifBlank { "Imported profile" },
                                    targetChannel?.let { EqChannel.valueOf(it) },
                                    true,
                                )
                                onDismiss()
                            },
                        ) { Text("Save & apply") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
