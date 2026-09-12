package tf.monochrome.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.FastScroller
import tf.monochrome.android.ui.components.bounceCombinedClick
import tf.monochrome.android.ui.components.TrackArtistAlbumLine
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.TrackListToolbar
import tf.monochrome.android.ui.components.TrackSort
import tf.monochrome.android.ui.components.TrackSortSaver
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.applyUnifiedSearchAndSort
import tf.monochrome.android.ui.navigation.openAlbum
import tf.monochrome.android.ui.navigation.openArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.LocalMiniPlayerInset
import tf.monochrome.android.ui.navigation.LocalNowPlayingTrackId
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.SearchAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    folderPath: String,
    navController: NavController,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
    onPlayTrack: (UnifiedTrack, List<UnifiedTrack>) -> Unit,
    onPlayAll: (List<UnifiedTrack>) -> Unit,
    onAddToQueue: (UnifiedTrack) -> Unit,
    playerViewModel: PlayerViewModel
) {
    val subfolders by viewModel.getSubfolders(folderPath).collectAsStateWithLifecycle()
    val tracks by viewModel.getTracksInFolder(folderPath).collectAsStateWithLifecycle()

    val displayName = folderPath.substringAfterLast('/')

    var listQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var listSort by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = TrackSortSaver) {
        mutableStateOf(TrackSort())
    }
    val visibleTracks = remember(tracks, listQuery, listSort) {
        tracks.applyUnifiedSearchAndSort(listQuery, listSort)
    }

    var folderToExclude by remember { mutableStateOf<FolderToExclude?>(null) }
    folderToExclude?.let { folder ->
        ExcludeFolderDialog(
            folder = folder,
            onDismiss = { folderToExclude = null },
            onConfirm = {
                viewModel.excludeFolder(folder.path)
                // The folder just left the library; staying on a page that is
                // now guaranteed empty is not useful.
                if (folder.path == folderPath) navController.popBackStack()
            },
        )
    }

    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }
    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                tf.monochrome.android.devedit.DevEditable("folder_path_bar") {
                Column {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        folderPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                }
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                SearchAction(open = searchOpen, onToggle = {
                    searchOpen = !searchOpen
                    // Closing clears the filter. A hidden query in place is how
                    // a list ends up looking like it has lost rows.
                    if (!searchOpen) listQuery = ""
                })

                if (visibleTracks.isNotEmpty()) {
                    IconButton(onClick = { onPlayAll(visibleTracks) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play All")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        SearchOverlay(
            open = searchOpen,
            query = listQuery,
            onQueryChange = { listQuery = it },
            placeholder = "Search this folder",
            onClose = { searchOpen = false; listQuery = "" },
        ) { searchTopInset ->
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                        top = searchTopInset,
                        bottom = 80.dp + LocalMiniPlayerInset.current,
                    )
        ) {
            // Subfolders first, then the folder's own audio files. Both kinds of
            // row live in one list, so each declares its contentType — without it
            // Compose would try to reuse a folder row's slots for a track row.
            items(subfolders, key = { it.path }, contentType = { "folder" }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceCombinedClick(
                            onLongClick = {
                                folderToExclude = FolderToExclude(
                                    path = folder.path,
                                    displayName = folder.displayName,
                                    trackCount = folder.trackCount,
                                )
                            },
                            onClick = {
                                navController.navigateSafe(
                                    "folder/" + java.net.URLEncoder.encode(folder.path, "UTF-8")
                                )
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            folder.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${folder.trackCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Tracks in this folder
            if (tracks.isNotEmpty()) {
                stickyHeader {
                    TrackListToolbar(
                        sort = listSort,
                        onSortChange = { listSort = it },
                    )
                }
            }
            items(visibleTracks, key = { it.id }, contentType = { "track" }) { track ->
                // legacyId, not id: the queue holds legacy Tracks, so that is
                // what LocalNowPlayingTrackId carries.
                val nowPlaying = track.legacyId == LocalNowPlayingTrackId.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MonoDimens.listRowHeight)
                        .background(
                            if (nowPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else Color.Transparent
                        )
                        .clickable { onPlayTrack(track, visibleTracks) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (track.artworkUri != null) {
                        AsyncImage(
                            model = track.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (nowPlaying) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            TrackArtistAlbumLine(
                                track = track,
                                onArtistClick = { ref -> ref.id?.let { navController.openArtist(track.sourceType, it) } },
                                onAlbumClick = { navController.openAlbum(track.albumId) },
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            track.qualityBadge?.let { badge ->
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    Text(
                        track.formattedDuration,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { onAddToQueue(track) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Add to queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { menuTrack = track },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        // A folder with nothing in it used to render two empty lists and say
        // nothing at all, which is how the broken folder tree went unnoticed:
        // a blank screen looks the same whether the folder is empty or the
        // browser lost track of its contents.
        if (subfolders.isEmpty() && tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No music here",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Nothing under this folder has been added to your library. " +
                        "If you have put music here since the last scan, scan again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { viewModel.startFullScan() }) { Text("Scan again") }
            }
        }
        FastScroller(state = listState)
        }
        }
    }
}
