// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import tf.monochrome.android.domain.patterns.CaptureSource
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.ui.navigation.LocalMiniPlayerInset
import tf.monochrome.android.ui.patterns.PanelLabel
import tf.monochrome.android.ui.patterns.PatternFader
import tf.monochrome.android.ui.patterns.PatternPanel
import tf.monochrome.android.ui.patterns.PatternPill
import tf.monochrome.android.ui.patterns.TransportKey
import kotlin.math.roundToInt

/**
 * Capture, trim, save.
 *
 * The screen is arranged around the fact that the interesting moment has
 * already passed by the time the user reaches for it: they heard something,
 * and now they want it. So RECORD is the largest thing on the page, the
 * capture starts on one tap with no dialog in front of it, and everything
 * after — trim, level, name, category — is optional. Saving with none of it
 * touched produces a usable sample.
 *
 * What it is deliberately not is a waveform editor. One selection, a level, a
 * few one-tap operations. Anything more belongs in a tool built for it.
 */
@Composable
fun SamplerScreen(
    modifier: Modifier = Modifier,
    viewModel: SamplerViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null,
    onOpenPatterns: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val capture by viewModel.captureState.collectAsStateWithLifecycle()
    val eligibility by viewModel.eligibility.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    // Deleting a sample removes a file and empties every channel that used it,
    // so it asks first. A long press that silently destroyed a sound would be
    // the worst possible thing to discover by accident.
    var pendingDelete by remember { mutableStateOf<SampleRef?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshEligibility() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalMiniPlayerInset.current)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                text = "Sampler",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (onOpenPatterns != null) {
                PatternPill(
                    label = "Patterns",
                    active = false,
                    accent = accent,
                    onClick = onOpenPatterns,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        PatternPanel {
            PanelLabel("Capture source")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PatternPill(
                    label = "Clean",
                    active = ui.source == CaptureSource.CLEAN,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    enabled = ui.stage != SamplerViewModel.Stage.RECORDING,
                    onClick = { viewModel.setSource(CaptureSource.CLEAN) },
                )
                PatternPill(
                    label = "Processed",
                    active = ui.source == CaptureSource.PROCESSED,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    enabled = ui.stage != SamplerViewModel.Stage.RECORDING,
                    onClick = { viewModel.setSource(CaptureSource.PROCESSED) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (ui.source == CaptureSource.CLEAN) {
                    "Decoded audio, before Tryptify's DSP."
                } else {
                    "After the full DSP chain — mixer, EQ and Oxford effects."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            // The eligibility line is never hidden. A greyed-out RECORD button
            // with no explanation is the worst version of this: the user
            // cannot tell whether the feature is broken or the source is not
            // allowed, and the answer is always the second one.
            Text(
                text = eligibility.reason,
                style = MaterialTheme.typography.labelSmall,
                color = if (eligibility.allowed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        when (ui.stage) {
            SamplerViewModel.Stage.IDLE, SamplerViewModel.Stage.RECORDING -> {
                PatternPanel {
                    PanelLabel("Level")
                    Spacer(Modifier.height(6.dp))
                    CaptureMeter(peak = capture.peak, accent = accent)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = formatDuration(capture.durationMs),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportKey(
                            label = "Record",
                            active = ui.stage == SamplerViewModel.Stage.RECORDING,
                            accent = MaterialTheme.colorScheme.error,
                            pulsing = ui.stage == SamplerViewModel.Stage.RECORDING,
                            enabled = eligibility.allowed &&
                                ui.stage != SamplerViewModel.Stage.RECORDING,
                            modifier = Modifier.weight(1f),
                            onClick = viewModel::startRecording,
                        )
                        TransportKey(
                            label = "Stop",
                            active = false,
                            accent = accent,
                            enabled = ui.stage == SamplerViewModel.Stage.RECORDING,
                            modifier = Modifier.weight(1f),
                            onClick = viewModel::stopRecording,
                        )
                    }
                    if (capture.full) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Reached the capture limit.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SamplerViewModel.Stage.EDITING -> {
                SampleEditor(
                    ui = ui,
                    accent = accent,
                    canUndo = ui.canUndo,
                    onTrim = viewModel::setTrim,
                    onApplyTrim = viewModel::applyTrim,
                    onGain = viewModel::setGainDb,
                    onNormalize = viewModel::normalize,
                    onFadeIn = viewModel::fadeIn,
                    onFadeOut = viewModel::fadeOut,
                    onReverse = viewModel::reverse,
                    onMono = viewModel::toMono,
                    onUndo = viewModel::undo,
                    onName = viewModel::setName,
                    onCategory = viewModel::setCategory,
                    onSave = { viewModel.save() },
                    onDiscard = viewModel::cancelCapture,
                )
            }
        }

        ui.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))

        PatternPanel {
            PanelLabel("Library — ${library.size} samples")
            Spacer(Modifier.height(8.dp))
            SampleList(
                samples = library,
                selected = null,
                accent = accent,
                modifier = Modifier.height(280.dp),
                onPick = { },
                onLongPress = { pendingDelete = it },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Long-press a sample to delete it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    pendingDelete?.let { sample ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${sample.name}?") },
            text = {
                Text(
                    "The audio file is removed and any pattern channel using it " +
                        "is emptied. The patterns themselves are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSample(sample)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/** The post-capture edit surface. Trim, level, a few one-tap operations, save. */
@Composable
private fun SampleEditor(
    ui: SamplerViewModel.UiState,
    accent: androidx.compose.ui.graphics.Color,
    canUndo: Boolean,
    onTrim: (Float, Float) -> Unit,
    onApplyTrim: () -> Unit,
    onGain: (Float) -> Unit,
    onNormalize: () -> Unit,
    onFadeIn: () -> Unit,
    onFadeOut: () -> Unit,
    onReverse: () -> Unit,
    onMono: () -> Unit,
    onUndo: () -> Unit,
    onName: (String) -> Unit,
    onCategory: (tf.monochrome.android.domain.patterns.SampleCategory) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    PatternPanel {
        PanelLabel("Trim")
        Spacer(Modifier.height(6.dp))
        WaveformView(
            peaks = ui.peaks,
            accent = accent,
            startFraction = ui.startFraction,
            endFraction = ui.endFraction,
            onTrimChange = onTrim,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(ui.trimmedDurationMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${(ui.startFraction * 100).roundToInt()}% – " +
                    "${(ui.endFraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill("Crop", false, Modifier.weight(1f), accent, onClick = onApplyTrim)
            PatternPill("Norm", false, Modifier.weight(1f), accent, onClick = onNormalize)
            PatternPill("Fade in", false, Modifier.weight(1f), accent, onClick = onFadeIn)
            PatternPill("Fade out", false, Modifier.weight(1f), accent, onClick = onFadeOut)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill("Reverse", false, Modifier.weight(1f), accent, onClick = onReverse)
            PatternPill("Mono", false, Modifier.weight(1f), accent, onClick = onMono)
            PatternPill("Undo", false, Modifier.weight(1f), accent, canUndo, onUndo)
        }

        Spacer(Modifier.height(10.dp))
        PatternFader(
            label = "Gain",
            value = ui.gainDb,
            valueRange = -24f..24f,
            display = "${if (ui.gainDb >= 0) "+" else ""}${(ui.gainDb * 10).roundToInt() / 10f} dB",
            accent = accent,
            onValueChange = onGain,
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = ui.name,
            onValueChange = onName,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        PanelLabel("Category")
        Spacer(Modifier.height(6.dp))
        CategoryStrip(selected = ui.category, accent = accent, onSelect = { onCategory(it ?: ui.category) })

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportKey(
                label = if (ui.saving) "Saving" else "Save sample",
                active = true,
                accent = accent,
                enabled = !ui.saving,
                modifier = Modifier.weight(2f),
                onClick = onSave,
            )
            TransportKey(
                label = "Discard",
                active = false,
                accent = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                onClick = onDiscard,
            )
        }
    }
}
