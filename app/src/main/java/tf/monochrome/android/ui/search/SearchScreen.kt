package tf.monochrome.android.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.ui.player.PlayerViewModel

@Composable
fun SearchScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val playlistResults by viewModel.playlists.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val showSourceFilter by viewModel.showSourceFilter.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsState()
    val libraryPlaylists by playerViewModel.playlists.collectAsState()

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
