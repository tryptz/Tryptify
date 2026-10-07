package tf.monochrome.android.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R

/**
 * The folder sort: one button, one menu, with the folders' keys and the
 * songs' keys in two labelled groups. The same choice applies to every
 * folder, so it is set once rather than per screen.
 *
 * Picking a key starts it in its natural direction ([FolderTrackOrder.firstAscending]);
 * picking the chosen key again turns it round, as the app's other sort menus do.
 *
 * [showSongs] is false on the Folders list, which shows no songs.
 */
@Composable
fun FolderSortButton(
    sort: FolderSort,
    onChange: (FolderSort) -> Unit,
    showSongs: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.SwapVert,
                contentDescription = stringResource(R.string.action_sort),
                // Lit while anything but the default applies, so a folder not
                // in its natural order says so.
                tint = if (sort == FolderSort()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GroupLabel(stringResource(R.string.category_folders))
            SubfolderOrder.entries.forEach { order ->
                val selected = order == sort.folders
                SortItem(
                    label = stringResource(order.label),
                    selected = selected,
                    ascending = sort.foldersAscending,
                    onClick = {
                        onChange(
                            if (selected) sort.copy(foldersAscending = !sort.foldersAscending)
                            else sort.copy(folders = order, foldersAscending = order.firstAscending)
                        )
                        open = false
                    },
                )
            }
            if (showSongs) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                GroupLabel(stringResource(R.string.category_songs))
                FolderTrackOrder.entries.forEach { order ->
                    val selected = order == sort.tracks
                    SortItem(
                        label = stringResource(order.label),
                        selected = selected,
                        ascending = sort.tracksAscending,
                        onClick = {
                            onChange(
                                if (selected) sort.copy(tracksAscending = !sort.tracksAscending)
                                else sort.copy(tracks = order, tracksAscending = order.firstAscending)
                            )
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SortItem(label: String, selected: Boolean, ascending: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (selected) {
                Icon(
                    if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = stringResource(if (ascending) R.string.sort_ascending else R.string.sort_descending),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}
