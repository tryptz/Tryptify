package tf.monochrome.android.ui.library

import tf.monochrome.android.ui.navigation.openTrackArtist
import tf.monochrome.android.ui.navigation.trackArtistAction
import tf.monochrome.android.ui.navigation.trackAlbumAction
import tf.monochrome.android.ui.navigation.popBackStackSafe
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.data.playlistfix.PlaylistFixState
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackListToolbar
import tf.monochrome.android.ui.components.TrackSort
import tf.monochrome.android.ui.components.TrackSortSaver
import tf.monochrome.android.ui.components.applySearchAndSort
import tf.monochrome.android.ui.components.TrackItem
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.SearchAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: PlaylistViewModel = hiltViewModel()
) {
    val playlistInfo by viewModel.playlistInfo.collectAsStateWithLifecycle()
    // A TIDAL playlist opened from search: read-only, and fetched rather than local.
    val catalogPlaylist by viewModel.catalogPlaylist.collectAsStateWithLifecycle()
    val catalogLoading by viewModel.catalogLoading.collectAsStateWithLifecycle()
    val catalogFailed by viewModel.catalogFailed.collectAsStateWithLifecycle()
    // Renaming, deleting, visibility and removing tracks are for the
    // listener's own playlists only.
    val isOwn = playlistInfo != null
    val title = playlistInfo?.name ?: catalogPlaylist?.title
    val description = playlistInfo?.description ?: catalogPlaylist?.description
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val playlists by playerViewModel.playlists.collectAsStateWithLifecycle()
    val activeDownloads by playerViewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadedTrackIds by playerViewModel.downloadedTrackIds.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistForSelection by remember { mutableStateOf(false) }

    // Repair and regenerate: the job lives in PlaylistFixer and outlives this screen.
    val fixState by viewModel.fixState.collectAsStateWithLifecycle()
    val fixBusyElsewhere by viewModel.fixBusyElsewhere.collectAsStateWithLifecycle()
    val fixServices by viewModel.fixServices.collectAsStateWithLifecycle()
    var showRepairDialog by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }
    var unmatchedSongs by remember { mutableStateOf<List<String>?>(null) }

    // Search text and order live here, not in the ViewModel: they describe how
    // this screen is being looked at right now, not anything about the playlist.
    var listQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var listSort by rememberSaveable(stateSaver = TrackSortSaver) {
        mutableStateOf(TrackSort())
    }
    val visibleTracks = remember(tracks, listQuery, listSort) {
        tracks.applySearchAndSort(listQuery, listSort)
    }
    // A TIDAL playlist can hold one track twice, and two rows with one key
    // crash the list, so the key is the id and which occurrence it is.
    val rowKeys = remember(visibleTracks) { LibraryKeys.occurrences(visibleTracks.map { it.id }) }
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
            onRemoveFromPlaylist = if (isOwn) ({ viewModel.removeTrack(track.id) }) else null,
            onDownloadTrack = if (playerViewModel.isLocalTrack(track)) null
            else ({ playerViewModel.downloadTrack(track) }),
            onShareFile = { playerViewModel.shareTrack(track) },
            onGoToAlbum = navController.trackAlbumAction(track, playerViewModel.unifiedFor(track)),
            onGoToArtist = navController.trackArtistAction(track, playerViewModel.unifiedFor(track))
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
            title = pluralStringResource(R.plurals.add_n_tracks_to_playlist, selection.count, selection.count),
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
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = { Text("\"${playlistInfo?.name.orEmpty()}\" will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deletePlaylist()
                    navController.popBackStackSafe()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showRepairDialog) {
        RepairPlaylistDialog(
            services = fixServices,
            onDismiss = { showRepairDialog = false },
            onConfirm = {
                showRepairDialog = false
                viewModel.repairPlaylist()
            },
        )
    }

    if (showRegenerateDialog) {
        RegeneratePlaylistDialog(
            services = fixServices,
            onDismiss = { showRegenerateDialog = false },
            onConfirm = { primary, fallback ->
                showRegenerateDialog = false
                viewModel.regeneratePlaylist(primary, fallback)
            },
        )
    }

    unmatchedSongs?.let { songs ->
        UnmatchedSongsDialog(songs = songs, onDismiss = { unmatchedSongs = null })
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
            title = stringResource(R.string.edit_playlist),
            confirmLabel = stringResource(R.string.action_save),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = title ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                SearchAction(open = searchOpen, onToggle = {
                    searchOpen = !searchOpen
                    // Closing clears the filter. A hidden query in place is how
                    // a list ends up looking like it has lost rows.
                    if (!searchOpen) listQuery = ""
                })

                if (isOwn) IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (playlistInfo?.isPublic == true) stringResource(R.string.make_private) else stringResource(R.string.make_public)) },
                        leadingIcon = { Icon(if (playlistInfo?.isPublic == true) Icons.Default.Lock else Icons.Default.Public, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            viewModel.togglePublic()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_playlist)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showEditDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_playlist)) },
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
                onDelete = if (isOwn) ({
                    viewModel.removeTracks(selection.selectedIds)
                    selection.clear()
                }) else null,
                deleteContentDescription = stringResource(R.string.action_remove_from_playlist)
            )
        }

        SearchOverlay(
            open = searchOpen,
            query = listQuery,
            onQueryChange = { listQuery = it },
            placeholder = stringResource(R.string.search_this_playlist),
            onClose = { searchOpen = false; listQuery = "" },
        ) { searchTopInset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                        top = searchTopInset,
                        bottom = 80.dp + LocalBottomChromeInset.current,
                    )
        ) {
            item {
                tf.monochrome.android.devedit.DevEditable("playlist_hero", Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                ) {
                    Text(
                        text = title ?: stringResource(R.string.loading),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!description.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pluralStringResource(R.plurals.track_count, tracks.size, tracks.size),
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
                            Text(stringResource(R.string.action_play), style = MaterialTheme.typography.titleMedium)
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
                            Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.visualizer_shuffle))
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
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download_all))
                        }
                    }
                    }

                    // Only the listener's own playlists can be rewritten.
                    if (isOwn) {
                        Spacer(modifier = Modifier.height(12.dp))
                        tf.monochrome.android.devedit.DevEditable("playlist_fix_row", Modifier.fillMaxWidth()) {
                            PlaylistFixActions(
                                enabled = tracks.isNotEmpty() &&
                                    fixState !is PlaylistFixState.Running && !fixBusyElsewhere,
                                onRepair = {
                                    viewModel.refreshFixServices()
                                    showRepairDialog = true
                                },
                                onRegenerate = {
                                    viewModel.refreshFixServices()
                                    showRegenerateDialog = true
                                },
                            )
                        }
                        PlaylistFixStatus(
                            state = fixState,
                            busyElsewhere = fixBusyElsewhere,
                            onStop = { viewModel.stopFix() },
                            onDismiss = { viewModel.dismissFixResult() },
                            onShowUnmatched = { unmatchedSongs = it },
                        )
                    }
                }
                }
            }

            if (tracks.isNotEmpty()) {
                stickyHeader {
                    TrackListToolbar(
                        sort = listSort,
                        onSortChange = { listSort = it },
                    )
                }
            }

            if (tracks.isEmpty() && catalogLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            } else if (tracks.isEmpty() && catalogFailed) {
                item {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.error_load_playlist),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { viewModel.loadCatalogPlaylist() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            } else if (tracks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.playlist_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else if (visibleTracks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_tracks_match, listQuery),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                itemsIndexed(visibleTracks, key = { index, _ -> rowKeys[index] }) { _, track ->
                    TrackItem(
                        track = track,
                        onClick = {
                            if (selection.active) selection.toggle(track.id)
                            else playerViewModel.playTrack(track, visibleTracks)
                        },
                        onLongClick = { selection.toggle(track.id) },
                        onMoreClick = { showContextMenuForTrack = track },
                        onArtistClick = { artistId -> navController.openTrackArtist(track, playerViewModel.unifiedFor(track), artistId) },
                        onAlbumClick = navController.trackAlbumAction(track, playerViewModel.unifiedFor(track)),
                        downloadState = activeDownloads[track.id],
                        isDownloaded = track.id in downloadedTrackIds,
                        selectionMode = selection.active,
                        selected = track.id in selection.selectedIds
                    )
                }
            }
        }
        }
    }
}
