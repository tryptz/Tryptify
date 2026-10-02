package tf.monochrome.android.ui.library

import tf.monochrome.android.ui.navigation.openTrackArtist
import tf.monochrome.android.ui.navigation.trackArtistAction
import tf.monochrome.android.ui.navigation.trackAlbumAction
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.android.R

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
/**
 * The Overview section's id. Not a page any more — Home draws it, embedded,
 * under its banners — but LibraryScreen still renders it, so it keeps an id.
 */
internal const val OVERVIEW_SECTION = "overview"

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

    // Headers and spacers need keys for the same reason the rows do. A bare
    // `item {}` takes a positional key, so inserting a section above one
    // renumbers every item after it and Compose treats the whole tail as new.
    // Sections here appear and disappear with their content, so that happens
    // whenever the first album is liked or the last one is unliked.
    fun header(id: String) = "header:$id"
    fun spacer(id: String) = "spacer:$id"
    const val EMPTY = "empty"
}

/**
 * What KIND of row an item is, for `LazyColumn`'s `contentType`.
 *
 * Compose reuses an item's composition only when the outgoing and incoming
 * items report the same content type; with none given every item reports null,
 * so a mixed list reuses a track row's slot table for an album row, throws
 * almost all of it away and rebuilds. Naming the shapes lets each kind reuse
 * its own, which is what makes scrolling a list of headers, tracks, albums and
 * artists cost the same as scrolling a list of tracks.
 *
 * Values are compared with `equals`, so these are plain strings.
 */
internal object LibraryContentType {
    const val TRACK = "track"
    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val HEADER = "header"
    const val SPACER = "spacer"
    const val EMPTY = "empty"
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
    // The visible pages and the nav host's way of opening one, for the section
    // switcher: its chips are the Library pages among them.
    pages: List<String>,
    onSelectPage: (String) -> Unit,
    // Home draws Overview inside its own chrome, so it asks for the section
    // alone — no Library title, no switcher.
    embedded: Boolean = false,
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

