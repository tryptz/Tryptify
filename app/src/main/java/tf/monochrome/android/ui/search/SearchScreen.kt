package tf.monochrome.android.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material.icons.filled.Settings
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.player.PlayerViewModel

/**
 * The Search tab: the catalogue search, and its history while the field is
 * empty. Its own page of the pager, so a query and its results are still here
 * after an album opened from them is closed.
 *
 * [autoFocus] is true only when the listener has just tapped the Search button
 * — a request to type. Coming back to this page from a detail screen rebuilds
 * it too, and popping the keyboard over the results then would be unasked for.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    autoFocus: Boolean = false,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlistResults by viewModel.playlists.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedSource.collectAsStateWithLifecycle()
    val showSourceFilter by viewModel.showSourceFilter.collectAsStateWithLifecycle()
    val albumSources by viewModel.albumSources.collectAsStateWithLifecycle()
    val artistSources by viewModel.artistSources.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val endReached by viewModel.endReached.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val libraryPlaylists by playerViewModel.playlists.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Every page's top bar is a way into Settings — see ui-invariants.md.
        androidx.compose.material3.TopAppBar(
            title = {
                androidx.compose.material3.Text(
                    text = "Search",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                )
            },
            actions = {
                androidx.compose.material3.IconButton(onClick = {
                    navController.navigateTool(
                        tf.monochrome.android.ui.navigation.Screen.Settings,
                        tf.monochrome.android.ui.navigation.Screen.Settings.createRoute(),
                    )
                }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Settings,
                        contentDescription = "Settings",
                    )
                }
            },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        // The bar floats over the results and they run underneath it, the same
        // as everywhere else. Permanent here rather than summoned — this screen
        // *is* the search — so the results are given its height as padding: they
        // start below the glass instead of with the first hit parked under it,
        // and still pass behind it as they scroll.
        SearchOverlay(
            open = true,
            query = query,
            onQueryChange = viewModel::onQueryChange,
            placeholder = "Search tracks, albums, artists, playlists…",
            onClose = null,
            autoFocus = autoFocus,
            onSubmit = viewModel::submitSearch,
        ) { searchTopInset ->
        Column(modifier = Modifier.fillMaxSize()) {
        SearchResultsContent(
            navController = navController,
            playerViewModel = playerViewModel,
            query = query,
            tracks = tracks,
            albums = albums,
            artists = artists,
            playlistResults = playlistResults,
            isSearching = isSearching,
            selectedType = selectedType,
            onTypeSelected = viewModel::setSelectedType,
            selectedSource = selectedSource,
            onSourceSelected = viewModel::setSelectedSource,
            showSourceFilter = showSourceFilter,
            favoriteTrackIds = favoriteTrackIds,
            libraryPlaylists = libraryPlaylists,
            onLoadMore = viewModel::loadMore,
            isLoadingMore = isLoadingMore,
            endReached = endReached,
            searchError = searchError,
            onRetry = viewModel::submitSearch,
            topInset = searchTopInset,
            albumSources = albumSources,
            artistSources = artistSources,
            emptyContent = {
                SearchHistoryContent(
                    history = searchHistory,
                    onSelect = viewModel::selectHistoryQuery,
                    onClearHistory = viewModel::clearSearchHistory
                )
            }
        )
        }
        }
    }
}
