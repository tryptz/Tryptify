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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.SearchAction
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.TrackListToolbar
import tf.monochrome.android.ui.components.TrackSort
import tf.monochrome.android.ui.components.TrackSortSaver
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.player.PlayerViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.android.R

@Composable
fun DownloadsScreen(
    navController: NavController,
    viewModel: DownloadsViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
    /**
     * Whether the search bar is open, for a host whose app bar carries the
     * search button — the Library. Null where there is no such bar (the
     * Download centre route): the screen then keeps it itself and puts the
     * button in its own toolbar.
     */
    searchOpen: Boolean? = null,
    onSearchOpenChange: (Boolean) -> Unit = {},
) {
    // Null until the list has loaded (see DownloadsViewModel.downloadedTracks).
    val loadedTracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val downloadedTracks = loadedTracks.orEmpty()
    val albumGroups by viewModel.albumGroups.collectAsStateWithLifecycle()

    // How the list is being looked at, not what is in it: screen state, as on
    // Liked Songs, and saveable so a rotation keeps the order and the query.
    var ownSearchOpen by rememberSaveable { mutableStateOf(false) }
    val isSearchOpen = searchOpen ?: ownSearchOpen
    val setSearchOpen: (Boolean) -> Unit = { open ->
        if (searchOpen == null) ownSearchOpen = open else onSearchOpenChange(open)
    }
    var query by rememberSaveable { mutableStateOf("") }
    // Closed from either button, the query goes with it — a hidden filter
    // still thinning the list is a list that looks like it lost tracks.
    LaunchedEffect(isSearchOpen) { if (!isSearchOpen) query = "" }
    var sort by rememberSaveable(stateSaver = TrackSortSaver) { mutableStateOf(TrackSort()) }
    val visibleTracks = remember(downloadedTracks, query, sort) {
        downloadedTracks.applyDownloadSearchAndSort(query, sort)
    }
    // Searching narrows the album cards too: an album stays while any of its
    // tracks matches, so "Satomi" keeps DJ Satomi's albums above the rows.
    val visibleAlbumGroups = remember(albumGroups, visibleTracks, query) {
        if (query.isBlank()) albumGroups
        else {
            val ids = visibleTracks.mapTo(HashSet()) { it.id }
            albumGroups.filter { group -> group.tracks.any { it.id in ids } }
        }
    }
    val playlists by playerViewModel.playlists.collectAsStateWithLifecycle()

    // Surface delete failures (e.g. a sideloaded file the provider won't remove)
    // instead of silently leaving the row behind.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(context, msg.resolve(context), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val selection = rememberTrackSelectionState<Long>()
    BackHandler(enabled = selection.active) { selection.clear() }

    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }
    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
    )
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
            title = { Text(pluralStringResource(R.plurals.delete_downloads_title, count, count)) },
            text = { Text(pluralStringResource(R.plurals.delete_downloads_body, count)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownloads(
                        downloadedTracks.filter { it.id in selection.selectedIds }
                    )
                    showDeleteConfirm = false
                    selection.clear()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
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
            title = pluralStringResource(R.plurals.add_n_tracks_to_playlist, selection.count, selection.count),
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
        // Still loading: an empty screen for the moment it takes, rather than
        // "No downloaded tracks found." over a list that is about to appear.
        if (loadedTracks == null) return
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.downloads_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    // Materialize unified-track lists once per recomposition. The visible list
    // drives 'tap a track to play within downloads' — what is on screen, in
    // the order it is on screen, so a search or a sort is also the queue. The
    // per-album lists drive 'tap an album card to play that album in order'.
    val visibleUnified = visibleTracks.map { it.toUnifiedTrack() }

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
            deleteContentDescription = stringResource(R.string.delete_downloads)
        )
    }

    // Pinned above the list, as on Liked Songs, so the sort is in reach from
    // anywhere down a long list rather than only from its top.
    TrackListToolbar(
        sort = sort,
        onSortChange = { sort = it },
        orders = DOWNLOAD_TRACK_ORDERS,
        trailing = if (searchOpen == null) {
            { SearchAction(open = isSearchOpen, onToggle = { setSearchOpen(!isSearchOpen) }) }
        } else {
            null
        },
    )

    SearchOverlay(
        open = isSearchOpen,
        query = query,
        onQueryChange = { query = it },
        placeholder = stringResource(R.string.search_downloads),
        onClose = { setSearchOpen(false) },
    ) { searchTopInset ->
    LazyColumn(
        contentPadding = PaddingValues(
            top = searchTopInset,
            bottom = tf.monochrome.android.ui.navigation.bottomChromePadding,
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        if (visibleAlbumGroups.isNotEmpty()) {
            item {
                tf.monochrome.android.devedit.DevEditable("downloads_albums_header", Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.filter_albums),
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
                    items(visibleAlbumGroups, key = { it.title + it.artistName }) { group ->
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
                    text = stringResource(R.string.filter_tracks),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                }
            }
        }

        if (visibleTracks.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_tracks_match, query.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }

        items(visibleTracks, key = { it.id }) { track ->
            DownloadedTrackRow(
                track = track,
                onClick = {
                    if (selection.active) {
                        selection.toggle(track.id)
                    } else {
                        val tappedUnified = track.toUnifiedTrack()
                        playerViewModel.playUnifiedTrack(tappedUnified, visibleUnified)
                    }
                },
                onLongClick = { selection.toggle(track.id) },
                onMoreClick = { menuTrack = track.toUnifiedTrack() },
                selectionMode = selection.active,
                selected = track.id in selection.selectedIds,
            )
        }
    }
    }
    }
}

@Composable
private fun AlbumCard(
    group: DownloadedAlbumGroup,
    onClick: () -> Unit,
) {
    // The singles bucket is a grouping key; only its name is translated.
    val groupTitle = if (group.title == DownloadsViewModel.SINGLES_LABEL) {
        stringResource(R.string.singles)
    } else {
        group.title
    }
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        CoverImage(
            url = group.cover,
            contentDescription = groupTitle,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = groupTitle,
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
    onMoreClick: () -> Unit,
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
                contentDescription = if (selected) stringResource(R.string.state_selected) else stringResource(R.string.state_not_selected),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // TIDAL's Atmos mix is what was downloaded.
                if (track.isDolbyAtmos) {
                    Spacer(modifier = Modifier.width(6.dp))
                    tf.monochrome.android.ui.components.DolbyAtmosBadgePill()
                }
            }
            val sizeText = android.text.format.Formatter.formatShortFileSize(LocalContext.current, track.sizeBytes)
            Text(
                text = "${track.artistName} • $sizeText",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Deletion is intentionally not a per-row button anymore — long-press
        // to select, then delete from the selection bar. Share used to be the
        // row's only action; it now lives in the ⋮ sheet alongside play next,
        // add to playlist and go to album, which this screen had none of.
        if (!selectionMode) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
