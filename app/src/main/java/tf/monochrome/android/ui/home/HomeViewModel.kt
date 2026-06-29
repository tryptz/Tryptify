package tf.monochrome.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.usecase.DiscoveryFeedUseCase
import tf.monochrome.android.domain.usecase.DiscoveryFeedUseCase.DiscoveryRow
import tf.monochrome.android.domain.usecase.toUnifiedTrackAuto
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val discoveryFeed: DiscoveryFeedUseCase,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val recommendationRepository: tf.monochrome.android.data.recommendations.RecommendationRepository,
) : ViewModel() {

    private val _recentTracks = MutableStateFlow<List<Track>>(emptyList())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Personalized discovery feed ("New from <artist>" rows).
    private val _discoveryRows = MutableStateFlow<List<DiscoveryRow>>(emptyList())
    val discoveryRows: StateFlow<List<DiscoveryRow>> = _discoveryRows.asStateFlow()

    // "From your favorites" row (null when the user has no hearted tracks).
    private val _favoritesRow = MutableStateFlow<DiscoveryRow?>(null)
    val favoritesRow: StateFlow<DiscoveryRow?> = _favoritesRow.asStateFlow()

    private val _discoveryLoading = MutableStateFlow(false)
    val discoveryLoading: StateFlow<Boolean> = _discoveryLoading.asStateFlow()

    init {
        loadHome()
    }

    private fun loadHome() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val tracks = libraryRepository.getHistory().first()
                _recentTracks.value = tracks.take(20)
            } catch (_: Exception) {
                // Empty state shown by HomeScreen when recentTracks stays empty.
            } finally {
                _isLoading.value = false
            }
        }
        loadDiscovery()
    }

    private fun loadDiscovery() {
        viewModelScope.launch {
            _discoveryLoading.value = true
            try {
                val favorites = libraryRepository.getFavoriteTracks().first()
                _favoritesRow.value = favorites
                    .take(DISCOVERY_ROW_SIZE)
                    .map { it.toUnifiedTrackAuto(qobuzIdRegistry) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { DiscoveryRow("From your favorites", it) }

                // "Because you played <X>" rows from the Spotify feature
                // dataset, seeded from recent history (distinct artists),
                // resolved to playable tracks via HiFi. Shown above the
                // existing "New from <artist>" feed.
                val history = runCatching { libraryRepository.getHistory().first() }.getOrNull().orEmpty()
                val seeds = history
                    .distinctBy { it.artist?.name ?: it.artists.firstOrNull()?.name }
                    .take(MAX_DATASET_ROWS)
                val datasetRows = datasetDiscoveryRows(seeds)

                _discoveryRows.value = datasetRows + discoveryFeed.build(tracksPerRow = DISCOVERY_ROW_SIZE)
            } catch (_: Exception) {
                // Leave rows empty — HomeScreen falls back to the genre seeds.
            } finally {
                _discoveryLoading.value = false
            }
        }
    }

    private suspend fun datasetDiscoveryRows(seeds: List<Track>): List<DiscoveryRow> =
        coroutineScope {
            seeds.map { t ->
                async {
                    val artist = t.artist?.name ?: t.artists.firstOrNull()?.name
                    artist?.let {
                        recommendationRepository.similarRow(
                            label = "Because you played ${t.title}",
                            seedArtist = it,
                            seedTitle = t.title,
                            perRow = DISCOVERY_ROW_SIZE,
                        )
                    }?.let { row -> DiscoveryRow(row.label, row.tracks) }
                }
            }.map { it.await() }.filterNotNull()
        }

    private companion object {
        const val DISCOVERY_ROW_SIZE = 12
        const val MAX_DATASET_ROWS = 2
    }
}
