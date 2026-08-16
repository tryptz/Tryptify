// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * Capture, edit, save.
 *
 * Three ways in and one way out. Audio arrives by recording what is playing,
 * by importing a file, or by re-opening something already in the library —
 * and all three land in the same editor, so there is one set of tools to
 * learn rather than three.
 *
 * The editor is built around a selection instead of a mode. Every operation
 * acts on the selection, or on the whole buffer when there is none, which is
 * what lets six buttons cover both "tidy up this hit" and "fix this one
 * syllable" without a toolbar full of variants.
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
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    // Deleting a sample removes a file and empties every channel that used it,
    // so it asks first. A long press that silently destroyed a sound would be
    // the worst possible thing to discover by accident.
    var pendingDelete by remember { mutableStateOf<SampleRef?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importFile(uri, displayNameOf(context, uri))
    }

    // A model file is hundreds of megabytes, so the URI is handed over as a
    // function that opens it rather than as an already-open stream: the copy
    // runs on a background dispatcher and should be the thing that decides when
    // to start reading, not the callback the picker happens to return on.
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importModel {
                context.contentResolver.openInputStream(uri)
                    ?: error("Could not open that file")
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshEligibility() }
    // Disk only — reads what is installed and which backend wins. Deliberately
    // not a catalogue fetch: a screen that phoned home on every open would be a
    // different app from the offline-after-install one the brief describes.
    LaunchedEffect(Unit) { viewModel.refreshAiState() }

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

        if (ui.stage != SamplerViewModel.Stage.EDITING) {
            CapturePanel(
                ui = ui,
                capture = capture,
                eligibilityReason = eligibility.reason,
                canRecord = eligibility.allowed,
                accent = accent,
                onSource = viewModel::setSource,
                onRecord = viewModel::startRecording,
                onStop = viewModel::stopRecording,
                onImport = {
                    // Anything the platform can decode. Narrowing to a
                    // whitelist of extensions only ever excludes files that
                    // would have worked.
                    importLauncher.launch(arrayOf("audio/*"))
                },
            )
        } else {
            WaveEditorPanel(
                ui = ui,
                previewState = previewState,
                accent = accent,
                viewModel = viewModel,
            )

            Spacer(Modifier.height(10.dp))

            // Below the editor rather than behind a tab: separating is
            // something you do *to* what is on screen, and having both visible
            // is what makes "split this, then edit the drums" one flow.
            // Above the studio, because "what is doing the separating" is the
            // question you have before you press the button, not after.
            StemAiPanel(
                ai = ui.ai,
                accent = accent,
                onInstall = viewModel::installModel,
                onCancelInstall = viewModel::cancelInstall,
                onCheckForUpdate = viewModel::checkForModels,
                onImport = { modelPicker.launch(arrayOf("*/*")) },
                onDelete = viewModel::deleteModel,
            )

            Spacer(Modifier.height(10.dp))

            StemStudioPanel(
                stems = ui.stems,
                description = viewModel.separatorDescription,
                accent = accent,
                playingStem = previewState.playing,
                onSeparate = viewModel::separateStems,
                onCancel = viewModel::cancelStems,
                onLevel = viewModel::setStemLevel,
                onPlayStem = viewModel::playStem,
                onPlayMix = viewModel::playStemMix,
                onStopPreview = viewModel::stopPreview,
                onEditStem = viewModel::editStem,
                onSaveStem = { viewModel.saveStem(it) },
                onSaveAll = viewModel::saveAllStems,
            )
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
                // Tapping opens the sample in the editor rather than doing
                // nothing: the library is where you go to fix a sample, and
                // this is the only route to that.
                onPick = viewModel::editExisting,
                onLongPress = { pendingDelete = it },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tap to edit. Long-press to delete.",
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

// ── capture ─────────────────────────────────────────────────────────────

@Composable
private fun CapturePanel(
    ui: SamplerViewModel.UiState,
    capture: tf.monochrome.android.audio.sampler.SampleCaptureEngine.CaptureState,
    eligibilityReason: String,
    canRecord: Boolean,
    accent: Color,
    onSource: (CaptureSource) -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
) {
    val recording = ui.stage == SamplerViewModel.Stage.RECORDING

    PatternPanel {
        PanelLabel("Capture source")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill(
                label = "Clean",
                active = ui.source == CaptureSource.CLEAN,
                accent = accent,
                modifier = Modifier.weight(1f),
                enabled = !recording,
                onClick = { onSource(CaptureSource.CLEAN) },
            )
            PatternPill(
                label = "Processed",
                active = ui.source == CaptureSource.PROCESSED,
                accent = accent,
                modifier = Modifier.weight(1f),
                enabled = !recording,
                onClick = { onSource(CaptureSource.PROCESSED) },
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

        Spacer(Modifier.height(6.dp))
        // What the capture will be attributed to. Always on screen, so the
        // saved sample's provenance is never a surprise.
        Text(
            text = eligibilityReason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
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
                active = recording,
                accent = MaterialTheme.colorScheme.error,
                pulsing = recording,
                enabled = canRecord && !recording,
                modifier = Modifier.weight(1f),
                onClick = onRecord,
            )
            TransportKey(
                label = "Stop",
                active = false,
                accent = accent,
                enabled = recording,
                modifier = Modifier.weight(1f),
                onClick = onStop,
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

        Spacer(Modifier.height(10.dp))
        PatternPill(
            label = "Import a file…",
            active = false,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
            enabled = !recording,
            onClick = onImport,
        )
    }
}

// ── editor ──────────────────────────────────────────────────────────────

@Composable
private fun WaveEditorPanel(
    ui: SamplerViewModel.UiState,
    previewState: tf.monochrome.android.audio.sampler.SamplePreviewPlayer.State,
    accent: Color,
    viewModel: SamplerViewModel,
) {
    val buffer = ui.buffer ?: return

    PatternPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelLabel(if (ui.editingSampleId != null) "Editing sample" else "New sample")
            Text(
                text = formatDuration(ui.totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))

        WaveEditor(
            peaks = ui.peaks,
            frames = ui.frames,
            view = ui.view,
            accent = accent,
            playheadFrame = previewState.frame.takeIf { previewState.playing },
            onViewChange = viewModel::setView,
        )

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (ui.view.hasSelection) {
                    "Selected ${formatDuration(ui.selectedDurationMs)}"
                } else {
                    "Whole sample — drag to select"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Pinch to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TransportKey(
                label = if (previewState.playing) "Stop" else "Play",
                active = previewState.playing,
                accent = accent,
                modifier = Modifier.weight(2f),
                onClick = {
                    if (previewState.playing) viewModel.stopPreview() else viewModel.playSelection()
                },
            )
            PatternPill(
                label = "Loop",
                active = ui.loopPreview,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.setLoopPreview(!ui.loopPreview) },
            )
        }

        Spacer(Modifier.height(10.dp))
        PanelLabel("View")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill("All", false, Modifier.weight(1f), accent, onClick = viewModel::selectAll)
            PatternPill(
                "Zoom sel",
                false,
                Modifier.weight(1f),
                accent,
                enabled = ui.view.hasSelection,
                onClick = viewModel::zoomToSelection,
            )
            PatternPill("Fit", false, Modifier.weight(1f), accent, onClick = viewModel::zoomOut)
            PatternPill(
                "Snap 0",
                ui.snapToZero,
                Modifier.weight(1f),
                accent,
                onClick = { viewModel.setSnapToZero(!ui.snapToZero) },
            )
        }

        Spacer(Modifier.height(10.dp))
        PanelLabel(if (ui.view.hasSelection) "Edit selection" else "Edit whole sample")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill("Crop", false, Modifier.weight(1f), accent, onClick = viewModel::cropToSelection)
            PatternPill("Cut", false, Modifier.weight(1f), accent, onClick = viewModel::deleteSelection)
            PatternPill("Silence", false, Modifier.weight(1f), accent, onClick = viewModel::silenceSelection)
            PatternPill("Norm", false, Modifier.weight(1f), accent, onClick = viewModel::normalize)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill("Fade in", false, Modifier.weight(1f), accent, onClick = viewModel::fadeIn)
            PatternPill("Fade out", false, Modifier.weight(1f), accent, onClick = viewModel::fadeOut)
            PatternPill("Reverse", false, Modifier.weight(1f), accent, onClick = viewModel::reverse)
            PatternPill("Mono", false, Modifier.weight(1f), accent, onClick = viewModel::toMono)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill("Trim silence", false, Modifier.weight(1f), accent, onClick = viewModel::trimSilence)
            PatternPill(
                "Undo",
                false,
                Modifier.weight(1f),
                accent,
                enabled = ui.canUndo,
                onClick = viewModel::undo,
            )
        }

        Spacer(Modifier.height(10.dp))
        PatternFader(
            label = "Gain",
            value = ui.gainDb,
            valueRange = -24f..24f,
            display = "${if (ui.gainDb >= 0) "+" else ""}${(ui.gainDb * 10).roundToInt() / 10f} dB",
            accent = accent,
            onValueChange = viewModel::setGainDb,
        )
        // Applied on save regardless; this button is for hearing it now, and
        // for stacking it with a further move of the fader.
        PatternPill(
            label = "Apply gain",
            active = false,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
            onClick = viewModel::applyGain,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ui.name,
            onValueChange = viewModel::setName,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        PanelLabel("Category")
        Spacer(Modifier.height(6.dp))
        CategoryStrip(
            selected = ui.category,
            accent = accent,
            onSelect = { viewModel.setCategory(it ?: ui.category) },
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportKey(
                label = when {
                    ui.saving -> "Saving"
                    ui.editingSampleId != null -> "Save changes"
                    else -> "Save sample"
                },
                active = true,
                accent = accent,
                enabled = !ui.saving,
                modifier = Modifier.weight(2f),
                onClick = { viewModel.save() },
            )
            TransportKey(
                label = "Discard",
                active = false,
                accent = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                onClick = viewModel::cancelCapture,
            )
        }
    }
}

/**
 * The file's own name, for the imported sample's default.
 *
 * A content URI's last path segment is usually an opaque document id, so the
 * provider is asked for the display name and the segment is only a fallback.
 */
private fun displayNameOf(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment
