package tf.monochrome.android.ui.library

import tf.monochrome.android.ui.theme.goToPage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import tf.monochrome.android.ui.components.TrackListToolbar
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.TrackSort
import tf.monochrome.android.ui.components.TrackSortSaver
import tf.monochrome.android.ui.components.applySearchAndSort
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.navigation.APP_PAGE_TITLES
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.SearchAction

// LOCAL_SECTION and LIBRARY_SECTION_NAMES used to live here. Page identity and
// page names belong to APP_PAGES in ui/navigation now, because Home and Discover
// are pages in the same list and this file could never have named them.

/**
 * How the Library pager ordered its sections before the flat page list: Local
 * pinned to the front regardless of what the user had actually set, which is why
 * moving Local in Settings never did anything.
 *
 * Kept ONLY to migrate a stored `library_tab_order` into the flat page order,
 * which has to reproduce what that install was seeing rather than what its CSV
 * said. Nothing renders from this any more and Local is genuinely movable now —
 * do not reintroduce the pin.
 */
internal fun legacyLibrarySections(order: List<String>): List<String> =
    listOf("local") + order.filter { it != "local" && it in APP_PAGE_TITLES }

/**
 * Lazy list keys for Library's two mixed pages.
 *
 * A key has to be unique across the whole `LazyColumn`, not just within the
 * `items()` call it came from, and Compose throws rather than recovering when
 * two collide. Both of these pages stack several `items()` blocks into one
 * list, and a bare `it.id` is unique in neither of them:
 *
 * - Favorites lists tracks, then albums, then artists. All three ids are
 *   `Long` from separate catalogue namespaces, so a liked track and a liked
 *   album sharing a number is coincidence, not corruption — and it crashed
 *   the page for anyone who happened to hold both.
 * - Overview shows recently played above liked songs, and a song you like and
 *   have just played is in both lists by construction.
 *
 * Tagging by the row's kind makes the key unique by shape rather than by
 * luck. They're `String`, which a lazy list can save and restore.
 */
internal object LibraryKeys {
    fun track(id: Long) = "track:$id"
    fun album(id: Long) = "album:$id"
    fun artist(id: Long) = "artist:$id"
    fun recent(id: Long) = "recent:$id"
    fun liked(id: Long) = "liked:$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    // One page per instance now. The app has a single flat pager in the nav host,
    // so this composable is the chrome around ONE section rather than a pager
    // over five. All instances share one LibraryViewModel: the pager sits outside
    // the NavHost, so hiltViewModel() resolves against the Activity store.
    sectionId: String,
    // The whole page list and the one pager, for the overflow menu's jumps.
    pages: List<String>,
    pager: PagerState,
    viewModel: LibraryViewModel = hiltViewModel(),
    localLibraryViewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val favoriteAlbums by viewModel.favoriteAlbums.collectAsStateWithLifecycle()
    val favoriteArtists by viewModel.favoriteArtists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val activeDownloads by playerViewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadedTrackIds by playerViewModel.downloadedTrackIds.collectAsStateWithLifecycle()

    val sectionScope = rememberCoroutineScope()
    // Page changes slide normally; with "Disable animations" on they jump.
    val animateTabs = !tf.monochrome.android.ui.theme.reduceMotion()

    // The overflow menu survives as a shortcut past the swipe — a page the user
    // has put several places away is a long drag — not as the only way in. It
    // lists Home and Discover too now, since they are ordinary pages in the same
    // sequence rather than a separate pager this screen could not reach.
    val menuSections = pages.filter { it != sectionId }
        .mapNotNull { id -> APP_PAGE_TITLES[id]?.let { id to it } }

    // Saveable so the dialog reopens after a process death triggered by its own
    // SAF CSV picker; the dialog's typed fields + picked uri are saveable too.
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForSelection by remember { mutableStateOf(false) }

    // How the Liked Songs list is being looked at right now. Screen-local
    // rather than ViewModel state: it describes the view, not the library.
    var likedQuery by rememberSaveable { mutableStateOf("") }
    var likedSearchOpen by rememberSaveable { mutableStateOf(false) }
    var likedSort by rememberSaveable(stateSaver = TrackSortSaver) { mutableStateOf(TrackSort()) }
    val visibleFavorites = remember(favoriteTracks, likedQuery, likedSort) {
        favoriteTracks.applySearchAndSort(likedQuery, likedSort)
    }