    // Page changes slide normally; with "Disable animations" on they jump.

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
            onGoToAlbum = navController.trackAlbumAction(track, playerViewModel.unifiedFor(track)),
            onGoToArtist = navController.trackArtistAction(track, playerViewModel.unifiedFor(track))
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
                    importMsgContext.getString(
                        R.string.import_done_toast,
                        importMsgContext.resources.getQuantityString(R.plurals.tracks_of_total, p.total, p.matched, p.total),
                        p.playlistName,
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                viewModel.resetImportProgress()
            }
            is tf.monochrome.android.data.import_.ImportProgress.Failed -> {
                val reason = p.message.ifBlank { importMsgContext.getString(R.string.playlist_file_unreadable) }
                android.widget.Toast.makeText(importMsgContext, importMsgContext.getString(R.string.import_failed_toast, reason), android.widget.Toast.LENGTH_LONG).show()
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
    // Session-scoped: an expanded history is a thing you did a moment ago, not
    // a preference, and coming back to a page scrolled into 200 rows you do not
    // remember opening is worse than re-tapping.
    var allRecentShown by remember { mutableStateOf(false) }

    val selectableTracks = remember(recentTracks, favoriteTracks) {
        (recentTracks + favoriteTracks).distinctBy { it.id }
    }

    if (showAddToPlaylistForSelection) {
        AddToPlaylistSheet(
            title = pluralStringResource(R.plurals.add_n_tracks_to_playlist, selection.count, selection.count),
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
        if (!embedded) tf.monochrome.android.devedit.DevEditable("library_header", Modifier.fillMaxWidth()) {
            TopAppBar(
                title = {
                    // One title for every section: they are all the Library
                    // tab, and the switcher under the bar says which is open.
                    Text(
                        text = stringResource(R.string.tab_library),
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                // No back arrow: Library is a tab, not a screen inside one.
                // Back is the nav host's, and goes to Home.
                actions = {
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
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }

        if (!embedded) LibrarySectionSwitcher(
            sections = pages.filter { it in tf.monochrome.android.ui.navigation.LIBRARY_PAGE_IDS },
            current = sectionId,
            onSelect = onSelectPage,
        )

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
                deleteContentDescription = stringResource(R.string.action_unlike)
            )
        }

        // No pager and no state holder here any more: this composable is one
        // page, and the nav host's single pager wraps it in the
        // SaveableStateProvider that keeps its scroll position.
        when (sectionId) {
            OVERVIEW_SECTION ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = tf.monochrome.android.ui.navigation.bottomChromePadding)
                ) {
                    if (recentTracks.isNotEmpty()) {
                        item(key = LibraryKeys.header("recent"), contentType = LibraryContentType.HEADER) {
                            SectionHeader(
                                title = stringResource(R.string.recently_played),
                                // Home used to carry the full history; it is
                                // the page list now, and five rows here was
                                // all that was left of it. Rather than a
                                // screen of its own, the section opens in
                                // place — the history is one list, and it is
                                // already loaded.
                                onSeeAllClick = if (recentTracks.size > RECENT_PREVIEW) {
                                    { allRecentShown = !allRecentShown }
                                } else null,
                                seeAllLabel = if (allRecentShown) stringResource(R.string.action_show_less) else stringResource(R.string.action_see_all),
                            )
                        }
                        items(
                            if (allRecentShown) recentTracks else recentTracks.take(RECENT_PREVIEW),
                            key = { LibraryKeys.recent(it.id) },
                            contentType = { LibraryContentType.TRACK },
                        ) { track ->
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
                                onArtistClick = { artistId -> navController.openTrackArtist(track, playerViewModel.unifiedFor(track), artistId) },
                                onAlbumClick = navController.trackAlbumAction(track, playerViewModel.unifiedFor(track)),
                                downloadState = activeDownloads[track.id],
                                isDownloaded = track.id in downloadedTrackIds,
                                selectionMode = selection.active,
                                selected = track.id in selection.selectedIds
                            )
                        }
                    }

                    if (favoriteTracks.isNotEmpty()) {
                        item(key = LibraryKeys.header("liked"), contentType = LibraryContentType.HEADER) {
                            SectionHeader(title = stringResource(R.string.liked_songs))
                        }
                        items(
                            favoriteTracks.take(5),
                            key = { LibraryKeys.liked(it.id) },
                            contentType = { LibraryContentType.TRACK },
                        ) { track ->
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
                                onArtistClick = { artistId -> navController.openTrackArtist(track, playerViewModel.unifiedFor(track), artistId) },
                                onAlbumClick = navController.trackAlbumAction(track, playerViewModel.unifiedFor(track)),
                                downloadState = activeDownloads[track.id],
                                isDownloaded = track.id in downloadedTrackIds,
                                selectionMode = selection.active,
                                selected = track.id in selection.selectedIds
                            )
                        }
                    }

                    if (favoriteTracks.isEmpty() && recentTracks.isEmpty()) {
                        item { EmptyState(stringResource(R.string.library_empty)) }
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
                    onFacetClick = { facet, value ->
                        navController.navigateSafe(Screen.LocalFacetDetail.createRoute(facet, value))
                    },
                    onFolderClick = { path ->
                        navController.navigateSafe(Screen.FolderBrowser.createRoute(path))
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
                    contentPadding = PaddingValues(bottom = tf.monochrome.android.ui.navigation.bottomChromePadding)
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
                                contentDescription = stringResource(R.string.new_playlist),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = stringResource(R.string.create_playlist_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (playlists.isEmpty()) {
                        item { EmptyState(stringResource(R.string.playlists_empty)) }
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
                                    contentDescription = stringResource(R.string.playlist),
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
                                        contentDescription = stringResource(R.string.download_all),
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
                    placeholder = stringResource(R.string.search_liked_songs),
                    onClose = { likedSearchOpen = false; likedQuery = "" },
                ) { searchTopInset ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = searchTopInset, bottom = tf.monochrome.android.ui.navigation.bottomChromePadding)
                ) {
                    if (favoriteTracks.isNotEmpty()) {
                        items(
                            visibleFavorites,
                            key = { LibraryKeys.track(it.id) },
                            contentType = { LibraryContentType.TRACK },
                        ) { track ->
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
                                onArtistClick = { artistId -> navController.openTrackArtist(track, playerViewModel.unifiedFor(track), artistId) },
                                onAlbumClick = navController.trackAlbumAction(track, playerViewModel.unifiedFor(track)),
                                downloadState = activeDownloads[track.id],
                                isDownloaded = track.id in downloadedTrackIds,
                                selectionMode = selection.active,
                                selected = track.id in selection.selectedIds
                            )
                        }
                    }

                    if (favoriteAlbums.isNotEmpty()) {
                        item(key = LibraryKeys.spacer("albums"), contentType = LibraryContentType.SPACER) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        item(key = LibraryKeys.header("albums"), contentType = LibraryContentType.HEADER) {
                            SectionHeader(title = stringResource(R.string.liked_albums))
                        }
                        items(
                            favoriteAlbums,
                            key = { LibraryKeys.album(it.id) },
                            contentType = { LibraryContentType.ALBUM },
                        ) { album ->
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
                        item(key = LibraryKeys.spacer("artists"), contentType = LibraryContentType.SPACER) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        item(key = LibraryKeys.header("artists"), contentType = LibraryContentType.HEADER) {
                            SectionHeader(title = stringResource(R.string.liked_artists))
                        }
                        items(
                            favoriteArtists,
                            key = { LibraryKeys.artist(it.id) },
                            contentType = { LibraryContentType.ARTIST },
                        ) { artist ->
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
                        item(key = LibraryKeys.EMPTY, contentType = LibraryContentType.EMPTY) {
                            EmptyState(stringResource(R.string.favorites_empty))
                        }
                    }
                }
                }
                }

            "downloads" ->
                DownloadsScreen(navController = navController)
        }
    }
}

/**
 * The Library tab's sections, as a row of glass chips under its title.
 *
 * Not a second pager, and it must not become one: each chip moves the nav host's
 * one pager to that section's page, exactly as the tab bar does for its tabs.
 * Scrolls sideways rather than squeezing, so a long section name at a large
 * text size still reads whole.
 */
@Composable
private fun LibrarySectionSwitcher(
    sections: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    if (sections.size < 2) return
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(sections, key = { it }) { id ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = tf.monochrome.android.ui.navigation.pageTitle(id),
                selected = id == current,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { if (id != current) onSelect(id) },
            )
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

/** Rows of history Overview shows before "See All" opens the rest. */
private const val RECENT_PREVIEW = 5
