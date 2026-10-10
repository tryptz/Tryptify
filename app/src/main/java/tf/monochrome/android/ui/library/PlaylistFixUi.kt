package tf.monochrome.android.ui.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.playlistfix.PlaylistFixMode
import tf.monochrome.android.data.playlistfix.PlaylistFixState

/** Repair and Regenerate, side by side under the playlist's Play row. */
@Composable
internal fun PlaylistFixActions(
    enabled: Boolean,
    onRepair: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FilledTonalButton(onClick = onRepair, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.playlist_fix_repair), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = onRegenerate, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.playlist_fix_regenerate), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The running job's progress, or the finished job's summary, for this playlist.
 * Shows nothing while there is neither.
 */
@Composable
internal fun PlaylistFixStatus(
    state: PlaylistFixState,
    busyElsewhere: Boolean,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onShowUnmatched: (List<String>) -> Unit,
) {
    if (state is PlaylistFixState.Idle) {
        if (busyElsewhere) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.playlist_fix_busy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Spacer(modifier = Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            when (state) {
                is PlaylistFixState.Running -> {
                    Text(
                        text = stringResource(
                            if (state.mode == PlaylistFixMode.REPAIR) R.string.playlist_fix_repairing
                            else R.string.playlist_fix_regenerating
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.total > 0) {
                        LinearProgressIndicator(
                            progress = { state.checked.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.playlist_fix_checking, state.checked, state.total, state.fixed),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onStop) { Text(stringResource(R.string.playlist_fix_stop)) }
                    }
                }

                is PlaylistFixState.Done -> {
                    val result = state.result
                    Text(
                        text = when {
                            result.stopped -> stringResource(R.string.playlist_fix_stopped)
                            state.mode == PlaylistFixMode.REPAIR -> stringResource(R.string.playlist_fix_repair_done)
                            else -> stringResource(R.string.playlist_fix_regenerate_done)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SummaryLine(
                        stringResource(
                            R.string.playlist_fix_summary,
                            result.fixed, result.alreadyRight, result.notMatched.size,
                        )
                    )
                    if (result.merged > 0) SummaryLine(stringResource(R.string.playlist_fix_merged, result.merged))
                    if (result.onDevice > 0) SummaryLine(stringResource(R.string.playlist_fix_on_device, result.onDevice))
                    if (result.unchecked > 0) SummaryLine(stringResource(R.string.playlist_fix_unchecked, result.unchecked))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (result.notMatched.isNotEmpty()) {
                            TextButton(onClick = { onShowUnmatched(result.notMatched) }) {
                                Text(stringResource(R.string.playlist_fix_show_unmatched))
                            }
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }

                is PlaylistFixState.Failed -> {
                    Text(
                        text = stringResource(R.string.playlist_fix_failed, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }

                PlaylistFixState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun SummaryLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

/** Confirms a repair; without a connected catalogue there is nothing to check against. */
@Composable
internal fun RepairPlaylistDialog(
    services: List<ApiService>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_fix_repair_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.playlist_fix_repair_body))
                if (services.isEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.playlist_fix_no_services),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = services.isNotEmpty()) {
                Text(stringResource(R.string.playlist_fix_repair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Picks the catalogue to rebuild the playlist from and, optionally, a second
 * one for the songs the first doesn't have. Only connected catalogues are offered.
 */
@Composable
internal fun RegeneratePlaylistDialog(
    services: List<ApiService>,
    onDismiss: () -> Unit,
    onConfirm: (primary: ApiService, fallback: ApiService?) -> Unit,
) {
    var primaryName by rememberSaveable { mutableStateOf<String?>(null) }
    var fallbackName by rememberSaveable { mutableStateOf<String?>(null) }
    // Defaults follow the connected list as it arrives: the first catalogue,
    // and the next one as its fallback.
    val primary = services.firstOrNull { it.name == primaryName } ?: services.firstOrNull()
    val fallbackChoices = services.filter { it != primary }
    val fallback = when (fallbackName) {
        null -> fallbackChoices.firstOrNull()
        NO_FALLBACK -> null
        else -> fallbackChoices.firstOrNull { it.name == fallbackName }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_fix_regenerate_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.playlist_fix_regenerate_body))
                if (services.isEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.playlist_fix_no_services),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.playlist_fix_service), style = MaterialTheme.typography.titleSmall)
                    Column(modifier = Modifier.selectableGroup()) {
                        services.forEach { service ->
                            ChoiceRow(
                                label = service.label,
                                selected = service == primary,
                                onSelect = {
                                    primaryName = service.name
                                    // A fallback can't be the service it falls back from.
                                    if (fallbackName == service.name) fallbackName = null
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.playlist_fix_fallback), style = MaterialTheme.typography.titleSmall)
                    Column(modifier = Modifier.selectableGroup()) {
                        fallbackChoices.forEach { service ->
                            ChoiceRow(
                                label = service.label,
                                selected = service == fallback,
                                onSelect = { fallbackName = service.name },
                            )
                        }
                        ChoiceRow(
                            label = stringResource(R.string.playlist_fix_no_fallback),
                            selected = fallback == null,
                            onSelect = { fallbackName = NO_FALLBACK },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { primary?.let { onConfirm(it, fallback) } },
                enabled = primary != null,
            ) { Text(stringResource(R.string.playlist_fix_regenerate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val NO_FALLBACK = "none"

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The songs no catalogue had a sure match for; they were left as they were. */
@Composable
internal fun UnmatchedSongsDialog(songs: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_fix_unmatched_title)) },
        text = {
            Column {
                Text(stringResource(R.string.playlist_fix_unmatched_body))
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(songs) { song ->
                        Text(
                            text = song,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
