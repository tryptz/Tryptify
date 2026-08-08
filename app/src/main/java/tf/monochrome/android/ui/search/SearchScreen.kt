package tf.monochrome.android.ui.search

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        SearchQueryField(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onSubmit = viewModel::submitSearch
        )

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
