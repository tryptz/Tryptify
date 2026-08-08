package tf.monochrome.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackItem
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel

/**
 * The section Library lands on — page 0, and deliberately absent from the
 * overflow menu, since it's reached by swiping back rather than being picked.
 */
internal const val LOCAL_SECTION = "local"

/** Display names for every Library section id. */
internal val LIBRARY_SECTION_NAMES = mapOf(
    "overview" to "Overview",
    "local" to "Local",
    "playlists" to "Playlists",
    "favorites" to "Favorites",
    "downloads" to "Downloads",
)

/**
 * Library's pages in order: the local library first, then whatever the user has
 * left in their configured section order.
 *
 * Shared with the nav host, which owns the `PagerState` for these pages so the
 * top-bar indicator can count and track every one of them — Home plus each
 * Library section — rather than just Home vs Library.
 */
internal fun librarySections(order: List<String>): List<String> =
    listOf(LOCAL_SECTION) + order.filter { it != LOCAL_SECTION && it in LIBRARY_SECTION_NAMES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    // Hoisted to the nav host: it owns these so the top-bar page indicator can
    // track Library's sections, not just the Home↔Library split.
    sections: List<String>,
    sectionPager: PagerState,
    viewModel: LibraryViewModel = hiltViewModel(),
    localLibraryViewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val favoriteAlbums by viewModel.favoriteAlbums.collectAsState()
    val favoriteArtists by viewModel.favoriteArtists.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsState()
    val activeDownloads by playerViewModel.activeDownloads.collectAsState()
    val downloadedTrackIds by playerViewModel.downloadedTrackIds.collectAsState()

    val sectionScope = rememberCoroutineScope()
    val currentSectionId = sections.getOrElse(sectionPager.currentPage) { LOCAL_SECTION }

    // The overflow menu survives as a shortcut past the swipe — Downloads sits
    // several pages deep — not as the only way in.
    val menuSections = sections.drop(1).mapNotNull { id ->
        LIBRARY_SECTION_NAMES[id]?.let { id to it }
    }

    // Saveable so the dialog reopens after a process death triggered by its own
    // SAF CSV picker; the dialog's typed fields + picked uri are saveable too.
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForSelection by remember { mutableStateOf(false) }

    val selection = rememberTrackSelectionState<Long>()
    // Back out of a menu section returns to the local library rather than
    // leaving Library entirely. Composed BEFORE the selection handler on
    // purpose: the dispatcher serves the LAST-composed enabled callback first,
    // so an active selection still wins the first back press.
    BackHandler(enabled = sectionPager.currentPage != 0) {
        sectionScope.launch { sectionPager.animateScrollToPage(0) }
    }
    BackHandler(enabled = selection.active) { selection.clear() }
    // Lists (and delete semantics) differ per section — drop any selection on switch.
    LaunchedEffect(currentSectionId) { selection.clear() }

    showContextMenuForTrack?.let { track ->
        TrackContextMenu(
            track = track,
            isLiked = favoriteTrackIds.contains(track.id),
            onDismiss = { showContextMenuForTrack = null },
            onPlayNext = { playerViewModel.playNext(track) },
            onAddToQueue = { playerViewModel.addToQueue(listOf(track)) },
            onToggleLike = { playerViewModel.toggleFavorite(track) },
            onAddToPlaylist = { showAddToPlaylistForTrack = track },
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
                viewModel.createPlaylist(name, description)
                showCreatePlaylistDialog = false
            },
            onImportCsv = { uri, strict, name, description ->
                viewModel.importCsvPlaylist(uri, strict, name, description)
                showCreatePlaylistDialog = false
            }
        )
    }

    // Surface CSV import outcome — previously importProgress was never
    // collected, so a malformed CSV produced no error and no playlist.
    val importProgressState = viewModel.importProgress.collectAsState()
    val importMsgContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(importProgressState.value) {
        when (val p = importProgressState.value) {
            is tf.monochrome.android.data.import_.ImportProgress.Done -> {
                android.widget.Toast.makeText(
                    importMsgContext,
                    "Imported ${p.matched}/${p.total} tracks into \"${p.playlistName}\"",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                viewModel.resetImportProgress()
            }
            is tf.monochrome.android.data.import_.ImportProgress.Failed -> {
                android.widget.Toast.makeText(importMsgContext, "Import failed: ${p.message}", android.widget.Toast.LENGTH_LONG).show()
                viewModel.resetImportProgress()
            }
            else -> {}
        }
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

    // Tracks that bulk-selection actions operate on; ids are unique across the
    // overview's two sections, so a combined distinct list resolves either.
    val selectableTracks = (recentTracks + favoriteTracks).distinctBy { it.id }

    if (showAddToPlaylistForSelection) {
        AddToPlaylistSheet(
            title = "Add ${selection.count} tracks to playlist",
            playlists = playlists,
            onDismiss = { showAddToPlaylistForSelection = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTracksToPlaylist(
                    playlist.id,
                    selectableTracks.filter { it.id in selection.selectedIds },
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

    Column(modifier = Modifier.fillMaxSize()) {
        var sectionMenuOpen by remember { mutableStateOf(false) }

        tf.monochrome.android.devedit.DevEditable("library_header", Modifier.fillMaxWidth()) {
            TopAppBar(
                title = {
                    Text(
                        // Titled for whatever is actually on screen: "Library"
                        // is the local library, anything else names itself so
                        // the menu section you picked is identifiable.
                        text = if (currentSectionId == LOCAL_SECTION) "Library"
                               else LIBRARY_SECTION_NAMES[currentSectionId] ?: "Library",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                navigationIcon = {
                    // Only while a menu section is showing — the local library
                    // has no entry in the menu, so this is the way back to it.
                    if (currentSectionId != LOCAL_SECTION) {
                        IconButton(onClick = {
                            sectionScope.launch { sectionPager.animateScrollToPage(0) }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to local library",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                actions = {
                    // Settings lets the user strip sections out of the order,
                    // so the button goes away rather than opening an empty menu.
                    if (menuSections.isNotEmpty()) Box {
                        IconButton(onClick = { sectionMenuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Other library sections",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        DropdownMenu(
                            expanded = sectionMenuOpen,
                            onDismissRequest = { sectionMenuOpen = false }
                        ) {
                            menuSections.forEach { (id, title) ->
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    onClick = {
                                        val page = sections.indexOf(id)
                                        if (page >= 0) {
                                            sectionScope.launch {
                                                sectionPager.animateScrollToPage(page)
                                            }
                                        }
                                        sectionMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { navController.navigate(Screen.Settings.createRoute()) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }

        AnimatedVisibility(visible = selection.active) {
            TrackSelectionBar(
                selectedCount = selection.count,
                onClose = { selection.clear() },
                onAddToQueue = {
                    playerViewModel.addToQueue(selectableTracks.filter { it.id in selection.selectedIds })
                    selection.clear()
                },
                onAddToPlaylist = { showAddToPlaylistForSelection = true },
                onDelete = if (currentSectionId == "favorites") {
                    {
                        playerViewModel.unlikeTracks(selection.selectedIds)
                        selection.clear()
                    }
                } else null,
                deleteContentDescription = "Unlike"
            )
        }

        val sectionStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        // Preserve each section's scroll position (and the local library's own
        // sub-tab state) across section switches, instead of remounting a fresh
        // list that snaps back to the top every time.
        //
        // Nested inside the nav host's Home↔Library pager, same axis. Compose
        // resolves that through nested scroll: this inner pager consumes the
        // drag until it's at page 0 and the user keeps pulling right, at which
        // point the outer pager takes over and carries them to Home.
        HorizontalPager(
            state = sectionPager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
        ) { page ->
        val sectionId = sections[page]
        sectionStateHolder.SaveableStateProvider(sectionId) {
        when (sectionId) {
            "overview" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (recentTracks.isNotEmpty()) {
                        item { SectionHeader(title = "Recently Played") }
                        items(recentTracks.take(5)) { track ->
                            TrackItem(
                                track = track,
                                isLiked = favoriteTrackIds.contains(track.id),
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = {
                                    if (selection.active) selection.toggle(track.id)
                                    else playerViewModel.playTrack(track, recentTracks)
                                },
                                onLongClick = { selection.toggle(track.id) },
                                onMoreClick = { showContextMenuForTrack = track },
                                onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                },
                                downloadState = activeDownloads[track.id],
                                isDownloaded = track.id in downloadedTrackIds,
                                selectionMode = selection.active,
                                selected = track.id in selection.selectedIds
                            )
                        }
                    }

                    if (favoriteTracks.isNotEmpty()) {
                        item { SectionHeader(title = "Liked Songs") }
                        items(favoriteTracks.take(5)) { track ->
                            TrackItem(
                                track = track,
                                isLiked = true,
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = {
                                    if (selection.active) selection.toggle(track.id)
                                    else playerViewModel.playTrack(track, favoriteTracks)
                                },
                                onLongClick = { selection.toggle(track.id) },
                                onMoreClick = { showContextMenuForTrack = track },
                                onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                },
                                downloadState = activeDownloads[track.id],
                                isDownloaded = track.id in downloadedTrackIds,
                                selectionMode = selection.active,
                                selected = track.id in selection.selectedIds
                            )
                        }
                    }

                    if (favoriteTracks.isEmpty() && recentTracks.isEmpty()) {
                        item { EmptyState("Start playing music to build your library.") }
                    }
                }

            "local" ->
                LocalLibraryTab(
                    viewModel = localLibraryViewModel,
                    onTrackClick = { track, queue ->
                        playerViewModel.playUnifiedTrack(track, queue)
                    },
                    onAlbumClick = { album ->
                        val albumId = album.id.removePrefix("local_album_").toLongOrNull()
                        if (albumId != null) {
                            navController.navigate("local_album/$albumId")
                        }
                    },
                    onArtistClick = { artist ->
                        val artistId = artist.id.removePrefix("local_artist_").toLongOrNull()
                        if (artistId != null) {
                            navController.navigate("local_artist/$artistId")
                        }
                    },
                    onGenreClick = { genre ->
                        navController.navigate(Screen.LocalGenreDetail.createRoute(genre))
                    },
                    onFolderClick = { path ->
                        navController.navigate("folder/${java.net.URLEncoder.encode(path, "UTF-8")}")
                    },
                    onShuffleAll = { tracks ->
                        playerViewModel.shufflePlayUnified(tracks)
                    },
                    navController = navController
                )

            "playlists" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCreatePlaylistDialog = true }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Playlist",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Create Playlist",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (playlists.isEmpty()) {
                        item { EmptyState("Create a playlist to organize your music.") }
                    } else {
                        items(playlists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate("playlist/${playlist.id}") }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistPlay,
                                    contentDescription = "Playlist",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!playlist.description.isNullOrEmpty()) {
                                        Text(
                                            text = playlist.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            "favorites" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (favoriteTracks.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionHeader(title = "Liked Songs")
                                IconButton(onClick = { playerViewModel.downloadAllTracks(favoriteTracks) }) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Download All",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        items(favoriteTracks) { track ->
                            TrackItem(
                                track = track,
                                isLiked = true,
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = {
                                    if (selection.active) selection.toggle(track.id)
                                    else playerViewModel.playTrack(track, favoriteTracks)
                                },
                                onLongClick = { selection.toggle(track.id) },
                                onMoreClick = { showContextMenuForTrack = track },
                                onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                },
                                downloadState = activeDownloads[track.id],
                                isDownloaded = track.id in downloadedTrackIds,
                                selectionMode = selection.active,
                                selected = track.id in selection.selectedIds
                            )
                        }
                    }

                    if (favoriteAlbums.isNotEmpty()) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item { SectionHeader(title = "Liked Albums") }
                        items(favoriteAlbums) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumItem(album = album, onClick = { navController.navigate(Screen.AlbumDetail.createRoute(album.id)) })
                            }
                        }
                    }

                    if (favoriteArtists.isNotEmpty()) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item { SectionHeader(title = "Liked Artists") }
                        items(favoriteArtists) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate(Screen.ArtistDetail.createRoute(artist.id)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtistItem(artist = artist, onClick = { navController.navigate(Screen.ArtistDetail.createRoute(artist.id)) })
                            }
                        }
                    }

                    if (favoriteTracks.isEmpty() && favoriteAlbums.isEmpty() && favoriteArtists.isEmpty()) {
                        item { EmptyState("Like tracks, albums, and artists to see them here.") }
                    }
                }

            "downloads" ->
                DownloadsScreen(navController = navController)
        }
        }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp)
    )
}
