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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
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
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    val recentTracks by viewModel.recentTracks.collectAsState()
    val recommendedTracks by viewModel.recommendedTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRadioLoading by viewModel.isRadioLoading.collectAsState()
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
    val hasSearchResults = searchQuery.isNotBlank()

    var showContextMenuForTrack by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Track?>(null)
    }
    var showAddToPlaylistForTrack by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Track?>(null)
    }
    var showCreatePlaylistDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
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
            onDownloadTrack = { playerViewModel.downloadTrack(track) },
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
            track = track,
            playlists = libraryPlaylists,
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "MonoTrypT",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            actions = {
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

        // Search bar
        SearchQueryField(
            query = searchQuery,
            onQueryChange = searchViewModel::onQueryChange,
            onSubmit = searchViewModel::submitSearch
        )

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
                libraryPlaylists = libraryPlaylists
            )
        } else if (isLoading) {
            LoadingScreen()
        } else {
            // ── Home content ────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 160.dp)
            ) {
                // ── Personalized Mix ──────────────────────────────
                item {
                    SectionHeader(
                        title = "Your Personalized Mix"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    InfiniteRadioButton(
                        isLoading = isRadioLoading,
                        onStartRadio = {
                            val tracks = viewModel.startHistoryMix()
                            if (tracks.isNotEmpty()) {
                                playerViewModel.playTrack(tracks.first(), tracks)
                            }
                        },
                        onRefresh = { viewModel.loadRecommendations() }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (recommendedTracks.isNotEmpty()) {
                    items(recommendedTracks) { track ->
                        TrackItem(
                            track = track,
                            isLiked = favoriteTrackIds.contains(track.id),
                            onLikeClick = { playerViewModel.toggleFavorite(track) },
                            onClick = { playerViewModel.playTrack(track, recommendedTracks) },
                            onLongClick = { showContextMenuForTrack = track },
                            onMoreClick = { showContextMenuForTrack = track },
                            onAlbumClick = track.album?.id?.let { albumId ->
                                { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                            }
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Play some music to get personalized recommendations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }

                // ── Recently Played ────────────────────────────────────
                if (recentTracks.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionHeader(title = "Jump Back In")
                    }
                    items(recentTracks) { track ->
                        TrackItem(
                            track = track,
                            isLiked = favoriteTrackIds.contains(track.id),
                            onLikeClick = { playerViewModel.toggleFavorite(track) },
                            onClick = { playerViewModel.playTrack(track, recentTracks) },
                            onLongClick = { showContextMenuForTrack = track },
                            onMoreClick = { showContextMenuForTrack = track }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfiniteRadioButton(
    isLoading: Boolean,
    onStartRadio: () -> Unit,
    onRefresh: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isLoading) 360f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "refresh_rotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ElevatedButton(
            onClick = onStartRadio,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 2.dp)
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Play My Mix",
                style = MaterialTheme.typography.labelLarge
            )
        }

        IconButton(onClick = onRefresh, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh recommendations",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
