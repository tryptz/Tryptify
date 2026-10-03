package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.dsp.model.MixPreset
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * Horizontal preset management bar: current name, save, load, import.
 *
 * The load menu groups built-in showcase presets ("Presets") above the user's
 * own saved presets ("My Presets"). Built-in presets can be loaded and
 * exported but not deleted.
 */
@Composable
fun PresetBar(
    currentPresetName: String?,
    presets: List<MixPreset>,
    onSave: (String) -> Unit,
    onLoad: (MixPreset) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: (MixPreset) -> Unit = {},
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<MixPreset?>(null) }

    val builtInPresets = presets.filter { !it.isCustom }
    val customPresets = presets.filter { it.isCustom }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)
    ) {
        // Current preset name
        Text(
            text = currentPresetName ?: stringResource(R.string.mixer_default_preset),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // Import from file
        IconButton(onClick = onImport, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.FileDownload,
                contentDescription = stringResource(R.string.mixer_import_preset),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Save
        IconButton(onClick = { showSaveDialog = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Save,
                contentDescription = stringResource(R.string.fx_save_preset),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Load
        IconButton(onClick = { showLoadMenu = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.mixer_load_preset),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )

            DropdownMenu(
                expanded = showLoadMenu,
                onDismissRequest = { showLoadMenu = false }
            ) {
                if (builtInPresets.isNotEmpty()) {
                    PresetMenuHeader(stringResource(R.string.visualizer_presets))
                    builtInPresets.forEach { preset ->
                        PresetMenuRow(
                            preset = preset,
                            onLoad = { onLoad(preset); showLoadMenu = false },
                            onExport = { onExport(preset); showLoadMenu = false },
                            onDelete = null
                        )
                    }
                }

                PresetMenuHeader(stringResource(R.string.mixer_my_presets))
                if (customPresets.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mixer_no_presets)) },
                        onClick = { showLoadMenu = false },
                        enabled = false
                    )
                } else {
                    customPresets.forEach { preset ->
                        PresetMenuRow(
                            preset = preset,
                            onLoad = { onLoad(preset); showLoadMenu = false },
                            onExport = { onExport(preset); showLoadMenu = false },
                            onDelete = { showDeleteConfirm = preset; showLoadMenu = false }
                        )
                    }
                }
            }
        }
    }

    // ── Save dialog ──────────────────────────────────────────────────
    if (showSaveDialog) {
        var name by remember { mutableStateOf(currentPresetName ?: "") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.fx_save_preset)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fx_preset_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            onSave(name.trim())
                            showSaveDialog = false
                        }
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // ── Delete confirmation ──────────────────────────────────────────
    showDeleteConfirm?.let { preset ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.settings_delete_preset)) },
            text = { Text(stringResource(R.string.settings_delete_named, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(preset.id)
                    showDeleteConfirm = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun PresetMenuHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingXs)
    )
}

@Composable
private fun PresetMenuRow(
    preset: MixPreset,
    onLoad: () -> Unit,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?
) {
    DropdownMenuItem(
        text = { Text(preset.name) },
        onClick = onLoad,
        trailingIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onExport, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.IosShare,
                        contentDescription = stringResource(R.string.mixer_export),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    )
}