    val selection = rememberTrackSelectionState<Long>()
    // The "back returns to the first page" handler lives in the nav host now,
    // composed before this page's content — which is what keeps the ordering
    // this comment has always been about: the dispatcher serves the
    // LAST-composed enabled callback first, so an active selection still wins
    // the first back press and only then does back move the pager.
    BackHandler(enabled = selection.active) { selection.clear() }
    // No "clear the selection when the section changes" effect any more: each
    // page is its own instance with its own selection state, so a selection
    // structurally cannot follow the user to another page.

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
                { navController.navigateSafe(Screen.AlbumDetail.createRoute(albumId)) }
            },
            onGoToArtist = track.artist?.id?.let { artistId ->
                { navController.navigateSafe(Screen.ArtistDetail.createRoute(artistId)) }
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
    val importProgressState = viewModel.importProgress.collectAsStateWithLifecycle()
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
    // Remembered because this composable is now one page rather than a pager
    // over five, so up to three instances of it are composed at once and each
    // would otherwise rebuild the list on every recomposition.
    val selectableTracks = remember(recentTracks, favoriteTracks) {
        (recentTracks + favoriteTracks).distinctBy { it.id }
    }

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
                        // The local library keeps calling itself "Library".
                        // That IS a special case, and a deliberate one — it is
                        // not a leftover of the old pin that made Local page 0.
                        // Every other page uses its registry title.
                        text = if (sectionId == "local") "Library"
                               else APP_PAGE_TITLES[sectionId] ?: "Library",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                // No back arrow: there is no page this one is "inside" any
                // more. Every page is a peer in one swipe list, and back is the
                // nav host's — it returns to the first page from any of them.
                actions = {
                    // Settings lets the user hide pages, so the button goes
                    // away rather than opening an empty menu.
                    if (menuSections.isNotEmpty()) Box {
                        IconButton(onClick = { sectionMenuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Other pages",
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
                                        val page = pages.indexOf(id)
                                        if (page >= 0) {
                                            sectionScope.launch {
                                                pager.goToPage(page, animateTabs)
                                            }
                                        }
                                        sectionMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    // Only where there is a list to search. The other sections
                    // are grids of albums and artists with no filter behind
                    // them, and an icon that does nothing is worse than none.
                    if (sectionId == "favorites") {
                        SearchAction(open = likedSearchOpen, onToggle = {
                            likedSearchOpen = !likedSearchOpen
                            if (!likedSearchOpen) likedQuery = ""
                        })
                    }
                    IconButton(onClick = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) }) {
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
                onDelete = if (sectionId == "favorites") {
                    {
                        playerViewModel.unlikeTracks(selection.selectedIds)
                        selection.clear()
                    }
                } else null,
                deleteContentDescription = "Unlike"
            )
        }

        // No pager and no state holder here any more: this composable is one
        // page, and the nav host's single pager wraps it in the
        // SaveableStateProvider that keeps its scroll position.
        when (sectionId) {
            "overview" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (recentTracks.isNotEmpty()) {
                        item { SectionHeader(title = "Recently Played") }
                        items(recentTracks.take(5), key = { LibraryKeys.recent(it.id) }) { track ->
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
                                    { navController.navigateSafe(Screen.AlbumDetail.createRoute(albumId)) }
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
                        items(favoriteTracks.take(5), key = { LibraryKeys.liked(it.id) }) { track ->
                            TrackItem(
                                track = track,
                                isLiked = true,
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = {
                                    if (selection.active) selection.toggle(track.id)
                                    else playerViewModel.playTrack(track, visibleFavorites)
                                },
                                onLongClick = { selection.toggle(track.id) },
                                onMoreClick = { showContextMenuForTrack = track },
                                onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigateSafe(Screen.AlbumDetail.createRoute(albumId)) }
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
                            navController.navigateSafe("local_album/$albumId")
                        }
                    },
                    onArtistClick = { artist ->
                        val artistId = artist.id.removePrefix("local_artist_").toLongOrNull()
                        if (artistId != null) {
                            navController.navigateSafe("local_artist/$artistId")
                        }
                    },
                    onGenreClick = { genre ->
                        navController.navigateSafe(Screen.LocalGenreDetail.createRoute(genre))
                    },
                    onFolderClick = { path ->
                        navController.navigateSafe("folder/${java.net.URLEncoder.encode(path, "UTF-8")}")
                    },
                    onShuffleAll = { tracks ->
                        playerViewModel.shufflePlayUnified(tracks)
                    },
                    navController = navController,
                    playerViewModel = playerViewModel
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
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigateSafe("playlist/${playlist.id}") }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // Pinned, not an item in the list.
                    //
                    // As a row of the list it scrolled away with the songs, so
                    // the way to search a long list was to scroll back to the
                    // top of it first. It also carried a "Liked Songs" heading
                    // directly under an app bar that already said Favorites —
                    // the same list, named twice, in the space where the tools
                    // for it should have been.
                    //
                    // Full key set: liked songs span every album and artist,
                    // unlike a single album's track list.
                    if (favoriteTracks.isNotEmpty()) {
                        TrackListToolbar(
                            sort = likedSort,
                            onSortChange = { likedSort = it },
                            trailing = {
                                IconButton(onClick = {
                                    playerViewModel.downloadAllTracks(visibleFavorites)
                                }) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Download All",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                        )
                    }
                SearchOverlay(
                    open = likedSearchOpen,
                    query = likedQuery,
                    onQueryChange = { likedQuery = it },
                    placeholder = "Search liked songs",
                    onClose = { likedSearchOpen = false; likedQuery = "" },
                ) { searchTopInset ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = searchTopInset, bottom = 80.dp)
                ) {
                    if (favoriteTracks.isNotEmpty()) {
                        items(visibleFavorites, key = { LibraryKeys.track(it.id) }) { track ->
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
                                    { navController.navigateSafe(Screen.AlbumDetail.createRoute(albumId)) }
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
                        items(favoriteAlbums, key = { LibraryKeys.album(it.id) }) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigateSafe(Screen.AlbumDetail.createRoute(album.id)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumItem(album = album, onClick = { navController.navigateSafe(Screen.AlbumDetail.createRoute(album.id)) })
                            }
                        }
                    }

                    if (favoriteArtists.isNotEmpty()) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item { SectionHeader(title = "Liked Artists") }
                        items(favoriteArtists, key = { LibraryKeys.artist(it.id) }) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigateSafe(Screen.ArtistDetail.createRoute(artist.id)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtistItem(artist = artist, onClick = { navController.navigateSafe(Screen.ArtistDetail.createRoute(artist.id)) })
                            }
                        }
                    }

                    if (favoriteTracks.isEmpty() && favoriteAlbums.isEmpty() && favoriteArtists.isEmpty()) {
                        item { EmptyState("Like tracks, albums, and artists to see them here.") }
                    }
                }
                }
                }

            "downloads" ->
                DownloadsScreen(navController = navController)
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
