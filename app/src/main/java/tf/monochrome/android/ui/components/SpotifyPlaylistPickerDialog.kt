package tf.monochrome.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tf.monochrome.android.data.api.SpotifySimplePlaylist

/**
 * Picker for the connected Spotify account's playlists. "Liked Songs" is
 * pinned at the top; tapping a row starts the import immediately.
 */
@Composable
fun SpotifyPlaylistPickerDialog(
    playlists: List<SpotifySimplePlaylist>,
    isLoading: Boolean,
    error: String?,
    onPick: (playlistId: String, name: String, strictAlbumMatch: Boolean) -> Unit,
    onPickLikedSongs: (strictAlbumMatch: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var strictAlbumMatch by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(vertical = 24.dp)
                .liquidGlass(shape = RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Import from Spotify",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Strict Album Matching", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Album name must match Spotify metadata",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = strictAlbumMatch, onCheckedChange = { strictAlbumMatch = it })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                    error != null -> {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            item {
                                PickerRow(
                                    title = "Liked Songs",
                                    subtitle = "Your saved Spotify tracks",
                                    isLikedSongs = true,
                                    onClick = { onPickLikedSongs(strictAlbumMatch) },
                                )
                            }
                            items(playlists, key = { it.id }) { playlist ->
                                PickerRow(
                                    title = playlist.name,
                                    subtitle = buildString {
                                        playlist.tracks?.let { append("${it.total} tracks") }
                                        playlist.owner?.displayName?.let {
                                            if (isNotEmpty()) append(" • ")
                                            append(it)
                                        }
                                    },
                                    isLikedSongs = false,
                                    onClick = { onPick(playlist.id, playlist.name, strictAlbumMatch) },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    isLikedSongs: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isLikedSongs) Icons.Default.Favorite else Icons.Default.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
