package tf.monochrome.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackItem
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.search.SearchQueryField
import tf.monochrome.android.ui.search.SearchHistoryContent
import tf.monochrome.android.ui.search.SearchResultsContent
import tf.monochrome.android.ui.search.SearchViewModel
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: HomeViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
    downloadCenter: tf.monochrome.android.ui.downloads.DownloadCenterViewModel = hiltViewModel(),
    settingsViewModel: tf.monochrome.android.ui.settings.SettingsViewModel = hiltViewModel(),
) {
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val activeDownloads by downloadCenter.active.collectAsStateWithLifecycle()
    val downloadProgress by downloadCenter.overallProgress.collectAsStateWithLifecycle()
    var showDownloadsMonitor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val downloadedTrackIds by playerViewModel.downloadedTrackIds.collectAsStateWithLifecycle()
    val libraryPlaylists by playerViewModel.playlists.collectAsStateWithLifecycle()

    // Update notice. Reads straight off the settings store so opening About
    // from anywhere — the bar, or the user's own navigation — clears it.
    val whatsNewSeen by settingsViewModel.whatsNewSeenVersion.collectAsStateWithLifecycle()
    val whatsNewNeverShow by settingsViewModel.whatsNewNeverShow.collectAsStateWithLifecycle()
    val showWhatsNew = tf.monochrome.android.ui.settings.WhatsNew
        .shouldNotify(whatsNewSeen, whatsNewNeverShow)
    val whatsNewVersionName = tf.monochrome.android.ui.settings.WhatsNew
        .current?.versionName.orEmpty()

    // A release waiting on GitHub outranks the notes for the build already
    // installed: "there's a newer version" is the more useful of the two, and
    // showing both at once would be two bars saying almost the same thing.
    val availableUpdate by settingsViewModel.availableUpdate.collectAsStateWithLifecycle()
    val showUpdateBar by settingsViewModel.showUpdateBar.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settingsViewModel.refreshUpdateStatus() }

    // Search state
    val searchQuery by searchViewModel.query.collectAsStateWithLifecycle()
    val searchTracks by searchViewModel.tracks.collectAsStateWithLifecycle()
    val searchAlbums by searchViewModel.albums.collectAsStateWithLifecycle()
    val searchArtists by searchViewModel.artists.collectAsStateWithLifecycle()
    val searchPlaylists by searchViewModel.playlists.collectAsStateWithLifecycle()
    val isSearching by searchViewModel.isSearching.collectAsStateWithLifecycle()
    val selectedType by searchViewModel.selectedType.collectAsStateWithLifecycle()
    val selectedSource by searchViewModel.selectedSource.collectAsStateWithLifecycle()
    val showSourceFilter by searchViewModel.showSourceFilter.collectAsStateWithLifecycle()
    val isLoadingMore by searchViewModel.isLoadingMore.collectAsStateWithLifecycle()
    val endReached by searchViewModel.endReached.collectAsStateWithLifecycle()
    val searchError by searchViewModel.searchError.collectAsStateWithLifecycle()
    val searchHistory by searchViewModel.searchHistory.collectAsStateWithLifecycle()
    val hasSearchResults = searchQuery.isNotBlank()

    // Search reveals on demand; radio is the resting primary action.
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val searchFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    // Only grab focus on a genuine user open (pendingFocus is non-saveable, so
    // returning from a detail screen with searchOpen restored true does NOT
    // re-pop the keyboard over the results).
    var pendingSearchFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pendingSearchFocus) {
        if (pendingSearchFocus) {
            pendingSearchFocus = false
            runCatching { searchFocus.requestFocus() }
        }
    }
    val isRadioActive by playerViewModel.isRadioActive.collectAsStateWithLifecycle()
    val isRadioGenerating by playerViewModel.isRadioGenerating.collectAsStateWithLifecycle()

    var showContextMenuForTrack by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Track?>(null)
    }
    var showAddToPlaylistForTrack by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Track?>(null)
    }
    var showCreatePlaylistDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Tracks handed over from an "Add to playlist → New Playlist" tap, added to
    // the playlist once it's created so they aren't dropped on the way.
    var pendingTracksForNewPlaylist by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<Track>>(emptyList())
    }
    var showAddToPlaylistForSelection by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    val selection = tf.monochrome.android.ui.components.rememberTrackSelectionState<Long>()
    androidx.activity.compose.BackHandler(enabled = selection.active) { selection.clear() }
    // Back closes an open search (and clears the query so the feed returns)
    // instead of falling through and exiting the app.
    androidx.activity.compose.BackHandler(enabled = searchOpen) {
        searchViewModel.onQueryChange("")
        searchOpen = false
    }

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
            onDismiss = {
                showCreatePlaylistDialog = false
                pendingTracksForNewPlaylist = emptyList()
            },
            onSubmit = { name, description ->
                playerViewModel.createPlaylist(name, description, pendingTracksForNewPlaylist)
                pendingTracksForNewPlaylist = emptyList()
                showCreatePlaylistDialog = false
            }
        )
    }

    showAddToPlaylistForTrack?.let { track ->
        AddToPlaylistSheet(
            playlists = libraryPlaylists,
            onDismiss = { showAddToPlaylistForTrack = null },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTrackToPlaylist(playlist.id, track)
                showAddToPlaylistForTrack = null
            },
            onCreateNew = {
                pendingTracksForNewPlaylist = listOf(track)
                showAddToPlaylistForTrack = null
                showCreatePlaylistDialog = true
            }
        )
    }

    if (showAddToPlaylistForSelection) {
        AddToPlaylistSheet(
            title = "Add ${selection.count} tracks to playlist",
            playlists = libraryPlaylists,
            onDismiss = { showAddToPlaylistForSelection = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTracksToPlaylist(
                    playlist.id,
                    recentTracks.filter { it.id in selection.selectedIds },
                )
                showAddToPlaylistForSelection = false
                selection.clear()
            },
            onCreateNew = {
                pendingTracksForNewPlaylist = recentTracks.filter { it.id in selection.selectedIds }
                showAddToPlaylistForSelection = false
                showCreatePlaylistDialog = true
            }
        )
    }

    if (showDownloadsMonitor) {
        tf.monochrome.android.ui.downloads.DownloadsMonitorSheet(
            downloads = activeDownloads,
            onCancel = downloadCenter::cancel,
            onCancelAll = downloadCenter::cancelAll,
            onDismiss = { showDownloadsMonitor = false },
            onRetry = downloadCenter::retry,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        tf.monochrome.android.devedit.DevEditable("home_header", Modifier.fillMaxWidth()) {
            TopAppBar(
                title = {
                    Text(
                        text = "Tryptify",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    IconButton(onClick = {
                        if (searchOpen) {
                            // Closing search also clears the query so the
                            // home feed comes back.
                            searchViewModel.onQueryChange("")
                        }
                        val opening = !searchOpen
                        searchOpen = opening
                        if (opening) pendingSearchFocus = true
                    }) {
                        Icon(
                            if (searchOpen) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search",
                            tint = if (searchOpen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    tf.monochrome.android.ui.downloads.DownloadTopBarIndicator(
                        activeCount = activeDownloads.size,
                        overallProgress = downloadProgress,
                        onClick = { showDownloadsMonitor = true },
                    )
                    IconButton(onClick = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigateTool(Screen.Profile) }) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            )
        }

        // Search — hidden by default, revealed by the top-bar search button.
        // The field stays visible while a query is active so results keep
        // their input attached.
        AnimatedVisibility(
            visible = searchOpen || hasSearchResults,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            tf.monochrome.android.devedit.DevEditable("home_search_bar", Modifier.fillMaxWidth()) {
                SearchQueryField(
                    query = searchQuery,
                    onQueryChange = searchViewModel::onQueryChange,
                    onSubmit = searchViewModel::submitSearch,
                    modifier = Modifier.focusRequester(searchFocus)
                )
            }
        }

        // Play Radio — the home screen's primary action: seed a station from
        // whatever is playing (falling back to recent history) and keep the
        // queue topped up.
        if (!searchOpen && !hasSearchResults) {
            tf.monochrome.android.devedit.DevEditable("home_play_radio", Modifier.fillMaxWidth()) {
                tf.monochrome.android.ui.components.PlayRadioButton(
                    isActive = isRadioActive,
                    isGenerating = isRadioGenerating,
                    onClick = {
                        if (isRadioActive) playerViewModel.stopRadio()
                        else playerViewModel.playRadio()
                    }
                )
            }
        }

        if (hasSearchResults) {
            SearchResultsContent(
                navController = navController,
                playerViewModel = playerViewModel,
                query = searchQuery,
                tracks = searchTracks,
                albums = searchAlbums,
                artists = searchArtists,
                playlistResults = searchPlaylists,
                isSearching = isSearching,
                selectedType = selectedType,
                onTypeSelected = searchViewModel::setSelectedType,
                selectedSource = selectedSource,
                onSourceSelected = searchViewModel::setSelectedSource,
                showSourceFilter = showSourceFilter,
                favoriteTrackIds = favoriteTrackIds,
                libraryPlaylists = libraryPlaylists,
                onLoadMore = searchViewModel::loadMore,
                isLoadingMore = isLoadingMore,
                endReached = endReached,
                searchError = searchError,
                onRetry = searchViewModel::submitSearch,
                // Recent-search history — previously only reachable from the
                // orphaned standalone SearchScreen; now shown when the Home
                // search is open with an empty query.
                emptyContent = {
                    SearchHistoryContent(
                        history = searchHistory,
                        onSelect = searchViewModel::selectHistoryQuery,
                        onClearHistory = searchViewModel::clearSearchHistory,
                    )
                },
            )
        } else if (isLoading) {
            LoadingScreen()
        } else {
            androidx.compose.animation.AnimatedVisibility(visible = selection.active) {
                tf.monochrome.android.ui.components.TrackSelectionBar(
                    selectedCount = selection.count,
                    onClose = { selection.clear() },
                    onAddToQueue = {
                        playerViewModel.addToQueue(recentTracks.filter { it.id in selection.selectedIds })
                        selection.clear()
                    },
                    onAddToPlaylist = { showAddToPlaylistForSelection = true },
                    onDelete = {
                        playerViewModel.removeFromHistory(selection.selectedIds)
                        selection.clear()
                    },
                    deleteContentDescription = "Remove from history"
                )
            }

            // ── Home content ────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 160.dp)
            ) {
                // One dismissible bar per release, at the top of the first
                // screen the user lands on. Tapping it opens the notes in
                // About; it never blocks anything.
                val update = availableUpdate
                if (showUpdateBar && update != null) {
                    item(key = "update_bar") {
                        tf.monochrome.android.ui.components.WhatsNewBar(
                            title = "Version ${update.versionName} is available",
                            subtitle = "Tap to see the release on GitHub",
                            onOpen = {
                                settingsViewModel.dismissUpdate()
                                runCatching {
                                    homeContext.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(update.releaseUrl),
                                        )
                                    )
                                }
                            },
                            onDismiss = { settingsViewModel.dismissUpdate() },
                            onNeverShow = { settingsViewModel.neverShowWhatsNew() },
                        )
                    }
                } else if (showWhatsNew) {
                    item(key = "whats_new_bar") {
                        tf.monochrome.android.ui.components.WhatsNewBar(
                            title = "Updated to $whatsNewVersionName",
                            subtitle = "See what's new",
                            onOpen = {
                                settingsViewModel.markWhatsNewSeen()
                                navController.navigateSafe(
                                    Screen.Settings.createRoute(
                                        tf.monochrome.android.ui.settings.SETTINGS_TAB_ABOUT
                                    )
                                )
                            },
                            onDismiss = { settingsViewModel.markWhatsNewSeen() },
                            onNeverShow = { settingsViewModel.neverShowWhatsNew() },
                        )
                    }
                }

                // Discovery moved out to its own tab. Home is now what the user
                // is doing right now — start a station, pick a recent track,
                // search — and Discover is where they go to look for something
                // new. Two jobs that were competing for one scroll.
                if (recentTracks.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Recently Played")
                    }
                    items(recentTracks, key = { it.id }) { track ->
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
                            isDownloaded = track.id in downloadedTrackIds,
                            selectionMode = selection.active,
                            selected = track.id in selection.selectedIds
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Play some music — your history will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}


// The About tab index used to be written down here as a literal and silently
// broke every time Settings was reordered. SETTINGS_TAB_ABOUT is derived from
// the tab list itself, so there is nothing left to keep in sync.
