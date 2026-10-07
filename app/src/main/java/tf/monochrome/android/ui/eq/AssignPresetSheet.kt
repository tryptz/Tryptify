package tf.monochrome.android.ui.eq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.OutputId
import tf.monochrome.android.audio.eq.OutputSlot
import tf.monochrome.android.audio.eq.labelRes
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.ui.components.GlassOverlay

/**
 * Poweramp's "Assign preset": tick the outputs [preset] should play on. Each
 * kind of output, and under Bluetooth and USB each device seen there by name,
 * whose own preset comes before its kind's.
 *
 * A glass pane in the app's overlay slot rather than a dialog, which is a
 * window of its own and could not blur the screen behind it.
 */
@Composable
internal fun AssignPresetSheet(
    preset: EqPreset,
    assignments: Map<String, String>,
    knownDevices: List<OutputId>,
    current: OutputId?,
    presetName: (String) -> String?,
    onConfirm: (Set<OutputId>) -> Unit,
    onDismiss: () -> Unit,
) {
    // The playing device even before it is remembered, and any device still
    // assigned after dropping off the remembered list: a row missing from the
    // sheet would be unassigned by its OK without anyone seeing it go.
    val devices = remember(knownDevices, current, assignments) {
        (listOfNotNull(current) + knownDevices + assignments.keys.mapNotNull(OutputId::fromKey))
            .filter { it.name != null }
            .distinct()
    }
    var ticked by remember(preset.id) {
        mutableStateOf(
            assignments.filterValues { it == preset.id }.keys.mapNotNull(OutputId::fromKey).toSet(),
        )
    }

    GlassOverlay(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.eq_assign_preset),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.eq_assign_body, preset.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(R.string.eq_assign_named_first),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            val rows = OutputSlot.entries.flatMap { slot ->
                listOf(OutputId(slot)) + if (slot.named) devices.filter { it.slot == slot } else emptyList()
            }
            for (output in rows) {
                val elsewhere = assignments[output.key]?.takeIf { it != preset.id }?.let(presetName)
                OutputRow(
                    output = output,
                    checked = output in ticked,
                    connected = output == current,
                    assignedElsewhere = elsewhere,
                    onCheckedChange = { on -> ticked = if (on) ticked + output else ticked - output },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onConfirm(emptySet())
                    onDismiss()
                }) { Text(stringResource(R.string.action_unassign)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onConfirm(ticked)
                    onDismiss()
                }) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

@Composable
private fun OutputRow(
    output: OutputId,
    checked: Boolean,
    connected: Boolean,
    assignedElsewhere: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    val detail = listOfNotNull(
        stringResource(R.string.eq_output_connected).takeIf { connected },
        assignedElsewhere?.let { stringResource(R.string.eq_output_now_other, it) },
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            // A device sits under its kind, as in Poweramp's sheet.
            .padding(start = if (output.name != null) 28.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = output.slot.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        // The row is the toggle; the box only shows its state.
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 6.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text = output.name ?: stringResource(output.slot.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal val OutputSlot.icon: ImageVector
    get() = when (this) {
        OutputSlot.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
        OutputSlot.WIRED -> Icons.Default.Headphones
        OutputSlot.BLUETOOTH -> Icons.Default.Bluetooth
        OutputSlot.USB -> Icons.Default.Usb
    }

/**
 * The outputs a preset is assigned to, under its name in the preset list:
 * nothing when it has none.
 */
@Composable
internal fun AssignedOutputsLine(outputs: List<OutputId>) {
    if (outputs.isEmpty()) return
    // Kinds first, then devices, each in the sheet's order.
    val sorted = outputs.sortedWith(compareBy<OutputId>({ it.slot.ordinal }, { it.name != null }, { it.name }))
    val labels = sorted.map { it.name ?: stringResource(it.slot.labelRes) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(
            imageVector = sorted.first().slot.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = labels.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** "AutoEQ off" as a preset the assign sheet can take; see OutputEq.EQ_OFF. */
@Composable
internal fun rememberEqOffPreset(): EqPreset {
    val name = stringResource(R.string.eq_off_preset)
    val detail = stringResource(R.string.eq_off_preset_detail)
    return remember(name, detail) {
        EqPreset(id = tf.monochrome.android.audio.eq.OutputEq.EQ_OFF, name = name, description = detail)
    }
}
