package tf.monochrome.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.player.PlayerViewModel

@Composable
fun DownloadsScreen(
    navController: NavController,
    viewModel: DownloadsViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val albumGroups by viewModel.albumGroups.collectAsState()
    val playlists by playerViewModel.playlists.collectAsState()

    // Surface delete failures (e.g. a sideloaded file the provider won't remove)
    // instead of silently leaving the row behind.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val selection = rememberTrackSelectionState<Long>()
    BackHandler(enabled = selection.active) { selection.clear() }
    var showAddToPlaylistForSelection by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Deleting removes the actual audio files from disk with no undo, and the
    // trash icon sits right next to add-to-playlist in the selection bar — a
    // mis-tap must not silently destroy the user's downloads.
    if (showDeleteConfirm) {
        val count = selection.count
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete $count download${if (count == 1) "" else "s"}?") },
            text = { Text("The audio file${if (count == 1) "" else "s"} will be permanently removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownloads(
                        downloadedTracks.filter { it.id in selection.selectedIds }
                    )
                    showDeleteConfirm = false
                    selection.clear()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onSubmit = { name, description ->
                playerViewModel.createPlaylist(name, description)
                showCreatePlaylistDialog = false
            }
        )
    }

    if (showAddToPlaylistForSelection) {
        AddToPlaylistSheet(
            title = "Add ${selection.count} tracks to playlist",
            playlists = playlists,
            onDismiss = { showAddToPlaylistForSelection = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTracksToPlaylist(
                    playlist.id,
                    downloadedTracks.filter { it.id in selection.selectedIds }
                        .map { it.toUnifiedTrack().toLegacyTrack() },
                )
                showAddToPlaylistForSelection = false
                selection.clear()
            },
            onCreateNew = {
                showAddToPlaylistForSelection = false
                showCreatePlaylistDialog = true
            }
        )
    }

    if (downloadedTracks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No downloaded tracks found.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    // Materialize unified-track lists once per recomposition. The full list
    // drives 'tap a track to play within the entire downloads queue', the
    // per-album lists drive 'tap an album card to play that album in order'.
    val allUnified = downloadedTracks.map { it.toUnifiedTrack() }

    Column(modifier = Modifier.fillMaxSize()) {
    AnimatedVisibility(visible = selection.active) {
        TrackSelectionBar(
            selectedCount = selection.count,
            onClose = { selection.clear() },
            onAddToQueue = {
                playerViewModel.addUnifiedToQueue(
                    downloadedTracks.filter { it.id in selection.selectedIds }
                        .map { it.toUnifiedTrack() },
                )
                selection.clear()
            },
            onAddToPlaylist = { showAddToPlaylistForSelection = true },
            onDelete = { showDeleteConfirm = true },
            deleteContentDescription = "Delete downloads"
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 120.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (albumGroups.isNotEmpty()) {
            item {
                tf.monochrome.android.devedit.DevEditable("downloads_albums_header", Modifier.fillMaxWidth()) {
                Text(
                    text = "Albums",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albumGroups, key = { it.title + it.artistName }) { group ->
                        AlbumCard(
                            group = group,
                            onClick = {
                                val groupUnified = group.tracks.map { it.toUnifiedTrack() }
                                groupUnified.firstOrNull()?.let { first ->
                                    playerViewModel.playUnifiedTrack(first, groupUnified)
                                }
                            },
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                tf.monochrome.android.devedit.DevEditable("downloads_tracks_header", Modifier.fillMaxWidth()) {
                Text(
                    text = "Tracks",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                }
            }
        }

        items(downloadedTracks, key = { it.id }) { track ->
            DownloadedTrackRow(
                track = track,
                onClick = {
                    if (selection.active) {
                        selection.toggle(track.id)
                    } else {
                        val tappedUnified = track.toUnifiedTrack()
                        playerViewModel.playUnifiedTrack(tappedUnified, allUnified)
                    }
                },
                onLongClick = { selection.toggle(track.id) },
                onShare = { playerViewModel.shareDownloadedTrack(track) },
                selectionMode = selection.active,
                selected = track.id in selection.selectedIds,
            )
        }
    }
    }
}

@Composable
private fun AlbumCard(
    group: DownloadedAlbumGroup,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        CoverImage(
            url = group.cover,
            contentDescription = group.title,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${group.artistName} • ${group.trackCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedTrackRow(
    track: DownloadedTrackEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        CoverImage(
            url = track.albumCover,
            contentDescription = track.title,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val mbSize = track.sizeBytes / (1024 * 1024)
            Text(
                text = "${track.artistName} • $mbSize MB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Deletion is intentionally not a per-row button anymore — long-press
        // to select, then delete from the selection bar.
        if (!selectionMode) {
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share Download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
