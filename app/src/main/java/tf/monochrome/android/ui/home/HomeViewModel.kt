package tf.monochrome.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
                // Collect the history flow (not first()) so "Recently Played"
                // stays live — new plays appear and "Remove from history"
                // actually removes the row instead of doing nothing.
                libraryRepository.getHistory().collect { tracks ->
                    _recentTracks.value = tracks.take(20)
                    _isLoading.value = false
                }
            } catch (_: Exception) {
                // Empty state shown by HomeScreen when recentTracks stays empty.
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

                _discoveryRows.value = discoveryFeed.build(tracksPerRow = DISCOVERY_ROW_SIZE)
            } catch (_: Exception) {
                // Leave rows empty — HomeScreen falls back to the genre seeds.
            } finally {
                _discoveryLoading.value = false
            }
        }
    }

    private companion object {
        const val DISCOVERY_ROW_SIZE = 12
    }
}
