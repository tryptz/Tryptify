package tf.monochrome.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.label
import tf.monochrome.android.audio.sync.OutputSync
import tf.monochrome.android.audio.sync.OutputSyncRepository
import tf.monochrome.android.glyph.data.TryptManiaRequests
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.components.holdToRepeat

@HiltViewModel
class OutputSyncViewModel @Inject constructor(val sync: OutputSyncRepository) : ViewModel()

/**
 * Settings › Audio's Bluetooth sync: how late the output playing now plays,
 * nudged in 5 ms steps or measured with Trypt Mania's tap test. One value per
 * output, used across the app (lyrics, visuals, visualizer, Trypt Mania).
 */
@Composable
fun BluetoothSyncRow(navController: NavController, viewModel: OutputSyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val output by viewModel.sync.output.collectAsStateWithLifecycle()
    val ms by viewModel.sync.delayMs.collectAsStateWithLifecycle()
    val title = stringResource(R.string.settings_bluetooth_sync)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsAnchor(title)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = output?.let { stringResource(R.string.settings_bluetooth_sync_desc, label(context, it)) }
                ?: stringResource(R.string.settings_bluetooth_sync_no_output),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            // Held down, the buttons keep stepping: a delay of a few hundred
            // milliseconds is thirty-odd taps otherwise.
            val lessEnabled = output != null && ms > OutputSync.MIN_MS
            IconButton(
                onClick = { viewModel.sync.setForCurrent(ms - STEP_MS) },
                enabled = lessEnabled,
                modifier = Modifier.holdToRepeat(lessEnabled) { viewModel.sync.setForCurrent(viewModel.sync.delayMs.value - STEP_MS) },
            ) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_bluetooth_sync_less))
            }
            Text(
                text = (if (ms > 0) "+" else "") + "$ms ms",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 72.dp),
            )
            val moreEnabled = output != null && ms < OutputSync.MAX_MS
            IconButton(
                onClick = { viewModel.sync.setForCurrent(ms + STEP_MS) },
                enabled = moreEnabled,
                modifier = Modifier.holdToRepeat(moreEnabled) { viewModel.sync.setForCurrent(viewModel.sync.delayMs.value + STEP_MS) },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_bluetooth_sync_more))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = {
                    TryptManiaRequests.openSync()
                    navController.navigateTool(Screen.Glyph)
                },
                enabled = output != null,
            ) {
                Text(stringResource(R.string.settings_bluetooth_sync_measure))
            }
        }
    }
}

/** A nudge: Trypt Mania's own stepper's. */
private const val STEP_MS = 5
