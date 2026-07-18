package tf.monochrome.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.ClickableArtists
import tf.monochrome.android.ui.components.CoverImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import tf.monochrome.android.ui.search.SearchResultsContent
import tf.monochrome.android.ui.search.SearchViewModel
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: HomeViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
    downloadCenter: tf.monochrome.android.ui.downloads.DownloadCenterViewModel = hiltViewModel()
) {
    val activeDownloads by downloadCenter.active.collectAsState()
    val downloadProgress by downloadCenter.overallProgress.collectAsState()
    var showDownloadsMonitor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val recentTracks by viewModel.recentTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val discoveryRows by viewModel.discoveryRows.collectAsState()
    val favoritesRow by viewModel.favoritesRow.collectAsState()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsState()
    val libraryPlaylists by playerViewModel.playlists.collectAsState()

    // Search state
    val searchQuery by searchViewModel.query.collectAsState()
    val searchTracks by searchViewModel.tracks.collectAsState()
    val searchAlbums by searchViewModel.albums.collectAsState()
    val searchArtists by searchViewModel.artists.collectAsState()
    val searchPlaylists by searchViewModel.playlists.collectAsState()
    val isSearching by searchViewModel.isSearching.collectAsState()
    val selectedType by searchViewModel.selectedType.collectAsState()
    val selectedSource by searchViewModel.selectedSource.collectAsState()
    val showSourceFilter by searchViewModel.showSourceFilter.collectAsState()
    val isLoadingMore by searchViewModel.isLoadingMore.collectAsState()
    val endReached by searchViewModel.endReached.collectAsState()
    val recommendations by searchViewModel.recommendations.collectAsState()
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
    val isRadioActive by playerViewModel.isRadioActive.collectAsState()
    val isRadioGenerating by playerViewModel.isRadioGenerating.collectAsState()

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
                    IconButton(onClick = { navController.navigate(Screen.Settings.createRoute()) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigate(Screen.Profile.route) }) {
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
                PlayRadioButton(
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
                // Personalized discovery feed: "From your favorites" first, then
                // "New from <artist>" rows. Falls back to the static genre seeds
                // only when the user has no taste data (new user / Qobuz empty).
                val personalizedRows = listOfNotNull(favoritesRow) + discoveryRows
                if (personalizedRows.isNotEmpty()) {
                    item { SectionHeader(title = "Discover") }
                    items(personalizedRows, key = { it.label }) { row ->
                        DiscoveryRowSection(
                            label = row.label,
                            tracks = row.tracks,
                            onPlay = { track -> playerViewModel.playUnifiedTrack(track, row.tracks) },
                            onArtistClick = { artist ->
                                artist.id?.let { artistId ->
                                    navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                                }
                            }
                        )
                    }
                } else if (recommendations.isNotEmpty()) {
                    item { SectionHeader(title = "Recommended") }
                    items(recommendations, key = { it.label }) { row ->
                        DiscoveryRowSection(
                            label = row.label,
                            tracks = row.tracks,
                            onPlay = { track -> playerViewModel.playUnifiedTrack(track, row.tracks) },
                            onArtistClick = { artist ->
                                artist.id?.let { artistId ->
                                    navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                                }
                            }
                        )
                    }
                }
                if (recentTracks.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Recently Played")
                    }
                    items(recentTracks) { track ->
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


@Composable
private fun PlayRadioButton(
    isActive: Boolean,
    isGenerating: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MonoDimens.shapePill)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.primary
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val contentColor =
            if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onPrimary
        if (isGenerating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Icon(
                if (isActive) Icons.Default.GraphicEq else Icons.Default.Podcasts,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = when {
                isGenerating -> "Finding similar tracks…"
                isActive -> "Radio on — tap to stop"
                else -> "Play Radio"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}


@androidx.compose.runtime.Composable
private fun DiscoveryRowSection(
    label: String,
    tracks: List<UnifiedTrack>,
    onPlay: (UnifiedTrack) -> Unit,
    onArtistClick: (UnifiedArtistRef) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                RecommendationCard(
                    track = track,
                    onPlay = { onPlay(track) },
                    onArtistClick = onArtistClick
                )
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun RecommendationCard(
    track: UnifiedTrack,
    onPlay: () -> Unit,
    onArtistClick: (UnifiedArtistRef) -> Unit
) {
    Column(modifier = Modifier.width(140.dp).padding(4.dp)) {
        // Artwork (and title) play the track; each credited artist name navigates
        // to that artist's page (supports multiple featured artists per track).
        CoverImage(
            url = track.artworkUri,
            contentDescription = track.title,
            size = 132.dp,
            cornerRadius = MonoDimens.radiusSm,
            modifier = Modifier.clickable(onClick = onPlay)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onPlay)
        )
        ClickableArtists(
            artists = track.artists,
            fallbackName = track.artistName,
            onArtistClick = onArtistClick,
        )
    }
}
