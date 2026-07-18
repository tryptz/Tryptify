package tf.monochrome.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackItem
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: PlaylistViewModel = hiltViewModel()
) {
    val playlistInfo by viewModel.playlistInfo.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsState()
    val playlists by playerViewModel.playlists.collectAsState()
    val activeDownloads by playerViewModel.activeDownloads.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistForSelection by remember { mutableStateOf(false) }

    val selection = rememberTrackSelectionState<Long>()
    BackHandler(enabled = selection.active) { selection.clear() }

    showContextMenuForTrack?.let { track ->
        TrackContextMenu(
            track = track,
            isLiked = favoriteTrackIds.contains(track.id),
            onDismiss = { showContextMenuForTrack = null },
            onPlayNext = { playerViewModel.playNext(track) },
            onAddToQueue = { playerViewModel.addToQueue(listOf(track)) },
            onToggleLike = { playerViewModel.toggleFavorite(track) },
            onAddToPlaylist = { showAddToPlaylistForTrack = track },
            onRemoveFromPlaylist = { viewModel.removeTrack(track.id) },
            onDownloadTrack = if (playerViewModel.isLocalTrack(track)) null
            else ({ playerViewModel.downloadTrack(track) }),
            onShareFile = { playerViewModel.shareTrack(track) },
            onGoToAlbum = track.album?.id?.let { albumId ->
                { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
            },
            onGoToArtist = track.artist?.id?.let { artistId ->
                { navController.navigate(Screen.ArtistDetail.createRoute(artistId)) }
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

    showAddToPlaylistForTrack?.let { track ->
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { showAddToPlaylistForTrack = null },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTrackToPlaylist(playlist.id, track)
                showAddToPlaylistForTrack = null
            },
            onCreateNew = {
                showAddToPlaylistForTrack = null
                showCreatePlaylistDialog = true
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
                    tracks.filter { it.id in selection.selectedIds },
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${playlistInfo?.name.orEmpty()}\" will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deletePlaylist()
                    navController.popBackStack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    val editInfo = playlistInfo
    if (showEditDialog && editInfo != null) {
        CreatePlaylistDialog(
            onDismiss = { showEditDialog = false },
            onSubmit = { name, desc ->
                viewModel.updatePlaylist(name, desc)
                showEditDialog = false
            },
            initialName = editInfo.name,
            initialDescription = editInfo.description.orEmpty(),
            title = "Edit Playlist",
            confirmLabel = "Save",
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = playlistInfo?.name ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (playlistInfo?.isPublic == true) "Make Private" else "Make Public") },
                        leadingIcon = { Icon(if (playlistInfo?.isPublic == true) Icons.Default.Lock else Icons.Default.Public, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            viewModel.togglePublic()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit Playlist") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showEditDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Playlist") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        }
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        AnimatedVisibility(visible = selection.active) {
            TrackSelectionBar(
                selectedCount = selection.count,
                onClose = { selection.clear() },
                onAddToQueue = {
                    playerViewModel.addToQueue(tracks.filter { it.id in selection.selectedIds })
                    selection.clear()
                },
                onAddToPlaylist = { showAddToPlaylistForSelection = true },
                onDelete = {
                    viewModel.removeTracks(selection.selectedIds)
                    selection.clear()
                },
                deleteContentDescription = "Remove from playlist"
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                tf.monochrome.android.devedit.DevEditable("playlist_hero", Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                ) {
                    Text(
                        text = playlistInfo?.name ?: "Loading...",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!playlistInfo?.description.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playlistInfo?.description ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${tracks.size} tracks",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    tf.monochrome.android.devedit.DevEditable("playlist_action_row", Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { playerViewModel.playAll(tracks) },
                            modifier = Modifier.weight(1f),
                            enabled = tracks.isNotEmpty(),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play", style = MaterialTheme.typography.titleMedium)
                        }

                        FilledIconButton(
                            onClick = { playerViewModel.shufflePlay(tracks) },
                            enabled = tracks.isNotEmpty(),
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                        }

                        FilledIconButton(
                            onClick = { playerViewModel.downloadAllTracks(tracks) },
                            enabled = tracks.isNotEmpty(),
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download All")
                        }
                    }
                    }
                }
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Text(
                        text = "This playlist is empty.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                items(tracks, key = { it.id }) { track ->
                    TrackItem(
                        track = track,
                        isLiked = favoriteTrackIds.contains(track.id),
                        onLikeClick = { playerViewModel.toggleFavorite(track) },
                        onClick = {
                            if (selection.active) selection.toggle(track.id)
                            else playerViewModel.playTrack(track, tracks)
                        },
                        onLongClick = { selection.toggle(track.id) },
                        onMoreClick = { showContextMenuForTrack = track },
                        onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                        onAlbumClick = track.album?.id?.let { albumId ->
                            { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                        },
                        downloadState = activeDownloads[track.id],
                        selectionMode = selection.active,
                        selected = track.id in selection.selectedIds
                    )
                }
            }
        }
    }
}
