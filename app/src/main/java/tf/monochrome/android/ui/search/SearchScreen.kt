package tf.monochrome.android.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.player.PlayerViewModel

@Composable
fun SearchScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
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
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val endReached by viewModel.endReached.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val libraryPlaylists by playerViewModel.playlists.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
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
            reserveSpace = true,
            // Arriving here is a request to type: this route only exists because
            // someone tapped search.
            autoFocus = true,
            onSubmit = viewModel::submitSearch,
        ) { searchTopInset ->
        Column(modifier = Modifier.fillMaxSize().padding(top = searchTopInset)) {
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
