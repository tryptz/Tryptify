package tf.monochrome.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject

/**
 * Home is what the listener is doing right now: start a station, pick up a
 * recent track, search.
 *
 * The discovery feed used to live here too, which meant every visit to Home
 * fired a fan-out of Qobuz searches for a section most of the screen wasn't
 * about. It now belongs to `DiscoverViewModel` and the Discover tab.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _recentTracks = MutableStateFlow<List<Track>>(emptyList())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
                    _recentTracks.value = tracks.take(RECENT_LIMIT)
                    _isLoading.value = false
                }
            } catch (_: Exception) {
                // Empty state shown by HomeScreen when recentTracks stays empty.
                _isLoading.value = false
            }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 20
    }
}
